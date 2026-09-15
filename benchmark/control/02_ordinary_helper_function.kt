// GROUND TRUTH (proposed — pending confirmation)
// expect: FLOW
// note: no coroutines — an ordinary (non-suspend) helper function pass-
//       through. Regression check for the paramToReturn interprocedural
//       bridge in isolation, without any coroutine reconstruction involved.
package benchmark.control.ordinaryhelper

fun identity(x: String): String {
    return x
}

fun ordinaryHelperFunction() {
    val secret = System.getenv("SECRET") // SOURCE
    val result = identity(secret)
    println(result) // SINK
}
