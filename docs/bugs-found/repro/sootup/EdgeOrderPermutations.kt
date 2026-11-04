// SootUp 2.0.0: a four-edge loop whose every edge is a fall-through
// (head -> if, if succ 0 -> body, body -> head) plus the exit (if succ 1 -> return),
// inserted in all 24 orders. Expected: 20 rejected as invalid, 4 accepted. (This
// minimal cycle iterates fine when accepted; the non-terminating iteration was
// only seen on a larger reconstructed body, see ../../README.md.)
package repro.edgeorderperms

import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.constant.IntConstant
import sootup.core.jimple.common.expr.JEqExpr
import sootup.core.jimple.common.stmt.JIfStmt
import sootup.core.jimple.common.stmt.JNopStmt
import sootup.core.jimple.common.stmt.JReturnVoidStmt
import sootup.core.model.Body
import sootup.java.core.JavaIdentifierFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// head -> if --(succ 0, fall-through)--> body -> head : every edge on the loop is a fall-through.
// Same four edges, every insertion order.
fun <T> perms(l: List<T>): List<List<T>> = if (l.size <= 1) listOf(l) else l.flatMap { x -> perms(l - x).map { listOf(x) + it } }

fun main() {
    val names = listOf("head->if", "if-0->body", "if-1->ret", "body->head")
    val counts = linkedMapOf<String, MutableList<String>>()
    for (order in perms(names.indices.toList())) {
        val pos = StmtPositionInfo.getNoStmtPositionInfo()
        val b = Body.builder(); b.setMethodSignature(JavaIdentifierFactory.getInstance().parseMethodSignature("<Repro: void m()>"))
        val g = b.stmtGraph
        val head = JNopStmt(pos); val cond = JIfStmt(JEqExpr(IntConstant.getInstance(0), IntConstant.getInstance(0)), pos)
        val body = JNopStmt(pos); val ret = JReturnVoidStmt(pos)
        listOf(head, cond, body, ret).forEach { g.addNode(it) }
        g.setStartingStmt(head)
        val edges = listOf({ g.putEdge(head, cond) }, { g.putEdge(cond, 0, body) }, { g.putEdge(cond, 1, ret) }, { g.putEdge(body, head) })
        order.forEach { edges[it]() }
        val label = order.joinToString(", ") { names[it] }
        val outcome = try {
            val built = b.build()
            val ex = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
            val f = ex.submit<Int> { built.stmts.size }
            try { "accepted, getStmts()=${f.get(3, TimeUnit.SECONDS)}" } catch (e: java.util.concurrent.TimeoutException) { "accepted, getStmts() NON-TERMINATING" }
        } catch (e: Exception) { "rejected (" + generateSequence(e as Throwable) { it.cause }.last().message + ")" }
        counts.getOrPut(outcome) { mutableListOf() }.add(label)
    }
    for ((k, v) in counts) { println("${v.size}x  $k"); println("     e.g. ${v.first()}") }
    System.exit(0)
}
