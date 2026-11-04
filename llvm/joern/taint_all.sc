// Runs the taint query over every CPG listed in a manifest, in one Joern
// process. Manifest lines: <mode> <cpgPath> <outPath>, mode = bin | src.
//
//  src (c2cpg / rust2cpg): SOURCE = calls to `source`, SINK = arguments of
//      calls to `sink`.
//  bin (ghidra2cpg): ghidra2cpg models instructions and registers, and a call
//      node defines nothing, so SOURCE = the first RAX/EAX read (operand
//      index >= 2) within 6 instructions after each `call source`, unless
//      RAX/EAX is overwritten first; SINK = arguments of
//      `call sink` (present because llvm/joern/stubs.c gives `sink` a DWARF
//      signature). The stub bodies themselves are excluded.
//
//  jvm (jimple2cpg on Kotlin .class files): SOURCE = calls to
//      System.getenv, SINK = non-receiver arguments of println.
//
// Output per CPG: a stats line, then one JSON line per distinct flow.
import io.shiftleft.semanticcpg.language.*
import io.joern.dataflowengineoss.language.*
import io.shiftleft.codepropertygraph.generated.nodes.{AstNode, CfgNode, Call, Expression}

def isRax(e: Expression) = e.code == "RAX" || e.code == "EAX"

/** The return value of a binary-level `call source`: its first read. */
def firstRaxRead(c: Call): Option[Expression] = {
  var cur: Option[CfgNode] = c.cfgNext.headOption
  var steps = 0
  while (cur.isDefined && steps < 6) {
    cur.get match {
      case i: Call =>
        val args = i.argument.l
        args.find(a => a.argumentIndex >= 2 && isRax(a)) match {
          case Some(r) => return Some(r)
          case None    => if (args.exists(a => a.argumentIndex == 1 && isRax(a))) return None
        }
      case _ =>
    }
    cur = cur.get.cfgNext.headOption
    steps += 1
  }
  None
}

@main def exec(manifest: String) = {
  val stubs = "source|sink|opaque|opaque_int|schedule|may_throw"
  for ((line, i) <- os.read.lines(os.Path(manifest)).filter(_.trim.nonEmpty).zipWithIndex) {
    val Array(mode, cpgPath, outPath) = line.trim.split("\\s+")
    val project = s"t$i"
    val out = try {
      importCpg(cpgPath, project)
      val user = cpg.method.isExternal(false).nameNot(stubs).l
      def srcs = mode match {
        case "bin" => user.call.nameExact("source").l.flatMap(firstRaxRead)
        case "jvm" => user.call.nameExact("getenv")
        case _     => user.call.nameExact("source")
      }
      def snks =
        if (mode == "jvm") user.call.nameExact("println").argument.filter(_.argumentIndex > 0)
        else user.call.nameExact("sink").argument
      val flows = snks.reachableByFlows(srcs).l
      def loc(n: AstNode) = {
        // method name plus entry (ghidra2cpg: address), so a .resume clone
        // that demangles to the ramp's name still counts separately
        val m = n match {
          case c: CfgNode => s"${c.method.name}@${c.method.lineNumber.getOrElse(-1)}"
          case _          => "?"
        }
        val code = n.code.take(48).replace("\"", "'").replace("\\", "/")
        s""""$m:${n.lineNumber.getOrElse(-1)}:$code""""
      }
      val rows = flows.map(p => s"""{"source":${loc(p.elements.head)},"sink":${loc(p.elements.last)},"len":${p.elements.size}}""").distinct
      val stats =
        s"""{"stats":true,"sources":${srcs.size},"sinks":${snks.size},"methods":${user.size},"nodes":${user.ast.size}}"""
      (stats +: rows).mkString("\n") + "\n"
    } catch {
      case e: Throwable => s"""{"stats":true,"error":"${e.toString.take(200).replace("\"", "'")}"}""" + "\n"
    }
    os.write.over(os.Path(outPath), out)
    try { delete(project) } catch { case _: Throwable => () }
    println(s"[$i] $mode $cpgPath")
  }
}
