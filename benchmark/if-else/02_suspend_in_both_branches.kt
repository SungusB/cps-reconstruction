// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh)
// expect: FLOW (reconstructed)
// note: a suspend call in *both* branches of the conditional — still real
//       branching logic between suspension points, just symmetric. Both
//       branches reconstruct via unrollGeneralCase's normal-successor walk.
package benchmark.ifelse.bothbranches

suspend fun identity(x: String): String {
    return x
}

suspend fun otherIdentity(x: String): String {
    return x
}

suspend fun suspendInBothBranches(flag: Boolean): String {
    val secret = System.getenv("SECRET") // SOURCE
    val result: String = if (flag) {
        identity(secret)
    } else {
        otherIdentity(secret)
    }
    println(result) // SINK
    return result
}
