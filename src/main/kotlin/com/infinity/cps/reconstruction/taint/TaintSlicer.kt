package com.infinity.cps.reconstruction.taint

import com.infinity.cps.reconstruction.cpg.CpgData
import sootup.core.jimple.basic.Local
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JNewExpr
import sootup.core.jimple.common.expr.JSpecialInvokeExpr
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.signatures.MethodSignature
import sootup.java.core.views.JavaView
import java.util.LinkedList

/**
 * Program slicing and taint flow analysis over a [CpgData].
 *
 * Identifies source statements (e.g. `readLine()`, `System.getenv()`) and
 * sink statements (e.g. `Runtime.exec()`, a file write), then computes a
 * forward slice (reachable from sources), a backward slice (can reach
 * sinks), their intersection (the chop), and validated source-to-sink paths
 * through the chop.
 *
 * This is a minimal, hand-verifiable engine, not a general vulnerability
 * scanner: it exists to demonstrate that flows recovered from a
 * suspend-chain body reconstructed by
 * [com.infinity.cps.reconstruction.reconstruct.SuspendChainReconstructor]
 * are real. It does not do severity scoring or sanitizer detection.
 */
object TaintSlicer {

    /**
     * A (class hint, method-name fragment) pair matched against a rendered
     * Jimple statement.
     *
     * SootUp renders a call as `<DeclaringClass: ReturnType methodName(...)>`,
     * so the class name is never adjacent to the method name — a combined
     * string like `"System.getenv("` can never match. The two fragments are
     * checked independently instead. An empty `classHint` matches on the
     * method name alone (for names specific enough not to need
     * disambiguating, e.g. `readLine(`).
     */
    data class MethodPattern(val classHint: String, val methodFragment: String) {
        fun matches(stmtLabel: String): Boolean =
            stmtLabel.contains(methodFragment) && (classHint.isEmpty() || stmtLabel.contains(classHint))
    }

    private data class SinkPattern(val pattern: MethodPattern, val category: TaintCategory)

    private val SOURCE_PATTERNS = listOf(
        MethodPattern("", "readLine("),
        MethodPattern("System", "getenv("),
        MethodPattern("System", "getProperty("),
    )

    private val SINK_PATTERNS = listOf(
        SinkPattern(MethodPattern("Runtime", "exec("), TaintCategory.COMMAND_INJECTION),
        SinkPattern(MethodPattern("ProcessBuilder", "start("), TaintCategory.COMMAND_INJECTION),
        SinkPattern(MethodPattern("FileOutputStream", "<init>("), TaintCategory.PATH_TRAVERSAL),
        SinkPattern(MethodPattern("Socket", "<init>("), TaintCategory.SSRF),
        SinkPattern(MethodPattern("URL", "openConnection("), TaintCategory.SSRF),
        SinkPattern(MethodPattern("", "println("), TaintCategory.INFORMATION_EXPOSURE),
    )

    fun isSource(stmtLabel: String): Boolean = SOURCE_PATTERNS.any { it.matches(stmtLabel) }

    fun isSink(stmtLabel: String): Boolean = SINK_PATTERNS.any { it.pattern.matches(stmtLabel) }

    fun sinkCategory(methodSig: String): TaintCategory =
        SINK_PATTERNS.firstOrNull { it.pattern.matches(methodSig) }?.category ?: TaintCategory.UNKNOWN

    class SliceResult(val methodSignature: String?) {
        val sourceStmts: MutableList<Int> = mutableListOf()
        val sinkStmts: MutableList<Int> = mutableListOf()
        val forwardSlice: MutableSet<Int> = linkedSetOf()
        val backwardSlice: MutableSet<Int> = linkedSetOf()
        val chop: MutableSet<Int> = linkedSetOf()
        val taintFlows: MutableList<TaintFlow> = mutableListOf()

        fun hasVulnerability(): Boolean = chop.isNotEmpty() && sourceStmts.isNotEmpty() && sinkStmts.isNotEmpty()
    }

    class TaintFlow(
        val sourceIdx: Int,
        val sinkIdx: Int,
        val sourceMethod: String,
        val sinkMethod: String,
        val category: TaintCategory,
    ) {
        val path: MutableList<Int> = mutableListOf()
    }

