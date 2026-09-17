// GROUND TRUTH (confirmed)
// expect: NO-FLOW
// note: RESULT-SLOT CONFLATION — the other shared slot in the coroutine
//       ABI. Verified against javap (kotlinc 2.4.20): the continuation's
//       `result` field is read ONCE into a single local (`$result`, JVM
//       slot 4) at method entry, before the dispatch switch. On the fast
//       path each call's return value stays on the operand stack; on the
//       resumed path (`case N`) the code reloads spilled locals and then
//       pushes `$result`. Both paths MERGE at the same `checkcast` after
//       each call, so once Jimple lifts the stack into locals, one shared
//       `$result` definition feeds the merge after suspension 1 (tainted
//       `identity(secret)`) AND the merge after suspension 2 (untainted
//       `tick()`). Only the latter is printed. A flow-insensitive analysis,
//       or one that models the runtime's write into `result` coarsely
//       ("any suspend call's value may land there"), conflates the two and
//       reports a false positive; after reconstruction each call's value
//       is a distinct direct assignment and `result` is never read.
package benchmark.spillslot.sharedresultslot

suspend fun identity(x: String): String = x
suspend fun tick(): Int = 1

suspend fun sharedResultSlot(): Int {
    val secret = System.getenv("SECRET") // SOURCE
    val echoed = identity(secret)        // suspension 1: $result = tainted
    val n = tick()                       // suspension 2: $result = untainted
    println(n)                           // SINK (should NOT be flagged)
    return echoed.length + n
}
