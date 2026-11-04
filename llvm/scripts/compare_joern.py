#!/usr/bin/env python3
"""Tabulate Joern results: raw vs. reconstructed, against ground truth.

LLVM suite (build/llvm, from run_joern.py): per benchmark, flows reported by
ghidra2cpg on the raw and the reconstructed binary, plus the source-level
frontend (c2cpg / rust2cpg) as the pre-lowering reference.
Kotlin suite (build/joern-kotlin, from run_joern_kotlin.py): jimple2cpg on
raw vs. reconstructed classes.

A flow is a distinct (source method@entry, sink method@entry) pair, so the
same source->sink found once in a C++ ramp and again in its .resume clone
counts twice -- that duplication is part of what is being measured.
"""
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(f: Path):
    if not f.exists():
        return None, []
    lines = [json.loads(l) for l in f.read_text().splitlines() if l.strip()]
    return lines[0], lines[1:]


def methods(flows):
    return sorted({(f["source"].split(":")[0], f["sink"].split(":")[0]) for f in flows})


def semantic_truth(expect):
    """What the program really does, which is what an external tool is scored
    against. The `// expect:` lines describe the in-house slicer, so two kinds
    are translated: `NO-FLOW (tool limitation ...)` (flow/*: the taint is real,
    the slicer can't model channels) and `UNSUPPORTED` (try-catch/02: a
    reconstruction label; `result = identity(secret)` in `finally` really
    reaches println) are both real flows."""
    if expect.startswith("NO-FLOW (tool limitation") or expect.startswith("UNSUPPORTED"):
        return "FLOW"
    if expect.startswith("FLOW"):
        return "FLOW"
    if expect.startswith("NO-FLOW"):
        return "NO-FLOW"
    return "?"


def verdict(expect, n):
    want = semantic_truth(expect)
    if want == "?":
        return "?"
    got = "FLOW" if n else "NO-FLOW"
    if want == got:
        return "TP" if n else "TN"
    return "FP" if n else "FN"


def table(title, rows, cols):
    print(f"\n==== {title}")
    hdr = f"{'benchmark':48} {'expect':8} " + " ".join(f"{c:>14}" for c in cols)
    print(hdr)
    print("-" * len(hdr))
    tally = {c: {} for c in cols}
    for name, expect, cells in rows:
        out = []
        for c in cols:
            stats, flows = cells[c]
            if stats is None:
                out.append(f"{'-':>14}")
                continue
            if "error" in stats:
                out.append(f"{'ERR':>14}")
                continue
            v = verdict(expect, len(methods(flows)))
            tally[c][v] = tally[c].get(v, 0) + 1
            out.append(f"{len(methods(flows)):>3} {v:2} n={stats.get('nodes', 0):<5}")
        print(f"{name[:48]:48} {semantic_truth(expect):8} " + " ".join(out))
    print("-" * len(hdr))
    for c in cols:
        t = tally[c]
        print(f"  {c:>10}: TP={t.get('TP', 0)} TN={t.get('TN', 0)} FP={t.get('FP', 0)} FN={t.get('FN', 0)}")
    return tally


def main():
    llvm = Path(os.environ.get("LLVM_OUT", ROOT / "build" / "llvm")).resolve()
    if (llvm / "suite.json").exists():
        suite = json.loads((llvm / "suite.json").read_text())
        for lang in ("cpp", "rust"):
            rows = []
            for r in suite:
                if r["lang"] != lang:
                    continue
                d = llvm / lang / r["bench"]
                rows.append((r["bench"], r["expect"], {
                    "src": load(d / "src.flows"),
                    "raw-bin": load(d / "raw.flows"),
                    "recon-bin": load(d / "recon.flows"),
                }))
            table(f"{lang}: Joern source frontend vs. ghidra2cpg raw / reconstructed", rows,
                  ["src", "raw-bin", "recon-bin"])
            # Size of the coroutine-bearing methods as Joern sees them.
            rn = sum((load(llvm / lang / r["bench"] / "raw.flows")[0] or {}).get("nodes", 0) for r in suite if r["lang"] == lang)
            cn = sum((load(llvm / lang / r["bench"] / "recon.flows")[0] or {}).get("nodes", 0) for r in suite if r["lang"] == lang)
            if rn:
                print(f"  total ghidra2cpg AST nodes (user methods): raw={rn} recon={cn} ({(cn - rn) / rn:+.1%})")

    kdir = Path(os.environ.get("JOERN_KOTLIN_OUT", ROOT / "build" / "joern-kotlin")).resolve()
    kt = kdir / "summary.json"
    if kt.exists():
        # Optional counterfactual: the same run with jimple2cpg's jump-target
        # CFG edge fixed (llvm/joern/jimple2cpg-cfg-entry.patch).
        kp = kdir.with_name(kdir.name + "-patched") / "summary.json"
        patched = {s["pkg"]: s for s in json.loads(kp.read_text())} if kp.exists() else {}
        cols = ["raw-jvm", "recon-jvm"] + (["raw-fixed", "recon-fixed"] if patched else [])
        rows = []
        for s in json.loads(kt.read_text()):
            cells = {"raw-jvm": (s["rawStats"], s["raw"]), "recon-jvm": (s["reconStats"], s["recon"])}
            if patched:
                # a package added after the patched run shows as "-"
                q = patched.get(s["pkg"])
                cells.update({"raw-fixed": (q["rawStats"], q["raw"]) if q else (None, []),
                              "recon-fixed": (q["reconStats"], q["recon"]) if q else (None, [])})
            rows.append((s["pkg"].removeprefix("benchmark/"), s["expect"], cells))
        table("kotlin: jimple2cpg on raw vs. reconstructed classes (upstream Joern; -fixed = CFG-patched jimple2cpg)", rows, cols)
        rn = sum(s["rawStats"].get("nodes", 0) for s in json.loads(kt.read_text()))
        cn = sum(s["reconStats"].get("nodes", 0) for s in json.loads(kt.read_text()))
        if rn:
            print(f"  total jimple2cpg AST nodes (user methods): raw={rn} recon={cn} ({(cn - rn) / rn:+.1%})")


if __name__ == "__main__":
    main()
