package com.infinity.cps.reconstruction.taint

import com.infinity.cps.reconstruction.cpg.CpgData
import com.infinity.cps.reconstruction.cpg.FieldAliasTracker
import sootup.core.jimple.basic.Local
import sootup.core.jimple.common.ref.JInstanceFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.signatures.MethodSignature
import sootup.java.core.views.JavaView
import java.util.LinkedList

/**
 * Computes an inter-procedural [TaintSummary] for a set of methods via
 * fixpoint iteration.
 *
 * Each summary captures which parameters taint the return value, which flow
 * to a sink, whether a source taints the return value, which parameters get
 * captured into fields (how a closure receives outer-scope data), and which
 * fields reach a sink when read (letting [TaintSlicer.findCapturedFieldFlows]
 * trace taint into a nested closure without inlining its body). A method
 * with no body gets an empty summary.
 *
 * The [CpgData] built for each method is cached and exposed via [getCpg], so
 * later per-method slicing doesn't rebuild the same CPG twice.
 */
class InterProceduralAnalyzer {

    private val summaries: MutableMap<String, TaintSummary> = linkedMapOf()
    private val cpgCache: MutableMap<String, CpgData> = linkedMapOf()
    private lateinit var view: JavaView

    fun analyze(view: JavaView, methods: Set<MethodSignature>) {
        this.view = view
        var changed = true
        var iter = 0
        while (changed && iter < 3) {
            changed = false
            for (sig in methods) {
                val old = summaries[sig.toString()]
                val newSummary = computeSummary(sig)
                if (old == null || !summariesEqual(old, newSummary)) {
                    summaries[sig.toString()] = newSummary
                    changed = true
                }
            }
            iter++
        }
        println("[*] inter-procedural analysis: ${summaries.size} summaries in $iter iterations")
    }

    fun getSummary(methodSig: String): TaintSummary? = summaries[methodSig]

    fun getCpg(methodSig: String): CpgData? = cpgCache[methodSig]

    private fun summariesEqual(a: TaintSummary, b: TaintSummary): Boolean =
        a.paramToReturn == b.paramToReturn &&
            a.sourceToReturn == b.sourceToReturn &&
            a.paramToSinks.size == b.paramToSinks.size &&
            a.paramToFields == b.paramToFields &&
            a.fieldToSinks.size == b.fieldToSinks.size

    private fun computeSummary(sig: MethodSignature): TaintSummary {
        val summary = TaintSummary(sig.toString())

        val methodOpt = view.getMethod(sig)
        if (methodOpt.isEmpty || !methodOpt.get().hasBody()) return summary

        val method = methodOpt.get()
        val cpg = CpgData.fromMethod(method)
        cpgCache[sig.toString()] = cpg

        val paramList: List<Local> = try {
            method.body.parameterLocals.toList()
        } catch (e: Exception) {
            return summary
        }

        val forwardAdj = linkedMapOf<Int, MutableList<Int>>()
        for (i in cpg.stmtLabels.indices) forwardAdj[i] = mutableListOf()
        for (edge in cpg.ddgEdges) {
            val parts = edge.split("|")
            forwardAdj[parts[0].toInt()]?.add(parts[1].toInt())
        }
        for (edge in cpg.cdgEdges) {
            val parts = edge.split("|")
            forwardAdj[parts[0].toInt()]?.add(parts[1].toInt())
        }

        val sourceStmts = linkedSetOf<Int>()
        for (i in cpg.stmtLabels.indices) {
            if (TaintSlicer.isSource(cpg.stmtLabels[i])) sourceStmts.add(i)
        }

        collectParamToFields(cpg, paramList, summary)
        collectFieldToSinks(cpg, forwardAdj, summary)
        collectParamToSinksAndReturn(cpg, paramList, forwardAdj, summary)
        collectSourceToReturn(cpg, sourceStmts, forwardAdj, summary)

        return summary
    }

