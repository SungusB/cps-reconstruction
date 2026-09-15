package com.infinity.cps.reconstruction.cpg

import sootup.core.jimple.basic.Local
import sootup.core.jimple.basic.LValue
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.SootMethod
import java.util.LinkedList

/**
 * A Code Property Graph (CPG) for a single method.
 *
 * A CPG merges three program representations:
 * - **Control Flow Graph (CFG)** — possible execution paths between statements
 * - **Data Dependence Graph (DDG)** — data flow from a variable definition to its uses
 * - **Control Dependence Graph (CDG)** — which statements control whether others execute
 *
 * Edges are stored as strings in `"src|dst"` or `"src|dst|label"` format.
 * Statements are indexed from 0, with a virtual exit node at index `n`
 * (the statement count).
 *
 * The CDG is built using the Cytron et al. dominance frontier algorithm,
 * with immediate post-dominators computed via the Cooper-Harvey-Kennedy
 * iterative algorithm.
 *
 * Instances are created via [fromMethod].
 */
class CpgData {

    var methodSignature: String? = null

    /** Human-readable statement labels (Jimple toString). */
    val stmtLabels: MutableList<String> = mutableListOf()

    /** Source line numbers for each statement (-1 if unknown). */
    val stmtLines: MutableList<Int> = mutableListOf()

    val cfgEdges: MutableSet<String> = linkedSetOf()
    val ddgEdges: MutableSet<String> = linkedSetOf()
    val cdgEdges: MutableSet<String> = linkedSetOf()

    val stmtToIndex: MutableMap<Stmt, Int> = linkedMapOf()
    val indexToStmt: MutableMap<Int, Stmt> = linkedMapOf()
    val cfgSuccessors: MutableMap<Int, MutableList<Int>> = linkedMapOf()
    val cfgPredecessors: MutableMap<Int, MutableList<Int>> = linkedMapOf()

    private fun buildDdgEdges() {
        for (defIdx in stmtLabels.indices) {
            val defStmt = indexToStmt[defIdx] ?: continue
            val defOpt = defStmt.def
            if (!defOpt.isPresent) continue
            val defVal: LValue = defOpt.get()
            if (defVal !is Local) continue
            val varName = defVal.toString()

            val visited = linkedSetOf<Int>()
            val queue: java.util.Queue<Int> = LinkedList()
            cfgSuccessors[defIdx]?.forEach { queue.add(it) }

            while (queue.isNotEmpty()) {
                val currIdx = queue.poll()
                if (currIdx in visited) continue
                visited.add(currIdx)

                val currStmt = indexToStmt[currIdx] ?: continue

                for (useVal in currStmt.uses.toList()) {
                    if (useVal is Local && useVal.toString() == varName) {
                        ddgEdges.add("$defIdx|$currIdx|$varName")
                        break
                    }
                }

                val currDef = currStmt.def
                if (currDef.isPresent && currDef.get() is Local && currDef.get().toString() == varName) {
                    // `X = $result` right after a suspend call that defined X is the
                    // same logical value arriving via the continuation, not a real
                    // overwrite — keep tracing instead of treating it as taint-killing.
                    val rightOp = (currStmt as? JAssignStmt)?.rightOp
                    if (rightOp is Local && rightOp.name == "\$result") {
                        cfgSuccessors[currIdx]?.forEach { succ ->
                            if (succ !in visited) queue.add(succ)
                        }
                    }
                    continue
                }

                cfgSuccessors[currIdx]?.forEach { succ ->
                    if (succ !in visited) queue.add(succ)
                }
            }
        }

        FieldAliasTracker.addFieldDdgEdges(this)
    }

