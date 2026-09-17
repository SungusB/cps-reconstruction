package com.infinity.cps.reconstruction.baseline

import com.infinity.cps.reconstruction.export.JsonUtil
import soot.G
import soot.PackManager
import soot.Scene
import soot.SootClass
import soot.SootMethod
import soot.jimple.DefinitionStmt
import soot.jimple.InstanceInvokeExpr
import soot.jimple.Stmt
import soot.jimple.SwitchStmt
import soot.jimple.infoflow.Infoflow
import soot.jimple.infoflow.InfoflowConfiguration
import soot.jimple.infoflow.InfoflowManager
import soot.jimple.infoflow.data.AccessPath
import soot.jimple.infoflow.data.SootMethodAndClass
import soot.jimple.infoflow.entryPointCreators.DefaultEntryPointCreator
import soot.jimple.infoflow.results.DataFlowResult
import soot.jimple.infoflow.sourcesSinks.definitions.MethodSourceSinkDefinition
import soot.jimple.infoflow.sourcesSinks.manager.ISourceSinkManager
import soot.jimple.infoflow.sourcesSinks.manager.SinkInfo
import soot.jimple.infoflow.sourcesSinks.manager.SourceInfo
import soot.jimple.infoflow.taintWrappers.EasyTaintWrapper
import soot.jimple.infoflow.util.SystemClassHandler
import soot.options.Options
import soot.tagkit.LineNumberTag
import java.io.File
import java.io.PrintWriter

/**
 * External-baseline harness: runs FlowDroid over the benchmark classes, with
 * or without the classic-Soot port of suspend-chain reconstruction applied to
 * the loaded bodies first, and records what FlowDroid reports either way.
 *
 * Usage: `baseline <classesDir> <outputDir> [--no-reconstruct] [--lib <classpath>] [--emit-classes] [--no-invokesuspend-entry]`
 *
 * `--lib` is the library classpath Soot resolves against (kotlin-stdlib,
 * kotlinx-coroutines); the JDK is prepended automatically. `--emit-classes`
 * additionally writes every application class — reconstructed bodies
 * included — as `.class` files under `<outputDir>/classes`, for any other
 * bytecode-consuming tool.
 *
 * FlowDroid runs once per benchmark package (one Soot scene each), with that
 * package's methods as the entry points: one benchmark is one experimental
 * unit, and a single dummy main over every benchmark at once let taint from
 * each source flow into every later entry call and blew the IFDS problem past
 * 10M edges. Sources and sinks are the same method patterns `TaintSlicer`
 * uses, so the two analyzers are answering the same question; no
 * platform-specific list is involved.
 */
