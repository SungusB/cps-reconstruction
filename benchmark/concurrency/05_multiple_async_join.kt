// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh)
// expect: FLOW (reconstructed)
// note: two independent `async {}` forks joined via two `.await()` calls —
//       only one of the two (`first`) carries tainted data; `second` is
//       unrelated. Checks two things 01_async_await.kt's single-fork case
//       can't: (1) that two sibling SuspendLambda classes from the same
//       outer function don't get confused with each other or double-counted
//       (the project has a documented history of a real dedup bug once a
//       method's CFG has more than one path — see CLAUDE.md) — confirmed
//       clean: exactly one flow reported, line 26 -> line 30, both with and
//       without reconstruction, and `first$1`/`second$1` are unrolled as
//       distinct classes in the reconstruction log; (2) reconstructs
//       (123→94 statements, 16→13 chop size without/with reconstruction).
//       `multipleAsyncJoin` itself has two sequential suspend points
//       (`first.await()`, `second.await()`) but no branching between them,
//       so it's still a straight-line switch (5 states) handled by the
//       existing `unrollCoroutine` path, not unrollGeneralCase — two
//       concurrent forks still doesn't produce a CFG shape distinct from an
//       ordinary sequential suspend chain from the caller's side.
package benchmark.concurrency.multipleasync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

suspend fun identity(x: String): String {
    return x
}

suspend fun otherIdentity(x: String): String {
    return x
}

suspend fun multipleAsyncJoin(scope: CoroutineScope): String {
    val secret = System.getenv("SECRET") // SOURCE
    val first = scope.async { identity(secret) }
    val second = scope.async { otherIdentity("unrelated") }
    val combined = first.await() + second.await()
    println(combined) // SINK
    return combined
}
