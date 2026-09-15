package com.infinity.cps.reconstruction.reconstruct

import sootup.core.graph.MutableStmtGraph
import sootup.core.graph.StmtGraph
import sootup.core.jimple.basic.LValue
import sootup.core.jimple.basic.Local
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JCastExpr
import sootup.core.jimple.common.expr.JInstanceOfExpr
import sootup.core.jimple.common.stmt.BranchingStmt
import sootup.core.jimple.common.stmt.JAssignStmt
import sootup.core.jimple.common.stmt.JGotoStmt
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JInvokeStmt
import sootup.core.jimple.common.stmt.JReturnStmt
import sootup.core.jimple.common.stmt.JReturnVoidStmt
import sootup.core.jimple.common.stmt.JThrowStmt
import sootup.core.jimple.common.stmt.Stmt
import sootup.core.jimple.javabytecode.stmt.JSwitchStmt
import sootup.core.model.Body
import sootup.core.model.SootMethod
import sootup.core.signatures.MethodSignature
import sootup.core.types.ClassType
import sootup.java.core.JavaSootClass
import sootup.java.core.JavaSootMethod
import sootup.java.core.views.JavaView
import sootup.java.core.views.MutableJavaView

/**
 * Reconstructs straight-line control flow from a Kotlin coroutine's
 * compiler-generated continuation-passing-style (CPS) state machine.
 *
 * The Kotlin compiler lowers every `suspend` function into a state machine:
 * a class implementing `ContinuationImpl`/`SuspendLambda` with a `label`
 * field, spill fields (`L$0`, `L$1`, ...) that persist locals across
 * suspension points, and a `tableswitch` dispatching on `label`. This class
 * strips that dispatch bookkeeping and splices the case blocks back into one
 * linear body — but only when doing so is sound.
 *
 * Reconstruction is sound only for a straight-line suspend chain: one or
 * more suspend calls with no other control flow between them. A method with
 * real branching or looping between suspension points is left completely
 * untouched (see [findUnsupportedControlFlow]) and recorded in
 * [getUnsupportedMethods] instead of being partially or incorrectly
 * unrolled.
 */
class SuspendChainReconstructor(private val view: JavaView) {

    /** Coroutine state machines that were detected but declined because they contain real control flow. */
    private val unsupportedMethods: MutableSet<MethodSignature> = linkedSetOf()

    fun getUnsupportedMethods(): Set<MethodSignature> = unsupportedMethods

    /**
     * Reconstructs every eligible coroutine state machine reachable from [methods].
     * Returns the set of method signatures that were rewritten.
     *
     * [view] must be a [MutableJavaView] for the rewrite to actually apply; otherwise
     * the unrolled bodies are computed but discarded.
     */
    fun demultiplex(methods: Set<MethodSignature>): Set<MethodSignature> {
        val modified = mutableSetOf<MethodSignature>()
        val replacementsByClass = linkedMapOf<ClassType, MutableList<MethodReplacement>>()

        for (sig in methods) {
            val opt = view.getMethod(sig)
            if (opt.isEmpty || !opt.get().hasBody()) continue

            val method = opt.get()
            if (!isCoroutineStateMachine(method)) continue

            val newBody = unrollCoroutine(method) ?: continue
            val newMethod = method.withBody(newBody)

            replacementsByClass.getOrPut(method.declaringClassType) { mutableListOf() }
                .add(MethodReplacement(method, newMethod))
            modified.add(sig)
        }

        val mutableView = view as? MutableJavaView
        if (mutableView != null) {
            for ((classType, replacements) in replacementsByClass) {
                applyReplacements(mutableView, classType, replacements)
            }
        } else {
            println("[SuspendChainReconstructor] view is not mutable — "
                + "${modified.size} unrolled bodies were computed but not applied")
        }

        return modified
    }

    private class MethodReplacement(val oldMethod: JavaSootMethod, val newMethod: JavaSootMethod)

