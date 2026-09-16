// GROUND TRUTH (confirmed — verified via scripts/run_ablation.sh)
// expect: FLOW (reconstructed)
// note: one protected region with two `catch` clauses of different
//       exception types on the same suspend call — the compiler emits two
//       exception-table entries covering the same range, so
//       exceptionalSuccessors(stmt) returns a two-entry map instead of one.
//       Was named as untested in docs/exception-handling/README.md §9;
//       confirmed to reconstruct (64→17 statements, 6→4 chop size
//       without/with reconstruction) — resolvePreservableExceptionalEdges
//       already loops over every entry in that map, so both catch clauses
//       are preserved with no special handling needed.
package benchmark.trycatch.multiplecatches

suspend fun identity(x: String): String {
    return x
}

suspend fun multipleCatchClauses(): String {
    val secret = System.getenv("SECRET") // SOURCE
    var result = "default"
    try {
        result = identity(secret)
    } catch (e: IllegalStateException) {
        result = "illegal-state"
    } catch (e: Exception) {
        result = "error"
    }
    println(result) // SINK
    return result
}