fun main(args: Array<String>) {
    val positional = mutableListOf<String>()
    var libPath = ""
    var reconstruct = true
    var emitClasses = false
    var skipInvokeSuspendEntries = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--no-reconstruct" -> reconstruct = false
            "--emit-classes" -> emitClasses = true
            "--no-invokesuspend-entry" -> skipInvokeSuspendEntries = true
            "--lib" -> libPath = args[++i]
            else -> positional.add(args[i])
        }
        i++
    }
    val classesDir = File(positional[0]).absolutePath
    val outputDir = File(positional.getOrElse(1) { "baseline-analysis" }).also { it.mkdirs() }

    println("[*] initializing Soot on $classesDir")
    initSoot(classesDir, libPath, emptyList())
    val packages = Scene.v().applicationClasses
        .filter { !it.name.startsWith("kotlin.") }
        .map { it.packageName }.distinct().sorted()
    println("[*] ${packages.size} benchmark packages")

    val reconstructed = mutableListOf<SootSuspendChainReconstructor.MethodResult>()
    val declined = linkedMapOf<String, String>()
    val flows = mutableListOf<FlowRecord>()
    val packageRuns = mutableListOf<PackageRun>()
    val classOut = File(outputDir, "classes")

    for (pkg in packages) {
        // Every other benchmark package is excluded (phantom, bodiless) so the
        // scene holds exactly one benchmark: kotlin.coroutines' resumeWith ->
        // invokeSuspend virtual call otherwise fans out to every continuation
        // class in the scene and one package's run reports the whole suite.
        initSoot(classesDir, libPath, packages.filter { it != pkg }.map { "$it.*" })
        val pkgClasses = Scene.v().applicationClasses.filter { it.packageName == pkg }

        dumpBodies(pkgClasses.flatMap { cls -> cls.methods.filter { isStateMachineShaped(it) } }, File(outputDir, "jimple/original"))

        if (reconstruct) {
            val reconstructor = SootSuspendChainReconstructor()
            reconstructor.reconstructAll(pkgClasses)
            dumpBodies(reconstructor.reconstructed.map { it.method }, File(outputDir, "jimple/reconstructed"))
            for (r in reconstructor.reconstructed) {
                println("    ${r.method.signature}: ${r.originalStatements} -> ${r.realStatements} real (${r.bodyStatements} emitted)")
            }
            for ((m, reason) in reconstructor.declined) println("    declined ${m.signature}: $reason")
            reconstructed.addAll(reconstructor.reconstructed)
            reconstructor.declined.forEach { (m, reason) -> declined[m.signature] = reason }
        }
        if (emitClasses) {
            classOut.mkdirs()
            for (cls in Scene.v().applicationClasses.toList()) if (cls !in pkgClasses) cls.setLibraryClass()
            // writeOutput() releases every body it has written unless told
            // not to; FlowDroid's callgraph builder then fails with "No
            // method source set" on the very next retrieveActiveBody().
            Options.v().set_no_writeout_body_releasing(true)
            Options.v().set_output_format(Options.output_format_class)
            Options.v().set_output_dir(classOut.absolutePath)
            PackManager.v().writeOutput()
            Options.v().set_output_format(Options.output_format_none)
        }

        // `invokeSuspend` on the generated continuation classes is an entry
        // point too: it is how the runtime resumes a state machine
        // (BaseContinuationImpl.resumeWith -> invokeSuspend -> the suspend
        // function with `this` as its continuation). Leaving it out models
        // "the coroutine never resumes", which is what --no-invokesuspend-entry
        // does — a diagnostic to show that the spill-slot false positives need
        // the resumption path, not a configuration to report numbers from.
        val entryPoints = pkgClasses.flatMap { cls ->
            cls.methods.filter { it.isConcrete && !it.isConstructor && !it.isStaticInitializer }
                .filter { !(skipInvokeSuspendEntries && it.name == "invokeSuspend") }
                .map { it.signature }
        }
        val run = runFlowDroid(pkg, classesDir, libPath, entryPoints)
        packageRuns.add(run)
        flows.addAll(run.flows)
        println("[*] [$pkg] ${run.flows.size} flow(s), ${run.wallMs}ms, ${run.callgraphEdges} cg edges, ${run.ifdsEdges} IFDS edges")
        for (f in run.flows) println("    ${f.describe()}")
    }

    println("[*] reconstructed ${reconstructed.size} suspend chain(s), declined ${declined.size}")
    println("[*] total FlowDroid flows: ${flows.size}")
    if (emitClasses) println("[*] class files written to $classOut")
    writeSummary(File(outputDir, "summary.json"), reconstruct, reconstructed, declined, flows, packageRuns)
}

private class FlowRecord(
    val sourceMethod: String, val sourceLine: Int, val source: String,
    val sinkMethod: String, val sinkLine: Int, val sink: String,
) {
    fun describe() = "$source (line $sourceLine) -> $sink (line $sinkLine)"
}

private class PackageRun(
    val pkg: String,
    val flows: List<FlowRecord>,
    val wallMs: Long,
    val callgraphEdges: Int,
    val ifdsEdges: Long,
)

private fun runFlowDroid(pkg: String, classesDir: String, libPath: String, entryPoints: List<String>): PackageRun {
    val infoflow = Infoflow()
    infoflow.taintWrapper = EasyTaintWrapper.getDefault().also { if (System.getProperty("tw.aggressive") == "true") it.setAggressiveMode(true) }
    val config = infoflow.config
    config.sootIntegrationMode = InfoflowConfiguration.SootIntegrationMode.UseExistingInstance
    config.callgraphAlgorithm = InfoflowConfiguration.CallgraphAlgorithm.SPARK
    // Source/sink pairs are all the comparison needs; FlowDroid's path builder
    // hangs on the cyclic abstraction graph BaseContinuationImpl.resumeWith's
    // dispatch loop produces, even when the IFDS solve itself finishes.
    // NoPaths still runs a (source-finding) path builder, and on the two
    // nested-lambda packages it is what hits dataFlowTimeout — in BOTH modes
    // — after a ~15 s solve. `wallMs` for those packages therefore measures
    // the timeout, not analysis work; `ifdsEdges` is the cost figure to use.
    config.pathConfiguration.pathReconstructionMode = InfoflowConfiguration.PathReconstructionMode.NoPaths
    config.dataFlowTimeout = 300
    config.setEnableLineNumbers(true)
    // The generated dummy main passes constants for primitive parameters;
    // FlowDroid's default constant propagation then folds `if (flag)` on a
    // boolean parameter and deletes the branch holding the suspend call
    // (benchmark/if-else/01 reports 0 flows in both modes with it on). That is
    // an entry-point artifact, not a property of the code under analysis.
    config.codeEliminationMode = InfoflowConfiguration.CodeEliminationMode.NoCodeElimination

    val start = System.nanoTime()
    infoflow.computeInfoflow(classesDir, libPath, DefaultEntryPointCreator(entryPoints), PatternSourceSinkManager())
    val wallMs = (System.nanoTime() - start) / 1_000_000
    val results = infoflow.results
    val perf = results.performanceData
    val flows = results.resultSet.orEmpty().map { f ->
        FlowRecord(
            methodOf(f.source.stmt), lineOf(f.source.stmt), f.source.stmt.toString(),
            methodOf(f.sink.stmt), lineOf(f.sink.stmt), f.sink.stmt.toString(),
        )
    }
    val cgEdges = if (Scene.v().hasCallGraph()) Scene.v().callGraph.size() else -1
    return PackageRun(pkg, flows, wallMs, cgEdges, perf?.edgePropagationCount ?: -1)
}

