// GROUND TRUTH (proposed — pending confirmation)
// expect: FLOW
// note: the sink is inside the suspend callee itself (paramToSinks path),
//       not reached via the callee's return value (paramToReturn path) —
//       exercises a different edge of the interprocedural mechanism than
//       the other straight-line files.
package benchmark.straightline.sinkinsidecallee

suspend fun logIt(x: String) {
    val prefixed = x
    println(prefixed) // SINK
}

suspend fun sinkInsideCallee() {
    val secret = System.getenv("SECRET") // SOURCE
    val a = secret
    logIt(a)
}
