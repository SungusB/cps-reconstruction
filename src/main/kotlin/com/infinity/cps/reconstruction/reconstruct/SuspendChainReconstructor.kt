package com.infinity.cps.reconstruction.reconstruct

import sootup.core.graph.MutableStmtGraph
import sootup.core.graph.StmtGraph
import sootup.core.jimple.basic.LValue
import sootup.core.jimple.basic.Local
import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.expr.AbstractInvokeExpr
import sootup.core.jimple.common.expr.JCastExpr
import sootup.core.jimple.common.expr.JInstanceOfExpr
import sootup.core.jimple.common.ref.JInstanceFieldRef
import sootup.core.jimple.common.stmt.BranchingStmt
import sootup.core.jimple.common.stmt.FallsThroughStmt
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

    /** Reconstructed bodies, keyed by signature — see [getReconstructedBodies]. */
    private val reconstructedBodies: MutableMap<MethodSignature, Body> = linkedMapOf()

    fun getUnsupportedMethods(): Set<MethodSignature> = unsupportedMethods

    /**
     * The bodies [demultiplex] produced, independent of whether [view]'s own
     * `getMethod` reflects them afterward.
     *
     * `MutableJavaView.replaceClass` was found, empirically, not to be reliably
     * visible to later `getMethod` lookups on the same view — reproduced with a
     * minimal case (iterating `view.classes` a second time before any
     * `replaceClass` call was enough to make some later `getMethod(sig)` calls
     * silently keep returning the pre-replacement body), and the exact trigger
     * turned out to vary run to run (consistent with a hash-ordering-dependent
     * cache inside the view implementation, not something safely avoidable by
     * calling the view a particular way). Callers that need a reconstructed
     * method's body should get it from here, not by re-querying [view].
     */
    fun getReconstructedBodies(): Map<MethodSignature, Body> = reconstructedBodies

    /**
     * Reconstructs every eligible coroutine state machine reachable from [methods].
     * Returns the set of method signatures that were rewritten; the bodies
     * themselves are available afterward via [getReconstructedBodies].
     *
     * [view] must be a [MutableJavaView] for the rewrite to actually apply; otherwise
     * the unrolled bodies are computed but discarded (still available via
     * [getReconstructedBodies], since that doesn't depend on the view).
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

            reconstructedBodies[sig] = newBody
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

    /** A single-level exceptional edge [unrollGeneralCase] decided to preserve. */
    private class PendingTrap(val protectedStmt: Stmt, val exceptionType: ClassType, val handler: Stmt)

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
        /**
         * Decides whether [stmt]'s exceptional successors (its traps, if any) can
         * be preserved as real exceptional edges in a reconstructed body, or force
         * a decline. Returns the `(protected statement, exception type, resolved
         * handler)` triples to preserve — empty if [stmt] has no exceptional
         * successors at all — or `null` if any one of them can't be safely
         * preserved:
         *
         * - the handler is itself exceptionally protected — a nested or
         *   self-referential scope, which is exactly the shape verified directly
         *   against compiled bytecode for a suspend call inside a `finally` block
         *   (see [unrollGeneralCase]'s doc comment); reconstructing that needs its
         *   own argument about nested handler scopes, so it stays declined here
         *   rather than guessed at.
         * - [resolve] (the same bookkeeping-skipping resolution a normal successor
         *   gets) can't resolve the handler to any real statement.
         *
         * Takes the [StmtGraph] and a [resolve] callback directly, rather than
         * depending on instance state, so it can be unit-tested against a small
         * hand-built graph the same way [findUnsupportedControlFlow] is.
         */
        internal fun resolvePreservableExceptionalEdges(
            graph: StmtGraph<*>,
            stmt: Stmt,
            resolve: (Stmt) -> List<Stmt>,
        ): List<Triple<Stmt, ClassType, Stmt>>? {
            val preserved = mutableListOf<Triple<Stmt, ClassType, Stmt>>()
            for ((exceptionType, handler) in graph.exceptionalSuccessors(stmt).entries) {
                if (graph.exceptionalSuccessors(handler).isNotEmpty()) return null
                val resolvedHandlers = resolve(handler)
                if (resolvedHandlers.isEmpty()) return null
                resolvedHandlers.forEach { preserved.add(Triple(stmt, exceptionType, it)) }
            }
            return preserved
        }

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
            // A real conditional, a loop back-edge, or a single-level try/catch is
            // exactly what the general-case path (unrollGeneralCase) is for — it now
            // preserves a resolvable exceptional edge instead of declining on sight.
            // It still declines on its own (see its doc comment) for a handler that is
            // itself exceptionally protected — Kotlin's finally-with-suspend shape.
            val general = unrollGeneralCase(method)
            if (general != null) return general
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

    /**
     * Reconstructs a suspend chain that has real control flow (branches, loops)
     * between suspension points — the case [unrollCoroutine]'s straight-line path
     * declines via [findUnsupportedControlFlow].
     *
     * Unlike the straight-line path (which flattens every case block's statements
     * into one linear list), this works directly off the method's real
     * [StmtGraph]: it walks forward from case 0's entry point — the state the
     * compiler enters on a *fresh* invocation, when `label == 0` — over the
     * **real** CFG edges, eliding every dispatch-bookkeeping statement it meets
     * along the way (label reads/writes, spill-field reads/writes, the
     * continuation-impl casts used only to reach those fields, the
     * `throwOnFailure`/`nullOutSpilledVariable` calls, and the coroutine-suspended
     * check itself) by rewiring the edge through them to whatever real statement
     * they eventually lead to.
     *
     * This works because of a structural property of Kotlin's coroutine codegen,
     * verified directly against compiled output rather than assumed: the
     * `label == 0` entry point's fast path — "what runs if none of this method's
     * suspend calls actually suspend" — is a complete copy of the method's real
     * control flow (including any `if`/`while` the user wrote), reached without
     * ever going through the `label`-dispatch `switch`. A suspension's *resumed*
     * path (`label >= 1`) reconverges onto that exact same downstream code once
     * it finishes restoring spilled locals — it doesn't branch to different
     * logic — so walking only the fast path's real statements and their real
     * edges is enough to recover the original control flow soundly, without
     * needing to merge two separately-extracted copies of anything.
     *
     * A statement covered by an exceptional edge (a real `try`/`catch`) is no
     * longer an automatic decline: if the edge's handler resolves to real code
     * — the same bookkeeping-skipping resolution normal successors get — the
     * edge is preserved as-is into the reconstructed body via
     * [MutableStmtGraph.addExceptionalEdge], protecting exactly the walked
     * statement and pointing at the resolved handler. This is deliberately
     * **single-level only**: if the handler statement is itself covered by
     * another exceptional edge, the method still declines. That shape —
     * verified directly against compiled bytecode, not assumed — is exactly
     * what Kotlin emits for a suspend call inside a `finally` block: the
     * user's protected region and its handler are each duplicated once for
     * the "didn't suspend" fast path and once more for the resumed path, and
     * the fast-path handler is wrapped in a second, self-targeting trap (its
     * `from`/`to` range covers only the handler's first instruction, targeting
     * itself) so that re-entering the finally logic during a suspend can still
     * route a second exception to the same place. Reconstructing that soundly
     * needs its own argument about nested handler scopes, not an extension of
     * this one, so it stays declined; an ordinary single `try`/`catch` — with
     * or without a suspend call inside it — does not have this shape and is
     * handled. Still declines (returns `null`) where a real branch's target,
     * or an exceptional edge's handler, can't be resolved to any real
     * statement.
     */
    private fun unrollGeneralCase(method: SootMethod): Body? {
        val body = method.body
        val graph = body.stmtGraph

        val switchStmt = body.stmts.filterIsInstance<JSwitchStmt>().firstOrNull() ?: return null
        val targets = switchStmt.getTargetStmts(body)
        val case0Index = switchStmt.values.indexOfFirst { it.value == 0 }
        if (case0Index !in targets.indices) return null
        val case0Target = targets[case0Index]

        val sentinelNames = findSentinelLocalNames(body)
        if (sentinelNames.isEmpty()) return null

        val entry = resolveGeneralCaseTarget(case0Target, graph, sentinelNames, linkedSetOf())
        if (entry.isEmpty()) return null

        val realStmts = linkedSetOf<Stmt>()
        val realSuccessors = linkedMapOf<Stmt, List<Stmt>>()
        val pendingTraps = mutableListOf<PendingTrap>()
        val queue = ArrayDeque<Stmt>()
        entry.forEach { queue.add(it) }

        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur in realStmts) continue

            val preservable = resolvePreservableExceptionalEdges(graph, cur) { target ->
                resolveGeneralCaseTarget(target, graph, sentinelNames, linkedSetOf())
            } ?: return null
            for ((protectedStmt, exceptionType, resolvedHandler) in preservable) {
                pendingTraps.add(PendingTrap(protectedStmt, exceptionType, resolvedHandler))
                if (resolvedHandler !in realStmts) queue.add(resolvedHandler)
            }

            realStmts.add(cur)

            val originalSuccessors = graph.successors(cur)
            val resolved = mutableListOf<Stmt>()
            for (succ in originalSuccessors) {
                val r = resolveGeneralCaseTarget(succ, graph, sentinelNames, linkedSetOf())
                if (r.isEmpty()) return null // a real edge that doesn't lead anywhere real — decline rather than guess
                resolved.addAll(r)
            }
            realSuccessors[cur] = resolved
            resolved.forEach { if (it !in realStmts) queue.add(it) }
        }
        if (realStmts.isEmpty()) return null

        val usedLocals = linkedSetOf<Local>()
        realStmts.forEach { collectLocals(it, usedLocals) }

        val freshBuilder = Body.builder()
        freshBuilder.setMethodSignature(body.methodSignature)
        freshBuilder.setLocals(usedLocals)
        freshBuilder.setPosition(body.position)

        val freshGraph: MutableStmtGraph = freshBuilder.stmtGraph
        for (stmt in realStmts) freshGraph.addNode(stmt)
        freshGraph.setStartingStmt(entry.first())

        for (trap in pendingTraps) {
            freshGraph.addExceptionalEdge(trap.protectedStmt, trap.exceptionType, trap.handler)
        }

        val hasPlainPredecessor = mutableSetOf<Stmt>()
        for (stmt in realStmts) {
            val succs = realSuccessors[stmt].orEmpty()
            when {
                succs.isEmpty() -> Unit // terminal statement (return/throw)
                succs.size >= 2 -> {
                    val branching = stmt as? BranchingStmt ?: return null
                    succs.forEachIndexed { idx, target -> freshGraph.putEdge(branching, idx, target) }
                }
                else -> {
                    val source = stmt as? FallsThroughStmt ?: return null
                    val target = succs[0]
                    if (target !in hasPlainPredecessor) {
                        freshGraph.putEdge(source, target)
                        hasPlainPredecessor.add(target)
                    } else {
                        // target already has a plain (fall-through) predecessor — this
                        // second incoming edge needs a branching vehicle instead, per
                        // MutableBlockStmtGraph's "at most one plain predecessor" rule.
                        val bridge = JGotoStmt(StmtPositionInfo.getNoStmtPositionInfo())
                        freshGraph.addNode(bridge)
                        freshGraph.putEdge(source, bridge)
                        freshGraph.putEdge(bridge, 0, target)
                    }
                }
            }
        }

        println("[SuspendChainReconstructor] general-case-unrolled ${method.signature}: "
            + "${body.stmts.size} statements -> ${realStmts.size} real statements")

        return freshBuilder.build()
    }

    /**
     * Resolves [start] to the real statement(s) it leads to, skipping past any
     * dispatch-bookkeeping statement by following its (sole) successor, and
     * collapsing a coroutine-suspended check to just its "didn't suspend"
     * successor. Returns an empty list if [start] leads nowhere real (a dead
     * bookkeeping chain — e.g. one that only reaches the "resume before invoke"
     * error path). [guard] prevents infinite recursion on a bookkeeping cycle,
     * which shouldn't occur in practice but must not hang if it somehow did.
     */
    private fun resolveGeneralCaseTarget(
        start: Stmt,
        graph: StmtGraph<*>,
        sentinelNames: Set<String>,
        guard: MutableSet<Stmt>,
    ): List<Stmt> {
        if (!guard.add(start)) return emptyList()

        if (start is JIfStmt && isCoroutineSuspendedCheck(start, graph)) {
            val continueTarget = graph.successors(start).firstOrNull { !isSuspendedReturn(it, sentinelNames) }
                ?: return emptyList()
            return resolveGeneralCaseTarget(continueTarget, graph, sentinelNames, guard)
        }

        if (isGeneralCaseBookkeeping(start)) {
            val successors = graph.successors(start)
            if (successors.isEmpty()) return emptyList()
            return successors.flatMap { resolveGeneralCaseTarget(it, graph, sentinelNames, guard) }
        }

        return listOf(start)
    }

    /** Every local assigned from `IntrinsicsKt.getCOROUTINE_SUSPENDED()` — the sentinel a suspend-check `if` compares against. */
    private fun findSentinelLocalNames(body: Body): Set<String> {
        val names = linkedSetOf<String>()
        for (stmt in body.stmts) {
            val assign = stmt as? JAssignStmt ?: continue
            val def = assign.def
            if (def.isPresent && def.get() is Local && assign.toString().contains("getCOROUTINE_SUSPENDED")) {
                names.add((def.get() as Local).name)
            }
        }
        return names
    }

    /** `return <sentinel>` — the arm of a suspend-check that actually suspends. */
    private fun isSuspendedReturn(stmt: Stmt, sentinelNames: Set<String>): Boolean {
        val ret = stmt as? JReturnStmt ?: return false
        val op = ret.op as? Local ?: return false
        return op.name in sentinelNames
    }

    /**
     * A statement that exists purely to maintain the state machine, not to
     * express the method's own logic: reading or writing the `label` field or
     * a spill field (`L$0`, `I$1`, ...), a cast to the continuation-impl class
     * used only to reach those fields, or a call to `throwOnFailure`/
     * `nullOutSpilledVariable`. A plain `goto` is bookkeeping too here — real
     * branches and loop back-edges are still preserved, just via the original
     * [JIfStmt]/other [BranchingStmt] rather than the `goto` that used to
     * carry a merge or back-edge; [unrollGeneralCase] synthesizes its own
     * `goto` where a genuine second predecessor is needed in the new graph.
     */
    private fun isGeneralCaseBookkeeping(stmt: Stmt): Boolean {
        if (stmt is JGotoStmt) return true

        val assign = stmt as? JAssignStmt
        if (assign != null) {
            val def = assign.def.orElse(null)
            if (def is JInstanceFieldRef && isDispatchField(def.fieldSignature.name)) return true

            val rightOp = assign.rightOp
            if (rightOp is JInstanceFieldRef && isDispatchField(rightOp.fieldSignature.name)) return true
            if (rightOp is JCastExpr && (rightOp.type as? ClassType)?.let { extendsContinuationClass(it) } == true) return true
            if (rightOp is AbstractInvokeExpr && isBookkeepingCallName(rightOp.methodSignature.name)) return true
        }

        if (stmt is JInvokeStmt) {
            val invokeOpt = stmt.invokeExpr
            if (invokeOpt.isPresent && isBookkeepingCallName(invokeOpt.get().methodSignature.name)) return true
        }

        return false
    }

    /** `label`, or a spill field name like `L$0`/`I$1`/`Z$0` — a single type-tag letter, `$`, then a digit index. */
    private fun isDispatchField(fieldName: String): Boolean =
        fieldName == "label" || Regex("^[A-Z]\\$\\d+$").matches(fieldName)

    private fun isBookkeepingCallName(methodName: String): Boolean =
        methodName == "throwOnFailure" || methodName == "nullOutSpilledVariable"

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