private fun isStateMachineShaped(m: SootMethod): Boolean =
    m.isConcrete && runCatching { m.retrieveActiveBody() }.getOrNull()?.units?.any { it is SwitchStmt } == true

/** One `.jimple` per method, named after the class and method. */
private fun dumpBodies(methods: List<SootMethod>, dir: File) {
    dir.mkdirs()
    for (m in methods) {
        if (!m.hasActiveBody()) continue
        File(dir, "${m.declaringClass.name}.${m.name}.jimple").writeText(m.activeBody.toString())
    }
}

private fun initSoot(classesDir: String, libPath: String, extraExcludes: List<String>) {
    G.reset()
    val o = Options.v()
    o.set_prepend_classpath(true)
    o.set_allow_phantom_refs(true)
    o.set_no_bodies_for_excluded(true)
    o.set_whole_program(true)
    o.set_keep_line_number(true)
    o.set_src_prec(Options.src_prec_only_class)
    o.set_output_format(Options.output_format_none)
    o.set_process_dir(listOf(classesDir))
    o.set_soot_classpath(listOf(classesDir, libPath).filter { it.isNotEmpty() }.joinToString(File.pathSeparator))
    // FlowDroid's standard configuration: platform classes stay bodiless and
    // are modeled by EasyTaintWrapper summaries instead of being analyzed
    // (with bodies, CHA over java.base alone blows past 5M IFDS edges).
    // kotlinx.coroutines is excluded too: Soot 4.6's ASM frontend fails on
    // some of its internal bodies (ClassCastException in
    // AsmMethodSource.convertInsn), and neither analyzer models the runtime.
    // kotlin.* is excluded wholesale (kotlin.collections/sequences alone
    // contributed ~13k callgraph edges reachable only via Intrinsics'
    // exception helpers; kotlin.text's StringsKt facade another ~11k), except
    // the packages whose bodies carry taint in a coroutine state machine:
    // nullOutSpilledVariable's `return null`, throwOnFailure, and
    // Ref$ObjectRef captures. kotlin.text is summarized by wrapper rules.
    o.set_exclude(listOf("java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*", "kotlinx.coroutines.*", "kotlin.*") + extraExcludes)
    o.set_include(listOf("kotlin.coroutines.*", "kotlin.jvm.internal.*", "kotlin.ResultKt", "kotlin.Result"))
    o.setPhaseOption("jb.ulp", "off")
    o.setPhaseOption("cg", "trim-clinit:false")
    Scene.v().loadNecessaryClasses()
}

/** Mirrors `TaintSlicer`'s SOURCE_PATTERNS / SINK_PATTERNS: `(simple class name, method name)`, empty class = any. */
private class PatternSourceSinkManager : ISourceSinkManager {
    private val sources = listOf("System" to "getenv")
    private val sinks = listOf(
        "Runtime" to "exec",
        "ProcessBuilder" to "start",
        "FileOutputStream" to "<init>",
        "Socket" to "<init>",
        "URL" to "openConnection",
        "" to "println",
    )

    override fun initialize() = Unit

    private fun matches(stmt: Stmt, patterns: List<Pair<String, String>>): SootMethod? {
        if (!stmt.containsInvokeExpr()) return null
        val ref = stmt.invokeExpr.methodRef
        val hit = patterns.any { (cls, name) ->
            ref.name == name && (cls.isEmpty() || ref.declaringClass.shortName == cls)
        }
        return if (hit) stmt.invokeExpr.method else null
    }

