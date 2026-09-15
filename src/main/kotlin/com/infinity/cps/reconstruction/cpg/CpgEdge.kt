package com.infinity.cps.reconstruction.cpg

/**
 * A typed CPG edge, replacing the earlier ad hoc `"src|dst|label"` string
 * encoding: `src` and `dst` are node ids (a bytecode statement index for
 * [EdgeKind.CFG]/[EdgeKind.DDG]/[EdgeKind.CDG] edges and one endpoint of
 * [EdgeKind.BINDS_TO]; a [com.infinity.cps.reconstruction.ast.KotlinAstNode]
 * id, offset by [com.infinity.cps.reconstruction.ast.KotlinAstNode.ID_OFFSET],
 * for [EdgeKind.AST] edges and the other endpoint of [EdgeKind.BINDS_TO]).
 *
 * [variable] carries the def-use variable name for a [EdgeKind.DDG] edge
 * (prefixed `field:`/`alias:` for the field-sensitive edges
 * [com.infinity.cps.reconstruction.cpg.FieldAliasTracker] adds). [condition]
 * carries the branch condition text for a [EdgeKind.CDG] edge.
 */
data class CpgEdge(
    val src: Int,
    val dst: Int,
    val kind: EdgeKind,
    val variable: String? = null,
    val condition: String? = null,
)
