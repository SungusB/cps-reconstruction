#!/usr/bin/env python3
"""Run Joern over the LLVM suite built by build_llvm_suite.py.

Per benchmark, three CPGs:
  raw.so.cpg    ghidra2cpg on the raw IR, linked into a .so with stubs.c
  recon.so.cpg  ghidra2cpg on the reconstructed IR, same link
  src.cpg       c2cpg / rust2cpg on the benchmark source (pre-lowering
                reference: what a source-level tool sees)
then one Joern process runs llvm/joern/taint_all.sc over all of them and
the results are tabulated against each file's `// expect:` line.

Needs: Joern staged at $JOERN (default ~/joern), JDK 21+ for Ghidra
($JOERN_JAVA_HOME, default /usr/lib/jvm/java-27-openjdk), cargo on PATH for
rust2cpg.

Usage: run_joern.py [--jobs N] [--skip-parse] [filter-substring]
"""
import argparse
import json
import re
import os
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(os.environ.get("LLVM_OUT", ROOT / "build" / "llvm")).resolve()
JDIR = ROOT / "llvm" / "joern"
JOERN = Path(os.environ.get("JOERN", Path.home() / "joern")).resolve()
STAGE = JOERN / "joern-cli" / "target" / "universal" / "stage"
JAVA_HOME = os.environ.get("JOERN_JAVA_HOME", "/usr/lib/jvm/java-27-openjdk")
ENV = dict(os.environ, JAVA_HOME=JAVA_HOME, PATH=f"{JAVA_HOME}/bin:{os.environ['PATH']}")


def sh(cmd, cwd=None, check=True):
    r = subprocess.run(cmd, capture_output=True, text=True, env=ENV, cwd=cwd)
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(map(str, cmd))}\n{r.stdout[-2000:]}\n{r.stderr[-2000:]}")
    return r


def prepare_source(d: Path, lang: str) -> Path:
    """A self-contained source tree for the source-level frontend."""
    s = d / "srcdir"
    if s.exists():
        shutil.rmtree(s)
    if lang == "cpp":
        s.mkdir()
        shutil.copy(d.parent / "common.hpp", s / "common.hpp")
        (s / "bench.cpp").write_text((d / "src.cpp").read_text().replace('"../common.hpp"', '"common.hpp"'))
    else:
        (s / "src").mkdir(parents=True)
        (s / "Cargo.toml").write_text('[package]\nname = "bench"\nversion = "0.1.0"\nedition = "2024"\n\n[lib]\npath = "src/lib.rs"\n')
        prelude = (d.parent / "common.rs").read_text()
        (s / "src" / "lib.rs").write_text((d / "src.rs").read_text().replace('include!("../common.rs");', prelude))
    return s


def for_binary(ll: Path, out: Path):
    """rustc marks functions nonlazybind (and sets RtLibUseGOT), so llc emits
    `mov rax,[GOT]; call *rax`. ghidra2cpg only gives a call arguments when it
    can name the callee, so normalize to ordinary PLT calls. Call lowering
    only; applied identically to raw and reconstructed IR."""
    t = ll.read_text().replace(" nonlazybind", "")
    t = re.sub(r'(!\{i32 \d+, !"RtLibUseGOT", i32) 1\}', r"\1 0}", t)
    out.write_text(t)


def parse_one(d: Path, lang: str, stubs_o: Path):
    jobs = []
    pics = []
    for v in ("raw", "recon"):
        pic = d / f"{v}.pic.o"
        for_binary(d / f"{v}.ll", d / f"{v}.bin.ll")
        sh(["llc", "-O0", "-relocation-model=pic", "-filetype=obj", str(d / f"{v}.bin.ll"), "-o", str(pic)])
        pics.append(pic)
    for v, pic in zip(("raw", "recon"), pics):
        sh(["clang", "-shared", "-o", str(d / f"{v}.so"), str(pic), str(stubs_o)])
        jobs.append(([str(STAGE / "ghidra2cpg"), "-J-Xmx2g", str(d / f"{v}.so"), "--output", str(d / f"{v}.so.cpg")], None))
    src = prepare_source(d, lang)
    front = "c2cpg.sh" if lang == "cpp" else "rust2cpg"
    jobs.append(([str(STAGE / front), "-J-Xmx2g", str(src), "--output", str(d / "src.cpg")], None))
    for cmd, cwd in jobs:
        sh(cmd, cwd=cwd)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jobs", type=int, default=3)
    ap.add_argument("--skip-parse", action="store_true")
    ap.add_argument("filter", nargs="?", default="")
    a = ap.parse_args()

    suite = json.loads((OUT / "suite.json").read_text())
    rows = [r for r in suite if a.filter in f"{r['lang']}/{r['bench']}"]
    stubs_o = OUT / "stubs.o"
    sh(["clang", "-O0", "-g", "-fPIC", "-c", str(JDIR / "stubs.c"), "-o", str(stubs_o)])

    if not a.skip_parse:
        def work(r):
            d = OUT / r["lang"] / r["bench"]
            try:
                parse_one(d, r["lang"], stubs_o)
                return f"parsed {r['lang']}/{r['bench']}"
            except RuntimeError as e:
                return f"PARSE FAILED {r['lang']}/{r['bench']}: {str(e)[:600]}"
        with ThreadPoolExecutor(a.jobs) as ex:
            for msg in ex.map(work, rows):
                print(msg, flush=True)

    manifest = OUT / "joern_manifest.txt"
    lines = []
    for r in rows:
        d = OUT / r["lang"] / r["bench"]
        for mode, cpg, out in (("bin", "raw.so.cpg", "raw.flows"), ("bin", "recon.so.cpg", "recon.flows"),
                               ("src", "src.cpg", "src.flows")):
            if (d / cpg).exists():
                lines.append(f"{mode} {d / cpg} {d / out}")
    manifest.write_text("\n".join(lines) + "\n")
    r = subprocess.run([str(JOERN / "joern"), "--script", str(JDIR / "taint_all.sc"),
                        "--param", f"manifest={manifest}"], env=ENV, cwd=JOERN,
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"joern failed:\n{r.stdout[-3000:]}\n{r.stderr[-3000:]}")
    print(f"queried {len(lines)} CPGs; tabulate with llvm/scripts/compare_joern.py")


if __name__ == "__main__":
    main()
