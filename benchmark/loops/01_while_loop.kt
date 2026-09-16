// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh)
// expect: FLOW (reconstructed)
// note: suspend call inside a while loop — the loop back-edge is a goto to
//       a non-dispatch target (the loop condition check), not one of the
//       switch's registered case labels. unrollGeneralCase's normal-successor
//       walk follows this back-edge and reconstructs the loop.
package benchmark.loops.whileloop

suspend fun identity(x: String): String {
    return x
}

suspend fun whileLoop(count: Int): String {
    val secret = System.getenv("SECRET") // SOURCE
    var result = secret
    var i = 0
    while (i < count) {
        result = identity(result)
        i++
    }
    println(result) // SINK
    return result
}
