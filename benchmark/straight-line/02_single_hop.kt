// GROUND TRUTH (proposed — pending confirmation)
// expect: FLOW
// note: the minimal case — exactly one suspend call between source and sink.
package benchmark.straightline.singlehop

suspend fun passthrough(x: String): String {
    return x
}

suspend fun singleHop(): String {
    val secret = System.getenv("SECRET") // SOURCE
    val result = passthrough(secret)
    println(result) // SINK
    return result
}
