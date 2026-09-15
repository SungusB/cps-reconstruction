package com.infinity.cps.reconstruction.export

import com.infinity.cps.reconstruction.cpg.CpgData
import com.infinity.cps.reconstruction.taint.TaintSlicer
import java.io.File
import java.io.PrintWriter

/**
 * Exports a [CpgData]'s [TaintSlicer.SliceResult] as a Graphviz DOT file
 * visualizing the slice. Nodes are color-coded: red for sources, orange for
 * sinks, yellow for the chop (statements on the taint path).
 */
object SliceExporter {

    fun exportDot(data: CpgData, slice: TaintSlicer.SliceResult, outputFile: String) {
        PrintWriter(File(outputFile)).use { writer ->
            writer.println("digraph Slice {")
            writer.println("  rankdir=TB;")
            writer.println("  node [shape=box, fontname=\"Monospace\", fontsize=9];")
            writer.println("  edge [fontname=\"Arial\", fontsize=8];")
            writer.println()

            for (i in data.stmtLabels.indices) {
                val label = JsonUtil.escape(data.stmtLabels[i])
                val line = data.stmtLines[i]
                val lineInfo = if (line > 0) " (line $line)" else ""

                val color = when {
                    i in slice.sourceStmts -> "#FF6666"
                    i in slice.sinkStmts -> "#FFAA00"
                    i in slice.chop -> "#FFFF99"
                    else -> "white"
                }

                writer.println("  $i [label=\"$i: $label$lineInfo\", fillcolor=\"$color\", style=\"filled\"];")
            }
            writer.println()

            for (edge in data.ddgEdges) {
                if (edge.src in slice.chop && edge.dst in slice.chop) {
                    writer.println("  ${edge.src} -> ${edge.dst} [color=\"green\", label=\"${edge.variable ?: ""}\", style=dashed];")
                }
            }

            for (edge in data.cdgEdges) {
                if (edge.src in slice.chop && edge.dst in slice.chop) {
                    val labelAttr = if (edge.condition.isNullOrEmpty()) "" else ", label=\"${edge.condition}\""
                    writer.println("  ${edge.src} -> ${edge.dst} [color=\"red\", style=dotted$labelAttr];")
                }
            }

            writer.println("}")
        }
    }
}
