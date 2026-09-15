// GROUND TRUTH (proposed — pending confirmation)
// expect: UNSUPPORTED
// note: suspend call inside a for loop over a range — compiles to an
//       iterator-driven loop with the same kind of back-edge as `while`.
package benchmark.loops.forloop

suspend fun identity(x: String): String {
    return x
}

suspend fun forLoop(count: Int): String {
    val secret = System.getenv("SECRET") // SOURCE
    var result = secret
    for (i in 0 until count) {
        result = identity(result)
    }
    println(result) // SINK
    return result
}