    private fun applyReplacements(view: MutableJavaView, classType: ClassType, replacements: List<MethodReplacement>) {
        val classOpt = view.getClass(classType)
        if (classOpt.isEmpty) return

        var currentClass: JavaSootClass = classOpt.get()
        for (rep in replacements) {
            currentClass = currentClass.withReplacedMethod(rep.oldMethod, rep.newMethod)
        }
        view.replaceClass(classOpt.get(), currentClass)
    }

    companion object {
        /** Compares two signatures by name and parameter types, ignoring Kotlin nullability annotations. */
        fun methodMatches(sig1: MethodSignature, sig2: MethodSignature): Boolean {
            if (sig1.name != sig2.name) return false

            val params1 = sig1.parameterTypes
            val params2 = sig2.parameterTypes
            if (params1.size != params2.size) return false

            for (i in params1.indices) {
                if (stripNullability(params1[i].toString()) != stripNullability(params2[i].toString())) return false
            }
            return true
        }

        /** Maps `methodName(paramTypes)` to its callers, for resolving calls without needing a declaring class. */
        fun buildCallerMap(view: JavaView, reachableMethods: Set<MethodSignature>): Map<String, List<MethodSignature>> {
            val callersMap = linkedMapOf<String, MutableList<MethodSignature>>()

            for (methodSig in reachableMethods) {
                val opt = view.getMethod(methodSig)
                if (opt.isEmpty || !opt.get().hasBody()) continue

                for (stmt in opt.get().body.stmts) {
                    if (stmt !is JInvokeStmt) continue
                    val invokeOpt = stmt.invokeExpr
                    if (!invokeOpt.isPresent) continue

                    val calleeSig = invokeOpt.get().methodSignature
                    val calleeKey = "${calleeSig.name}(" +
                        calleeSig.parameterTypes.joinToString(",") { stripNullability(it.toString()) } + ")"
                    callersMap.getOrPut(calleeKey) { mutableListOf() }.add(methodSig)
                }
            }
            return callersMap
        }

        private fun stripNullability(typeName: String) = typeName.replace(Regex("@NotNull|@Nullable|\\?"), "")

        /**
         * Checks a switch's extracted case blocks for control flow that isn't part of
         * the switch's own dispatch bookkeeping. Three things disqualify a case block:
         *
         * - a [JIfStmt] that isn't the compiler's `COROUTINE_SUSPENDED` check (a real
         *   conditional — dispatch logic never needs one, the switch already is the dispatch)
         * - a [JGotoStmt] whose target isn't one of the switch's own case labels (a loop
         *   back-edge, most commonly)
         * - a statement with exceptional successors (it's covered by a try/catch trap)
         *
         * A goto that does target another case label is normal dispatch bookkeeping and
         * is left alone — it's stripped later during assembly.
         *
         * Takes the [StmtGraph] directly, rather than a [Body] or [SootMethod], so it can
         * be unit-tested against a small hand-built graph.
         */
        internal fun findUnsupportedControlFlow(
            graph: StmtGraph<*>,
            caseBlocks: List<List<Stmt>>,
            dispatchTargets: Set<Stmt>,
        ): String? {
            for (caseIdx in caseBlocks.indices) {
                for (stmt in caseBlocks[caseIdx]) {
                    if (stmt is JIfStmt && !isCoroutineSuspendedCheck(stmt, graph)) {
                        return "case block $caseIdx contains a conditional branch: $stmt"
                    }

                    if (stmt is JGotoStmt) {
                        val targets = graph.successors(stmt)
                        if (targets.isEmpty()) continue
                        if (targets[0] !in dispatchTargets) {
                            return "case block $caseIdx contains a goto to a non-dispatch target " +
                                "(likely a loop back-edge): $stmt"
                        }
                    }

                    if (graph.exceptionalSuccessors(stmt).isNotEmpty()) {
                        return "case block $caseIdx contains a statement inside a try/catch " +
                            "protected region (exception tables are out of scope): $stmt"
                    }
                }
            }
            return null
        }

        /**
         * Every suspend call site is followed by `if (result == COROUTINE_SUSPENDED)
         * return COROUTINE_SUSPENDED` — compiler bookkeeping, not a user conditional.
         * Recognized by tracing either compared operand back to a call to
         * `IntrinsicsKt.getCOROUTINE_SUSPENDED()`, searching the whole method body since
         * the sentinel is computed once in a shared preamble ahead of the switch, not
         * inside any individual case block.
         */
        private fun isCoroutineSuspendedCheck(ifStmt: JIfStmt, graph: StmtGraph<*>): Boolean {
            val condition = ifStmt.condition
            val operandNames = setOfNotNull(
                (condition.op1 as? Local)?.name,
                (condition.op2 as? Local)?.name,
            )
            if (operandNames.isEmpty()) return false

            for (stmt in graph.stmts) {
                val assign = stmt as? JAssignStmt ?: continue
                val def = assign.def
                if (!def.isPresent || def.get() !is Local) continue
                if ((def.get() as Local).name in operandNames && assign.toString().contains("getCOROUTINE_SUSPENDED")) {
                    return true
                }
            }
            return false
        }
    }