    /** For an instance call with a local argument, returns the receiver's name — a write to it is a side effect. */
    private fun getSideEffectDef(stmt: Stmt?): String? {
        if (stmt is JInvokeStmt) {
            val invokeOpt = stmt.invokeExpr
            if (invokeOpt.isPresent && invokeOpt.get() is AbstractInstanceInvokeExpr) {
                val instanceInvoke = invokeOpt.get() as AbstractInstanceInvokeExpr
                val base = instanceInvoke.base
                val hasLocalArg = instanceInvoke.uses.anyMatch { v -> v is Local && v.toString() != base.toString() }
                if (hasLocalArg) return base.toString()
            }
        }
        return null
    }

    private fun buildCdgEdges() {
        val n = stmtLabels.size
        if (n < 2) return

        val virtualExit = n

        val postorder = IntArray(n + 1) { -1 }
        val counter = intArrayOf(0)
        val visited = BooleanArray(n + 1)
        dfsPostorder(virtualExit, visited, postorder, counter)

        val rpoNodes = (0..n).sortedByDescending { postorder[it] }

        val ipdom = IntArray(n + 1) { -1 }
        ipdom[virtualExit] = virtualExit

        var changed = true
        while (changed) {
            changed = false
            for (node in rpoNodes) {
                if (node == virtualExit || postorder[node] == -1) continue

                val succs = cfgSuccessors[node]
                if (succs.isNullOrEmpty()) {
                    if (ipdom[node] != virtualExit) {
                        ipdom[node] = virtualExit
                        changed = true
                    }
                    continue
                }

                var newIdom = -1
                for (s in succs) {
                    if (ipdom[s] != -1) {
                        newIdom = if (newIdom == -1) s else intersectChk(ipdom, postorder, newIdom, s)
                    }
                }

                if (newIdom != -1 && ipdom[node] != newIdom) {
                    ipdom[node] = newIdom
                    changed = true
                }
            }
        }

        val domFrontier = linkedMapOf<Int, MutableSet<Int>>()
        for (i in 0 until n) domFrontier[i] = linkedSetOf()

        for (i in 0 until n) {
            val succs = cfgSuccessors[i]
            if (succs == null || succs.size < 2) continue

            for (s in succs) {
                var runner = s
                while (runner != virtualExit && runner != ipdom[i] && runner != -1) {
                    domFrontier[i]!!.add(runner)
                    if (ipdom[runner] == -1 || ipdom[runner] == runner) break
                    runner = ipdom[runner]
                }
            }
        }

        for (d in 0 until n) {
            val frontier = domFrontier[d] ?: continue
            val condition = getBranchCondition(d)
            for (target in frontier) {
                if (condition != null) {
                    cdgEdges.add("$d|$target|$condition")
                } else {
                    cdgEdges.add("$d|$target")
                }
            }
        }

        for (defIdx in stmtLabels.indices) {
            val defStmt = indexToStmt[defIdx]
            val varName = getSideEffectDef(defStmt) ?: continue

            val seVisited = linkedSetOf<Int>()
            val seQueue: java.util.Queue<Int> = LinkedList()
            cfgSuccessors[defIdx]?.forEach { seQueue.add(it) }

            while (seQueue.isNotEmpty()) {
                val currIdx = seQueue.poll()
                if (currIdx in seVisited) continue
                seVisited.add(currIdx)

                val currStmt = indexToStmt[currIdx] ?: continue

                for (useVal in currStmt.uses.toList()) {
                    if (useVal is Local && useVal.toString() == varName) {
                        ddgEdges.add("$defIdx|$currIdx|$varName")
                        break
                    }
                }

                val currDef = currStmt.def
                if (currDef.isPresent && currDef.get() is Local && currDef.get().toString() == varName) {
                    continue
                }

                val sideEffect = getSideEffectDef(currStmt)
                if (sideEffect != null && sideEffect == varName) continue

                cfgSuccessors[currIdx]?.forEach { succ ->
                    if (succ !in seVisited) seQueue.add(succ)
                }
            }
        }
    }

    private fun dfsPostorder(node: Int, visited: BooleanArray, postorder: IntArray, counter: IntArray) {
        if (visited[node]) return
        visited[node] = true
        cfgPredecessors[node]?.forEach { p -> dfsPostorder(p, visited, postorder, counter) }
        postorder[node] = counter[0]++
    }

