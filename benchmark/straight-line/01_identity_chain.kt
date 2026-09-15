// GROUND TRUTH (proposed — pending confirmation)
// expect: FLOW
// note: validated against real kotlinc output during Phase 1 development —
//       two sequential suspend calls, no control flow between them.
package benchmark.straightline.identitychain

suspend fun identity(x: String): String {
    return x
}

suspend fun identityChain(): String {
    val secret = System.getenv("SECRET") // SOURCE
    val a = identity(secret)
    val b = identity(a)
    println(b) // SINK
    return b
}
