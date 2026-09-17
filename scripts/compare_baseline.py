#!/usr/bin/env python3
"""Compares two BaselineCli (FlowDroid) summary.json outputs — with vs
without the classic-Soot port of suspend-chain reconstruction applied to the
bodies FlowDroid analyzes — and, optionally, checks the Soot port against the
SootUp reconstruction it mirrors.

Reports:

1. FlowDroid flows per benchmark package, with vs without reconstruction,
   plus the (source line, sink line) pairs found in only one mode. A pair
   reported only WITHOUT reconstruction on a `expect: NO-FLOW` benchmark is
   the external-tool false positive the `benchmark/spill-slot/` family
   predicts; a pair reported only WITH reconstruction is one the raw state
   machine hid from FlowDroid.

2. FlowDroid cost per package: wall time and IFDS edge propagations.

3. If a ReconstructionCli summary.json (the SootUp tool's own, from
   scripts/run_ablation.sh) is given as a third argument: a sanity check of
   the Soot port against the SootUp walk it mirrors. Exact statement-count
   parity is NOT expected and not checked: the two frontends produce
   different Jimple for the same bytecode (classic Soot's `jb` pack runs
   copy propagation and dead-assignment elimination, so `e = $caught`
   copies and dead `a = "default"` stores disappear; SootUp keeps each
   `(Continuation) $continuation` checkcast as its own statement, Soot
   folds them; the port never counts `goto`s as real statements; and
   SootUp's `methodStats` only lists methods with a finding while the Soot
   port lists every reconstructed method). Verified by hand on
   `multipleTryCatchBlocks` (21 vs 13: 2 dead stores + 2 exception copies
   + 2 casts + 2 gotos, no real statement missing either way). What is
   checked: the aggregate reconstructed/declined counts agree, and for every
   method present on both sides the Soot port's case-0-reachable count is
   at most the SootUp count — a port that kept *more* real statements than
   the SootUp walk would mean it left dispatch bookkeeping in the body.

Usage: compare_baseline.py <with-summary.json> <without-summary.json> [sootup-with-summary.json]
"""
import json
import sys
from collections import defaultdict


def load(path):
    with open(path) as f:
        return json.load(f)


def package_of(method_sig):
    cls = method_sig[1:].split(":")[0]
    return cls.rsplit(".", 1)[0].split("$")[0]


def pairs_by_package(summary):
    out = defaultdict(set)
    for f in summary["findings"]:
        out[package_of(f["sourceMethod"])].add((f["sourceLine"], f["sinkLine"]))
    return out


def print_flow_table(with_s, without_s):
    with_pairs = pairs_by_package(with_s)
    without_pairs = pairs_by_package(without_s)
    packages = sorted({r["package"] for r in with_s["packageRuns"]} | {r["package"] for r in without_s["packageRuns"]})

    print(f"{'Package':<48} {'With':>5} {'Without':>8}  Only-with / Only-without (srcLine->sinkLine)")
    print("-" * 112)
    only_with_total = only_without_total = 0
    for pkg in packages:
        w, wo = with_pairs.get(pkg, set()), without_pairs.get(pkg, set())
        only_w, only_wo = sorted(w - wo), sorted(wo - w)
        only_with_total += len(only_w)
        only_without_total += len(only_wo)
        note = ""
        if only_w:
            note += " +with:" + ",".join(f"{a}->{b}" for a, b in only_w)
        if only_wo:
            note += " +without:" + ",".join(f"{a}->{b}" for a, b in only_wo)
        print(f"{pkg:<48} {len(w):>5} {len(wo):>8} {note}")
    print("-" * 112)
    print(f"{'TOTAL':<48} {with_s['totalTaintFlows']:>5} {without_s['totalTaintFlows']:>8}"
          f"   only-with={only_with_total} only-without={only_without_total}")


def print_cost_table(with_s, without_s):
    wr = {r["package"]: r for r in with_s["packageRuns"]}
    wor = {r["package"]: r for r in without_s["packageRuns"]}
    print(f"\n{'Package':<48} {'ms with':>9} {'ms w/o':>9} {'IFDS with':>11} {'IFDS w/o':>11}")
    print("-" * 92)
    for pkg in sorted(set(wr) | set(wor)):
        a, b = wr.get(pkg, {}), wor.get(pkg, {})
        print(f"{pkg:<48} {a.get('wallMs', -1):>9} {b.get('wallMs', -1):>9} "
              f"{a.get('ifdsEdges', -1):>11} {b.get('ifdsEdges', -1):>11}")
    print("-" * 92)
    print(f"{'TOTAL':<48} {with_s['flowDroidWallMs']:>9} {without_s['flowDroidWallMs']:>9} "
          f"{with_s['flowDroidEdgePropagations']:>11} {without_s['flowDroidEdgePropagations']:>11}")


def print_parity(soot_s, sootup_s):
    soot = {m["method"]: m["statementCount"] for m in soot_s["methodStats"]}
    sootup = {m["method"]: m["statementCount"] for m in sootup_s["methodStats"]}
    print(f"\nSoot port vs SootUp walk sanity check")
    print("-" * 112)
    print(f"  reconstructed: soot={soot_s['suspendChainsReconstructed']} sootup={sootup_s['suspendChainsReconstructed']}"
          f"   declined: soot={soot_s['suspendChainsUnsupported']} sootup={sootup_s['suspendChainsUnsupported']}")
    both = sorted(set(soot) & set(sootup))
    print(f"  {len(both)} methods reconstructed by the Soot port and carrying a SootUp finding:")
    print(f"  {'SootUp':>7} {'Soot':>5} {'delta':>6}  method")
    suspicious = 0
    for method in both:
        a, b = sootup[method], soot[method]
        flag = ""
        if b > a:
            flag = "  <-- Soot port kept MORE than the SootUp walk"
            suspicious += 1
        print(f"  {a:>7} {b:>5} {b - a:>+6}  {method}{flag}")
    print("  (negative delta = SootUp's retained Continuation checkcasts; see docstring)")
    if suspicious:
        print(f"  {suspicious} method(s) where the Soot port kept more statements than SootUp — inspect the port")
    else:
        print("  no method where the Soot port kept more statements than the SootUp walk")
    return suspicious


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    with_s, without_s = load(sys.argv[1]), load(sys.argv[2])
    print("FlowDroid flows per benchmark package")
    print_flow_table(with_s, without_s)
    print_cost_table(with_s, without_s)
    if len(sys.argv) > 3:
        print_parity(with_s, load(sys.argv[3]))


if __name__ == "__main__":
    main()