    fun slice(data: CpgData): SliceResult {
        val result = SliceResult(data.methodSignature)

        val n = data.stmtLabels.size
        if (n < 2) return result

        for (i in 0 until n) {
            val stmtStr = data.stmtLabels[i]
            if (isSource(stmtStr)) result.sourceStmts.add(i)
            if (isSink(stmtStr)) result.sinkStmts.add(i)
        }

        // A source with no local sink still matters: findInterProceduralFlows
        // and findCapturedFieldFlows need forwardSlice to find a tainted
        // argument whose sink lives inside the callee instead of here.
        if (result.sourceStmts.isEmpty()) return result

        val forwardAdj = linkedMapOf<Int, MutableList<Int>>()
        val backwardAdj = linkedMapOf<Int, MutableList<Int>>()
        for (i in 0 until n) {
            forwardAdj[i] = mutableListOf()
            backwardAdj[i] = mutableListOf()
        }

        // Taint propagates over DDG (value/data dependence) edges only. CDG
        // (control dependence) edges used to be included here too, which is an
        // over-approximation verified to produce real false positives: it treats
        // "this statement executes conditionally on a branch that used tainted
        // data" as equivalent to "this statement's value depends on tainted
        // data," which is a claim about *implicit* information flow (Denning
        // 1976), not the explicit data flow this tool's benchmarks are written
        // against. Confirmed via benchmark/control/04_tainted_guard_clause_unrelated_sink.kt
        // and 05_tainted_branch_no_early_exit.kt (an ordinary tainted `if`, no
        // coroutines at all, whose branch body assigns an unrelated literal —
        // reported as a flow purely because that assignment is control-dependent
        // on the tainted condition) and independently by
        // benchmark/flow/*.kt (see CLAUDE.md). DDG alone is sufficient for every
        // other benchmark in the suite — none of them depend on control
        // dependence for their sink to actually use the tainted value, since
        // real code always does that via an ordinary assignment DDG already
        // captures. If a future benchmark genuinely needs implicit-flow
        // detection, it should be a separate, explicitly-labeled analysis pass,
        // not a silent addition to this adjacency list.
        for (edge in data.ddgEdges) {
            forwardAdj[edge.src]?.add(edge.dst)
            backwardAdj[edge.dst]?.add(edge.src)
        }

        for (src in result.sourceStmts) bfsForward(src, forwardAdj, result.forwardSlice)
        for (snk in result.sinkStmts) bfsBackward(snk, backwardAdj, result.backwardSlice)

        for (idx in result.forwardSlice) {
            if (idx in result.backwardSlice) result.chop.add(idx)
        }

        for (src in result.sourceStmts) {
            for (snk in result.sinkStmts) {
                if (pathExistsInChop(src, snk, forwardAdj, result.chop)) {
                    val srcStr = data.stmtLabels[src]
                    val snkStr = data.stmtLabels[snk]
                    val flow = TaintFlow(src, snk, srcStr, snkStr, sinkCategory(snkStr))
                    buildPath(src, snk, forwardAdj, result.chop, flow.path)
                    result.taintFlows.add(flow)
                }
            }
        }

        return result
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

    private fun bfsBackward(start: Int, backwardAdj: Map<Int, List<Int>>, visited: MutableSet<Int>) {
        val queue: java.util.Queue<Int> = LinkedList()
        queue.add(start)
        visited.add(start)
        while (queue.isNotEmpty()) {
            val curr = queue.poll()
            for (p in backwardAdj[curr].orEmpty()) {
                if (p !in visited) {
                    visited.add(p)
                    queue.add(p)
                }
            }
        }
    }

    private fun pathExistsInChop(src: Int, dst: Int, adj: Map<Int, List<Int>>, chop: Set<Int>): Boolean {
        if (src == dst) return true
        val visited = linkedSetOf<Int>()
        val queue: java.util.Queue<Int> = LinkedList()
        queue.add(src)
        visited.add(src)
        while (queue.isNotEmpty()) {
            val curr = queue.poll()
            for (s in adj[curr].orEmpty()) {
                if (s == dst) return true
                if (s in chop && s !in visited) {
                    visited.add(s)
                    queue.add(s)
                }
            }
        }
        return false
    }

    private fun buildPath(src: Int, dst: Int, adj: Map<Int, List<Int>>, chop: Set<Int>, path: MutableList<Int>) {
        val visited = linkedSetOf<Int>()
        val parent = linkedMapOf<Int, Int>()
        val queue: java.util.Queue<Int> = LinkedList()
        queue.add(src)
        visited.add(src)
        parent[src] = -1
        while (queue.isNotEmpty()) {
            val curr = queue.poll()
            if (curr == dst) break
            for (s in adj[curr].orEmpty()) {
                if (s !in visited && (s == dst || s in chop)) {
                    visited.add(s)
                    parent[s] = curr
                    queue.add(s)
                }
            }
        }
        if (parent.containsKey(dst)) {
            val reversePath = mutableListOf<Int>()
            var curr = dst
            while (curr != -1) {
                reversePath.add(curr)
                curr = parent.getOrDefault(curr, -1)
            }
            for (i in reversePath.indices.reversed()) path.add(reversePath[i])
        } else {
            path.add(src)
            path.add(dst)
        }
    }

    /**
     * Finds a tainted call argument whose callee summary says either that the
     * argument reaches a sink inside the callee ([TaintSummary.paramToSinks]),
     * or that it flows to the callee's return value
     * ([TaintSummary.paramToReturn]) — in which case the call's own result,
     * if assigned to a local, is traced forward to see whether it reaches one
     * of *this* method's sinks, bridging the call boundary rather than
     * stopping at it.
     *
     * Handles both a bare call statement ([JInvokeStmt], result discarded)
     * and a call whose result is assigned ([JAssignStmt] with an invoke
     * expression on the right-hand side) — the latter is how most calls
     * whose return value is actually used are represented.
     */
    fun findInterProceduralFlows(data: CpgData, intraResult: SliceResult, analyzer: InterProceduralAnalyzer): List<TaintFlow> {
        val flows = mutableListOf<TaintFlow>()
        val backwardDdg = buildBackwardDdg(data)

        for (srcIdx in intraResult.sourceStmts) {
            for (idx in intraResult.forwardSlice) {
                val stmt = data.indexToStmt[idx] ?: continue
                val invoke = invokeExprOf(stmt) ?: continue

                val calleeSig = invoke.methodSignature
                val calleeSummary = analyzer.getSummary(calleeSig.toString()) ?: continue

                for (argIdx in 0 until invoke.argCount) {
                    val arg = invoke.getArg(argIdx) as? Local ?: continue
                    val argName = arg.toString()

                    if (!isArgTaintedByDdg(idx, argName, srcIdx, backwardDdg, data)) continue

                    calleeSummary.paramToSinks[argIdx]?.forEach { sinkInfo ->
                        val srcStr = data.stmtLabels[srcIdx]
                        val sinkStr = "INTER-PROC: ${calleeSig.name} -> ${sinkInfo.sinkMethod}"
                        val flow = TaintFlow(srcIdx, idx, srcStr, sinkStr, sinkCategory(sinkStr))
                        flow.path.add(srcIdx)
                        flow.path.add(idx)
                        flows.add(flow)
                    }

                    if (argIdx in calleeSummary.paramToReturn && stmt is JAssignStmt) {
                        for (sinkIdx in intraResult.sinkStmts) {
                            if (sinkIdx > idx && isReachableForward(idx, sinkIdx, data)) {
                                val srcStr = data.stmtLabels[srcIdx]
                                val sinkStr = data.stmtLabels[sinkIdx]
                                val flow = TaintFlow(srcIdx, sinkIdx, srcStr, sinkStr, sinkCategory(sinkStr))
                                flow.path.addAll(listOf(srcIdx, idx, sinkIdx))
                                flows.add(flow)
                            }
                        }
                    }
                }
            }
        }

        return flows
    }

    private fun invokeExprOf(stmt: Stmt): AbstractInvokeExpr? = when (stmt) {
        is JInvokeStmt -> stmt.invokeExpr.orElse(null)
        is JAssignStmt -> stmt.rightOp as? AbstractInvokeExpr
        else -> null
    }

    /**
     * Finds taint flowing into a nested closure (most notably a suspend
     * lambda) through a captured variable.
     *
     * A closure's captured outer variables compile to constructor parameters
     * written into fields of the generated class, not method arguments, so
     * [findInterProceduralFlows] never sees them, and
     * [com.infinity.cps.reconstruction.reconstruct.SuspendChainReconstructor]
     * only ever reconstructs one method body in isolation, never the
     * enclosing scope. This walks `new SomeClosure(taintedArg)` sites
     * reachable from a source, uses the constructor's
     * [TaintSummary.paramToFields] to find which field the argument lands
     * in, then checks every other method on the constructed class for a
     * [TaintSummary.fieldToSinks] entry for that field.
     */
    fun findCapturedFieldFlows(
        data: CpgData,
        intraResult: SliceResult,
        analyzer: InterProceduralAnalyzer,
        view: JavaView,
    ): List<TaintFlow> {
        val flows = mutableListOf<TaintFlow>()
        val backwardDdg = buildBackwardDdg(data)

        // Deliberately scans every statement, not intraResult.forwardSlice:
        // `new Closure` takes no operands, so it's never itself data-tainted
        // and never appears in a DDG forward slice from the source — only the
        // *following* `<init>` invoke (which reads the captured argument) is.
        // Filtering candidate sites by forwardSlice membership here excluded
        // every real candidate; the actual taint check already happens below
        // via isArgTaintedByDdg, so this loop only needs to enumerate `new`
        // sites, not pre-filter them.
        for (srcIdx in intraResult.sourceStmts) {
            for (idx in data.stmtLabels.indices) {
                val assign = data.indexToStmt[idx] as? JAssignStmt ?: continue
                if (assign.rightOp !is JNewExpr) continue

                val ctorIdx = idx + 1
                val ctorStmt = data.indexToStmt[ctorIdx] as? JInvokeStmt ?: continue
                val ctorInvokeOpt = ctorStmt.invokeExpr
                if (!ctorInvokeOpt.isPresent || ctorInvokeOpt.get() !is JSpecialInvokeExpr) continue
                val ctorInvoke = ctorInvokeOpt.get() as JSpecialInvokeExpr
                val ctorSig = ctorInvoke.methodSignature
                if (ctorSig.name != "<init>") continue

                val ctorSummary = analyzer.getSummary(ctorSig.toString()) ?: continue
                if (ctorSummary.paramToFields.isEmpty()) continue

                val classOpt = view.getClass(ctorSig.declClassType)
                if (classOpt.isEmpty) continue
                val otherMethods = classOpt.get().methods

                for (argIdx in 0 until ctorInvoke.argCount) {
                    val arg = ctorInvoke.getArg(argIdx) as? Local ?: continue
                    val fieldSigs = ctorSummary.paramToFields[argIdx] ?: continue
                    if (!isArgTaintedByDdg(ctorIdx, arg.toString(), srcIdx, backwardDdg, data)) continue

                    for (fieldSig in fieldSigs) {
                        for (method in otherMethods) {
                            if (!method.hasBody()) continue
                            val methodSummary = analyzer.getSummary(method.signature.toString()) ?: continue
                            val sinkInfos = methodSummary.fieldToSinks[fieldSig] ?: continue

                            for (sinkInfo in sinkInfos) {
                                val srcStr = data.stmtLabels[srcIdx]
                                val sinkStr = "CAPTURED: $fieldSig -> ${sinkInfo.sinkMethod} in ${method.signature}"
                                val flow = TaintFlow(srcIdx, idx, srcStr, sinkStr, sinkInfo.category)
                                flow.path.add(srcIdx)
                                flow.path.add(idx)
                                flows.add(flow)
                            }
                        }
                    }
                }
            }
        }

        return flows
    }

    private fun buildBackwardDdg(data: CpgData): Map<Int, MutableList<Int>> {
        val backwardDdg = linkedMapOf<Int, MutableList<Int>>()
        for (i in data.stmtLabels.indices) backwardDdg[i] = mutableListOf()
        for (edge in data.ddgEdges) {
            backwardDdg[edge.dst]?.add(edge.src)
        }
        return backwardDdg
    }

    /** Traces backward from [invokeIdx] to find where [argName] was defined, then checks if [srcIdx] reaches that definition. */
    private fun isArgTaintedByDdg(
        invokeIdx: Int,
        argName: String,
        srcIdx: Int,
        backwardDdg: Map<Int, List<Int>>,
        data: CpgData,
    ): Boolean {
        val visited = linkedSetOf<Int>()
        val queue: java.util.Queue<Int> = LinkedList()
        queue.add(invokeIdx)
        visited.add(invokeIdx)

        while (queue.isNotEmpty()) {
            val curr = queue.poll()
            val currStmt = data.indexToStmt[curr]
            if (currStmt != null) {
                val defOpt = currStmt.def
                if (defOpt.isPresent && defOpt.get() is Local && defOpt.get().toString() == argName) {
                    if (isReachableForward(srcIdx, curr, data)) return true
                }
            }
            for (pred in backwardDdg[curr].orEmpty()) {
                if (pred !in visited) {
                    visited.add(pred)
                    queue.add(pred)
                }
            }
        }
        return false
    }

    private fun isReachableForward(src: Int, dst: Int, data: CpgData): Boolean {
        if (src == dst) return true
        val visited = linkedSetOf<Int>()
        val queue: java.util.Queue<Int> = LinkedList()
        queue.add(src)
        visited.add(src)

        val forwardDdg = linkedMapOf<Int, MutableList<Int>>()
        for (edge in data.ddgEdges) {
            forwardDdg.getOrPut(edge.src) { mutableListOf() }.add(edge.dst)
        }

        while (queue.isNotEmpty()) {
            val curr = queue.poll()
            for (s in forwardDdg[curr].orEmpty()) {
                if (s == dst) return true
                if (s !in visited) {
                    visited.add(s)
                    queue.add(s)
                }
            }
        }
        return false
    }
}
