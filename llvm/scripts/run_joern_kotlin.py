#!/usr/bin/env python3
"""Joern (jimple2cpg) on the Kotlin suite's raw vs. reconstructed .class files:
the second bytecode-level tool after FlowDroid (CLAUDE.md, Tier A item 1).

Raw classes come from scripts/run_ablation.sh (build/ablation/classes), the
reconstructed ones from scripts/run_baseline.sh --emit-classes
(build/baseline/with-reconstruction/classes). One CPG per benchmark package,
like the FlowDroid harness's one Soot scene per package.

Writes build/joern-kotlin/<pkg>/{raw,recon}.flows and summary.json.
Overrides: JIMPLE2CPG (frontend launcher), JOERN_KOTLIN_OUT (output dir), and
for another suite (e.g. benchmark-gen/kotlin): KOTLIN_RAW, KOTLIN_RECON
(class dirs), KOTLIN_BENCH (sources with `// expect:`), KOTLIN_PKG_GLOB.
"""
import json
import os
import re
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RAW = Path(os.environ.get("KOTLIN_RAW", ROOT / "build" / "ablation" / "classes")).resolve()
RECON = Path(os.environ.get("KOTLIN_RECON", ROOT / "build" / "baseline" / "with-reconstruction" / "classes")).resolve()
BENCH = Path(os.environ.get("KOTLIN_BENCH", ROOT / "benchmark")).resolve()
PKG_GLOB = os.environ.get("KOTLIN_PKG_GLOB", "benchmark/*/*")
OUT = Path(os.environ.get("JOERN_KOTLIN_OUT", ROOT / "build" / "joern-kotlin")).resolve()
JOERN = Path(os.environ.get("JOERN", Path.home() / "joern")).resolve()
STAGE = JOERN / "joern-cli" / "target" / "universal" / "stage"
JAVA_HOME = os.environ.get("JOERN_JAVA_HOME", "/usr/lib/jvm/java-27-openjdk")
ENV = dict(os.environ, JAVA_HOME=JAVA_HOME, PATH=f"{JAVA_HOME}/bin:{os.environ['PATH']}")
# jimple2cpg's Soot loads the running JRE's own classes and its ASM tops out
# below JDK 27's class-file version, so the frontend runs on JDK 17.
JIMPLE_JAVA = os.environ.get("JIMPLE_JAVA_HOME", "/usr/lib/jvm/java-17-openjdk")
JENV = dict(os.environ, JAVA_HOME=JIMPLE_JAVA, PATH=f"{JIMPLE_JAVA}/bin:{os.environ['PATH']}")


def expectations():
    exp = {}
    for kt in BENCH.rglob("*.kt"):
        t = kt.read_text()
        pkg = re.search(r"^package\s+(\S+)", t, re.M)
        e = re.search(r"^// *expect:\s*(.+)$", t, re.M)
        if pkg:
            exp[pkg.group(1).replace(".", "/")] = (str(kt.relative_to(ROOT)), e.group(1).strip() if e else "?")
    return exp


def main():
    if not RECON.exists():
        sys.exit("run scripts/run_baseline.sh first (needs --emit-classes output)")
    exp = expectations()
    pkgs = sorted(str(p.relative_to(RAW)) for p in RAW.glob(PKG_GLOB) if p.is_dir())
    flt = sys.argv[1] if len(sys.argv) > 1 else ""
    pkgs = [p for p in pkgs if flt in p]

    def parse(pkg):
        d = OUT / pkg.replace("/", "_")
        msgs = []
        for v, base in (("raw", RAW), ("recon", RECON)):
            root = d / f"{v}-classes"
            if root.exists():
                shutil.rmtree(root)
            shutil.copytree(base / pkg, root / pkg)
            r = subprocess.run([os.environ.get("JIMPLE2CPG", str(STAGE / "jimple2cpg")), "-J-Xmx2g", str(root), "--output", str(d / f"{v}.cpg")],
                               capture_output=True, text=True, env=JENV)
            msgs.append(f"{v}:{'ok' if r.returncode == 0 else 'FAILED ' + r.stderr[-300:]}")
        return f"{pkg} {' '.join(msgs)}"

    OUT.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(4) as ex:
        for m in ex.map(parse, pkgs):
            print(m, flush=True)

    manifest = OUT / "manifest.txt"
    manifest.write_text("".join(f"jvm {OUT / p.replace('/', '_') / (v + '.cpg')} {OUT / p.replace('/', '_') / (v + '.flows')}\n"
                                for p in pkgs for v in ("raw", "recon")))
    r = subprocess.run([str(JOERN / "joern"), "--script", str(ROOT / "llvm" / "joern" / "taint_all.sc"),
                        "--param", f"manifest={manifest}"], env=ENV, cwd=JOERN, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"joern failed:\n{r.stdout[-3000:]}\n{r.stderr[-3000:]}")

    def load(f):
        lines = [json.loads(l) for l in f.read_text().splitlines() if l.strip()]
        return lines[0], [l for l in lines[1:]]
    summary = []
    for p in pkgs:
        d = OUT / p.replace("/", "_")
        (rs, rf), (cs, cf) = load(d / "raw.flows"), load(d / "recon.flows")
        kt, e = exp.get(p, ("?", "?"))
        summary.append({"pkg": p, "kt": kt, "expect": e, "raw": rf, "recon": cf, "rawStats": rs, "reconStats": cs})
    (OUT / "summary.json").write_text(json.dumps(summary, indent=1))
    print(f"wrote {OUT / 'summary.json'}")


if __name__ == "__main__":
    main()
