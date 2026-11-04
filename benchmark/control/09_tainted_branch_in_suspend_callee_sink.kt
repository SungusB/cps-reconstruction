// GROUND TRUTH (confirmed 2026-10-19 — before the fixes: a false positive
// WITHOUT reconstruction only; WITH reconstruction it looked clean, but only
// because the reconstructed callee had lost its parameters (see
// straight-line/04 and docs/bugs-found/README.md A1). After both fixes:
// NO-FLOW in both modes; FlowDroid and Joern report nothing)
// expect: NO-FLOW
// note: suspend version of 07_tainted_branch_in_callee_sink.kt — same
//       explicit-flow truth (none; the callee only branches on the secret and
//       prints literals), but the callee is a coroutine state machine, so its
//       summary is computed on the reconstructed body in one ablation mode
//       and on the raw dispatch-switch body in the other. Tests whether the
//       CDG-in-summary behaviour depends on reconstruction. `secretArg` is
//       again chosen not to be a substring of other labels (see 08).
package benchmark.control.taintedbranchinsuspendcallee

suspend fun tick(): Int = 1

suspend fun reportLengthLater(secretArg: String) {
    tick()                                   // suspension 1
    if (secretArg.length > 3) {
        println("long")
    } else {
        println("short")
    }
    tick()                                   // suspension 2
}

suspend fun taintedBranchInSuspendCallee() {
    val secret = System.getenv("SECRET")     // SOURCE
    reportLengthLater(secret)
}
