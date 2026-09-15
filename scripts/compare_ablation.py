#!/usr/bin/env python3
"""Compares two ReconstructionCli summary.json outputs (with vs without
suspend-chain reconstruction).

Reports two things:

1. Flow count (informational only — see the WARNING this script prints).
   Kotlin's coroutine ABI always emits a "didn't actually suspend" fast path
   as ordinary sequential bytecode ahead of the label-dispatch switch, so a
   reachability-only analysis finds the same flows with or without
   reconstruction, for every suspend-chain shape (straight-line, branches,
   loops) — this was verified directly against the raw Jimple for several
   benchmarks, not assumed. A zero delta here is expected, not a bug.

2. Explanation size (the actual ablation signal): statementCount and
   chopSize per method, from `methodStats` in each summary. Reconstruction's
   real, measurable effect is shrinking the CPG and the minimal slice that
   explains a flow, by stripping label writes, spill-field read/writes, the
   suspend-check `if`, and the switch itself — bookkeeping a taint analyzer
   (or a human) would otherwise have to traverse or read to reach the same
   conclusion.

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


def stats_by_method(summary):
    return {m["method"]: m for m in summary.get("methodStats", [])}


def print_flow_table(with_summary, without_summary):
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
        print("NOTE: zero additional flows is EXPECTED here, not a sign reconstruction")
        print("did nothing — see this script's module docstring. The explanation-size")
        print("table below is the metric that actually reflects reconstruction's effect.")


def print_explanation_size_table(with_summary, without_summary):
    with_stats = stats_by_method(with_summary)
    without_stats = stats_by_method(without_summary)
    all_methods = sorted(set(with_stats) | set(without_stats))
    if not all_methods:
        return

    print("")
    print("================ Explanation-size comparison ================")
    print(f"{'Method':<70} {'Stmts(w)':>9} {'Stmts(wo)':>10} {'Chop(w)':>8} {'Chop(wo)':>9}")
    print("-" * 112)

    total_stmts_w = total_stmts_wo = total_chop_w = total_chop_wo = 0
    counted = 0
    for method in all_methods:
        w = with_stats.get(method)
        wo = without_stats.get(method)
        if w is None or wo is None:
            continue  # only present on one side (e.g. a general-case-unsupported method that gained/lost a flow) — not comparable
        counted += 1
        total_stmts_w += w["statementCount"]
        total_stmts_wo += wo["statementCount"]
        total_chop_w += w["chopSize"]
        total_chop_wo += wo["chopSize"]
        short = method if len(method) <= 70 else method[:67] + "..."
        print(f"{short:<70} {w['statementCount']:>9} {wo['statementCount']:>10} {w['chopSize']:>8} {wo['chopSize']:>9}")

    print("-" * 112)
    if counted == 0:
        print("(no methods had comparable methodStats on both sides)")
        return

    stmt_reduction = 100.0 * (total_stmts_wo - total_stmts_w) / total_stmts_wo if total_stmts_wo else 0.0
    chop_reduction = 100.0 * (total_chop_wo - total_chop_w) / total_chop_wo if total_chop_wo else 0.0
    print(f"Total CPG statements (all {counted} methods):  with={total_stmts_w}  without={total_stmts_wo}  "
          f"({stmt_reduction:+.1f}%)")
    print(f"Total chop size (all {counted} methods):       with={total_chop_w}  without={total_chop_wo}  "
          f"({chop_reduction:+.1f}%)")
    print("(most methods are untouched by reconstruction — general-case-unsupported, or not a")
    print(" coroutine state machine at all — and are identical on both sides, which dilutes the")
    print(" number above; the line below isolates the methods reconstruction actually rewrote.)")

    touched = [
        m for m in all_methods
        if m in with_stats and m in without_stats and with_stats[m]["statementCount"] != without_stats[m]["statementCount"]
    ]
    if touched:
        tw_stmts = sum(with_stats[m]["statementCount"] for m in touched)
        two_stmts = sum(without_stats[m]["statementCount"] for m in touched)
        tw_chop = sum(with_stats[m]["chopSize"] for m in touched)
        two_chop = sum(without_stats[m]["chopSize"] for m in touched)
        print("")
        print(f"Reconstructed methods only ({len(touched)} of {counted}):")
        print(f"  statements: with={tw_stmts}  without={two_stmts}  "
              f"({100.0 * (two_stmts - tw_stmts) / two_stmts:+.1f}%)")
        print(f"  chop size:  with={tw_chop}   without={two_chop}   "
              f"({100.0 * (two_chop - tw_chop) / two_chop:+.1f}%)")
    else:
        print("")
        print("No method's statementCount differed between the two runs — reconstruction may not")
        print("have applied to any method that also produced a finding. Check suspendChainsReconstructed.")


def main():
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(1)

    with_summary = load(sys.argv[1])
    without_summary = load(sys.argv[2])

    print_flow_table(with_summary, without_summary)
    print_explanation_size_table(with_summary, without_summary)


if __name__ == "__main__":
    main()
