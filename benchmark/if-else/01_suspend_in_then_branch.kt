// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh)
// expect: FLOW (reconstructed)
// note: the suspend call only happens inside the `if` branch — a genuine
//       conditional (JIfStmt) sits between the source and the suspend call.
//       SuspendChainReconstructor.unrollGeneralCase walks the branch's real
//       CFG successors and reconstructs this instead of declining.
package benchmark.ifelse.thenbranch

suspend fun identity(x: String): String {
    return x
}

suspend fun suspendInThenBranch(flag: Boolean): String {
    val secret = System.getenv("SECRET") // SOURCE
    var result = "default"
    if (flag) {
        result = identity(secret)
    }
    println(result) // SINK
    return result
}
