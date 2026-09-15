// GROUND TRUTH (proposed — pending confirmation)
// expect: UNSUPPORTED
// note: suspend call inside a while loop — the loop back-edge is a goto to
//       a non-dispatch target (the loop condition check), not one of the
//       switch's registered case labels.
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
