// GROUND TRUTH (confirmed)
// expect: NO-FLOW
// note: SPILL-SLOT CONFLATION, sequential-loop variant — loop-body locals
//       are the classic JVM slot-reuse case. `secret` (first loop body)
//       and `safe` (second loop body) share a slot and are both spilled to
//       `L$0` (verified against javap); the loop counters go to `I$0`/`I$1`.
//       The sink prints the untainted `safe` reloaded from `L$0` on the
//       case-2 resume path. See 01 for the full argument.
package benchmark.spillslot.sequentialloops

suspend fun tick(): Int = 1

suspend fun sequentialLoops(): Int {
    var len = 0
    for (i in 0 until 1) {
        val secret = System.getenv("SECRET") // SOURCE
        tick()                               // suspension 1: secret -> L$0
        len += secret.length
    }
    for (j in 0 until 1) {
        val safe = "constant"
        tick()                               // suspension 2: safe -> L$0
        println(safe)                        // SINK (should NOT be flagged)
    }
    return len
}
