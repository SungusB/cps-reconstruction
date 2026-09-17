// GROUND TRUTH (confirmed — verified against compiled bytecode, see
// docs/exception-handling/README.md)
// expect: UNSUPPORTED
// note: a suspend call inside `finally` still declines, and is confirmed to
//       be a materially different shape from 01/03, not just an unhandled
//       instance of the same one: kotlinc compiles the try's catch-all
//       handler as itself wrapped in a second, self-targeting trap (so
//       re-entering the finally logic on suspend can still route a further
//       exception to the same place) — a nested/self-referential protected
//       scope that SuspendChainReconstructor's single-level exceptional-edge
//       handling explicitly declines rather than guesses at.
//       The try body is a call, not a constant assignment, on purpose: classic
//       Soot's frontend removes a trap whose protected range cannot throw
//       (the finally handler becomes unreachable code), which turned the
//       original `result = "unchanged"` version into a trap-free straight-line
//       method in Soot's view — the FlowDroid baseline then never saw the
//       finally shape at all. SootUp keeps the trap either way.
package benchmark.trycatch.suspendinfinally

suspend fun identity(x: String): String {
    return x
}

fun plain(x: String): String {
    return x
}

suspend fun suspendInFinally(): String {
    val secret = System.getenv("SECRET") // SOURCE
    var result = "default"
    try {
        result = plain("unchanged")
    } finally {
        result = identity(secret)
    }
    println(result) // SINK
    return result
}
