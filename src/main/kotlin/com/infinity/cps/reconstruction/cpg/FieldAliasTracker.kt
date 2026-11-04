package com.infinity.cps.reconstruction.cpg

import sootup.core.jimple.basic.Local
import sootup.core.jimple.basic.Value
import sootup.core.jimple.common.expr.JVirtualInvokeExpr
import sootup.core.jimple.common.ref.JInstanceFieldRef
import sootup.core.jimple.common.ref.JStaticFieldRef
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.Stmt
import java.util.LinkedList

/**
 * Tracks field-level data flow within a method for taint analysis.
 *
 * SootUp's standard DDG only tracks local variable definitions and uses.
 * This adds field accesses too — including Kotlin synthetic getter/setter
 * calls — so a write to a field at statement X and a later read of the same
 * field at statement Y (X < Y) becomes a DDG edge. That's what lets taint
 * cross a field write/read boundary within one method's CPG, e.g.
 * `this.x = tainted; ...; sink(this.x)`.
 */
object FieldAliasTracker {

    /** A single field access — a direct reference or a getter/setter call. */
    data class FieldAccess(
        val stmtIdx: Int,
        val baseLocalName: String,
        val fieldName: String,
        val fieldSignature: String,
        val isWrite: Boolean,
        val accessType: String,
    )

    fun findFieldAccesses(data: CpgData): List<FieldAccess> {
        val accesses = mutableListOf<FieldAccess>()

        for (i in data.stmtLabels.indices) {
            val stmt = data.indexToStmt[i] ?: continue

            if (stmt is JAssignStmt) {
                val defOpt = stmt.def
                if (defOpt.isPresent && defOpt.get() is JInstanceFieldRef) {
                    val fieldRef = defOpt.get() as JInstanceFieldRef
                    val sig = fieldRef.fieldSignature
                    accesses.add(FieldAccess(i, fieldRef.base.toString(), sig.name, "${sig.declClassType}.${sig.name}", true, "DIRECT"))
                }
                if (defOpt.isPresent && defOpt.get() is JStaticFieldRef) {
                    val sig = (defOpt.get() as JStaticFieldRef).fieldSignature
                    accesses.add(FieldAccess(i, "STATIC", sig.name, "${sig.declClassType}.${sig.name}", true, "DIRECT"))
                }

                val rightOp: Value = stmt.rightOp
                for (useVal in stmt.uses.toList()) {
                    if (useVal is JInstanceFieldRef) {
                        if (defOpt.isPresent && defOpt.get() === useVal) continue
                        val sig = useVal.fieldSignature
                        accesses.add(FieldAccess(i, useVal.base.toString(), sig.name, "${sig.declClassType}.${sig.name}", false, "DIRECT"))
                    }
                    if (useVal is JStaticFieldRef) {
                        val sig = useVal.fieldSignature
                        accesses.add(FieldAccess(i, "STATIC", sig.name, "${sig.declClassType}.${sig.name}", false, "DIRECT"))
                    }
                }

                if (rightOp is JVirtualInvokeExpr) {
                    val methodSig = rightOp.methodSignature
                    val fieldName = extractFieldNameFromGetter(methodSig.name)
                    if (fieldName != null) {
                        val baseLocal = (rightOp.base as? Local)?.toString() ?: "this"
                        accesses.add(FieldAccess(i, baseLocal, fieldName, "${methodSig.declClassType}.$fieldName", false, "GETTER"))
                    }
                }
            } else {
                for (useVal in stmt.uses.toList()) {
                    if (useVal is JInstanceFieldRef) {
                        val sig = useVal.fieldSignature
                        accesses.add(FieldAccess(i, useVal.base.toString(), sig.name, "${sig.declClassType}.${sig.name}", false, "DIRECT"))
                    }
                    if (useVal is JStaticFieldRef) {
                        val sig = useVal.fieldSignature
                        accesses.add(FieldAccess(i, "STATIC", sig.name, "${sig.declClassType}.${sig.name}", false, "DIRECT"))
                    }
                }
            }

            if (stmt is JInvokeStmt) {
                val invokeOpt = stmt.invokeExpr
                if (invokeOpt.isPresent && invokeOpt.get() is JVirtualInvokeExpr) {
                    val invoke = invokeOpt.get() as JVirtualInvokeExpr
                    val fieldName = extractFieldNameFromSetter(invoke.methodSignature.name)
                    if (fieldName != null) {
                        val baseLocal = (invoke.base as? Local)?.toString() ?: "this"
                        accesses.add(FieldAccess(i, baseLocal, fieldName, "${invoke.methodSignature.declClassType}.$fieldName", true, "SETTER"))
                    }
                }
            }
            if (stmt is JAssignStmt) {
                val rightOp = stmt.rightOp
                if (rightOp is JVirtualInvokeExpr) {
                    val fieldName = extractFieldNameFromSetter(rightOp.methodSignature.name)
                    if (fieldName != null) {
                        val baseLocal = (rightOp.base as? Local)?.toString() ?: "this"
                        accesses.add(FieldAccess(i, baseLocal, fieldName, "${rightOp.methodSignature.declClassType}.$fieldName", true, "SETTER"))
                    }
                }
            }
        }

        return accesses
    }

