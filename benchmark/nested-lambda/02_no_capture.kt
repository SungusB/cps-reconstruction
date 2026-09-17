// GROUND TRUTH (confirmed)
// expect: NO-FLOW
// note: negative control for this category — the source is read outside the
//       lambda but never captured into it; the lambda's sink only ever sees
//       a constant. Confirms the capture mechanism doesn't over-approximate
//       (report a flow just because a nested lambda + a source both exist
//       somewhere in the same function).
package benchmark.nestedlambda.nocapture

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

fun runSuspend(block: suspend () -> Unit) {
    block.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) {}
    })
}

fun noCapture() {
    val secret = System.getenv("SECRET") // SOURCE, deliberately unused below
    check(secret != null)
    runSuspend {
        println("constant, not tainted") // SINK (should NOT be flagged)
    }
}
