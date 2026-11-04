// GROUND TRUTH (confirmed 2026-10-19 — before the fix this benchmark motivated,
// the in-house analyzer found the flow WITHOUT reconstruction only (0 vs 1): the
// first flow ever lost to reconstruction, see docs/bugs-found/README.md A1.
// After the fix: found in both modes; FlowDroid and Joern find it in both)
// expect: FLOW
// note: like 03_sink_inside_callee.kt, but the callee actually suspends
//       before its sink, so it compiles to a state machine and is itself
//       reconstructed. 03's `logIt` has no suspension point, is never
//       reconstructed, and so never tested whether a reconstructed callee's
//       TaintSummary still knows its parameters. The secret reaches the
//       callee's println by plain assignment, so this is an explicit flow.
package benchmark.straightline.sinkinsidesuspendingcallee

suspend fun tick(): Int = 1

suspend fun logLater(x: String) {
    tick()                                   // suspension 1
    println(x)                               // SINK
}

suspend fun sinkInsideSuspendingCallee() {
    val secret = System.getenv("SECRET")     // SOURCE
    logLater(secret)
}
