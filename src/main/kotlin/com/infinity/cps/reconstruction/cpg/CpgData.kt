package com.infinity.cps.reconstruction.cpg

import com.infinity.cps.reconstruction.ast.JimpleAstBuilder
import com.infinity.cps.reconstruction.ast.JimpleAstNode
import sootup.core.graph.StmtGraph
import sootup.core.jimple.basic.Local
import sootup.core.jimple.basic.LValue
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.model.SootMethod

/**
 * A Code Property Graph (CPG) for a single method, following the definition
 * of Yamaguchi et al. ("Modeling and Discovering Vulnerabilities with Code
 * Property Graphs", IEEE S&P 2014): a CPG merges four program
 * representations into one graph over a shared node space:
 * - **Abstract Syntax Tree (AST)** — the syntactic structure of each
 *   statement, built from Jimple by [JimpleAstBuilder] ([astNodes]/[astEdges])
 * - **Control Flow Graph (CFG)** — possible execution paths between
 *   statements ([cfgEdges])
 * - **Control Dependence Graph (CDG)** — which statements control whether
 *   others execute ([cdgEdges])
 * - **Data Dependence Graph (DDG)** — data flow from a variable definition to
 *   its uses ([ddgEdges])
 *
 * (CDG and DDG together are the Program Dependence Graph, the PDG, in the
 * original CPG formulation.)
 *
 * Statements are indexed from 0, with a virtual exit node at index `n` (the
 * statement count). AST nodes live in a disjoint id space
 * ([JimpleAstNode.ID_OFFSET] and up) so the two node kinds can share one
 * edge type; [bindingEdges] is the fusion edge connecting each statement to
 * the root of its own AST subtree (exact, since the AST is built from the
 * same Jimple statement — see [JimpleAstBuilder]'s doc comment for why this
 * tool builds the AST from the IR rather than from Kotlin source).
 *
 * The CDG is built using the Cytron et al. dominance frontier algorithm
 * (Cytron, Ferrante, Rosen, Wegman, Zadeck, "Efficiently Computing Static
 * Single Assignment Form and the Control Dependence Graph", TOPLAS 1991),
 * with immediate post-dominators computed via the iterative
 * Cooper-Harvey-Kennedy algorithm ("A Simple, Fast Dominance Algorithm",
 * 2001). The DDG is built via the standard iterative reaching-definitions
 * dataflow analysis (Kildall 1973; Aho, Lam, Sethi, Ullman, "Compilers:
 * Principles, Techniques, and Tools", Ch. 9) run to a fixpoint over the CFG,
 * rather than a per-definition graph search — the fixpoint formulation is
 * what makes it correct at CFG joins (a diamond where one branch redefines a
 * variable and the other doesn't): a definition's reach at a join is the
 * union of what reaches it along every incoming path, computed
 * simultaneously, not path-at-a-time.
 *
 * Instances are created via [fromMethod].
 */
class CpgData {

    var methodSignature: String? = null

    /** Human-readable statement labels (Jimple toString). */
    val stmtLabels: MutableList<String> = mutableListOf()

    /** Source line numbers for each statement (-1 if unknown). */
    val stmtLines: MutableList<Int> = mutableListOf()

    val cfgEdges: MutableSet<CpgEdge> = linkedSetOf()
    val ddgEdges: MutableSet<CpgEdge> = linkedSetOf()
    val cdgEdges: MutableSet<CpgEdge> = linkedSetOf()

    /** AST parent-child structural edges (see [JimpleAstBuilder]). */
    val astEdges: MutableSet<CpgEdge> = linkedSetOf()

    /** Statement-index -> AST-root-node fusion edges ([EdgeKind.BINDS_TO]). */
    val bindingEdges: MutableSet<CpgEdge> = linkedSetOf()

    val astNodes: MutableList<JimpleAstNode> = mutableListOf()

