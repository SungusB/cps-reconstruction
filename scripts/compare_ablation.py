#!/usr/bin/env python3
"""Compares two ReconstructionCli summary.json outputs (with vs without
suspend-chain reconstruction) and reports the delta — the number of flows
recovered only because reconstruction ran.

Usage: compare_ablation.py <with-summary.json> <without-summary.json>
"""
import json
import sys
from collections import Counter


def load(path):
    with open(path) as f:
        return json.load(f)


def flow_counts_by_method(summary):
    counts = Counter()
    for finding in summary["findings"]:
        counts[finding["method"]] += 1
    return counts


def main():
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(1)

    with_summary = load(sys.argv[1])
    without_summary = load(sys.argv[2])

    with_counts = flow_counts_by_method(with_summary)
    without_counts = flow_counts_by_method(without_summary)

    all_methods = sorted(set(with_counts) | set(without_counts))

    print(f"{'Method':<90} {'With':>6} {'Without':>8} {'Delta':>6}")
    print("-" * 112)
    only_with_total = 0
    only_without_total = 0
    for method in all_methods:
        w = with_counts.get(method, 0)
        wo = without_counts.get(method, 0)
        delta = w - wo
        if delta > 0:
            only_with_total += delta
        elif delta < 0:
            only_without_total += -delta
        short = method if len(method) <= 90 else method[:87] + "..."
        print(f"{short:<90} {w:>6} {wo:>8} {delta:>+6}")

    print("-" * 112)
    print(f"Total flows WITH reconstruction:    {with_summary['totalTaintFlows']}")
    print(f"Total flows WITHOUT reconstruction: {without_summary['totalTaintFlows']}")
    print(f"Flows recovered ONLY because of reconstruction:   {only_with_total}")
    print(f"Flows found ONLY without reconstruction (unexpected — investigate): {only_without_total}")
    print(f"Suspend chains reconstructed: {with_summary['suspendChainsReconstructed']}")
    print(f"Suspend chains left general-case-unsupported: {with_summary['suspendChainsUnsupported']}")

    if only_with_total == 0:
        print("")
        print("WARNING: reconstruction produced zero additional flows on this benchmark set.")
        print("This usually means the field-based DDG fallback (FieldAliasTracker) is already")
        print("bridging the same flows independently of reconstruction — see the project notes")
        print("on this before treating the benchmark suite as demonstrating reconstruction's value.")


if __name__ == "__main__":
    main()
