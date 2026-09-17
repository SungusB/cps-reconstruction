// GROUND TRUTH (confirmed)
// expect: FLOW
// note: the outer function reads a source and captures it into a nested
//       suspend lambda's closure; the lambda's own body sinks it. Captured
//       variables compile to constructor-param-to-field writes, not method
//       arguments, so this needs TaintSlicer.findCapturedFieldFlows
//       specifically, not the ordinary call-argument path. This was
//       unvalidated (and, it turned out, silently broken) from the
//       project's first commit until it was fixed and confirmed via
//       scripts/run_ablation.sh — see CLAUDE.md's "findCapturedFieldFlows
//       filtered out every real candidate" entry for the root cause
//       (TaintSlicer.kt's candidate loop scanned only `intraResult.forwardSlice`,
//       which a `new` expression never appears in since it takes no
//       operands — only the following `<init>` invoke is data-tainted).
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
