// GROUND TRUTH (proposed — pending confirmation)
// expect: UNSUPPORTED
// note: the suspend call itself is unconditional, but a genuine conditional
//       sits between the (first) suspend call's resume point and the sink —
//       i.e. real branching logic inside a case block, not just at a case
//       boundary.
package benchmark.ifelse.conditionalafter

suspend fun identity(x: String): String {
    return x
}

suspend fun conditionalAfterSuspend(): String {
    val secret = System.getenv("SECRET") // SOURCE
    val a = identity(secret)
    val result = if (a.isNotEmpty()) a else "default"
    println(result) // SINK
    return result
}
