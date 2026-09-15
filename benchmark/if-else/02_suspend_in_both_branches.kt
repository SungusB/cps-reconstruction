// GROUND TRUTH (proposed — pending confirmation)
// expect: UNSUPPORTED
// note: a suspend call in *both* branches of the conditional — still real
//       branching logic between suspension points, just symmetric.
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
