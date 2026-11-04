// GROUND TRUTH (confirmed 2026-10-19 — scripts/run_ablation.sh before and after
// the fix this benchmark motivated: reported a false-positive flow in both modes
// before, NO-FLOW in both after; FlowDroid and Joern report nothing)
// expect: NO-FLOW
// note: inter-procedural version of 05_tainted_branch_no_early_exit.kt.
//       The secret is passed to a helper that only *branches* on it; both
//       arms print a literal. No value derived from the secret reaches a
//       sink, so there is no explicit flow (there is an implicit one: which
//       literal is printed reveals one bit of the secret — out of scope, as
//       for 04/05). The sink lives in the callee, so the caller's own slice
//       cannot report anything; only the callee's TaintSummary.paramToSinks
//       can. Written to test whether InterProceduralAnalyzer's summaries
//       still propagate taint over CDG edges (the bug removed from
//       TaintSlicer.slice() by 04/05). The parameter is deliberately named
//       `secretArg`, which is not a substring of any other statement label in
//       the callee, so this case isolates the CDG mechanism from the
//       parameter-name matching tested by 08_short_param_name_callee_sink.kt.
package benchmark.control.taintedbranchincallee

fun reportLength(secretArg: String) {
    if (secretArg.length > 3) {
        println("long")
    } else {
        println("short")
    }
}

fun taintedBranchInCallee() {
    val secret = System.getenv("SECRET") // SOURCE
    reportLength(secret)
}
