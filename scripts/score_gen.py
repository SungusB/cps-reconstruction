#!/usr/bin/env python3
"""Score an analyzer's with/without-reconstruction runs on the generated suite.

Reads two summary.json files (the in-house ReconstructionCli's, or
BaselineCli/FlowDroid's — merged by run_baseline_per_package.sh) and scores
each Kotlin program in benchmark-gen/manifest.json: a program counts as
reporting a flow when any finding's method lies in its package. Ground truth
is the manifest's `expect` (exact static analysis, checked dynamically by
gen_validate.py), so FP/FN here are the analyzer's, per mode.

Usage: score_gen.py <with/summary.json> <without/summary.json>
"""
import collections
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = {m["kt_package"]: m for m in json.loads((ROOT / "benchmark-gen" / "manifest.json").read_text())
            if "kotlin" in m["langs"]}


def pkg(sig):
    m = re.match(r"<([\w.$]+)\.[^.:]+:", sig)
    return m.group(1) if m else None


def load(path):
    s = json.loads(Path(path).read_text())
    flows = collections.Counter()
    for f in s["findings"]:
        flows[pkg(f.get("method") or f.get("sinkMethod"))] += 1
    stmts = sum(m.get("statementCount", 0) for m in s.get("methodStats", []))
    return s, flows, stmts


def main():
    runs = {"with": load(sys.argv[1]), "without": load(sys.argv[2])}
    tally = {k: collections.Counter() for k in runs}
    fam = collections.defaultdict(lambda: {k: collections.Counter() for k in runs})
    odd = []
    for p, m in sorted(MANIFEST.items(), key=lambda kv: kv[1]["id"]):
        v = {}
        for mode, (_, flows, _) in runs.items():
            got, want = flows[p] > 0, m["expect"] == "FLOW"
            v[mode] = ("TP" if got else "FN") if want else ("FP" if got else "TN")
            tally[mode][v[mode]] += 1
            fam[m["family"]][mode][v[mode]] += 1
        if v["with"] != v["without"] or {"FP", "FN"} & set(v.values()):
            odd.append((m["id"], m["family"], m["name"], m["expect"], v["with"], v["without"]))

    for mode, (s, _, stmts) in runs.items():
        extra = f"stmts={stmts}" if stmts else f"ifdsEdges={s.get('flowDroidEdgePropagations', '?')}"
        print(f"{mode:8} {s.get('tool', '?')}: reconstructed={s.get('suspendChainsReconstructed')} "
              f"declined={s.get('suspendChainsUnsupported')} flows={s['totalTaintFlows']} {extra}  "
              + " ".join(f"{k}={tally[mode][k]}" for k in ("TP", "TN", "FP", "FN")))
    print("\nper family (with | without):")
    for f, d in sorted(fam.items()):
        fmt = lambda c: " ".join(f"{k}={c[k]}" for k in ("TP", "TN", "FP", "FN") if c[k])
        print(f"  {f:13} {fmt(d['with']):30} | {fmt(d['without'])}")
    print("\nFP/FN or mode-dependent programs:")
    for r in odd:
        print("  %s %-12s %-28s %-7s with=%s without=%s" % r)


if __name__ == "__main__":
    main()
