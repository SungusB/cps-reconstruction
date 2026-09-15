package com.infinity.cps.reconstruction.cpg

import com.infinity.cps.reconstruction.ast.JimpleAstNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sootup.core.graph.MutableBlockStmtGraph
import sootup.core.jimple.basic.Local
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.expr.JAddExpr
import sootup.core.jimple.common.expr.JEqExpr
import sootup.core.jimple.common.expr.JLtExpr
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JGotoStmt
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JNopStmt
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.jimple.common.stmt.JReturnVoidStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.types.PrimitiveType

/**
 * Unit tests for [CpgData]'s CFG/DDG/CDG/AST construction, built directly
 * from small, hand-built [MutableBlockStmtGraph]s — the same fixture style
 * [com.infinity.cps.reconstruction.reconstruct.SuspendChainReconstructorTest]
 * uses — via [CpgData.fromBody], rather than by compiling real class files.
 *
 * Nothing previously verified that the reaching-definitions DDG, the
 * dominance-frontier CDG, or the Jimple-derived AST layer actually produce
 * correct edges; these tests hand-verify each against the textbook
 * definition of the corresponding analysis.
 *
 * Assertions look statement indices up via [CpgData.stmtToIndex] rather than
 * assuming a statement's index matches its `addNode` insertion order:
 * [CpgData.fromBody] numbers statements by [sootup.core.graph.StmtGraph]'s
 * own basic-block traversal order, which can (and for a merge point with a
 * back edge, does) differ from insertion order.
 */
class CpgDataTest {

    private val pos: StmtPositionInfo = StmtPositionInfo.getNoStmtPositionInfo()
    private val intType = PrimitiveType.getInt()

    private fun local(name: String) = Local(name, intType)

    private fun buildGraph(vararg stmts: Stmt): MutableBlockStmtGraph {
        val graph = MutableBlockStmtGraph()
        for (stmt in stmts) graph.addNode(stmt)
        graph.setStartingStmt(stmts[0])
        return graph
    }

    private fun ddgEdge(cpg: CpgData, src: Stmt, dst: Stmt): CpgEdge? {
        val s = cpg.stmtToIndex.getValue(src)
        val d = cpg.stmtToIndex.getValue(dst)
        return cpg.ddgEdges.firstOrNull { it.src == s && it.dst == d }
    }

    private fun isCdgParent(cpg: CpgData, ctrl: Stmt, dependent: Stmt): Boolean {
        val c = cpg.stmtToIndex.getValue(ctrl)
        val d = cpg.stmtToIndex.getValue(dependent)
        return c in cpg.getCdgParents(d)
    }

    // ---- DDG: straight-line def-use ----------------------------------

    @Test
    fun `straight-line def-use chain produces a DDG edge per definition`() {
        // r0 = 1; r1 = r0 + 1; return r1
        val s0 = JAssignStmt(local("r0"), IntConstant.getInstance(1), pos)
        val s1 = JAssignStmt(local("r1"), JAddExpr(local("r0"), IntConstant.getInstance(1)), pos)
        val s2 = JReturnStmt(local("r1"), pos)

        val graph = buildGraph(s0, s1, s2)
        graph.putEdge(s0, s1)
        graph.putEdge(s1, s2)

        val cpg = CpgData.fromBody(graph, "test")

        assertNotNull(ddgEdge(cpg, s0, s1), "r0's definition must reach its use in r1 = r0 + 1")
        assertNotNull(ddgEdge(cpg, s1, s2), "r1's definition must reach its use in return r1")
        assertEquals("r0", ddgEdge(cpg, s0, s1)?.variable)
    }

    @Test
    fun `redefinition kills the earlier definition`() {
        // r0 = 1 (killed); r0 = 2 (reaches the use); r1 = r0
        val s0 = JAssignStmt(local("r0"), IntConstant.getInstance(1), pos)
        val s1 = JAssignStmt(local("r0"), IntConstant.getInstance(2), pos)
        val s2 = JAssignStmt(local("r1"), local("r0"), pos)

        val graph = buildGraph(s0, s1, s2)
        graph.putEdge(s0, s1)
        graph.putEdge(s1, s2)

        val cpg = CpgData.fromBody(graph, "test")

        assertNotNull(ddgEdge(cpg, s1, s2), "the second, non-killed definition must reach the use")
        assertNull(ddgEdge(cpg, s0, s2), "the first definition was killed by the redefinition and must not reach the use")
    }

