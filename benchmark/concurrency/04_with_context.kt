// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh)
// expect: FLOW (reconstructed)
// note: `withContext(Dispatchers.Default) {}` — a structured-concurrency
//       context/dispatcher switch, was named as entirely unstudied in
//       CLAUDE.md. Confirmed to reconstruct (53→27 statements, 8→6 chop
//       size without/with reconstruction) — `withContext` is also `inline`,
//       so the same reasoning as 02/03 applies: one ordinary suspend call
//       from the caller's perspective, handled by the existing
//       straight-line path (reconstruction log: "4 states -> 32
//       statements"). CLAUDE.md specifically flags that `CancellationException`
//       is deliberately not supposed to propagate out of this the way an
//       ordinary exception does, so the try/catch reconstruction work
//       doesn't automatically extend here — this file only exercises the
//       plain (no try/catch) shape; a `withContext` interacting with a
//       `catch (e: CancellationException)` is not covered by this file and
//       stays an open question.
//       [Update: the straight-line splice path referred to above has since
//       been retired — every chain now goes through the general-case
//       fast-path walk, which also elides the continuation's spill/reload
//       traffic, so current with-reconstruction statement counts are lower
//       than the ones quoted here (re-run scripts/run_ablation.sh). The
//       mechanism claim — one ordinary suspend call from the caller's
//       perspective — is unchanged. See benchmark/spill-slot/01 for why.]
package benchmark.concurrency.withcontext

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun identity(x: String): String {
    return x
}

suspend fun withContextSwitch(): String {
    val secret = System.getenv("SECRET") // SOURCE
    val result = withContext(Dispatchers.Default) {
        identity(secret)
    }
    println(result) // SINK
    return result
}