    val stmtToIndex: MutableMap<Stmt, Int> = linkedMapOf()
    val indexToStmt: MutableMap<Int, Stmt> = linkedMapOf()
    val cfgSuccessors: MutableMap<Int, MutableList<Int>> = linkedMapOf()
    val cfgPredecessors: MutableMap<Int, MutableList<Int>> = linkedMapOf()

    /** A definition site: variable [variable] defined at statement [defIdx]. */
    private data class DefSite(val variable: String, val defIdx: Int)

    /**
     * `X = $result` immediately following a suspend call that itself defined
     * `X` is the same logical value arriving via the continuation, not a
     * real overwrite. In reaching-definitions terms this statement is
     * *transparent* for `X`: it neither generates a new definition nor kills
     * the one already reaching it, so earlier definitions of `X` keep
     * reaching past it instead of being killed here.
     */
    private fun isTransparentContinuationReload(stmt: Stmt, varName: String): Boolean {
        val assign = stmt as? JAssignStmt ?: return false
        val rightOp = assign.rightOp
        return rightOp is Local && rightOp.name == "\$result" &&
            assign.def.let { it.isPresent && it.get() is Local && (it.get() as Local).toString() == varName }
    }

    /**
     * Standard iterative reaching-definitions dataflow, run to a fixpoint:
     *
     * ```
     * OUT[n] = GEN[n] U (IN[n] - KILL[n])
     * IN[n]  = union over predecessors p of OUT[p]
     * ```
     *
     * GEN[n] is the (at most one, since Jimple is close to three-address
     * code) non-transparent local definition made at statement n; KILL[n] is
     * every other definition of that same variable anywhere in the method. A
     * DDG edge `def -> use` (labeled with the variable) is then emitted for
     * every definition in IN[use] whose variable is actually read at `use`.
     */
    private fun buildDdgEdges() {
        val n = stmtLabels.size
        if (n == 0) return

        val allDefsByVar = linkedMapOf<String, MutableList<Int>>()
        val defSiteAt = arrayOfNulls<DefSite>(n)

        for (i in 0 until n) {
            val stmt = indexToStmt[i] ?: continue
            val defOpt = stmt.def
            if (!defOpt.isPresent) continue
            val defVal: LValue = defOpt.get()
            if (defVal !is Local) continue
            val varName = defVal.toString()
            if (isTransparentContinuationReload(stmt, varName)) continue

            allDefsByVar.getOrPut(varName) { mutableListOf() }.add(i)
            defSiteAt[i] = DefSite(varName, i)
        }

        val gen = Array(n) { i -> defSiteAt[i]?.let { setOf(it) } ?: emptySet() }
        val kill = Array(n) { i ->
            val d = defSiteAt[i]
            if (d == null) {
                emptySet()
            } else {
                allDefsByVar.getValue(d.variable).asSequence()
                    .filter { it != i }
                    .map { DefSite(d.variable, it) }
                    .toSet()
            }
        }

        val inSets = Array(n) { emptySet<DefSite>() }
        val outSets = Array(n) { emptySet<DefSite>() }

        var changed = true
        while (changed) {
            changed = false
            for (i in 0 until n) {
                val newIn = mutableSetOf<DefSite>()
                for (p in cfgPredecessors[i].orEmpty()) {
                    if (p < n) newIn.addAll(outSets[p])
                }
                if (newIn != inSets[i]) {
                    inSets[i] = newIn
                    changed = true
                }

                val newOut = gen[i] + (inSets[i] - kill[i])
                if (newOut != outSets[i]) {
                    outSets[i] = newOut
                    changed = true
                }
            }
        }

        for (useIdx in 0 until n) {
            val stmt = indexToStmt[useIdx] ?: continue
            val usedVars = stmt.uses.toList().filterIsInstance<Local>().mapTo(linkedSetOf()) { it.toString() }
            if (usedVars.isEmpty()) continue
            for (site in inSets[useIdx]) {
                if (site.variable in usedVars) {
                    ddgEdges.add(CpgEdge(site.defIdx, useIdx, EdgeKind.DDG, variable = site.variable))
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
                cdgEdges.add(CpgEdge(d, target, EdgeKind.CDG, condition = condition))
            }
        }

        for (defIdx in stmtLabels.indices) {
            val defStmt = indexToStmt[defIdx]
            val varName = getSideEffectDef(defStmt) ?: continue

            val seVisited = linkedSetOf<Int>()
            val seQueue: java.util.Queue<Int> = java.util.LinkedList()
            cfgSuccessors[defIdx]?.forEach { seQueue.add(it) }

            while (seQueue.isNotEmpty()) {
                val currIdx = seQueue.poll()
                if (currIdx in seVisited) continue
                seVisited.add(currIdx)

                val currStmt = indexToStmt[currIdx] ?: continue

                for (useVal in currStmt.uses.toList()) {
                    if (useVal is Local && useVal.toString() == varName) {
                        ddgEdges.add(CpgEdge(defIdx, currIdx, EdgeKind.DDG, variable = varName))
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

    /** Builds the AST layer (one subtree per statement) and the exact statement-to-AST-root binding edges. */
    private fun buildAstLayer() {
        var nextAstId = JimpleAstNode.ID_OFFSET
        for (i in stmtLabels.indices) {
            val stmt = indexToStmt[i] ?: continue
            val result = JimpleAstBuilder.build(stmt, nextAstId)
            astNodes.addAll(result.nodes)
            astEdges.addAll(result.edges)
            bindingEdges.add(CpgEdge(i, result.rootId, EdgeKind.BINDS_TO))
            nextAstId = result.nextId
        }
    }

    fun countStatements(): Int = stmtLabels.size
    fun countCfgEdges(): Int = cfgEdges.size
    fun countDdgEdges(): Int = ddgEdges.size
    fun countCdgEdges(): Int = cdgEdges.size
    fun countAstNodes(): Int = astNodes.size
    fun countAstEdges(): Int = astEdges.size

    fun getCdgParents(stmtIdx: Int): List<Int> = cdgEdges.filter { it.dst == stmtIdx }.map { it.src }

    companion object {
        fun fromMethod(method: SootMethod): CpgData {
            if (!method.hasBody()) {
                val data = CpgData()
                data.methodSignature = method.signature.toString()
                return data
            }
            return fromBody(method.body.stmtGraph, method.signature.toString())
        }

        /**
         * Builds a [CpgData] directly from a [StmtGraph], bypassing
         * [SootMethod]/class loading — used by tests to exercise the
         * CFG/DDG/CDG/AST construction against small, hand-built statement
         * graphs (the same [sootup.core.graph.MutableBlockStmtGraph]-based
         * fixture style [com.infinity.cps.reconstruction.reconstruct.SuspendChainReconstructorTest]
         * already uses) instead of compiling real class files.
         */
        fun fromBody(stmtGraph: StmtGraph<*>, methodSignature: String?): CpgData {
            val data = CpgData()
            data.methodSignature = methodSignature

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
                        data.cfgEdges.add(CpgEdge(srcIdx, dstIdx, EdgeKind.CFG))
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
                                    data.cfgEdges.add(CpgEdge(srcIdx, dstIdx, EdgeKind.CFG))
                                }
                            }
                        }
                    }
                }
            }

            for (edge in data.cfgEdges) {
                data.cfgSuccessors.getOrPut(edge.src) { mutableListOf() }.add(edge.dst)
            }

            for (i in 0..data.stmtLabels.size) {
                data.cfgPredecessors[i] = mutableListOf()
            }
            for (edge in data.cfgEdges) {
                data.cfgPredecessors[edge.dst]!!.add(edge.src)
            }
            val virtualExit = data.stmtLabels.size
            for (i in 0 until data.stmtLabels.size) {
                if (data.cfgSuccessors[i].isNullOrEmpty()) {
                    data.cfgPredecessors[virtualExit]!!.add(i)
                }
            }

            data.buildDdgEdges()
            data.buildCdgEdges()
            data.buildAstLayer()

            return data
        }
    }
}
