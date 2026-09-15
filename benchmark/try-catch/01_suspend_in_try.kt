// GROUND TRUTH (confirmed — verified against compiled bytecode, see
// docs/exception-handling/README.md)
// expect: FLOW (reconstructed)
// note: a single-level try/catch around a suspend call is now reconstructed:
//       SuspendChainReconstructor.unrollGeneralCase preserves the exceptional
//       edge to the catch handler instead of declining, once the handler
//       resolves to real code and isn't itself exceptionally protected.
package benchmark.trycatch.suspendintry

suspend fun identity(x: String): String {
    return x
}

suspend fun suspendInTry(): String {
    val secret = System.getenv("SECRET") // SOURCE
    var result = "default"
    try {
        result = identity(secret)
    } catch (e: Exception) {
        result = "error"
    }
    println(result) // SINK
    return result
}
