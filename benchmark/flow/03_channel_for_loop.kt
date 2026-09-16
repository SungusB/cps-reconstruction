// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh, node-level
// taint-path inspection below)
// expect: NO-FLOW (tool limitation, not a real absence of taint — see note)
// note: `for (item in channel)` desugars to a suspend `hasNext()` call
//       feeding a *real* boolean check (not the COROUTINE_SUSPENDED
//       sentinel check `isCoroutineSuspendedCheck` already knows to skip),
//       deciding whether to continue the loop or fall through — the
//       suspend call sits in the loop's own back-edge/condition, not the
//       body, unlike benchmark/loops/*.kt. Structurally this DID hit
//       unrollGeneralCase (not the plain straight-line path — see log:
//       "general-case-unrolled ... 94 statements -> 19 real statements")
//       and reconstructed cleanly, no decline, no crash — so the "different
//       suspend-iteration state machine shape" concern this file was
//       written to test turned out to already be handled by the existing
//       general-case walk. What it actually surfaced instead is the same
//       TaintSlicer limitation as 01/02_*.kt, in an even more clear-cut
//       form: the pre-reconstruction "flow" (line 19→27) claims `secret`
//       taints `result = "default"` — a *string literal assignment*, with
//       zero data dependency on anything — purely because that assignment
//       statement is control-dependent (CDG) on the same suspend-check `if`
//       chain that `channel.send(secret)` feeds into. That's about as
//       unambiguous a false positive as this mechanism produces. See
//       01_flow_emit_collect.kt's note and CLAUDE.md for the general
//       TaintSlicer CDG-over-approximation writeup this file motivated.
package benchmark.flow.channelforloop

import kotlinx.coroutines.channels.Channel

suspend fun channelForLoop(): String {
    val secret = System.getenv("SECRET") // SOURCE
    val channel = Channel<String>(1)
    channel.send(secret)
    channel.close()
    var result = "default"
    for (item in channel) {
        result = item
    }
    println(result) // SINK
    return result
}
