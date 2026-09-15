// GROUND TRUTH (proposed — pending confirmation)
// expect: UNSUPPORTED
// note: the suspend call itself is textually after the try/catch, but it's
//       part of the same case block as the protected region (no suspension
//       point separates them), so the case block as a whole is still
//       trap-covered.
package benchmark.trycatch.suspendaftercatch

suspend fun identity(x: String): String {
    return x
}

suspend fun suspendAfterCatch(): String {
    val secret = System.getenv("SECRET") // SOURCE
    var parsed = secret
    try {
        parsed = secret.trim()
    } catch (e: Exception) {
        parsed = "error"
    }
    val result = identity(parsed)
    println(result) // SINK
    return result
}
