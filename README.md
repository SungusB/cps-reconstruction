# CPS State-Machine Reconstruction for Static Taint Analysis

A tool for reconstructing analyzable control flow from Kotlin's
compiler-generated coroutine continuation-passing-style (CPS) state machines,
so that static taint analysis can see through `suspend` function calls
instead of stopping at them.

## Motivation

The Kotlin compiler lowers every `suspend` function into a state machine: a
class implementing `ContinuationImpl` with a `label` field, spill fields
(`L$0`, `L$1`, ...) that persist local variables across suspension points, and
a `tableswitch` dispatch loop that resumes execution at the right point after
a coroutine suspends. This lowering is opaque to off-the-shelf static
analysis: a tool operating on the compiled bytecode (or an intermediate
representation built from it, such as Jimple via SootUp) sees a single flat
dispatch loop instead of the sequential suspend chain the original source
expressed, and taint flows that cross a suspension point are invisible.

This project reconstructs the original sequential control flow for
**straight-line suspend chains** — a `suspend` function body containing one or
more suspend calls with no other control flow (no branches, loops, or
exception handlers between suspension points) — by identifying and stripping
the compiler-generated dispatch bookkeeping (label writes, spill field
read/write pairs, the switch statement itself, and gotos that target other
dispatch cases) from each case block, then splicing the remaining statements
back into one linear method body.

## Scope

**Current scope:** straight-line suspend chains in Kotlin coroutines.

**Explicitly out of scope:** suspend calls inside conditionals, loops, or
try/catch blocks. Reconstructing these soundly requires solving the general
phi-node synthesis / back-edge disambiguation problem for a lowered state
machine, which is a substantially harder problem than dispatch-artifact
stripping. Rather than guess at an unsound partial transformation, the tool
detects these cases and declines to transform them, leaving the method body
untouched and flagging it as unsupported (`general-case-unsupported`). See
`SuspendChainReconstructor.kt`'s `findUnsupportedControlFlow` for the
detection logic that draws this line.

A closure captured into a nested suspend lambda (e.g. `launch { sink(x) }`,
where `x` is read from the enclosing scope) is compiled as a
constructor-parameter-to-field write rather than a method argument, so it
falls outside both the reconstruction above and ordinary call-argument taint
tracking. `InterProceduralAnalyzer`/`TaintSlicer.findCapturedFieldFlows`
handle this case directly: a per-method summary records which constructor
parameters are written into which fields, and which fields — when read —
reach a sink, letting a captured-variable flow be traced into the lambda's
`invokeSuspend` without inlining its body.

## What this is not

This is a control-flow reconstruction tool, not a security product. The
included taint-analysis engine (source-to-sink propagation across ordinary
calls, captured closures, and reconstructed suspend chains) exists to
demonstrate reconstruction's effect on reachability — it is intentionally
minimal, not a general-purpose vulnerability scanner. It does not do
Android-specific modeling (Intent/IPC resolution, manifest parsing),
vulnerability triage, or report generation for downstream tools.

## Project layout

```
src/main/kotlin/com/infinity/cps/reconstruction/
  reconstruct/   SuspendChainReconstructor — coroutine state-machine reconstruction
  cpg/           CpgData, FieldAliasTracker — CFG/DDG/CDG construction
  taint/         InterProceduralAnalyzer, TaintSlicer — the minimal taint engine
  export/        DOT/JSON exporters
  cli/           ReconstructionCli — the entry point
benchmark/       hand-written Kotlin fixtures, one per control-flow shape
scripts/         the ablation harness (with vs. without reconstruction)
```

## Building

```
./gradlew build
```

This is a Gradle `application` project written in Kotlin, built with SootUp
for CPG construction and Kotlin/Java bytecode analysis. Run it with:

```
./gradlew run --args="<classPath> [outputDir] [--no-reconstruct]"
```

`--no-reconstruct` skips suspend-chain reconstruction and analyzes the raw
bytecode instead. `scripts/run_ablation.sh` runs the benchmark suite both
ways and reports the delta.

## License

BSD 3-Clause — see [LICENSE](LICENSE). Copyright Marcio Aparecido de Godoi
Junior, Laboratório de Sistemas Computacionais (LSC), UNICAMP.
