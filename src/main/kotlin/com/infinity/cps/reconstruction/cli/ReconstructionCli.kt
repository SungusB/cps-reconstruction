@file:JvmName("ReconstructionCli")

package com.infinity.cps.reconstruction.cli

import com.infinity.cps.reconstruction.cpg.CpgData
import com.infinity.cps.reconstruction.export.CpgExporter
import com.infinity.cps.reconstruction.export.CpgJsonExporter
import com.infinity.cps.reconstruction.export.JsonUtil
import com.infinity.cps.reconstruction.export.SliceExporter
import com.infinity.cps.reconstruction.reconstruct.SuspendChainReconstructor
import com.infinity.cps.reconstruction.taint.InterProceduralAnalyzer
import com.infinity.cps.reconstruction.taint.TaintSlicer
import sootup.core.inputlocation.AnalysisInputLocation
import sootup.core.signatures.MethodSignature
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation
import sootup.java.core.views.JavaView
import sootup.java.core.views.MutableJavaView
import java.io.File
import java.io.PrintWriter

/**
 * Entry point for the CPS state-machine reconstruction research artifact.
 *
 * A minimal driver, not a general-purpose scanner: it exists to demonstrate
 * that [SuspendChainReconstructor] lets source-to-sink taint flows be found
 * across a `suspend` call that would otherwise stop the analysis. There is
 * no call graph and no notion of an entry point — every method with a body
 * on the classpath is analyzed directly, which keeps results easy to verify
 * against hand-traced benchmark files.
 *
 * Usage: `reconstruction-cli <classPath> [outputDir] [--no-reconstruct]`
 *
 * `--no-reconstruct` skips [SuspendChainReconstructor] and analyzes the raw
 * bytecode instead. Running the same classpath with and without this flag
 * and comparing `totalTaintFlows` across the two `summary.json` outputs is
 * the ablation: how many flows are recovered only because of reconstruction.
 */
fun main(args: Array<String>) {
    val positional = args.filterNot { it.startsWith("--") }
    val reconstructionEnabled = "--no-reconstruct" !in args
    val classPath = positional[0]
    val outputDir = positional.getOrElse(1) { "reconstruction-analysis" }

    println("[*] loading classes from: $classPath")
    val inputLocations: List<AnalysisInputLocation> = listOf(JavaClassPathAnalysisInputLocation(classPath))
    val view = MutableJavaView(inputLocations)
    println("[*] loaded ${view.classes.count()} classes")

    val methods = collectMethodSignatures(view)
    val reconstruction = if (reconstructionEnabled) reconstructSuspendChains(view, methods) else ReconstructionStats()

    println("[*] running inter-procedural taint analysis on ${methods.size} methods...")
    val analyzer = InterProceduralAnalyzer()
    analyzer.analyze(view, methods)

    val outputDirs = OutputDirs(outputDir)
    val findings = sliceAndExport(view, methods, analyzer, outputDirs)

    writeSummary(outputDirs, methods.size, reconstructionEnabled, reconstruction, findings)
    println("[*] total taint flows: ${findings.size}")
}

private fun collectMethodSignatures(view: JavaView): Set<MethodSignature> {
    val methods = mutableSetOf<MethodSignature>()
    view.classes.forEach { cls -> cls.methods.forEach { if (it.hasBody()) methods.add(it.signature) } }
    return methods
}

private class ReconstructionStats(val reconstructedCount: Int = 0, val unsupportedCount: Int = 0)

private fun reconstructSuspendChains(view: JavaView, methods: Set<MethodSignature>): ReconstructionStats {
    println("[*] reconstructing Kotlin suspend chains...")
    val reconstructor = SuspendChainReconstructor(view)
    val reconstructed = reconstructor.demultiplex(methods)
    val unsupported = reconstructor.getUnsupportedMethods()

    println("[*] reconstructed ${reconstructed.size} suspend chain(s)")
    if (unsupported.isNotEmpty()) {
        println("[*] ${unsupported.size} suspend chain(s) left unmodified (general-case-unsupported):")
        for (sig in unsupported) println("    - $sig")
    }
    return ReconstructionStats(reconstructed.size, unsupported.size)
}

