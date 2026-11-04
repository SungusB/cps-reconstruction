// Runs the project's reconstruction on a class directory, then calls
// Body.getStmts() and getParameterLocals() on every reconstructed body with a
// 10 s cap each, printing any body that does not finish. With the pre-fix walk
// (fall-through cycles) this found 6 of 186 bodies in build/gen/kotlin/classes.
package repro.iterate

import com.infinity.cps.reconstruction.reconstruct.SuspendChainReconstructor
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation
import sootup.java.core.views.MutableJavaView
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

fun main(args: Array<String>) {
    val view = MutableJavaView(listOf(JavaClassPathAnalysisInputLocation(args[0])))
    val methods = view.classes.toList().flatMap { c -> c.methods.map { it.signature } }.toSet()
    val r = SuspendChainReconstructor(view)
    r.demultiplex(methods)
    val bodies = r.getReconstructedBodies()
    var ok = 0; var slow = 0; var totalMs = 0L
    for ((sig, body) in bodies) {
        val ex = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        val t0 = System.nanoTime()
        val f = ex.submit<String> {
            val blocks = body.stmtGraph.blocks.size
            val nodes = body.stmtGraph.nodes.size
            val n = body.stmts.size
            val p = body.parameterLocals.size
            "nodes=$nodes blocks=$blocks iterStmts=$n params=$p"
        }
        try {
            val res = f.get(10, TimeUnit.SECONDS)
            val ms = (System.nanoTime() - t0) / 1_000_000
            totalMs += ms; ok++
            if (ms > 200) println("SLOW ${ms}ms $res $sig")
        } catch (e: TimeoutException) {
            slow++
            val g = body.stmtGraph
            println("TIMEOUT>10s nodes=${g.nodes.size} blocks=${g.blocks.size} $sig")
            f.cancel(true)
        } catch (e: Exception) {
            println("THROWS ${e.cause} $sig")
        }
        ex.shutdownNow()
    }
    println("bodies=${bodies.size} ok=$ok timeouts=$slow okTotalMs=$totalMs")
    System.exit(0)
}
