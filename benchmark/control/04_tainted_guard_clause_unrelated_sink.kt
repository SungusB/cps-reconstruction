// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh, before and
// after the TaintSlicer fix this benchmark motivated)
// expect: NO-FLOW
// note: before the fix, this WAS reported as a false-positive flow (line
//       14 -> line 20), confirming CLAUDE.md's hypothesis: the
//       CDG-as-taint-propagating-edge over-approximation found on
//       benchmark/flow/*.kt is general, not specific to coroutine dispatch
//       bookkeeping — an ordinary early-return guard clause on tainted data
//       is enough. `TaintSlicer.slice()` was fixed to stop treating CDG
//       edges as taint-propagating (see TaintSlicer.kt and CLAUDE.md); after
//       the fix this file correctly reports NO-FLOW, and the whole
//       benchmark suite's previously-reported flows are unaffected (see
//       CLAUDE.md's verification of that).
package benchmark.control.taintedguardclause

fun ordinaryTaintedGuardClause(): String {
    val secret = System.getenv("SECRET") // SOURCE
    if (secret.isEmpty()) {
        return "empty"
    }
    val result = "unrelated"
    println(result) // SINK
    return result
}
