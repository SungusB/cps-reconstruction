package com.infinity.cps.reconstruction.export

import com.infinity.cps.reconstruction.cpg.CpgData
import com.infinity.cps.reconstruction.cpg.CpgEdge
import com.infinity.cps.reconstruction.taint.TaintSlicer
import java.io.File
import java.io.PrintWriter

/**
 * Exports a [CpgData] and its [TaintSlicer.SliceResult] as JSON: the full
 * four-layer CPG (AST, CFG, DDG, CDG — see [CpgData]'s doc comment) plus
 * slice/taint-flow annotations, following a Joern-compatible schema shape
 * (typed nodes and edges, edge-kind-tagged) extended with an `astNodes`
 * array and `AST`/`BINDS_TO` edges Joern's own JVM-bytecode CPG doesn't
 * expose in the same form.
 */
object CpgJsonExporter {

    fun export(data: CpgData, slice: TaintSlicer.SliceResult, outputFile: String) {
        PrintWriter(File(outputFile)).use { writer ->
            writer.println("{")
            writer.println("  \"schema\": \"cpg-ast-cfg-ddg-cdg-v2\",")
            writer.println("  \"method\": \"${JsonUtil.escape(data.methodSignature ?: "")}\",")
            writer.println("  \"metadata\": {")
            writer.println("    \"statementCount\": ${data.countStatements()},")
            writer.println("    \"astNodeCount\": ${data.countAstNodes()},")
            writer.println("    \"cfgEdgeCount\": ${data.countCfgEdges()},")
            writer.println("    \"ddgEdgeCount\": ${data.countDdgEdges()},")
            writer.println("    \"cdgEdgeCount\": ${data.countCdgEdges()},")
            writer.println("    \"astEdgeCount\": ${data.countAstEdges()},")
            writer.println("    \"sourceCount\": ${slice.sourceStmts.size},")
            writer.println("    \"sinkCount\": ${slice.sinkStmts.size},")
            writer.println("    \"chopSize\": ${slice.chop.size},")
            writer.println("    \"taintFlowCount\": ${slice.taintFlows.size}")
            writer.println("  },")
            writer.println("  \"nodes\": [")
            writeNodes(writer, data, slice)
            writer.println("  ],")
            writer.println("  \"astNodes\": [")
            writeAstNodes(writer, data)
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

    private fun writeAstNodes(writer: PrintWriter, data: CpgData) {
        for ((idx, node) in data.astNodes.withIndex()) {
            writer.println("    {")
            writer.println("      \"id\": ${node.id},")
            writer.println("      \"kind\": \"${JsonUtil.escape(node.kind)}\",")
            writer.println("      \"text\": \"${JsonUtil.escape(node.text)}\"")
            writer.println(if (idx < data.astNodes.size - 1) "    }," else "    }")
        }
    }

    private fun writeEdges(writer: PrintWriter, data: CpgData, slice: TaintSlicer.SliceResult) {
        val allEdges = mutableListOf<String>()
        for (edge in data.cfgEdges) allEdges.add(edgeJson(edge))
        for (edge in data.ddgEdges) allEdges.add(edgeJson(edge))
        for (edge in data.cdgEdges) allEdges.add(edgeJson(edge))
        for (edge in data.astEdges) allEdges.add(edgeJson(edge))
        for (edge in data.bindingEdges) allEdges.add(edgeJson(edge))
        for (flow in slice.taintFlows) {
            for (i in 0 until flow.path.size - 1) {
                allEdges.add("      {\"src\": ${flow.path[i]}, \"dst\": ${flow.path[i + 1]}, \"type\": \"TAINT_FLOW\", \"category\": \"${JsonUtil.escape(flow.category.toString())}\"}")
            }
        }
        for (i in allEdges.indices) {
            writer.println(allEdges[i] + if (i < allEdges.size - 1) "," else "")
        }
    }

    private fun edgeJson(edge: CpgEdge): String {
        val attrs = mutableListOf("\"src\": ${edge.src}", "\"dst\": ${edge.dst}", "\"type\": \"${edge.kind}\"")
        edge.variable?.let { attrs.add("\"variable\": \"${JsonUtil.escape(it)}\"") }
        edge.condition?.let { attrs.add("\"condition\": \"${JsonUtil.escape(it)}\"") }
        return "      {${attrs.joinToString(", ")}}"
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
