// GROUND TRUTH (proposed — pending confirmation)
// expect: NO-FLOW
// note: no coroutines, no source, no sink — pure negative control.
package benchmark.control.notaint

fun noTaint(): Int {
    val x = 21
    val y = x * 2
    return y
}
