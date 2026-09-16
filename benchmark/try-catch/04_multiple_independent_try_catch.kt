// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh)
// expect: FLOW (reconstructed)
// note: two separate, non-nested try/catch blocks in the same method, each
//       wrapping its own suspend call. Was named as untested in
//       docs/exception-handling/README.md §9; confirmed to reconstruct
//       (92→21 statements, 10→6 chop size without/with reconstruction) —
//       unrollGeneralCase's walk calls resolvePreservableExceptionalEdges
//       independently for each protected statement it meets, so a second,
//       unrelated try/catch downstream needs no special handling.
package benchmark.trycatch.multipleblocks

suspend fun identity(x: String): String {
    return x
}

suspend fun multipleTryCatchBlocks(): String {
    val secret = System.getenv("SECRET") // SOURCE
    var a = "default"
    try {
        a = identity(secret)
    } catch (e: Exception) {
        a = "error-a"
    }
    var b = "default"
    try {
        b = identity(a)
    } catch (e: Exception) {
        b = "error-b"
    }
    println(b) // SINK
    return b
}
