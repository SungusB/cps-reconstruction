package com.infinity.cps.reconstruction.export

import com.infinity.cps.reconstruction.cpg.CpgData
import java.io.File
import java.io.PrintWriter

/**
 * Exports a [CpgData] as a Graphviz DOT file: CFG edges in blue (solid), DDG
 * edges in green (dashed, labeled with variable name), CDG edges in red
 * (dotted, labeled with branch condition), and exceptional (trap) edges in
 * orange (dashed, labeled with the exception type) — a statement's edge to
 * its catch handler, distinct from ordinary control flow.
 */
object CpgExporter {

    fun exportCfg(data: CpgData, outputFile: String) {
        PrintWriter(File(outputFile)).use { writer ->
            writer.println("digraph CFG {")
            writer.println("  rankdir=TB;")
            writer.println("  node [shape=box, fontname=\"Monospace\", fontsize=9];")
            writer.println("  edge [fontname=\"Arial\", fontsize=8];")
            writer.println()

            for (i in data.stmtLabels.indices) {
                val label = JsonUtil.escape(data.stmtLabels[i])
                val line = data.stmtLines[i]
                val lineInfo = if (line > 0) " (line $line)" else ""
                writer.println("  $i [label=\"$i: $label$lineInfo\"];")
            }
            writer.println()

            for (edge in data.cfgEdges) {
                writer.println("  ${edge.src} -> ${edge.dst} [color=\"blue\"];")
            }
            for (edge in data.ddgEdges) {
                writer.println("  ${edge.src} -> ${edge.dst} [color=\"green\", label=\"${edge.variable ?: ""}\", style=dashed];")
            }
            for (edge in data.cdgEdges) {
                val labelAttr = if (edge.condition.isNullOrEmpty()) "" else ", label=\"${edge.condition}\""
                writer.println("  ${edge.src} -> ${edge.dst} [color=\"red\", style=dotted$labelAttr];")
            }
            for (edge in data.exceptionalEdges) {
                val labelAttr = if (edge.condition.isNullOrEmpty()) "" else ", label=\"${JsonUtil.escape(edge.condition)}\""
                writer.println("  ${edge.src} -> ${edge.dst} [color=\"orange\", style=dashed, penwidth=1.5$labelAttr];")
            }

            writer.println("}")
            println("    CPG: ${data.countStatements()} statements, ${data.countCfgEdges()} CFG edges, "
                + "${data.countDdgEdges()} DDG edges, ${data.countCdgEdges()} CDG edges, "
                + "${data.countExceptionalEdges()} exceptional edges")
        }
    }
}