    /**
     * Kotlin compiles a `suspend` construct into a state machine one of two ways:
     *
     * - A **suspend lambda** (passed to a coroutine builder, or any higher-order
     *   function with a `suspend` parameter) generates a class extending
     *   `SuspendLambda`/`ContinuationImpl` whose `invokeSuspend` method contains the
     *   `label` switch directly.
     * - An **ordinary `suspend fun`** keeps the switch in its own body. The compiler
     *   still generates a sibling `ContinuationImpl` subclass to hold the `label` and
     *   spill fields, and the function casts to (or `instanceof`-checks against) that
     *   class before reading `label`. That class's own `invokeSuspend` is a trivial
     *   shim with no switch of its own, and is correctly not matched here.
     */
    private fun isCoroutineStateMachine(method: SootMethod): Boolean {
        if (!method.hasBody()) return false
        if (!method.body.stmts.any { it is JSwitchStmt }) return false

        if (method.name == "invokeSuspend" && extendsContinuationClass(method.declaringClassType)) {
            return true
        }

        return method.body.stmts.any { stmt ->
            referencedClassType(stmt)?.let { extendsContinuationClass(it) } == true
        }
    }

    private fun referencedClassType(stmt: Stmt): ClassType? {
        val rightOp = (stmt as? JAssignStmt)?.rightOp ?: return null
        val type = when (rightOp) {
            is JCastExpr -> rightOp.type
            is JInstanceOfExpr -> rightOp.checkType
            else -> return null
        }
        return type as? ClassType
    }

    private fun extendsContinuationClass(classType: ClassType): Boolean {
        val classOpt = view.getClass(classType)
        if (classOpt.isEmpty) return false

        var current = classOpt.get()
        while (true) {
            val superName = if (current.superclass.isPresent) current.superclass.get().className else ""
            if (superName.contains("SuspendLambda") ||
                superName.contains("ContinuationImpl") ||
                superName.contains("RestrictedSuspendLambda")
            ) {
                return true
            }
            if (superName == "Object" || superName.isEmpty()) return false
            current = view.getClass(current.superclass.get()).orElse(null) ?: return false
        }
    }

