package com.infinity.cps.reconstruction.taint

/**
 * Summarizes how taint flows through a single method:
 * - which parameters taint the return value ([paramToReturn])
 * - which parameters flow to a sink ([paramToSinks])
 * - whether a source taints the return value ([sourceToReturn])
 * - which parameters get captured into fields ([paramToFields]) — how a
 *   closure receives outer-scope data, compiled as constructor-param-to-field
 *   writes rather than a method call
 * - which fields, when read, reach a sink ([fieldToSinks])
 *
 * The last two let [TaintSlicer.findCapturedFieldFlows] trace taint from an
 * outer function into a nested lambda's captured variable without inlining
 * the lambda's body.
 *
 * Computed by [InterProceduralAnalyzer], consumed by [TaintSlicer].
 */
class TaintSummary(var methodSignature: String? = null) {

    val paramToReturn: MutableSet<Int> = linkedSetOf()
    val paramToSinks: MutableMap<Int, MutableList<SinkInfo>> = linkedMapOf()
    var sourceToReturn: Boolean = false
    val paramToFields: MutableMap<Int, MutableSet<String>> = linkedMapOf()
    val fieldToSinks: MutableMap<String, MutableList<SinkInfo>> = linkedMapOf()

    /** A single sink reached by a tainted parameter or field. */
    data class SinkInfo(val stmtIdx: Int, val sinkMethod: String, val category: TaintCategory)
}
