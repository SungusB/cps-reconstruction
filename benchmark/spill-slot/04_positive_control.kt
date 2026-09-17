// GROUND TRUTH (confirmed)
// expect: FLOW
// note: POSITIVE CONTROL for the spill-slot family. Same two-block shape
//       and same slot sharing as 01, but the tainted value genuinely
//       escapes the first block (via `carried`) and is printed in the
//       second. This confirms NO-FLOW on 01-03 means "no taint reaches
//       the sink," not "the tool can't see across this shape at all."
package benchmark.spillslot.positivecontrol

suspend fun tick(): Int = 1

suspend fun positiveControl(): Int {
    val carried = run {
        val secret = System.getenv("SECRET") // SOURCE
        tick()                               // suspension 1
        secret
    }
    run {
        val safe = "constant"
        tick()                               // suspension 2
        println(safe)
        println(carried)                     // SINK
    }
    return carried.length
}
