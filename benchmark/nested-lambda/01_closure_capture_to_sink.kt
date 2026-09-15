// GROUND TRUTH (proposed — pending confirmation)
// expect: FLOW
// note: EXERCISES THE LEAST-VALIDATED MECHANISM IN THE TOOL — the outer
//       function reads a source and captures it into a nested suspend
//       lambda's closure; the lambda's own body sinks it. Captured
//       variables compile to constructor-param-to-field writes, not method
//       arguments, so this needs TaintSlicer.findCapturedFieldFlows
//       specifically, not the ordinary call-argument path. This mechanism
//       has NOT yet been validated against real compiled bytecode (unlike
//       the other categories) — treat this file's expectation with extra
//       scrutiny.
package benchmark.nestedlambda.capturetosink

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

fun runSuspend(block: suspend () -> Unit) {
    block.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) {}
    })
}

fun closureCaptureToSink() {
    val secret = System.getenv("SECRET") // SOURCE
    runSuspend {
        println(secret) // SINK
    }
}
