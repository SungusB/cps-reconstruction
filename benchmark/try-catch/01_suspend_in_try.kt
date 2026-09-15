// GROUND TRUTH (proposed — pending confirmation)
// expect: UNSUPPORTED
// note: suspend call inside a try block — the statement is covered by a
//       trap (exceptional edge to the catch handler); exception tables are
//       explicitly out of scope.
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
