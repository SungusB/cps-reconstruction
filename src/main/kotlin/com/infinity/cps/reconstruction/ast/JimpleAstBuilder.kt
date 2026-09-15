package com.infinity.cps.reconstruction.ast

import com.infinity.cps.reconstruction.cpg.CpgEdge
import com.infinity.cps.reconstruction.cpg.EdgeKind
import sootup.core.jimple.basic.Value
import sootup.core.jimple.common.expr.AbstractBinopExpr
import sootup.core.jimple.common.expr.AbstractInstanceInvokeExpr
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.AbstractUnopExpr
import sootup.core.jimple.common.expr.JCastExpr
import sootup.core.jimple.common.expr.JInstanceOfExpr
import sootup.core.jimple.common.expr.JNewArrayExpr
import sootup.core.jimple.common.expr.JNewMultiArrayExpr
import sootup.core.jimple.common.ref.JArrayRef
import sootup.core.jimple.common.ref.JInstanceFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JIdentityStmt
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.jimple.common.stmt.JThrowStmt
import sootup.core.jimple.common.stmt.Stmt

/**
 * Builds the AST layer of the CPG (Yamaguchi et al., "Modeling and
 * Discovering Vulnerabilities with Code Property Graphs", IEEE S&P 2014)
 * directly from Jimple — the same IR SootUp already builds the CFG/DDG/CDG
 * from — instead of re-parsing Kotlin source.
 *
 * This keeps all four layers at the same node granularity: a statement's AST
 * subtree is rooted at a node that corresponds exactly to that statement, so
 * the fusion edge to its CFG/DDG/CDG node ([EdgeKind.BINDS_TO], added by the
 * caller) is exact by construction. A source-level AST would instead need a
 * source-line heuristic, because a compiler-lowered statement (especially
 * post suspend-chain reconstruction) doesn't correspond 1:1 with a source
 * line — one Kotlin expression can compile to several Jimple statements, or
 * several source lines can collapse into one after coroutine dispatch
 * artifacts are stripped.
 *
 * Each expression/value one level inside a statement becomes a child AST
 * node, recursively, down to `Local`/`Constant` leaves — e.g. `x = a + b`
 * becomes a `JAssignStmt` root with a `target` child (`x`) and a `value`
 * child (`JAddExpr`), itself with `op1`/`op2` children (`a`, `b`).
 */
object JimpleAstBuilder {

    class AstResult(val nodes: List<JimpleAstNode>, val edges: List<CpgEdge>, val rootId: Int, val nextId: Int)

    /**
     * Builds the AST subtree for one statement. Node ids start at [idOffset]
     * — callers building a whole method's AST layer pass in the previous
     * call's [AstResult.nextId] so ids stay unique across the method.
     */
    fun build(stmt: Stmt, idOffset: Int): AstResult {
        val nodes = mutableListOf<JimpleAstNode>()
        val edges = mutableListOf<CpgEdge>()
        var nextId = idOffset

        fun addNode(kind: String, text: String, parentId: Int?): Int {
            val id = nextId++
            nodes.add(JimpleAstNode(id, kind, text))
            if (parentId != null) edges.add(CpgEdge(parentId, id, EdgeKind.AST))
            return id
        }

        fun walkValue(value: Value, parentId: Int) {
            val id = addNode(value.javaClass.simpleName, value.toString(), parentId)
            for ((_, child) in valueChildren(value)) {
                walkValue(child, id)
            }
        }

        val rootId = addNode(stmt.javaClass.simpleName, stmt.toString(), null)
        for ((_, child) in stmtChildren(stmt)) {
            walkValue(child, rootId)
        }

        return AstResult(nodes, edges, rootId, nextId)
    }

    private fun stmtChildren(stmt: Stmt): List<Pair<String, Value>> = when (stmt) {
        is JAssignStmt -> listOf("target" to stmt.leftOp, "value" to stmt.rightOp)
        is JIdentityStmt -> listOf("target" to stmt.leftOp, "value" to stmt.rightOp)
        is JIfStmt -> listOf("condition" to stmt.condition)
        is JReturnStmt -> listOf("value" to stmt.op)
        is JThrowStmt -> listOf("value" to stmt.op)
        is JInvokeStmt -> stmt.invokeExpr.orElse(null)?.let { listOf("call" to (it as Value)) } ?: emptyList()
        else -> emptyList()
    }

    private fun valueChildren(value: Value): List<Pair<String, Value>> = when (value) {
        is AbstractInstanceInvokeExpr -> listOf("base" to value.base) + value.args.mapIndexed { i, arg -> "arg$i" to arg }
        is AbstractInvokeExpr -> value.args.mapIndexed { i, arg -> "arg$i" to arg }
        is AbstractBinopExpr -> listOf("op1" to value.op1, "op2" to value.op2)
        is AbstractUnopExpr -> listOf("op" to value.op)
        is JCastExpr -> listOf("op" to value.op)
        is JInstanceOfExpr -> listOf("op" to value.op)
        is JNewMultiArrayExpr -> value.sizes.mapIndexed { i, s -> "size$i" to s }
        is JNewArrayExpr -> listOf("size" to value.size)
        is JArrayRef -> listOf("base" to value.base, "index" to value.index)
        is JInstanceFieldRef -> listOf("base" to value.base)
        else -> emptyList()
    }
}
