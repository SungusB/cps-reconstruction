package com.infinity.cps.reconstruction.export

import com.infinity.cps.reconstruction.cpg.CpgData
import com.infinity.cps.reconstruction.taint.TaintSlicer
import java.io.File
import java.io.PrintWriter

/**
 * Exports a [CpgData] and its [TaintSlicer.SliceResult] as JSON, following a
 * Joern-compatible CPG schema: nodes with statement labels, line numbers,
 * types, and slice roles; edges (CFG, DDG, CDG, and taint flow paths); and
 * summary metadata.
 */
object CpgJsonExporter {

    fun export(data: CpgData, slice: TaintSlicer.SliceResult, outputFile: String) {
        PrintWriter(File(outputFile)).use { writer ->
            writer.println("{")
            writer.println("  \"schema\": \"joern-cpg-v1\",")
            writer.println("  \"method\": \"${JsonUtil.escape(data.methodSignature ?: "")}\",")
            writer.println("  \"metadata\": {")
            writer.println("    \"statementCount\": ${data.countStatements()},")
            writer.println("    \"cfgEdgeCount\": ${data.countCfgEdges()},")
            writer.println("    \"ddgEdgeCount\": ${data.countDdgEdges()},")
            writer.println("    \"cdgEdgeCount\": ${data.countCdgEdges()},")
            writer.println("    \"sourceCount\": ${slice.sourceStmts.size},")
            writer.println("    \"sinkCount\": ${slice.sinkStmts.size},")
            writer.println("    \"chopSize\": ${slice.chop.size},")
            writer.println("    \"taintFlowCount\": ${slice.taintFlows.size}")
            writer.println("  },")
            writer.println("  \"nodes\": [")
            writeNodes(writer, data, slice)
            writer.println("  ],")
            writer.println("  \"edges\": [")
            writeEdges(writer, data, slice)
            writer.println("  ]")
            writer.println("}")
        }
    }

    private fun writeNodes(writer: PrintWriter, data: CpgData, slice: TaintSlicer.SliceResult) {
        for (i in data.stmtLabels.indices) {
            val label = JsonUtil.escape(data.stmtLabels[i])
            val line = data.stmtLines[i]
            val nodeType = classifyStatement(data.stmtLabels[i])
            writer.println("    {")
            writer.println("      \"id\": $i,")
            writer.println("      \"label\": \"$label\",")
            writer.println("      \"line\": $line,")
            writer.println("      \"type\": \"$nodeType\",")

            val roles = mutableListOf<String>()
            if (i in slice.sourceStmts) roles.add("SOURCE")
            if (i in slice.sinkStmts) roles.add("SINK")
            if (i in slice.chop) roles.add("CHOP")
            if (i in slice.forwardSlice) roles.add("FORWARD_SLICE")
            if (i in slice.backwardSlice) roles.add("BACKWARD_SLICE")
            writer.println("      \"roles\": [${roles.joinToString(", ") { "\"$it\"" }}],")

            val onTaintPath = slice.taintFlows.any { i in it.path }
            writer.println("      \"onTaintPath\": $onTaintPath")
            writer.println(if (i < data.stmtLabels.size - 1) "    }," else "    }")
        }
    }

    private fun writeEdges(writer: PrintWriter, data: CpgData, slice: TaintSlicer.SliceResult) {
        val allEdges = mutableListOf<String>()
        for (edge in data.cfgEdges) {
            val parts = edge.split("|")
            allEdges.add("      {\"src\": ${parts[0]}, \"dst\": ${parts[1]}, \"type\": \"CFG\"}")
        }
        for (edge in data.ddgEdges) {
            val parts = edge.split("|", limit = 3)
            val varName = if (parts.size > 2) parts[2] else ""
            allEdges.add("      {\"src\": ${parts[0]}, \"dst\": ${parts[1]}, \"type\": \"DDG\", \"variable\": \"${JsonUtil.escape(varName)}\"}")
        }
        for (edge in data.cdgEdges) {
            val parts = edge.split("|", limit = 3)
            val condition = if (parts.size > 2) parts[2] else ""
            val condAttr = if (condition.isEmpty()) "" else ", \"condition\": \"${JsonUtil.escape(condition)}\""
            allEdges.add("      {\"src\": ${parts[0]}, \"dst\": ${parts[1]}, \"type\": \"CDG\"$condAttr}")
        }
        for (flow in slice.taintFlows) {
            for (i in 0 until flow.path.size - 1) {
                allEdges.add("      {\"src\": ${flow.path[i]}, \"dst\": ${flow.path[i + 1]}, \"type\": \"TAINT_FLOW\", \"category\": \"${JsonUtil.escape(flow.category.toString())}\"}")
            }
        }
        for (i in allEdges.indices) {
            writer.println(allEdges[i] + if (i < allEdges.size - 1) "," else "")
        }
    }

    private fun classifyStatement(stmt: String): String {
        if (stmt.startsWith("if ")) return "BRANCH"
        if (stmt.contains("goto")) return "GOTO"
        if (stmt.contains("return")) return "RETURN"
        if (stmt.contains("throw")) return "THROW"
        if (stmt.contains("virtualinvoke") || stmt.contains("staticinvoke")
            || stmt.contains("interfaceinvoke") || stmt.contains("specialinvoke")
        ) {
            if (TaintSlicer.isSink(stmt)) return "SINK_CALL"
            if (TaintSlicer.isSource(stmt)) return "SOURCE_CALL"
            return "CALL"
        }
        if (stmt.contains("=")) return "ASSIGN"
        return "OTHER"
    }
}