    @Test
    fun `both definitions on either side of a diamond reach the join`() {
        // x = 0 (def A); if (cond) goto nop; x = 1 (def B, fall-through only); goto join; nop; join: r9 = x
        val defA = JAssignStmt(local("x"), IntConstant.getInstance(0), pos)
        val ifStmt = JIfStmt(JEqExpr(local("cond"), IntConstant.getInstance(1)), pos)
        val defB = JAssignStmt(local("x"), IntConstant.getInstance(1), pos)
        val gotoJoin = JGotoStmt(pos)
        val nop = JNopStmt(pos)
        val join = JAssignStmt(local("r9"), local("x"), pos)

        val graph = buildGraph(defA, ifStmt, defB, gotoJoin, nop, join)
        graph.putEdge(defA, ifStmt)
        graph.putEdge(ifStmt, 0, defB) // fall-through (condition false)
        graph.putEdge(ifStmt, 1, nop) // branch target (condition true) — x untouched
        graph.putEdge(defB, gotoJoin)
        graph.putEdge(gotoJoin, 0, join)
        graph.putEdge(nop, join)

        val cpg = CpgData.fromBody(graph, "test")

        assertNotNull(ddgEdge(cpg, defA, join), "def A reaches the join via the branch-taken path where x is untouched")
        assertNotNull(ddgEdge(cpg, defB, join), "def B reaches the join via the fall-through path")
    }

    // ---- DDG: loop (fixpoint over a back edge) -------------------------

    @Test
    fun `a definition inside a loop reaches uses across the back edge`() {
        // i = 0; header: if (i < 10) goto exit; body: i = i + 1; goto header; exit: return
        val preLoop = JAssignStmt(local("i"), IntConstant.getInstance(0), pos)
        val header = JIfStmt(JLtExpr(local("i"), IntConstant.getInstance(10)), pos)
        val body = JAssignStmt(local("i"), JAddExpr(local("i"), IntConstant.getInstance(1)), pos)
        val backGoto = JGotoStmt(pos)
        val exit = JReturnVoidStmt(pos)

        val graph = buildGraph(preLoop, header, body, backGoto, exit)
        graph.putEdge(preLoop, header)
        graph.putEdge(header, 0, exit) // fall-through (condition false) -> exit
        graph.putEdge(header, 1, body) // branch target (condition true) -> body
        graph.putEdge(body, backGoto)
        graph.putEdge(backGoto, 0, header) // back edge

        val cpg = CpgData.fromBody(graph, "test")

        assertNotNull(ddgEdge(cpg, preLoop, header), "pre-loop def reaches the header on the first iteration")
        assertNotNull(ddgEdge(cpg, preLoop, body), "pre-loop def reaches i+1's use of i on the first iteration")
        assertNotNull(ddgEdge(cpg, body, header), "the in-loop redefinition reaches the header via the back edge on later iterations")
        assertNotNull(ddgEdge(cpg, body, body), "the in-loop redefinition reaches its own next use of i via the back edge (a self-loop DDG edge, expected for a variable redefined inside a loop)")
    }

    // ---- CDG -----------------------------------------------------------

    @Test
    fun `both branches of an if-else are control dependent on the branch, the join is not`() {
        val entry = JAssignStmt(local("x"), IntConstant.getInstance(0), pos)
        val ifStmt = JIfStmt(JEqExpr(local("cond"), IntConstant.getInstance(1)), pos)
        val thenBranch = JAssignStmt(local("x"), IntConstant.getInstance(1), pos)
        val gotoJoin = JGotoStmt(pos)
        val elseBranch = JNopStmt(pos)
        val join = JAssignStmt(local("r9"), local("x"), pos)

        val graph = buildGraph(entry, ifStmt, thenBranch, gotoJoin, elseBranch, join)
        graph.putEdge(entry, ifStmt)
        graph.putEdge(ifStmt, 0, thenBranch)
        graph.putEdge(ifStmt, 1, elseBranch)
        graph.putEdge(thenBranch, gotoJoin)
        graph.putEdge(gotoJoin, 0, join)
        graph.putEdge(elseBranch, join)

        val cpg = CpgData.fromBody(graph, "test")

        assertTrue(isCdgParent(cpg, ifStmt, thenBranch), "the fall-through branch body is control dependent on the if")
        assertTrue(isCdgParent(cpg, ifStmt, elseBranch), "the branch-taken body is control dependent on the if")
        assertFalse(isCdgParent(cpg, ifStmt, join), "the join point post-dominates the if and must not be control dependent on it")
    }

