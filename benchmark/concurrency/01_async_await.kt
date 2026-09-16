// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh)
// expect: FLOW (reconstructed)
// note: `async {}` forks a child coroutine and `.await()` joins it back.
//       Was named as entirely unstudied in CLAUDE.md ("async {}/
//       Deferred.await() (a fork-join shape, not a linear chain)"); first
//       benchmark to exercise it. Confirmed to reconstruct (65→38
//       statements, 9→7 chop size without/with reconstruction) — but the
//       reason is more mundane than "fork-join" suggested: from
//       `asyncAwait`'s own bytecode perspective, `.await()` is just one
//       ordinary suspend call, so its state machine is a plain 2-state
//       switch that the existing straight-line `unrollCoroutine` path
//       already handles (see reconstruction log: "4 states -> 43
//       statements", not unrollGeneralCase). The forked lambda
//       (`asyncAwait$deferred$1`) is a separate suspend-lambda class with
//       its own straight-line state machine, reconstructed independently.
//       The actual concurrent-execution semantics of the fork (interleaving
//       with the parent) aren't modeled by this tool at all — it's a static
//       single-threaded CFG analysis — so "fork-join" doesn't manifest as a
//       CFG shape distinct from an ordinary sequential suspend chain here.
//       See 05_multiple_async_join.kt for two concurrent forks.
package benchmark.concurrency.asyncawait

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

suspend fun identity(x: String): String {
    return x
}

suspend fun asyncAwait(scope: CoroutineScope): String {
    val secret = System.getenv("SECRET") // SOURCE
    val deferred = scope.async { identity(secret) }
    val result = deferred.await()
    println(result) // SINK
    return result
}
