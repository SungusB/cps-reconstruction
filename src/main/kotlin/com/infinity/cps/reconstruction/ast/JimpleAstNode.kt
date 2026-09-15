package com.infinity.cps.reconstruction.ast

/**
 * One node of the AST layer, built from Jimple (SootUp's three-address-code
 * IR) by [JimpleAstBuilder] rather than from Kotlin source: [kind] is the
 * concrete Jimple class name of the statement or expression/value this node
 * represents (e.g. `"JAssignStmt"`, `"JAddExpr"`, `"Local"`,
 * `"JVirtualInvokeExpr"`), and [text] is that node's own Jimple
 * pretty-printed text (not its subtree's).
 *
 * [id] is offset by [ID_OFFSET] so it never collides with a bytecode
 * statement index in the same [com.infinity.cps.reconstruction.cpg.CpgData],
 * letting AST and statement nodes share one edge/id space. A statement's own
 * AST root node is *not* the same id as the statement's CFG/DDG/CDG index —
 * the two are connected by an explicit [com.infinity.cps.reconstruction.cpg.EdgeKind.BINDS_TO]
 * edge — since a node identified purely by an AST node type needs its own
 * id space distinct from "the n-th statement in the method".
 */
data class JimpleAstNode(
    val id: Int,
    val kind: String,
    val text: String,
) {
    companion object {
        const val ID_OFFSET = 1_000_000
    }
}
