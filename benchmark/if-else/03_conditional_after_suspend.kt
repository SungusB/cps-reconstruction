// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh)
// expect: FLOW (reconstructed)
// note: the suspend call itself is unconditional, but a genuine conditional
//       sits between the (first) suspend call's resume point and the sink —
//       i.e. real branching logic inside a case block, not just at a case
//       boundary. This also reconstructs via unrollGeneralCase.
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
