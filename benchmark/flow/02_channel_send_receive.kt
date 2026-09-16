// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh, node-level
// taint-path inspection below)
// expect: NO-FLOW (tool limitation, not a real absence of taint — see note)
// note: a buffered `Channel`, one `send()` and one `receive()` — no
//       iteration protocol, just two ordinary suspend calls in sequence.
//       Same finding as 01_flow_emit_collect.kt, in an even sharper form
//       because there's no lambda involved at all here: `secret`,
//       `channel.send(secret)`, and `channel.receive()` are all in the same
//       function, so if this tool modeled "value written into a container,
//       then read back out of the same container" as data flow, this is
//       the simplest possible case for it to succeed on. It doesn't —
//       raw-CPG inspection of the pre-reconstruction "flow" (line 14→18)
//       shows its `onTaintPath` nodes are `secret` -> `send(secret)` ->
//       `if $stack20 != COROUTINE_SUSPENDED` -> `if $stack18 != COROUTINE_SUSPENDED`
//       (`receive()`'s *own*, structurally-unrelated suspend check) ->
//       `println`: a chain of CDG edges through two different suspend-check
//       `if`s, not a DDG path through the channel. `channel.send()` and
//       `channel.receive()` are never connected by anything but "the same
//       local variable holds both channel references" — the DDG has no
//       concept of a container's contents, so it was never going to find
//       this either way. Reconstruction removes both suspend-check `if`s as
//       bookkeeping, which correctly removes the spurious CDG chain and
//       reports no flow — the honest answer, just not for a reason
//       connected to the channel semantics at all.
package benchmark.flow.channelsendreceive

import kotlinx.coroutines.channels.Channel

suspend fun channelSendReceive(): String {
    val secret = System.getenv("SECRET") // SOURCE
    val channel = Channel<String>(1)
    channel.send(secret)
    val result = channel.receive()
    println(result) // SINK
    return result
}
