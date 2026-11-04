// SootUp 2.0.0, MutableBlockStmtGraph: the same two edges (nop -> goto, goto -> return)
// give a valid graph in one insertion order and an invalid one in the other
// ("JGoto must have '1' outgoing flow but has '0'"). Putting a plain edge into a
// statement whose outgoing branch edge already exists drops that edge.
package repro.edgeorder

import sootup.core.jimple.basic.StmtPositionInfo
import sootup.core.jimple.common.stmt.JGotoStmt
import sootup.core.jimple.common.stmt.JNopStmt
import sootup.core.jimple.common.stmt.JReturnVoidStmt
import sootup.core.model.Body
import sootup.core.signatures.MethodSignature
import sootup.java.core.JavaIdentifierFactory

fun build(gotoEdgeFirst: Boolean): String {
    val pos = StmtPositionInfo.getNoStmtPositionInfo()
    val sig: MethodSignature = JavaIdentifierFactory.getInstance().parseMethodSignature("<Repro: void m()>")
    val b = Body.builder(); b.setMethodSignature(sig)
    val g = b.stmtGraph
    val nop = JNopStmt(pos); val jump = JGotoStmt(pos); val ret = JReturnVoidStmt(pos)
    g.addNode(nop); g.addNode(jump); g.addNode(ret)
    g.setStartingStmt(nop)
    if (gotoEdgeFirst) { g.putEdge(jump, 0, ret); g.putEdge(nop, jump) }
    else { g.putEdge(nop, jump); g.putEdge(jump, 0, ret) }
    return try { b.build(); "valid" } catch (e: Exception) { "INVALID: " + generateSequence(e as Throwable) { it.cause }.last().message }
}

fun main() {
    println("nop->goto, then goto->return : " + build(false))
    println("goto->return, then nop->goto : " + build(true))
}
