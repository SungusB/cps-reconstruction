// GROUND TRUTH (proposed — pending confirmation)
// expect: FLOW
// note: like 01, but the captured value goes through a straight-line
//       suspend chain *inside* the lambda before reaching the sink — tests
//       that capture-bridging and suspend-chain reconstruction compose.
package benchmark.nestedlambda.capturedthenchain

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

suspend fun identity(x: String): String {
    return x
}

fun runSuspend(block: suspend () -> Unit) {
    block.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) {}
    })
}

fun capturedThenChain() {
    val secret = System.getenv("SECRET") // SOURCE
    runSuspend {
        val a = identity(secret)
        val b = identity(a)
        println(b) // SINK
    }
}
