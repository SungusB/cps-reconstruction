// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh)
// expect: FLOW (reconstructed)
// note: `coroutineScope {}` is an inline suspend function whose lambda
//       argument is `crossinline` — structured concurrency, was named as
//       entirely unstudied in CLAUDE.md. Confirmed to reconstruct (51→25
//       statements, 8→6 chop size without/with reconstruction): being
//       `inline` means `coroutineScope`'s own suspend machinery does not
//       appear as extra states in `withCoroutineScope`'s switch at all —
//       from the caller's perspective this is still just one ordinary
//       suspend call (a plain 2-state switch), handled by the existing
//       straight-line `unrollCoroutine` path (see reconstruction log: "4
//       states -> 30 statements"). The block (`{ identity(secret) }`)
//       compiles to its own separate suspend-lambda class with its own
//       straight-line state machine, reconstructed independently.
//       [Update: the straight-line splice path referred to above has since
//       been retired — every chain now goes through the general-case
//       fast-path walk, which also elides the continuation's spill/reload
//       traffic, so current with-reconstruction statement counts are lower
//       than the ones quoted here (re-run scripts/run_ablation.sh). The
//       mechanism claim — one ordinary suspend call from the caller's
//       perspective — is unchanged. See benchmark/spill-slot/01 for why.]
package benchmark.concurrency.coroutinescope

import kotlinx.coroutines.coroutineScope

suspend fun identity(x: String): String {
    return x
}

suspend fun withCoroutineScope(): String {
    val secret = System.getenv("SECRET") // SOURCE
    val result = coroutineScope {
        identity(secret)
    }
    println(result) // SINK
    return result
}