private class OutputDirs(outputDir: String) {
    val base = File(outputDir)
    val cfg = File(base, "cfg")
    val slices = File(base, "slices")

    init {
        base.mkdirs()
        cfg.mkdirs()
        slices.mkdirs()
    }
}

private class Finding(val method: MethodSignature, val category: String, val sourceLine: Int, val sinkLine: Int)

private fun sliceAndExport(
    view: JavaView,
    methods: Set<MethodSignature>,
    analyzer: InterProceduralAnalyzer,
    dirs: OutputDirs,
): List<Finding> {
    val findings = mutableListOf<Finding>()

    for (methodSig in methods) {
        val methodOpt = view.getMethod(methodSig)
        if (methodOpt.isEmpty || !methodOpt.get().hasBody()) continue

        val cpgData = analyzer.getCpg(methodSig.toString()) ?: CpgData.fromMethod(methodOpt.get())
        if (cpgData.countStatements() < 2) continue

        val slice = TaintSlicer.slice(cpgData)
        if (slice.sourceStmts.isNotEmpty()) {
            slice.taintFlows.addAll(TaintSlicer.findInterProceduralFlows(cpgData, slice, analyzer))
            slice.taintFlows.addAll(TaintSlicer.findCapturedFieldFlows(cpgData, slice, analyzer, view))
        }
        if (!slice.hasVulnerability() && slice.taintFlows.isEmpty()) continue

        val safeName = "${methodSig.declClassType.className}_${methodOpt.get().name}"
        exportArtifacts(cpgData, slice, safeName, dirs)

        for (flow in slice.taintFlows) {
            val srcLine = cpgData.stmtLines.getOrElse(flow.sourceIdx) { -1 }
            val sinkLine = cpgData.stmtLines.getOrElse(flow.sinkIdx) { -1 }
            findings.add(Finding(methodSig, flow.category.toString(), srcLine, sinkLine))
            println("    [!] flow in $methodSig [${flow.category}]: line $srcLine -> line $sinkLine")
        }
    }

    return findings
}

private fun exportArtifacts(cpgData: CpgData, slice: TaintSlicer.SliceResult, safeName: String, dirs: OutputDirs) {
    runCatching {
        CpgExporter.exportCfg(cpgData, File(dirs.cfg, "$safeName.dot").path)
    }.onFailure { e -> println("    [!] failed to export CFG for $safeName: ${e.message}") }

    runCatching {
        SliceExporter.exportDot(cpgData, slice, File(dirs.slices, "${safeName}_slice.dot").path)
        CpgJsonExporter.export(cpgData, slice, File(dirs.slices, "${safeName}_cpg.json").path)
    }.onFailure { e -> println("    [!] failed to export slice for $safeName: ${e.message}") }
}

private fun writeSummary(
    dirs: OutputDirs,
    methodsAnalyzed: Int,
    reconstructionEnabled: Boolean,
    reconstruction: ReconstructionStats,
    findings: List<Finding>,
) {
    runCatching {
        PrintWriter(File(dirs.base, "summary.json")).use { sw ->
            sw.println("{")
            sw.println("  \"tool\": \"ReconstructionCli\",")
            sw.println("  \"version\": \"1.0\",")
            sw.println("  \"methodsAnalyzed\": $methodsAnalyzed,")
            sw.println("  \"reconstructionEnabled\": $reconstructionEnabled,")
            sw.println("  \"suspendChainsReconstructed\": ${reconstruction.reconstructedCount},")
            sw.println("  \"suspendChainsUnsupported\": ${reconstruction.unsupportedCount},")
            sw.println("  \"totalTaintFlows\": ${findings.size},")
            sw.println("  \"findings\": [")
            for ((i, f) in findings.withIndex()) {
                val comma = if (i < findings.size - 1) "," else ""
                sw.println("    {\"method\": \"${JsonUtil.escape(f.method.toString())}\", " +
                    "\"category\": \"${JsonUtil.escape(f.category)}\", " +
                    "\"sourceLine\": ${f.sourceLine}, \"sinkLine\": ${f.sinkLine}}$comma")
            }
            sw.println("  ]")
            sw.println("}")
        }
    }.onFailure { e -> println("[!] failed to write summary: ${e.message}") }
}
