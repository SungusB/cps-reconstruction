// GROUND TRUTH (confirmed — verified against compiled bytecode, see
// docs/exception-handling/README.md)
// expect: FLOW (reconstructed)
// note: the try/catch here doesn't even wrap the suspend call (it protects
//       the ordinary secret.trim() call, unrelated to suspension) — the
//       general case walks straight through it, preserving the exceptional
//       edge, and reaches the later suspend call with no special handling
//       needed at all.
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
