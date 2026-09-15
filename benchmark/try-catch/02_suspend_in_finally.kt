// GROUND TRUTH (proposed — pending confirmation)
// expect: UNSUPPORTED
// note: suspend call inside a finally block — still a trap-covered region
//       (finally blocks are compiled as duplicated protected/handler code).
package benchmark.trycatch.suspendinfinally

suspend fun identity(x: String): String {
    return x
}

suspend fun suspendInFinally(): String {
    val secret = System.getenv("SECRET") // SOURCE
    var result = "default"
    try {
        result = "unchanged"
    } finally {
        result = identity(secret)
    }
    println(result) // SINK
    return result
}
