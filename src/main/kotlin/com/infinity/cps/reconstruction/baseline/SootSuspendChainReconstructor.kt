package com.infinity.cps.reconstruction.baseline

import soot.Body
import soot.Local
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.Trap
import soot.Unit
import soot.jimple.AssignStmt
import soot.jimple.CastExpr
import soot.jimple.ConditionExpr
import soot.jimple.GotoStmt
import soot.jimple.IfStmt
import soot.jimple.InstanceFieldRef
import soot.jimple.InstanceOfExpr
import soot.jimple.InvokeExpr
import soot.jimple.Jimple
import soot.jimple.LookupSwitchStmt
import soot.jimple.ReturnStmt
import soot.jimple.Stmt
import soot.jimple.SwitchStmt
import soot.jimple.TableSwitchStmt
import soot.tagkit.AbstractHost

/**
 * Classic-Soot port of `SuspendChainReconstructor.unrollGeneralCase`, so a
 * reconstructed body can be consumed in-process by FlowDroid and written out
 * as a `.class` file by Soot's bytecode backend — neither of which SootUp 2.0
 * can do (it ships no bytecode writer, and FlowDroid runs on classic Soot).
 *
 * The walk is the same fast-path argument as the SootUp version: start at the
 * method entry, follow real CFG edges, and rewire around every dispatch
 * bookkeeping statement (label/spill-field traffic, continuation casts,
 * `throwOnFailure`/`nullOutSpilledVariable`, the `COROUTINE_SUSPENDED`
 * check, and plain `goto`s). The one deliberate difference: this walk starts
 * from the method's *first* unit rather than the switch's `case 0` target,
 * treating the dispatch switch itself as bookkeeping that resolves to case 0.
 * That keeps the preamble (parameter identity statements, continuation
 * allocation) in the body, which FlowDroid needs to map arguments and Soot
 * needs to emit loadable bytecode. [MethodResult.realStatements] counts only
 * what the SootUp walk would have produced (statements reachable from the
 * case-0 entry), so the two implementations can be checked against each
 * other statement-for-statement.
 */
class SootSuspendChainReconstructor {

    class MethodResult(
        val method: SootMethod,
        val originalStatements: Int,
        val realStatements: Int,
        val bodyStatements: Int,
    )

    val reconstructed: MutableList<MethodResult> = mutableListOf()
    val declined: MutableMap<SootMethod, String> = linkedMapOf()

    fun reconstructAll(classes: Collection<SootClass>) {
        for (cls in classes) {
            if (cls.isPhantom) continue
            for (method in cls.methods.toList()) {
                if (!method.isConcrete) continue
                val body = runCatching { method.retrieveActiveBody() }.getOrNull() ?: continue
                if (!isCoroutineStateMachine(method, body)) continue
                when (val outcome = unroll(method, body)) {
                    is Outcome.Unrolled -> {
                        method.activeBody = outcome.body
                        reconstructed.add(
                            MethodResult(method, body.units.size, outcome.realStatements, outcome.body.units.size),
                        )
                    }
                    is Outcome.Declined -> declined[method] = outcome.reason
                }
            }
        }
    }

    private sealed class Outcome {
        class Unrolled(val body: Body, val realStatements: Int) : Outcome()
        class Declined(val reason: String) : Outcome()
    }

    private class PendingTrap(val protectedUnit: Unit, val exception: SootClass, val handler: Unit)

    private fun isCoroutineStateMachine(method: SootMethod, body: Body): Boolean {
        if (body.units.none { it is SwitchStmt }) return false
        if (method.name == "invokeSuspend" && extendsContinuationClass(method.declaringClass)) return true
        return body.units.any { unit ->
            val rightOp = (unit as? AssignStmt)?.rightOp
            val type = when (rightOp) {
                is CastExpr -> rightOp.castType
                is InstanceOfExpr -> rightOp.checkType
                else -> null
            }
            (type as? RefType)?.let { extendsContinuationClass(it.sootClass) } == true
        }
    }

