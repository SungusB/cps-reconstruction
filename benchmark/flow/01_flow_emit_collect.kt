// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh, node-level
// taint-path inspection below)
// expect: NO-FLOW (tool limitation, not a real absence of taint — see note)
// note: `flow { emit(secret) }` builds a cold Flow; `.collect { }` drains
//       it. Named as entirely unstudied in CLAUDE.md ("Flow/channel
//       constructs (a different suspend-iteration state machine shape)");
//       first benchmark to exercise it. This one surfaced a real,
//       previously-undocumented TaintSlicer limitation, not a
//       SuspendChainReconstructor bug: reconstruction succeeds cleanly
//       (no decline, no crash) and correctly reports NO flow, but the
//       *un*reconstructed run reports one (line 14→21) that is a verified
//       FALSE POSITIVE, not a real detection. Inspecting the raw CPG's
//       `onTaintPath` nodes shows the "flow" is built entirely from CDG
//       edges chained through the coroutine dispatch's own suspend-check
//       `if`s (`if $stack22 != l5`, i.e. `!= COROUTINE_SUSPENDED`) —
//       TaintSlicer.slice() puts `cdgEdges` in the same forward/backward
//       adjacency as `ddgEdges` (see TaintSlicer.kt), so "this statement
//       runs conditionally on a branch that used tainted data" gets treated
//       as equivalent to "this statement's value depends on tainted data."
//       That coincidentally lines up with the right answer in every
//       straight-line/branch/loop/try-catch benchmark tested so far (the
//       CDG-based path and the real DDG-based path agree there), but here
//       it manufactures a flow with no genuine value dependency at all: the
//       real path from `secret` to `println` is `emit(secret)` -> the
//       collector callback assigns it to `result` -> `println(result)`, a
//       flow through a *callback invocation*, which this tool has no model
//       for at all (it has no call graph, see ReconstructionCli's doc
//       comment) — reconstruction doesn't fix that, it just removes the
//       suspend-check `if`s that were producing the spurious CDG path,
//       correctly eliminating a false positive without providing a real
//       true positive to replace it. See CLAUDE.md for the broader
//       TaintSlicer CDG-over-approximation note this benchmark motivated.
package benchmark.flow.emitcollect

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

suspend fun flowEmitCollect(): String {
    val secret = System.getenv("SECRET") // SOURCE
    var result = "default"
    flow {
        emit(secret)
    }.collect { value ->
        result = value
    }
    println(result) // SINK
    return result
}
