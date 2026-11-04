// GROUND TRUTH (confirmed 2026-10-19 — reported a false-positive flow in both
// modes before the fix this benchmark motivated, NO-FLOW in both after;
// FlowDroid and Joern report nothing)
// expect: NO-FLOW
// note: the helper ignores its parameter entirely and prints a literal, so
//       there is no flow of any kind, explicit or implicit. Written to test
//       InterProceduralAnalyzer.collectParamToSinksAndReturn, which (before the
//       fix) found the statements that use a parameter by substring match on
//       the rendered Jimple label (`stmtLabels[i].contains(paramName)`). SootUp keeps
//       Kotlin's local names, so a parameter called `p` "occurs" in
//       `virtualinvoke $stack1.<java.io.PrintStream: void println(...)>("constant")`
//       (via `PrintStream` and `println`), which would make the sink itself a
//       start point of the parameter's forward slice. No branch, no CDG edge
//       involved — compare 07_tainted_branch_in_callee_sink.kt.
package benchmark.control.shortparamname

fun logConstant(p: String) {
    println("constant")
}

fun shortParamNameCallee() {
    val secret = System.getenv("SECRET") // SOURCE
    logConstant(secret)
}