    private fun extendsContinuationClass(cls: SootClass): Boolean {
        var current = cls
        while (current.hasSuperclass()) {
            val superName = current.superclass.name
            if (superName.endsWith("SuspendLambda") ||
                superName.endsWith("ContinuationImpl") ||
                superName.endsWith("RestrictedSuspendLambda")
            ) return true
            if (superName == "java.lang.Object") return false
            current = current.superclass
        }
        return false
    }

    private fun unroll(method: SootMethod, body: Body): Outcome {
        val units = body.units.toList()
        val dispatchSwitch = units.filterIsInstance<SwitchStmt>().first()
        val case0Target = case0Target(dispatchSwitch)
            ?: return Outcome.Declined("dispatch switch has no case 0")

        val sentinelNames = units.filterIsInstance<AssignStmt>()
            .filter { it.leftOp is Local && (it.rightOp as? InvokeExpr)?.method?.name == "getCOROUTINE_SUSPENDED" }
            .map { (it.leftOp as Local).name }
            .toSet()

        val trapsCovering = trapsCovering(body, units)
        val ctx = WalkContext(body, units, dispatchSwitch, case0Target, sentinelNames, trapsCovering)

        val entry = ctx.resolve(case0Target, linkedSetOf())
        if (entry.isEmpty()) return Outcome.Declined("case 0 resolves to nothing real")

        // The preamble — everything reachable from the method entry without
        // crossing the dispatch switch: parameter identities, the
        // continuation instanceof/cast/allocate diamond, the `$result` and
        // sentinel reads — is kept verbatim. Its label/cast statements look
        // like bookkeeping to the walk's filter, but here they define locals
        // the preamble's own real branches use, and eliding them leaves
        // undefined locals behind that trip FlowDroid's optimizer passes.
        val preamble = linkedSetOf<Unit>()
        val preambleSuccessors = linkedMapOf<Unit, List<Unit>>()
        run {
            val queue = ArrayDeque(listOf(units.first()))
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (cur === dispatchSwitch || !preamble.add(cur)) continue
                if (trapsCovering[cur].orEmpty().isNotEmpty()) return Outcome.Declined("preamble unit '$cur' is trap-protected")
                val succs = ctx.successors(cur)
                preambleSuccessors[cur] = succs.flatMap { if (it === dispatchSwitch) entry else listOf(it) }
                succs.forEach { if (it !== dispatchSwitch && it !in preamble) queue.add(it) }
            }
        }

