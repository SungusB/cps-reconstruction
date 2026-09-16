// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh)
// expect: FLOW (reconstructed)
// note: `supervisorScope {}` — structured concurrency with independent
//       child-failure isolation (a `SupervisorJob` instead of a plain
//       `Job`), was named as entirely unstudied in CLAUDE.md alongside
//       `coroutineScope`/`withContext`. Confirmed to reconstruct (51→25
//       statements, 8→6 chop size without/with reconstruction) — same
//       reason as 02_coroutine_scope.kt: `supervisorScope` is also `inline`,
//       so it doesn't add states to the caller's own switch; the caller
//       reconstructs via the existing straight-line path (reconstruction
//       log: "4 states -> 30 statements"). The `SupervisorJob` failure-
//       isolation semantics themselves aren't exercised by this file — it
//       has no failing child to isolate — and remain unstudied.
package benchmark.concurrency.supervisorscope

import kotlinx.coroutines.supervisorScope

suspend fun identity(x: String): String {
    return x
}

suspend fun withSupervisorScope(): String {
    val secret = System.getenv("SECRET") // SOURCE
    val result = supervisorScope {
        identity(secret)
    }
    println(result) // SINK
    return result
}