    /** Records constructor-param-to-field writes (`this.x = param`) — how a closure captures outer-scope data. */
    private fun collectParamToFields(cpg: CpgData, paramList: List<Local>, summary: TaintSummary) {
        for (i in cpg.stmtLabels.indices) {
            val stmt = cpg.indexToStmt[i] as? JAssignStmt ?: continue
            val defOpt = stmt.def
            if (!defOpt.isPresent || defOpt.get() !is JInstanceFieldRef) continue
            val fieldRef = defOpt.get() as JInstanceFieldRef

            // Normalized as "DeclClass.fieldName" to match FieldAliasTracker's
            // own field-signature convention, since the two are joined by
            // this key in TaintSlicer.findCapturedFieldFlows.
            val fieldSig = fieldRef.fieldSignature
            val fieldSigStr = "${fieldSig.declClassType}.${fieldSig.name}"

            for (useVal in stmt.uses.toList()) {
                if (useVal !is Local) continue
                val useName = useVal.toString()
                for (paramIdx in paramList.indices) {
                    if (paramList[paramIdx].toString() == useName) {
                        summary.paramToFields.getOrPut(paramIdx) { linkedSetOf() }.add(fieldSigStr)
                    }
                }
            }
        }
    }

    /** Records which fields, when read, reach a sink within this method. */
    private fun collectFieldToSinks(cpg: CpgData, forwardAdj: Map<Int, List<Int>>, summary: TaintSummary) {
        val fieldAccesses = FieldAliasTracker.findFieldAccesses(cpg)
        for (acc in fieldAccesses) {
            if (acc.isWrite) continue
            val fieldSlice = linkedSetOf<Int>()
            bfsForward(acc.stmtIdx, forwardAdj, fieldSlice)
            for (idx in fieldSlice) {
                val stmtStr = cpg.stmtLabels[idx]
                if (TaintSlicer.isSink(stmtStr)) {
                    summary.fieldToSinks.getOrPut(acc.fieldSignature) { mutableListOf() }
                        .add(TaintSummary.SinkInfo(idx, stmtStr, TaintSlicer.sinkCategory(stmtStr)))
                }
            }
        }
    }

    /** Records which parameters reach a sink, and which flow to the return value. */
    private fun collectParamToSinksAndReturn(
        cpg: CpgData,
        paramList: List<Local>,
        forwardAdj: Map<Int, List<Int>>,
        summary: TaintSummary,
    ) {
        for (paramIdx in paramList.indices) {
            val paramName = paramList[paramIdx].toString()

            val startPoints = linkedSetOf<Int>()
            for (i in cpg.stmtLabels.indices) {
                if (cpg.stmtLabels[i].contains(paramName)) startPoints.add(i)
            }

            val slice = linkedSetOf<Int>()
            for (start in startPoints) bfsForward(start, forwardAdj, slice)

            for (idx in slice) {
                val stmtStr = cpg.stmtLabels[idx]
                if (TaintSlicer.isSink(stmtStr)) {
                    summary.paramToSinks.getOrPut(paramIdx) { mutableListOf() }
                        .add(TaintSummary.SinkInfo(idx, stmtStr, TaintSlicer.sinkCategory(stmtStr)))
                }
            }

            if (slice.any { cpg.stmtLabels[it].let { s -> s.startsWith("return ") && s != "return" } }) {
                summary.paramToReturn.add(paramIdx)
            }
        }
    }

    private fun collectSourceToReturn(
        cpg: CpgData,
        sourceStmts: Set<Int>,
        forwardAdj: Map<Int, List<Int>>,
        summary: TaintSummary,
    ) {
        val sourceSlice = linkedSetOf<Int>()
        for (src in sourceStmts) bfsForward(src, forwardAdj, sourceSlice)

        summary.sourceToReturn = sourceSlice.any {
            cpg.stmtLabels[it].let { s -> s.startsWith("return ") && s != "return" }
        }
    }

    private fun bfsForward(start: Int, adj: Map<Int, List<Int>>, visited: MutableSet<Int>) {
        val queue: java.util.Queue<Int> = LinkedList()
        queue.add(start)
        visited.add(start)
        while (queue.isNotEmpty()) {
            val curr = queue.poll()
            for (s in adj[curr].orEmpty()) {
                if (s !in visited) {
                    visited.add(s)
                    queue.add(s)
                }
            }
        }
    }
}