    private fun intersectChk(ipdom: IntArray, postorder: IntArray, b1In: Int, b2In: Int): Int {
        var b1 = b1In
        var b2 = b2In
        while (b1 != b2) {
            while (b1 != -1 && postorder[b1] != -1 && postorder[b1] < postorder[b2]) {
                b1 = ipdom[b1]
            }
            while (b2 != -1 && postorder[b2] != -1 && postorder[b2] < postorder[b1]) {
                b2 = ipdom[b2]
            }
        }
        return b1
    }

    private fun getBranchCondition(idx: Int): String? {
        val stmt = indexToStmt[idx] ?: return null
        val s = stmt.toString()
        if (s.startsWith("if ")) {
            val gotoIdx = s.indexOf(" goto")
            return if (gotoIdx > 3) s.substring(3, gotoIdx).trim() else s.substring(3).trim()
        }
        return null
    }

    fun countStatements(): Int = stmtLabels.size
    fun countCfgEdges(): Int = cfgEdges.size
    fun countDdgEdges(): Int = ddgEdges.size
    fun countCdgEdges(): Int = cdgEdges.size

    fun getCdgParents(stmtIdx: Int): List<Int> {
        val parents = mutableListOf<Int>()
        for (edge in cdgEdges) {
            val parts = edge.split("|")
            if (parts[1].toInt() == stmtIdx) parents.add(parts[0].toInt())
        }
        return parents
    }

    companion object {
        fun fromMethod(method: SootMethod): CpgData {
            val data = CpgData()
            data.methodSignature = method.signature.toString()

            if (!method.hasBody()) return data

            val stmtGraph = method.body.stmtGraph

            var idx = 0
            for (block in stmtGraph.blocks) {
                for (stmt in block.stmts) {
                    if (data.stmtToIndex.containsKey(stmt)) continue
                    data.stmtLabels.add(stmt.toString())
                    data.stmtToIndex[stmt] = idx
                    data.indexToStmt[idx] = stmt

                    val line = stmt.positionInfo?.stmtPosition?.firstLine ?: -1
                    data.stmtLines.add(line)
                    idx++
                }
            }

            for (block in stmtGraph.blocks) {
                val stmts = block.stmts
                for (i in 0 until stmts.size - 1) {
                    val srcIdx = data.stmtToIndex[stmts[i]]
                    val dstIdx = data.stmtToIndex[stmts[i + 1]]
                    if (srcIdx != null && dstIdx != null) {
                        data.cfgEdges.add("$srcIdx|$dstIdx")
                    }
                }
                if (stmts.isNotEmpty()) {
                    val srcIdx = data.stmtToIndex[stmts.last()]
                    if (srcIdx != null) {
                        for (succ in block.successors) {
                            val succStmts = succ.stmts
                            if (succStmts.isNotEmpty()) {
                                val dstIdx = data.stmtToIndex[succStmts[0]]
                                if (dstIdx != null) {
                                    data.cfgEdges.add("$srcIdx|$dstIdx")
                                }
                            }
                        }
                    }
                }
            }

            for (edge in data.cfgEdges) {
                val parts = edge.split("|")
                data.cfgSuccessors.getOrPut(parts[0].toInt()) { mutableListOf() }.add(parts[1].toInt())
            }

            for (i in 0..data.stmtLabels.size) {
                data.cfgPredecessors[i] = mutableListOf()
            }
            for (edge in data.cfgEdges) {
                val parts = edge.split("|")
                data.cfgPredecessors[parts[1].toInt()]!!.add(parts[0].toInt())
            }
            val virtualExit = data.stmtLabels.size
            for (i in 0 until data.stmtLabels.size) {
                if (data.cfgSuccessors[i].isNullOrEmpty()) {
                    data.cfgPredecessors[virtualExit]!!.add(i)
                }
            }

            data.buildDdgEdges()
            data.buildCdgEdges()

            return data
        }
    }
}