    override fun getSourceInfo(stmt: Stmt, manager: InfoflowManager): SourceInfo? {
        val callee = matches(stmt, sources) ?: return null
        val def = stmt as? DefinitionStmt ?: return null
        val ap = manager.accessPathFactory.createAccessPath(def.leftOp, true)
        return SourceInfo(MethodSourceSinkDefinition(SootMethodAndClass(callee)), ap)
    }

    override fun getSinkInfo(stmt: Stmt, manager: InfoflowManager, ap: AccessPath?): SinkInfo? {
        val callee = matches(stmt, sinks) ?: return null
        val expr = stmt.invokeExpr
        if (!SystemClassHandler.v().isTaintVisible(ap, callee)) return null
        val def = MethodSourceSinkDefinition(SootMethodAndClass(callee))
        if (ap == null) return SinkInfo(def)
        if (ap.isStaticFieldRef) return null
        val plain = ap.plainValue
        val argHit = (0 until expr.argCount).any { expr.getArg(it) === plain } && (ap.taintSubFields || ap.isLocal)
        val baseHit = (expr as? InstanceInvokeExpr)?.base === plain
        return if (argHit || baseHit) SinkInfo(def) else null
    }
}

private fun lineOf(stmt: Stmt): Int = (stmt.getTag("LineNumberTag") as? LineNumberTag)?.lineNumber ?: -1

private fun methodOf(stmt: Stmt): String {
    for (cls in Scene.v().applicationClasses) for (m in cls.methods) {
        if (m.hasActiveBody() && m.activeBody.units.contains(stmt)) return m.signature
    }
    return "?"
}

private fun writeSummary(
    file: File,
    reconstructionEnabled: Boolean,
    stats: List<SootSuspendChainReconstructor.MethodResult>,
    declined: Map<String, String>,
    flows: List<FlowRecord>,
    packageRuns: List<PackageRun>,
) {
    PrintWriter(file).use { sw ->
        sw.println("{")
        sw.println("  \"tool\": \"BaselineCli/FlowDroid\",")
        sw.println("  \"reconstructionEnabled\": $reconstructionEnabled,")
        sw.println("  \"suspendChainsReconstructed\": ${stats.size},")
        sw.println("  \"suspendChainsUnsupported\": ${declined.size},")
        sw.println("  \"totalTaintFlows\": ${flows.size},")
        sw.println("  \"flowDroidWallMs\": ${packageRuns.sumOf { it.wallMs }},")
        sw.println("  \"flowDroidEdgePropagations\": ${packageRuns.sumOf { it.ifdsEdges.coerceAtLeast(0) }},")
        sw.println("  \"findings\": [")
        for ((i, f) in flows.withIndex()) {
            val comma = if (i < flows.size - 1) "," else ""
            sw.println("    {\"sourceMethod\": \"${JsonUtil.escape(f.sourceMethod)}\", " +
                "\"sourceLine\": ${f.sourceLine}, " +
                "\"sinkMethod\": \"${JsonUtil.escape(f.sinkMethod)}\", " +
                "\"sinkLine\": ${f.sinkLine}, " +
                "\"sink\": \"${JsonUtil.escape(f.sink)}\"}$comma")
        }
        sw.println("  ],")
        sw.println("  \"packageRuns\": [")
        for ((i, r) in packageRuns.withIndex()) {
            val comma = if (i < packageRuns.size - 1) "," else ""
            sw.println("    {\"package\": \"${JsonUtil.escape(r.pkg)}\", \"flows\": ${r.flows.size}, " +
                "\"wallMs\": ${r.wallMs}, \"callgraphEdges\": ${r.callgraphEdges}, \"ifdsEdges\": ${r.ifdsEdges}}$comma")
        }
        sw.println("  ],")
        sw.println("  \"methodStats\": [")
        for ((i, r) in stats.withIndex()) {
            val comma = if (i < stats.size - 1) "," else ""
            sw.println("    {\"method\": \"${JsonUtil.escape(r.method.signature)}\", " +
                "\"originalStatements\": ${r.originalStatements}, " +
                "\"statementCount\": ${r.realStatements}, " +
                "\"bodyStatements\": ${r.bodyStatements}}$comma")
        }
        sw.println("  ],")
        sw.println("  \"declined\": [")
        val declinedList = declined.entries.toList()
        for ((i, e) in declinedList.withIndex()) {
            val comma = if (i < declinedList.size - 1) "," else ""
            sw.println("    {\"method\": \"${JsonUtil.escape(e.key)}\", \"reason\": \"${JsonUtil.escape(e.value)}\"}$comma")
        }
        sw.println("  ]")
        sw.println("}")
    }
}