    /** getXxx() -> xxx, isXxx() -> xxx. Null if not a getter. */
    private fun extractFieldNameFromGetter(methodName: String): String? = when {
        methodName.startsWith("get") && methodName.length > 3 ->
            methodName[3].lowercaseChar() + methodName.substring(4)
        methodName.startsWith("is") && methodName.length > 2 ->
            methodName[2].lowercaseChar() + methodName.substring(3)
        else -> null
    }

    /** setXxx() -> xxx. Null if not a setter. */
    private fun extractFieldNameFromSetter(methodName: String): String? =
        if (methodName.startsWith("set") && methodName.length > 3) {
            methodName[3].lowercaseChar() + methodName.substring(4)
        } else {
            null
        }

    /**
     * DDG edges connecting field writes to field reads: a write at statement X
     * connects to a read at statement Y if the same field is accessed at both
     * and Y is actually reachable from X in the CFG. Also adds local-alias
     * edges for `r2 = r1` copies where `r1` was itself assigned from a field
     * read reachable from that copy.
     *
     * Reachability, not statement index order, is what makes this sound: two
     * statements can be adjacent in a method's flat statement list while
     * belonging to control-flow regions with no path between them — most
     * notably, a Kotlin coroutine's `invokeSuspend` switch cases each end in
     * a `return` with no CFG successor (resuming happens via a separate call,
     * not a fall-through), so a write in one case and a read in another are
     * not actually connected within a single execution.
     */
    fun findFieldDdgEdges(data: CpgData): List<CpgEdge> {
        // A set, and one memoized BFS per source statement: the original
        // per-pair BFS plus `edge !in edges` on a list was roughly cubic in
        // method size, and took >10 minutes on the raw (unreconstructed)
        // generated-suite bodies, which carry hundreds of spill-field
        // accesses. Same edges, same order.
        val edges = linkedSetOf<CpgEdge>()
        val reach = Reachability(data)
        val accesses = findFieldAccesses(data)

        val byField = linkedMapOf<String, MutableList<FieldAccess>>()
        for (acc in accesses) {
            byField.getOrPut(acc.fieldSignature) { mutableListOf() }.add(acc)
        }

        for ((_, fieldAccesses) in byField) {
            val writes = fieldAccesses.filter { it.isWrite }
            val reads = fieldAccesses.filter { !it.isWrite }
            for (write in writes) {
                for (read in reads) {
                    if (read.stmtIdx != write.stmtIdx && reach.isReachable(write.stmtIdx, read.stmtIdx)) {
                        edges.add(CpgEdge(write.stmtIdx, read.stmtIdx, EdgeKind.DDG, variable = "field:${write.fieldName}"))
                    }
                }
            }
        }

        addLocalAliasEdges(data, accesses, edges, reach)
        return edges.toList()
    }

    private fun addLocalAliasEdges(data: CpgData, accesses: List<FieldAccess>, edges: MutableSet<CpgEdge>, reach: Reachability) {
        for (i in data.stmtLabels.indices) {
            val stmt = data.indexToStmt[i] as? JAssignStmt ?: continue
            val defOpt = stmt.def
            if (!defOpt.isPresent || defOpt.get() !is Local) continue

            val right = stmt.rightOp
            if (right !is Local) continue

            for (acc in accesses) {
                if (acc.isWrite || acc.stmtIdx == i || !reach.isReachable(acc.stmtIdx, i)) continue
                val fieldReadStmt = data.indexToStmt[acc.stmtIdx] as? JAssignStmt ?: continue
                val fieldDef = fieldReadStmt.def
                if (fieldDef.isPresent && fieldDef.get() is Local && (fieldDef.get() as Local).name == right.name) {
                    edges.add(CpgEdge(acc.stmtIdx, i, EdgeKind.DDG, variable = "alias:${acc.fieldName}"))
                }
            }
        }
    }

    /** CFG reachability over one or more steps, one BFS per source statement, cached. */
    private class Reachability(private val data: CpgData) {
        private val cache = HashMap<Int, Set<Int>>()

        fun isReachable(from: Int, to: Int): Boolean =
            from == to || to in cache.getOrPut(from) { successorsClosure(from) }

        private fun successorsClosure(from: Int): Set<Int> {
            val visited = HashSet<Int>()
            val queue: java.util.Queue<Int> = LinkedList()
            queue.add(from)
            while (queue.isNotEmpty()) {
                val curr = queue.poll()
                for (succ in data.cfgSuccessors[curr].orEmpty()) {
                    if (visited.add(succ)) queue.add(succ)
                }
            }
            return visited
        }
    }

    /** Adds field-based DDG edges to [data]'s `ddgEdges`. Returns the number added. */
    fun addFieldDdgEdges(data: CpgData): Int {
        var added = 0
        for (edge in findFieldDdgEdges(data)) {
            if (data.ddgEdges.add(edge)) added++
        }
        return added
    }
}