    @Test
    fun `a loop header is control dependent on itself, the exit is not`() {
        // i = 0; header: if (i < 10) goto exit; body: r = i; i = i + 1; goto header; exit: return
        val preLoop = JAssignStmt(local("i"), IntConstant.getInstance(0), pos)
        val header = JIfStmt(JLtExpr(local("i"), IntConstant.getInstance(10)), pos)
        val bodyUse = JAssignStmt(local("r"), local("i"), pos)
        val bodyDef = JAssignStmt(local("i"), JAddExpr(local("i"), IntConstant.getInstance(1)), pos)
        val backGoto = JGotoStmt(pos)
        val exit = JReturnVoidStmt(pos)

        val graph = buildGraph(preLoop, header, bodyUse, bodyDef, backGoto, exit)
        graph.putEdge(preLoop, header)
        graph.putEdge(header, 0, exit) // fall-through (condition false) -> exit
        graph.putEdge(header, 1, bodyUse) // branch target (condition true) -> body
        graph.putEdge(bodyUse, bodyDef)
        graph.putEdge(bodyDef, backGoto)
        graph.putEdge(backGoto, 0, header) // back edge

        val cpg = CpgData.fromBody(graph, "test")

        assertTrue(isCdgParent(cpg, header, bodyUse), "loop body is control dependent on the header condition")
        assertTrue(isCdgParent(cpg, header, bodyDef), "loop body is control dependent on the header condition")
        assertTrue(isCdgParent(cpg, header, backGoto), "the back-edge goto is control dependent on the header condition")
        assertTrue(isCdgParent(cpg, header, header), "the loop header is control dependent on its own condition (self-loop CDG edge, the textbook property for a loop's controlling predicate)")
        assertFalse(isCdgParent(cpg, header, exit), "the loop exit post-dominates the header and must not be control dependent on it")
    }

    // ---- AST -------------------------------------------------------------

    @Test
    fun `AST subtree mirrors the Jimple expression tree`() {
        val assign = JAssignStmt(local("x"), JAddExpr(local("a"), local("b")), pos)
        val ret = JReturnStmt(local("x"), pos)

        val graph = buildGraph(assign, ret)
        graph.putEdge(assign, ret)

        val cpg = CpgData.fromBody(graph, "test")

        val assignIdx = cpg.stmtToIndex.getValue(assign)
        val rootEdge = cpg.bindingEdges.single { it.src == assignIdx }
        val root = cpg.astNodes.single { it.id == rootEdge.dst }
        assertEquals("JAssignStmt", root.kind)
        assertEquals(assign.toString(), root.text)

        val children = cpg.astEdges.filter { it.src == root.id }.map { e -> cpg.astNodes.single { it.id == e.dst } }
        assertEquals(2, children.size, "the assignment has exactly two direct children: target and value")

        val addExprNode = children.singleOrNull { it.kind == "JAddExpr" }
        assertNotNull(addExprNode, "the assignment's value child must be the JAddExpr")

        val grandchildren = cpg.astEdges.filter { it.src == addExprNode!!.id }.map { e -> cpg.astNodes.single { it.id == e.dst } }
        assertEquals(setOf("a", "b"), grandchildren.map { it.text }.toSet(), "JAddExpr's children are its two operands")
    }

    @Test
    fun `every statement binds to exactly one AST root whose text matches the statement`() {
        val assign = JAssignStmt(local("x"), IntConstant.getInstance(1), pos)
        val ret = JReturnVoidStmt(pos)

        val graph = buildGraph(assign, ret)
        graph.putEdge(assign, ret)

        val cpg = CpgData.fromBody(graph, "test")

        for (i in cpg.stmtLabels.indices) {
            val bindings = cpg.bindingEdges.filter { it.src == i }
            assertEquals(1, bindings.size, "statement $i must bind to exactly one AST root")
            val astRoot = cpg.astNodes.single { it.id == bindings[0].dst }
            assertEquals(cpg.stmtLabels[i], astRoot.text)
            assertTrue(astRoot.id >= JimpleAstNode.ID_OFFSET, "AST node ids must never collide with statement indices")
        }
    }
}
