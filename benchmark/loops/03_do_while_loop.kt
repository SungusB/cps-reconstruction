// GROUND TRUTH (proposed — pending confirmation)
// expect: UNSUPPORTED
// note: suspend call inside a do-while loop — condition is checked at the
//       end, but the back-edge shape is the same class of problem.
package benchmark.loops.dowhileloop

suspend fun identity(x: String): String {
    return x
}

suspend fun doWhileLoop(count: Int): String {
    val secret = System.getenv("SECRET") // SOURCE
    var result = secret
    var i = 0
    do {
        result = identity(result)
        i++
    } while (i < count)
    println(result) // SINK
    return result
}
