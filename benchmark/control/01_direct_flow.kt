// GROUND TRUTH (proposed — pending confirmation)
// expect: FLOW
// note: no coroutines at all — plain intra-procedural source-to-sink flow.
//       Regression check: the tool must still find the simplest possible
//       case with no suspend machinery involved anywhere.
package benchmark.control.directflow

fun directFlow() {
    val secret = System.getenv("SECRET") // SOURCE
    println(secret) // SINK
}
