package com.infinity.cps.reconstruction.reconstruct

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import sootup.core.graph.MutableBlockStmtGraph
import sootup.core.jimple.basic.Local
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.expr.JEqExpr
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JGotoStmt
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JNopStmt
import sootup.core.jimple.common.stmt.JReturnVoidStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.signatures.PackageName
import sootup.core.types.PrimitiveType
import sootup.java.core.types.JavaClassType

/**
 * Unit tests for [SuspendChainReconstructor.findUnsupportedControlFlow] — the
 * detection-and-decline logic that makes reconstruction sound: a case block
 * extracted from a coroutine's switch must be declined (not spliced into a
 * linear body) if it contains real control flow rather than pure
 * state-dispatch bookkeeping.
 *
 * Each test builds a small, real [MutableBlockStmtGraph] by hand, with the
 * same `caseBlocks` / `dispatchTargets` shape [SuspendChainReconstructor]
 * would produce from an actual switch: a case block is a short statement
 * sequence, and `dispatchTargets` is the set of statements the switch itself
 * jumps to.
 */
class SuspendChainReconstructorTest {

    private val pos: StmtPositionInfo = StmtPositionInfo.getNoStmtPositionInfo()
    private val intType = PrimitiveType.getInt()

    private fun local(name: String) = Local(name, intType)

    /**
     * Straight-line suspend chain — the solved case. Case 0 does some work
     * and falls through to a goto that jumps straight to case 1's dispatch
     * target (a legal "this case does nothing but hand off" pattern); case 1
     * does more work and returns. No conditional, no non-dispatch jump.
     */
    @Test
    fun `straight-line chain is not flagged unsupported`() {
        val assign1 = JAssignStmt(local("r0"), IntConstant.getInstance(1), pos)
        val dispatchGoto = JGotoStmt(pos)
        val assign2 = JAssignStmt(local("r1"), IntConstant.getInstance(2), pos)
        val ret = JReturnVoidStmt(pos)

        val graph = MutableBlockStmtGraph()
        graph.addNode(assign1)
        graph.addNode(dispatchGoto)
        graph.addNode(assign2)
        graph.addNode(ret)
        graph.setStartingStmt(assign1)
        graph.putEdge(assign1, dispatchGoto)
        graph.putEdge(dispatchGoto, 0, assign2)
        graph.putEdge(assign2, ret)

        val caseBlocks = listOf(
            listOf(assign1, dispatchGoto), // case 0: hands off to case 1
            listOf(assign2, ret),          // case 1: the final case
        )
        val dispatchTargets: Set<Stmt> = setOf(assign1, assign2)

        val reason = SuspendChainReconstructor.findUnsupportedControlFlow(graph, caseBlocks, dispatchTargets)

        assertNull(reason, "a straight-line chain must not be flagged unsupported")
    }

    /**
     * Suspend call inside an if/else — a genuine conditional between
     * suspension points. Must be detected and declined, not silently
     * flattened.
     */
    @Test
    fun `if-else between suspension points is flagged unsupported`() {
        val cond = JEqExpr(local("r0"), IntConstant.getInstance(0))
        val ifStmt = JIfStmt(cond, pos)
        val thenBranch = JAssignStmt(local("r1"), IntConstant.getInstance(1), pos)
        val elseBranch = JAssignStmt(local("r1"), IntConstant.getInstance(2), pos)
        val thenReturn = JReturnVoidStmt(pos)
        val elseReturn = JReturnVoidStmt(pos)

        val graph = MutableBlockStmtGraph()
        graph.addNode(ifStmt)
        graph.addNode(thenBranch)
        graph.addNode(elseBranch)
        graph.addNode(thenReturn)
        graph.addNode(elseReturn)
        graph.setStartingStmt(ifStmt)
        graph.putEdge(ifStmt, 0, thenBranch) // fall-through (condition false)
        graph.putEdge(ifStmt, 1, elseBranch) // branch target (condition true)
        graph.putEdge(thenBranch, thenReturn)
        graph.putEdge(elseBranch, elseReturn)

        val caseBlocks = listOf(listOf(ifStmt, thenBranch, elseBranch, thenReturn, elseReturn))
        val dispatchTargets: Set<Stmt> = setOf(ifStmt)

        val reason = SuspendChainReconstructor.findUnsupportedControlFlow(graph, caseBlocks, dispatchTargets)

        assertTrue(reason != null && reason.contains("conditional branch"),
            "expected an if/else to be declined with a 'conditional branch' reason, got: $reason")
    }

    /**
     * Suspend call inside a while loop — a loop back-edge (a goto whose
     * target is a mid-block statement, not one of the switch's own dispatch
     * targets). Must be detected and declined.
     */
    @Test
    fun `while-loop back-edge is flagged unsupported`() {
        val caseEntry = JNopStmt(pos)      // this case's actual dispatch target
        val loopCheck = JNopStmt(pos)      // loop condition check — NOT a dispatch target
        val body = JAssignStmt(local("r0"), IntConstant.getInstance(1), pos)
        val backGoto = JGotoStmt(pos)      // loop back-edge

        val graph = MutableBlockStmtGraph()
        graph.addNode(caseEntry)
        graph.addNode(loopCheck)
        graph.addNode(body)
        graph.addNode(backGoto)
        graph.setStartingStmt(caseEntry)
        graph.putEdge(caseEntry, loopCheck)
        graph.putEdge(loopCheck, body)
        graph.putEdge(body, backGoto)
        graph.putEdge(backGoto, 0, loopCheck) // back-edge to the loop header, not to caseEntry

        val caseBlocks = listOf(listOf(caseEntry, loopCheck, body, backGoto))
        val dispatchTargets: Set<Stmt> = setOf(caseEntry)

        val reason = SuspendChainReconstructor.findUnsupportedControlFlow(graph, caseBlocks, dispatchTargets)

        assertTrue(reason != null && reason.contains("non-dispatch target"),
            "expected a loop back-edge to be declined with a 'non-dispatch target' reason, got: $reason")
    }

