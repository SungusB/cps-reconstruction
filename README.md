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
  cpg/           CpgData, FieldAliasTracker — CFG/DDG/CDG construction, typed CpgEdge/EdgeKind
  ast/           JimpleAstBuilder, JimpleAstNode — the AST layer, built from Jimple
  taint/         InterProceduralAnalyzer, TaintSlicer — the minimal taint engine
  export/        DOT/JSON exporters
  cli/           ReconstructionCli — the entry point
benchmark/       hand-written Kotlin fixtures, one per control-flow shape
scripts/         the ablation harness (with vs. without reconstruction)
```

## Code Property Graph construction

`CpgData` builds a Code Property Graph per method, following the definition
of Yamaguchi et al. ("Modeling and Discovering Vulnerabilities with Code
Property Graphs", IEEE S&P 2014): a CPG merges an **AST**, a **CFG**, and a
**PDG** (here split into its two constituents, the **CDG** and the **DDG**)
into one graph over a shared node space. All four layers are typed
(`CpgEdge`/`EdgeKind` in the `cpg` package), not the earlier ad hoc
`"src|dst|label"` string encoding.

- **AST.** Built by `JimpleAstBuilder` directly from Jimple — the same IR
  SootUp already builds the CFG/DDG/CDG from — rather than by re-parsing
  Kotlin source. A statement's AST subtree is rooted at a node that
  corresponds exactly to that statement (e.g. `x = a + b` becomes a
  `JAssignStmt` root with a `target` child `x` and a `value` child
  `JAddExpr`, itself with `op1`/`op2` children `a`, `b`), and an
  `EdgeKind.BINDS_TO` edge connects the statement's CFG/DDG/CDG node to that
  root exactly — not via a source-line heuristic. A source-level Kotlin AST
  was considered and rejected: a compiler-lowered statement (especially
  after suspend-chain reconstruction) doesn't correspond 1:1 with a source
  line, since one Kotlin expression can compile to several Jimple
  statements, or several source lines can collapse into one after coroutine
  dispatch artifacts are stripped — a source AST could only be fused to the
  other three layers approximately, at line granularity.
- **CFG.** Basic-block successor edges from SootUp's `stmtGraph`, unchanged.
- **CDG.** The Cytron et al. dominance-frontier algorithm (Cytron, Ferrante,
  Rosen, Wegman, Zadeck, "Efficiently Computing Static Single Assignment
  Form and the Control Dependence Graph", TOPLAS 1991), with immediate
  post-dominators via the iterative Cooper-Harvey-Kennedy algorithm ("A
  Simple, Fast Dominance Algorithm", 2001).
- **DDG.** Standard iterative reaching-definitions dataflow (Kildall 1973;
  Aho, Lam, Sethi, Ullman, "Compilers: Principles, Techniques, and Tools",
  Ch. 9), `OUT[n] = GEN[n] ∪ (IN[n] − KILL[n])` run to a fixpoint over the
  CFG, replacing an earlier per-definition graph search (a BFS from each
  definition that stopped at any redefinition of the same variable). The two
  aren't known to disagree on any case this tool has hit — reachability
  with a blocking node is a path-order-independent graph property, so the
  older formulation was likely already sound here — but the fixpoint
  formulation is the citable textbook one, is unit-testable through its
  GEN/KILL sets directly rather than only end-to-end, and computes all
  variables' reaching definitions in one shared pass instead of one BFS per
  definition. A def-use edge is emitted for every reaching definition of a
  variable actually read at a use. One domain-specific exception: `X =
  $result` immediately after a suspend call that itself defined `X` is
  treated as transparent (neither GEN nor KILL) for `X`, since it's the same
  value arriving via the continuation, not a real overwrite.

See `CpgDataTest` for hand-verified fixtures covering straight-line def-use,
kill-at-redefinition, a diamond join where definitions on both branches must
reach the merge, a loop back edge (the DDG fixpoint case that a single
forward pass can't get right), if/else and loop CDG shapes (including a
loop header's well-known self-dependency on its own condition), and the
AST/BINDS_TO layer's shape and exactness.

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

### What the ablation actually measures

Raw taint-flow count is **not** a meaningful ablation signal for this tool,
and this was verified directly (not assumed): Kotlin's coroutine ABI always
emits a "didn't actually suspend" fast path as ordinary sequential bytecode
ahead of the `label`-dispatch switch — the compiler must handle the case
where none of a method's suspend calls actually suspend at runtime, so it
keeps that entire path (including any real `if`/`while` structure the user
wrote) flat and walkable without ever touching the dispatch machinery. A
reachability-only ("does a flow exist") analysis finds the same answer via
that fast path with or without reconstruction, for every suspend-chain shape
— straight-line, branches, or loops — which is why `totalTaintFlows` is
identical on `benchmark/` with and without reconstruction, and why that
being unchanged is expected, not a bug.

What reconstruction demonstrably changes is **how much of the method a
taint analyzer has to look at to explain the same flow**: `scripts/compare_ablation.py`
reports `methodStats[].statementCount` (CPG size) and `.chopSize` (the
minimal slice explaining a flow) per method from each `summary.json`. On the
methods reconstruction actually rewrites, both shrink substantially — e.g.
`singleHop` goes from 49 to 23 statements and a 6- to 4-statement chop,
`identityChain` from 70 to 42 statements and a 10- to 6-statement chop —
because label writes, spill-field reads/writes, the suspend-check `if`, and
the switch itself no longer have to be traversed or read to reach the same
conclusion. Methods reconstruction doesn't touch (general-case-unsupported,
or not a coroutine state machine at all) are identical on both sides, which
is why the script also reports a "reconstructed methods only" breakout
rather than just an aggregate over the whole benchmark suite.

## License

BSD 3-Clause — see [LICENSE](LICENSE). Copyright Marcio Aparecido de Godoi
Junior, Laboratório de Sistemas Computacionais (LSC), UNICAMP.
