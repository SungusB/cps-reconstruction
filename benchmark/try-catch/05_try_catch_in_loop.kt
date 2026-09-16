// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh)
// expect: FLOW (reconstructed)
// note: a try/catch nested inside a while loop body, wrapping the loop's
//       suspend call — combines the loop back-edge shape from
//       benchmark/loops/ with the exceptional-edge shape from 01. Was named
//       as untested in docs/exception-handling/README.md §9; confirmed to
//       reconstruct (76→20 statements, 10→5 chop size without/with
//       reconstruction). The general-case walk treats exceptional edges the
//       same way regardless of where the protected statement sits in the
//       CFG, so a try/catch nested inside an `if` branch instead of a loop
//       goes through the same code path and isn't tracked as a separate
//       untested case.
package benchmark.trycatch.nestedinloop

suspend fun identity(x: String): String {
    return x
}

suspend fun tryCatchInLoop(count: Int): String {
    val secret = System.getenv("SECRET") // SOURCE
    var result = secret
    var i = 0
    while (i < count) {
        try {
            result = identity(result)
        } catch (e: Exception) {
            result = "error"
        }
        i++
    }
    println(result) // SINK
    return result
}