    /**
     * Suspend call inside a try/catch — the statement is covered by a trap
     * (an exceptional edge to a handler). Exception tables are explicitly
     * out of scope; this must be detected and declined too, not just
     * silently spliced out of its handler context.
     */
    @Test
    fun `statement inside try-catch is flagged unsupported`() {
        val guarded = JAssignStmt(local("r0"), IntConstant.getInstance(1), pos)
        val handler = JNopStmt(pos)
        val exceptionType = JavaClassType("Exception", PackageName("java.lang"))

        val graph = MutableBlockStmtGraph()
        graph.addNode(guarded)
        graph.addNode(handler)
        graph.setStartingStmt(guarded)
        graph.addExceptionalEdge(guarded, exceptionType, handler)

        val caseBlocks = listOf(listOf(guarded))
        val dispatchTargets: Set<Stmt> = setOf(guarded)

        val reason = SuspendChainReconstructor.findUnsupportedControlFlow(graph, caseBlocks, dispatchTargets)

        assertTrue(reason != null && reason.contains("try/catch"),
            "expected a trapped statement to be declined with a 'try/catch' reason, got: $reason")
    }

    /**
     * Unit tests for [SuspendChainReconstructor.resolvePreservableExceptionalEdges] —
     * the decision [unrollGeneralCase] uses to preserve a single-level try/catch
     * instead of declining on sight, extracted into a graph-only function
     * (same rationale as [findUnsupportedControlFlow]: testable against a small
     * hand-built graph, no [sootup.core.model.SootMethod] required).
     */
    @Nested
    inner class ResolvePreservableExceptionalEdges {

        private val exceptionType = JavaClassType("Exception", PackageName("java.lang"))

        /**
         * The case01/case03-shaped trap: a protected statement whose handler has
         * no further exceptional successors of its own, and resolves (via the
         * caller's bookkeeping-skip) to a real statement. Must be preserved, not
         * declined.
         */
        @Test
        fun `a single-level resolvable trap is preserved`() {
            val guarded = JAssignStmt(local("r0"), IntConstant.getInstance(1), pos)
            val handler = JNopStmt(pos)

            val graph = MutableBlockStmtGraph()
            graph.addNode(guarded)
            graph.addNode(handler)
            graph.setStartingStmt(guarded)
            graph.addExceptionalEdge(guarded, exceptionType, handler)

            val preserved = SuspendChainReconstructor.resolvePreservableExceptionalEdges(graph, guarded) { listOf(it) }

            assertNotNull(preserved, "a single-level resolvable trap must not be declined")
            assertEquals(listOf(Triple(guarded, exceptionType, handler)), preserved)
        }

        /**
         * A statement with no exceptional successors at all — the common case.
         * Must return an empty list (nothing to preserve), not decline.
         */
        @Test
        fun `a statement with no exceptional successors preserves nothing and does not decline`() {
            val plain = JAssignStmt(local("r0"), IntConstant.getInstance(1), pos)
            val graph = MutableBlockStmtGraph()
            graph.addNode(plain)
            graph.setStartingStmt(plain)

            val preserved = SuspendChainReconstructor.resolvePreservableExceptionalEdges(graph, plain) { listOf(it) }

            assertEquals(emptyList<Triple<Stmt, sootup.core.types.ClassType, Stmt>>(), preserved)
        }

        /**
         * The case02 (`finally`-with-suspend) shape verified directly against
         * compiled bytecode: the handler is itself exceptionally protected (a
         * nested/self-referential trap). Must decline (`null`), not guess.
         */
        @Test
        fun `a handler that is itself exceptionally protected is declined`() {
            val guarded = JAssignStmt(local("r0"), IntConstant.getInstance(1), pos)
            val handler = JNopStmt(pos)
            val nestedHandler = JNopStmt(pos)

            val graph = MutableBlockStmtGraph()
            graph.addNode(guarded)
            graph.addNode(handler)
            graph.addNode(nestedHandler)
            graph.setStartingStmt(guarded)
            graph.addExceptionalEdge(guarded, exceptionType, handler)
            graph.addExceptionalEdge(handler, exceptionType, nestedHandler) // handler is itself trapped

            val preserved = SuspendChainReconstructor.resolvePreservableExceptionalEdges(graph, guarded) { listOf(it) }

            assertNull(preserved, "a handler that is itself exceptionally protected must be declined")
        }

        /**
         * A handler that resolves to nothing real (e.g. a dead bookkeeping-only
         * chain) must decline rather than silently drop the edge.
         */
        @Test
        fun `a handler that resolves to nothing real is declined`() {
            val guarded = JAssignStmt(local("r0"), IntConstant.getInstance(1), pos)
            val handler = JNopStmt(pos)

            val graph = MutableBlockStmtGraph()
            graph.addNode(guarded)
            graph.addNode(handler)
            graph.setStartingStmt(guarded)
            graph.addExceptionalEdge(guarded, exceptionType, handler)

            val preserved = SuspendChainReconstructor.resolvePreservableExceptionalEdges(graph, guarded) { emptyList() }

            assertNull(preserved, "a handler resolving to nothing real must be declined")
        }
    }
}
