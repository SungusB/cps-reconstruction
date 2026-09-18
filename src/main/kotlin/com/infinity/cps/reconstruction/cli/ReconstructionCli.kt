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
import sootup.core.model.Body
import sootup.java.core.JavaSootClass
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
 * and comparing the two `summary.json` outputs is the ablation.
 *
 * `totalTaintFlows` alone is not a meaningful ablation signal for this tool:
 * Kotlin's coroutine ABI always emits a "didn't actually suspend" fast path
 * as ordinary sequential bytecode ahead of the `label`-dispatch switch, so a
 * reachability-only ("does a flow exist") analysis finds the same flows with
 * or without reconstruction — the fast path alone is a complete, walkable
 * source-to-sink path for any suspend-chain shape (straight-line, branches,
 * or loops), independent of whether the dispatch machinery is stripped. What
 * reconstruction demonstrably changes is *how much of the method a taint
 * analyzer has to look at to explain that flow*: `methodStats[].statementCount`
 * — the size of the CPG — shrinks substantially once dispatch bookkeeping
 * (label writes, spill-field reads/writes, the suspend-check `if`, the
 * switch itself) is stripped, because that bookkeeping no longer has to be
 * traversed or reasoned about to establish the same flow.
 *
 * `methodStats[].chopSize` is reported too but is **not** currently reliable
 * evidence of the same effect: until [com.infinity.cps.reconstruction.taint.TaintSlicer]'s
 * CDG-as-taint-propagating-edge over-approximation was fixed (see
 * `TaintSlicer.slice()`'s comment and `CLAUDE.md`), chop size before
 * reconstruction was inflated by spurious control-dependence chains through
 * the very suspend-check `if`s reconstruction removes — meaning the
 * chop-size reduction previously attributed to reconstruction was largely an
 * artifact of that bug, not reconstruction's own effect. Post-fix, chop size
 * is close to identical with and without reconstruction across the current
 * benchmark suite (verified via `scripts/run_ablation.sh`), which is the
 * *expected* result now, the same way flow-count parity always was —
 * `statementCount` is the metric that actually demonstrates reconstruction's
 * value.
 */
fun main(args: Array<String>) {
    val positional = args.filterNot { it.startsWith("--") }
    val reconstructionEnabled = "--no-reconstruct" !in args
    val classPath = positional[0]
    val outputDir = positional.getOrElse(1) { "reconstruction-analysis" }

    println("[*] loading classes from: $classPath")
    val inputLocations: List<AnalysisInputLocation> = listOf(JavaClassPathAnalysisInputLocation(classPath))
    val view = MutableJavaView(inputLocations)

    // `view.classes` is iterated exactly once, here, before any mutation:
    // iterating it a second time before SuspendChainReconstructor's
    // replaceClass() calls run triggers a SootUp MutableJavaView caching bug
    // where later view.getMethod() lookups keep returning the pre-replacement
    // method — reproduced directly by adding a second `view.classes.count()`
    // call ahead of reconstruction and observing replaced bodies silently
    // revert to their original (un-reconstructed) statement count.
    val classes = view.classes.toList()
    println("[*] loaded ${classes.size} classes")

    val methods = collectMethodSignatures(classes)
    val reconstruction = if (reconstructionEnabled) reconstructSuspendChains(view, methods) else ReconstructionStats()

    println("[*] running inter-procedural taint analysis on ${methods.size} methods...")
    val analyzer = InterProceduralAnalyzer()
    analyzer.analyze(view, methods, reconstruction.bodies)

    val outputDirs = OutputDirs(outputDir)
    val result = sliceAndExport(view, methods, analyzer, reconstruction.bodies, outputDirs)

    writeSummary(outputDirs, methods.size, reconstructionEnabled, reconstruction, result.findings, result.methodStats)
    println("[*] total taint flows: ${result.findings.size}")
}

private fun collectMethodSignatures(classes: List<JavaSootClass>): Set<MethodSignature> {
    val methods = mutableSetOf<MethodSignature>()
    classes.forEach { cls -> cls.methods.forEach { if (it.hasBody()) methods.add(it.signature) } }
    return methods
}

private class ReconstructionStats(
    val reconstructedCount: Int = 0,
    val unsupportedCount: Int = 0,
    val bodies: Map<MethodSignature, Body> = emptyMap(),
)

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
    return ReconstructionStats(reconstructed.size, unsupported.size, reconstructor.getReconstructedBodies())
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

/**
 * Per-method size of the evidence needed to explain a flow — the ablation
 * signal that actually differs between reconstruction on and off (see
 * [main]'s doc comment for why raw flow-count doesn't).
 */
private class MethodStats(val method: MethodSignature, val statementCount: Int, val chopSize: Int)

private class SliceResult(val findings: List<Finding>, val methodStats: List<MethodStats>)

private fun sliceAndExport(
    view: JavaView,
    methods: Set<MethodSignature>,
    analyzer: InterProceduralAnalyzer,
    reconstructedBodies: Map<MethodSignature, Body>,
    dirs: OutputDirs,
): SliceResult {
    val findings = mutableListOf<Finding>()
    val methodStats = mutableListOf<MethodStats>()

    for (methodSig in methods) {
        // Bug found via benchmark/flow/*.kt (see CLAUDE.md): view.getMethod() is subject to
        // the documented MutableJavaView staleness bug, so it must not gate whether a
        // reconstructed method is processed at all — only methods with neither a
        // reconstructed body nor a resolvable original body should be skipped here.
        val reconstructedBody = reconstructedBodies[methodSig]
        val methodOpt = view.getMethod(methodSig)
        if (reconstructedBody == null && (methodOpt.isEmpty || !methodOpt.get().hasBody())) continue

        val cpgData = analyzer.getCpg(methodSig.toString())
            ?: reconstructedBody?.let { CpgData.fromBody(it.stmtGraph, methodSig.toString()) }
            ?: CpgData.fromMethod(methodOpt.get())
        if (cpgData.countStatements() < 2) continue

        val slice = TaintSlicer.slice(cpgData)
        if (slice.sourceStmts.isNotEmpty()) {
            slice.taintFlows.addAll(TaintSlicer.findInterProceduralFlows(cpgData, slice, analyzer))
            slice.taintFlows.addAll(TaintSlicer.findCapturedFieldFlows(cpgData, slice, analyzer, view))
        }

        // slice(), findInterProceduralFlows(), and findCapturedFieldFlows() each walk the
        // CPG independently and can all report the same (source, sink) pair once a method's
        // CFG has more than one real path between them — e.g. a reconstructed try/catch,
        // where the intra-procedural chop now succeeds on its own *and* the inter-procedural
        // callee-summary pass still separately confirms the same pair. Previously latent
        // because a straight-line body has exactly one path; surfaced once general-case
        // reconstruction started preserving exceptional edges (see SuspendChainReconstructor's
        // unrollGeneralCase). Dedup by (source stmt, sink stmt, category), not by line number,
        // since two distinct statements can share a source line.
        val dedupedFlows = slice.taintFlows.distinctBy { Triple(it.sourceIdx, it.sinkIdx, it.category) }
        slice.taintFlows.clear()
        slice.taintFlows.addAll(dedupedFlows)

        // Export the CFG/slice `.dot`s for every method that so much as mentions a
        // source or a sink, not only for the ones where a flow was found: the
        // `expect: NO-FLOW` benchmarks (e.g. `benchmark/spill-slot/01`–`03`) are the
        // ones where the raw-vs-reconstructed CFG is most worth looking at, and they
        // were previously invisible in `cfg/` precisely because nothing was reported.
        // The stats/findings gate below is unchanged, so `summary.json` is unaffected.
        val safeName = "${methodSig.declClassType.className}_${methodSig.name}"
        if (slice.sourceStmts.isNotEmpty() || slice.sinkStmts.isNotEmpty()) {
            exportArtifacts(cpgData, slice, safeName, dirs)
        }

        if (!slice.hasVulnerability() && slice.taintFlows.isEmpty()) continue

        methodStats.add(MethodStats(methodSig, cpgData.countStatements(), slice.chop.size))

        for (flow in slice.taintFlows) {
            val srcLine = cpgData.stmtLines.getOrElse(flow.sourceIdx) { -1 }
            val sinkLine = cpgData.stmtLines.getOrElse(flow.sinkIdx) { -1 }
            findings.add(Finding(methodSig, flow.category.toString(), srcLine, sinkLine))
            println("    [!] flow in $methodSig [${flow.category}]: line $srcLine -> line $sinkLine")
        }
    }

    return SliceResult(findings, methodStats)
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
    methodStats: List<MethodStats>,
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
            sw.println("  ],")
            sw.println("  \"methodStats\": [")
            for ((i, m) in methodStats.withIndex()) {
                val comma = if (i < methodStats.size - 1) "," else ""
                sw.println("    {\"method\": \"${JsonUtil.escape(m.method.toString())}\", " +
                    "\"statementCount\": ${m.statementCount}, \"chopSize\": ${m.chopSize}}$comma")
            }
            sw.println("  ]")
            sw.println("}")
        }
    }.onFailure { e -> println("[!] failed to write summary: ${e.message}") }
}