        val realUnits = linkedSetOf<Unit>()
        val realSuccessors = linkedMapOf<Unit, List<Unit>>()
        val pendingTraps = mutableListOf<PendingTrap>()
        val queue = ArrayDeque(entry)

        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur in realUnits) continue

            for ((exception, handler) in trapsCovering[cur].orEmpty()) {
                if (trapsCovering[handler].orEmpty().isNotEmpty()) {
                    return Outcome.Declined("handler of a trap covering '$cur' is itself protected (nested/finally shape)")
                }
                val resolvedHandlers = ctx.resolve(handler, linkedSetOf())
                if (resolvedHandlers.isEmpty()) return Outcome.Declined("trap handler for '$cur' resolves to nothing real")
                for (h in resolvedHandlers) {
                    pendingTraps.add(PendingTrap(cur, exception, h))
                    if (h !in realUnits) queue.add(h)
                }
            }

            realUnits.add(cur)

            val resolved = mutableListOf<Unit>()
            for (succ in ctx.successors(cur)) {
                val r = ctx.resolve(succ, linkedSetOf())
                if (r.isEmpty()) return Outcome.Declined("a real edge out of '$cur' leads nowhere real")
                resolved.addAll(r)
            }
            realSuccessors[cur] = resolved
            resolved.forEach { if (it !in realUnits) queue.add(it) }
        }

        val allUnits = linkedSetOf<Unit>().apply { addAll(preamble); addAll(realUnits) }
        val allSuccessors = linkedMapOf<Unit, List<Unit>>().apply { putAll(preambleSuccessors); putAll(realSuccessors) }

        return assemble(method, units, units.first(), allUnits, allSuccessors, pendingTraps)
            ?.let { Outcome.Unrolled(it, realUnits.size) }
            ?: Outcome.Declined("branch shape could not be re-linearized")
    }

    private fun case0Target(switch: SwitchStmt): Unit? = when (switch) {
        is TableSwitchStmt -> switch.targets.getOrNull(0 - switch.lowIndex)
        is LookupSwitchStmt -> switch.lookupValues.indexOfFirst { it.value == 0 }
            .takeIf { it >= 0 }?.let { switch.targets[it] }
        else -> null
    }

    private fun trapsCovering(body: Body, units: List<Unit>): Map<Unit, List<Pair<SootClass, Unit>>> {
        val index = units.withIndex().associate { (i, u) -> u to i }
        val result = linkedMapOf<Unit, MutableList<Pair<SootClass, Unit>>>()
        for (trap in body.traps) {
            val begin = index[trap.beginUnit] ?: continue
            val end = index[trap.endUnit] ?: units.size
            for (i in begin until end) {
                val list = result.getOrPut(units[i]) { mutableListOf() }
                val entry = trap.exception to trap.handlerUnit
                if (entry !in list) list.add(entry)
            }
        }
        return result
    }

    private inner class WalkContext(
        val body: Body,
        val units: List<Unit>,
        val dispatchSwitch: SwitchStmt,
        val case0Target: Unit,
        val sentinelNames: Set<String>,
        val trapsCovering: Map<Unit, List<Pair<SootClass, Unit>>>,
    ) {
        private val next: Map<Unit, Unit?> = units.indices.associate { i -> units[i] to units.getOrNull(i + 1) }

        /** Mirrors SootUp's successor order: fall-through first, then branch targets in unit-box order. */
        fun successors(unit: Unit): List<Unit> {
            val result = mutableListOf<Unit>()
            if (unit.fallsThrough()) next[unit]?.let { result.add(it) }
            if (unit.branches()) unit.unitBoxes.forEach { result.add(it.unit) }
            return result
        }

        fun resolve(start: Unit, guard: MutableSet<Unit>): List<Unit> {
            if (!guard.add(start)) return emptyList()

            if (start === dispatchSwitch) return resolve(case0Target, guard)

            if (start is IfStmt && isCoroutineSuspendedCheck(start)) {
                val continueTarget = successors(start).firstOrNull { !isSuspendedReturn(it) } ?: return emptyList()
                return resolve(continueTarget, guard)
            }

            if (isBookkeeping(start)) {
                val succs = successors(start)
                if (succs.isEmpty()) return emptyList()
                return succs.flatMap { resolve(it, guard) }
            }

            return listOf(start)
        }

        private fun isCoroutineSuspendedCheck(ifStmt: IfStmt): Boolean {
            val cond = ifStmt.condition as? ConditionExpr ?: return false
            val operands = setOfNotNull((cond.op1 as? Local)?.name, (cond.op2 as? Local)?.name)
            return operands.any { it in sentinelNames }
        }

        private fun isSuspendedReturn(unit: Unit): Boolean {
            val op = (unit as? ReturnStmt)?.op as? Local ?: return false
            return op.name in sentinelNames
        }

        private fun isBookkeeping(unit: Unit): Boolean {
            if (unit is GotoStmt) return true

            if (unit is AssignStmt) {
                val left = unit.leftOp
                if (left is InstanceFieldRef && isDispatchField(left.fieldRef.name())) return true

                val right = unit.rightOp
                if (right is InstanceFieldRef && isDispatchField(right.fieldRef.name())) return true
                if (right is CastExpr && (right.castType as? RefType)?.let { extendsContinuationClass(it.sootClass) } == true) return true
                if (right is InvokeExpr && isBookkeepingCallName(right.methodRef.name)) return true
            }

            if (unit is Stmt && unit.containsInvokeExpr() && unit !is AssignStmt) {
                if (isBookkeepingCallName(unit.invokeExpr.methodRef.name)) return true
            }

            return false
        }
    }

    private fun isDispatchField(fieldName: String): Boolean =
        fieldName == "label" || Regex("^[A-Z]\\$\\d+$").matches(fieldName)

    private fun isBookkeepingCallName(methodName: String): Boolean =
        methodName == "throwOnFailure" || methodName == "nullOutSpilledVariable"

    /**
     * Re-linearizes the real-statement graph into a Soot unit chain. Soot's
     * fall-through is positional (the next unit in the chain), so every
     * fall-through edge whose target can't be placed immediately after its
     * source gets an explicit bridge `goto` — the same role the synthesized
     * `JGotoStmt` plays in the SootUp version, just needed more often.
     */
    private fun assemble(
        method: SootMethod,
        originalOrder: List<Unit>,
        entry: Unit,
        realUnits: Set<Unit>,
        realSuccessors: Map<Unit, List<Unit>>,
        pendingTraps: List<PendingTrap>,
    ): Body? {
        fun fallThroughSuccessor(u: Unit): Unit? {
            val succs = realSuccessors[u].orEmpty()
            return when {
                succs.isEmpty() -> null
                u is SwitchStmt || u is GotoStmt -> null
                else -> succs[0]
            }
        }

        val placed = linkedSetOf<Unit>()
        fun placeChain(start: Unit) {
            var cur: Unit? = start
            while (cur != null && cur !in placed) {
                placed.add(cur)
                cur = fallThroughSuccessor(cur)
            }
        }
        placeChain(entry)
        for (u in originalOrder) if (u in realUnits) placeChain(u)

        val clones = linkedMapOf<Unit, Unit>()
        for (u in placed) {
            val c = u.clone() as Unit
            (c as? AbstractHost)?.addAllTagsOf(u)
            clones[u] = c
        }

        val order = placed.toList()
        val newBody = Jimple.v().newBody(method)
        val units = newBody.units
        for ((i, u) in order.withIndex()) {
            val c = clones.getValue(u)
            units.addLast(c)
            val succs = realSuccessors[u].orEmpty()
            when (u) {
                is IfStmt -> {
                    if (succs.size != 2) return null
                    (c as IfStmt).setTarget(clones.getValue(succs[1]))
                }
                is SwitchStmt -> {
                    if (succs.size != u.targets.size + 1) return null
                    val cs = c as SwitchStmt
                    for (t in u.targets.indices) cs.setTarget(t, clones.getValue(succs[t]))
                    cs.setDefaultTarget(clones.getValue(succs.last()))
                }
                is GotoStmt -> {
                    if (succs.size != 1) return null
                    (c as GotoStmt).target = clones.getValue(succs[0])
                }
                else -> if (succs.size > 1) return null
            }
            val ft = fallThroughSuccessor(u)
            if (ft != null && order.getOrNull(i + 1) !== ft) {
                units.addLast(Jimple.v().newGotoStmt(clones.getValue(ft)))
            }
        }

        val locals = linkedSetOf<Local>()
        for (c in clones.values) for (box in c.useAndDefBoxes) (box.value as? Local)?.let { locals.add(it) }
        newBody.locals.addAll(locals)

        addTraps(newBody, pendingTraps, clones)
        return newBody
    }

    /** Consecutive units with identical trap coverage collapse into one exception-table range. */
    private fun addTraps(body: Body, pendingTraps: List<PendingTrap>, clones: Map<Unit, Unit>) {
        if (pendingTraps.isEmpty()) return
        val units = body.units
        val coverage: Map<Unit, List<Pair<SootClass, Unit>>> = pendingTraps
            .groupBy { clones.getValue(it.protectedUnit) }
            .mapValues { (_, traps) -> traps.map { it.exception to clones.getValue(it.handler) }.distinct() }

        var rangeStart: Unit? = null
        var rangeCoverage: List<Pair<SootClass, Unit>> = emptyList()
        val traps = mutableListOf<Trap>()

        fun closeRange(endExclusive: Unit) {
            val start = rangeStart ?: return
            for ((exc, handler) in rangeCoverage) traps.add(Jimple.v().newTrap(exc, start, endExclusive, handler))
            rangeStart = null
            rangeCoverage = emptyList()
        }

        for (u in units.toList()) {
            val cov = coverage[u].orEmpty()
            if (cov == rangeCoverage) continue
            closeRange(u)
            if (cov.isNotEmpty()) {
                rangeStart = u
                rangeCoverage = cov
            }
        }
        if (rangeStart != null) {
            val nop = Jimple.v().newNopStmt()
            units.addLast(nop)
            closeRange(nop)
        }
        body.traps.addAll(traps)
    }
}