    /**
     * Unrolls one coroutine's switch into a sequential body:
     * 1. Find the switch and its case targets.
     * 2. Extract each case block (target up to its return or goto).
     * 3. Bail if any block has real control flow ([findUnsupportedControlFlow]).
     * 4. Strip dispatch bookkeeping (label writes, spill writes, gotos, the switch itself).
     * 5. Splice the remaining statements into one new [Body].
     */
    private fun unrollCoroutine(method: SootMethod): Body? {
        val body = method.body
        val stmts = body.stmts

        val switchStmt = stmts.filterIsInstance<JSwitchStmt>().firstOrNull() ?: return null

        val caseTargets = switchStmt.getTargetStmts(body)
        val defaultTargetOpt = switchStmt.getDefaultTarget(body)
        val orderedTargets = caseTargets.toMutableList()
        if (defaultTargetOpt.isPresent) orderedTargets.add(defaultTargetOpt.get())
        if (orderedTargets.isEmpty()) return null

        val caseBlocks = orderedTargets.map { extractCaseBlock(stmts, it) }.filter { it.isNotEmpty() }
        if (caseBlocks.isEmpty()) return null

        val dispatchTargets = orderedTargets.toSet()
        val unsupportedReason = findUnsupportedControlFlow(body.stmtGraph, caseBlocks, dispatchTargets)
        if (unsupportedReason != null) {
            unsupportedMethods.add(method.signature)
            println("[SuspendChainReconstructor] general-case-unsupported: ${method.signature} — $unsupportedReason")
            return null
        }

        val unrolledStmts = mutableListOf<Stmt>()
        val usedLocals = linkedSetOf<Local>()

        for (caseIdx in caseBlocks.indices) {
            val isLastCase = caseIdx == caseBlocks.size - 1
            for (stmt in caseBlocks[caseIdx]) {
                if (isLabelAssignment(stmt)) continue
                if ((stmt is JReturnStmt || stmt is JReturnVoidStmt) && !isLastCase) continue // intermediate suspend return
                if (stmt is JGotoStmt) continue
                if (stmt is BranchingStmt) continue // the switch itself, or bookkeeping already cleared above
                if (isBoxingWrite(stmt)) continue

                collectLocals(stmt, usedLocals)
                unrolledStmts.add(stmt)
            }
        }
        if (unrolledStmts.isEmpty()) return null

        val dedupedStmts = mutableListOf<Stmt>()
        val seen = linkedSetOf<Stmt>()
        for (s in unrolledStmts) {
            if (seen.add(s)) dedupedStmts.add(s)
        }
        if (dedupedStmts.isEmpty()) return null

        // Everything after the first return/throw belongs to a different case
        // block and would be unreachable in the spliced body.
        val truncatedStmts = mutableListOf<Stmt>()
        for (s in dedupedStmts) {
            truncatedStmts.add(s)
            if (s is JReturnStmt || s is JReturnVoidStmt || s is JThrowStmt) break
        }

        val freshBuilder = Body.builder()
        freshBuilder.setMethodSignature(body.methodSignature)
        freshBuilder.setLocals(usedLocals)
        freshBuilder.setPosition(body.position)

        val freshGraph: MutableStmtGraph = freshBuilder.stmtGraph
        freshGraph.setStartingStmt(truncatedStmts[0])
        freshGraph.addBlock(truncatedStmts)

        println("[SuspendChainReconstructor] unrolled ${method.signature}: "
            + "${caseBlocks.size} states -> ${unrolledStmts.size} statements")

        return freshBuilder.build()
    }

    private fun isLabelAssignment(stmt: Stmt): Boolean {
        val s = stmt.toString()
        return (s.contains("label") && s.contains("fieldput")) ||
            (s.contains("label") && s.contains("specialinvoke") && s.contains("="))
    }

    private fun isBoxingWrite(stmt: Stmt): Boolean {
        val s = stmt.toString()
        return s.contains("fieldput") && (s.contains("L$") || s.contains("\$context"))
    }

    private fun extractCaseBlock(allStmts: List<Stmt>, startStmt: Stmt): List<Stmt> {
        val block = mutableListOf<Stmt>()
        val startIdx = allStmts.indexOf(startStmt)
        if (startIdx < 0) return block

        for (i in startIdx until allStmts.size) {
            val stmt = allStmts[i]
            block.add(stmt)
            if (stmt is JReturnStmt || stmt is JReturnVoidStmt || stmt is JGotoStmt) break
        }
        return block
    }

    private fun collectLocals(stmt: Stmt, locals: MutableSet<Local>) {
        stmt.uses.forEach { value ->
            if (value is Local) locals.add(value)
            if (value is AbstractInvokeExpr) {
                value.args.forEach { arg -> if (arg is Local) locals.add(arg) }
            }
        }
        val def: LValue? = stmt.def.orElse(null)
        if (def is Local) locals.add(def)
    }
}
