// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh, before and
// after the TaintSlicer fix this benchmark motivated)
// expect: NO-FLOW
// note: companion boundary case to 04_tainted_guard_clause_unrelated_sink.kt.
//       Before the fix, this ALSO reported a false-positive flow — even
//       though `println` itself is not control-dependent on the `if` at all
//       (both branches reconverge into it unconditionally, so standard
//       control dependence doesn't apply to `println` the way it does to
//       04's early-return case). The false positive here comes one step
//       earlier than expected: `result#1 = "unrelated-a"` *inside* the `if`
//       branch IS control-dependent on the tainted condition (taking the
//       `else` path skips it), so the old TaintSlicer marked that
//       assignment tainted — and from there a completely legitimate DDG
//       edge (println's argument really is defined by that assignment)
//       carries it the rest of the way. Confirmed directly by inspecting
//       the raw CPG's `onTaintPath` nodes (see CLAUDE.md). This means the
//       over-approximation isn't specific to early-exit guard clauses at
//       all — any branch body assignment conditioned on tainted data was
//       affected, which is a broader bug than 04 alone would suggest. Fixed
//       the same way; this file now correctly reports NO-FLOW.
package benchmark.control.taintedbranchnoearlyexit

fun ordinaryTaintedBranchNoEarlyExit(): String {
    val secret = System.getenv("SECRET") // SOURCE
    var result = "default"
    if (secret.isEmpty()) {
        result = "unrelated-a"
    } else {
        result = "unrelated-b"
    }
    println(result) // SINK
    return result
}
