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

This project reconstructs the original sequential control flow the lowering
obscures: for a **straight-line suspend chain** (one or more suspend calls
with no other control flow between them) by identifying and stripping the
compiler-generated dispatch bookkeeping (label writes, spill field
read/write pairs, the switch statement itself, and gotos that target other
dispatch cases) from each case block and splicing the remaining statements
back into one linear method body; and, via a general case that walks the
method's real control-flow graph instead of text-slicing case blocks, for a
suspend call inside a branch, a loop, or a single (non-nested) `try`/`catch`
as well. See **Scope** below for the precise, verified boundary.

## Scope

**Current scope:** every coroutine state machine goes through one
reconstruction (`SuspendChainReconstructor.unrollGeneralCase`): a plain
straight-line suspend chain, a suspend call inside `if`/`else` or a
`while`/`for` loop, and a single (non-nested) `try`/`catch` — including one
that doesn't even wrap the suspend call itself. It works by walking the
method's real CFG from its `label == 0` entry point (the "didn't actually
suspend" fast path Kotlin's coroutine ABI always emits as ordinary
sequential bytecode ahead of the dispatch switch) and eliding dispatch
bookkeeping — label reads/writes, `L$n`/`I$n` spill and reload traffic, the
suspended-check `if`s, `throwOnFailure`/`nullOutSpilledVariable` — by
rewiring around it, rather than text-slicing case blocks. So it isn't
pattern-matching specific shapes; it's a general transformation over
whatever real control flow that fast path contains, exceptional edges
included. See `SuspendChainReconstructor.kt`'s `unrollGeneralCase` doc
comment for the verified structural argument this relies on, and
`docs/exception-handling/README.md` for the try/catch case specifically
(mechanism, literature, evaluation).

An earlier straight-line-only path that spliced the switch's case blocks
end to end was retired after `benchmark/spill-slot/01_run_block_scopes.kt`
showed it produced a false positive: it left the continuation's spill
fields in the reconstructed body, and kotlinc reuses one spill field for
different variables in disjoint scopes, so a tainted spill at one suspension
point reached an unrelated reload at the next. The fast-path walk never
emits spill fields, so that hazard can't arise — which is also the first
result in this project where reconstruction changes an analysis *outcome*
rather than only the size of the graph.

**Explicitly out of scope:** a suspend call inside `finally` — verified
directly against compiled bytecode (not assumed) to compile to a nested,
self-referential exception-table entry rather than the single-level trap the
general case's exceptional-edge handling preserves; see
`docs/exception-handling/README.md` §1.3 for the exact bytecode. Rather than
guess at an unsound partial transformation for any case it can't verify
sound, the tool detects these and declines to transform them, leaving the
method body untouched and flagging it as unsupported
(`general-case-unsupported`). See `SuspendChainReconstructor.kt`'s
`findUnsupportedControlFlow` and `unrollGeneralCase` for the detection logic
that draws this line.

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
docs/            deeper write-ups for specific reconstruction cases (currently:
                 exception-handling/, the try/catch case — mechanism, literature,
                 evaluation, and the .dot files backing it)
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
  A statement's exceptional successors (its traps, if any) are tracked
  separately as `EdgeKind.EXCEPTIONAL` — visible in DOT exports (orange,
  labeled with the exception type) but deliberately kept out of
  `cfgSuccessors`/`cfgPredecessors`, so they don't affect the CDG/DDG
  computation below. That's a known incompleteness, not just a design
  choice: it means a caught statement's handler currently has no CDG-modeled
  control dependence on it. See `docs/exception-handling/README.md` §4-5.
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
— straight-line, branches, loops, or a suspend call under a single
try/catch (see `docs/exception-handling/README.md` §7 for that case
specifically) — which is why `totalTaintFlows` is identical on `benchmark/`
with and without reconstruction, and why that being unchanged is expected,
not a bug.

What reconstruction demonstrably changes is **how much of the method a
taint analyzer has to look at to explain the same flow**: `scripts/compare_ablation.py`
reports `methodStats[].statementCount` (CPG size) per method from each
`summary.json`. On the methods reconstruction actually rewrites, this
shrinks substantially — e.g. `singleHop` goes from 49 to 23 statements,
`identityChain` from 70 to 42 — because label writes, spill-field
reads/writes, the suspend-check `if`, and the switch itself no longer have
to be traversed or read to reach the same conclusion. Methods reconstruction
doesn't touch (general-case-unsupported, or not a coroutine state machine at
all) are identical on both sides, which is why the script also reports a
"reconstructed methods only" breakout rather than just an aggregate over the
whole benchmark suite.

`methodStats[].chopSize` (the minimal slice explaining a flow) is also
reported, but is **not** currently good ablation evidence: it used to shrink
alongside statement count, but that shrinkage was largely a side effect of a
now-fixed `TaintSlicer` bug (it treated control-dependence edges as
taint-propagating, which inflated the *unreconstructed* chop with spurious
paths through the same suspend-check `if`s reconstruction strips — see
`CLAUDE.md` for the verified false-positive benchmarks that found this).
Post-fix, chop size is close to identical with and without reconstruction
across the current suite — an expected result, the same way flow-count
parity is, not a regression. Statement count is the metric that actually
demonstrates reconstruction's value.

### External baseline: FlowDroid on raw vs. reconstructed bytecode

The ablation above is measured with this project's own (deliberately
simple) analyzer. The claim that matters to anyone else — that a
production taint analysis is *wrong* on raw coroutine bytecode in a way
reconstruction fixes — is tested separately by `scripts/run_baseline.sh`,
which runs FlowDroid 2.14.1 in-process on the same benchmark classes, with
and without a classic-Soot port of the reconstruction
(`baseline/SootSuspendChainReconstructor.kt`) applied to the bodies it
analyzes. Sources and sinks are the same method patterns the in-house
analyzer uses. One Soot scene per benchmark package, FlowDroid's default
`EasyTaintWrapper` extended with rules for the `kotlin.text` string helpers
(keyed on the classes that *declare* them, not the `StringsKt` facade — a
rule keyed on the facade never matches).

Result on the current 36-file suite: **FlowDroid reports 20 flows with
reconstruction and 23 without.** No flow is found only with reconstruction;
the 3 found only without are exactly `benchmark/spill-slot/01`–`03`, whose
ground truth is `NO-FLOW`. Every `FLOW` benchmark reports the same
source→sink pair in both modes. The mechanism was verified rather than
inferred: FlowDroid reaches the state machine's resumption path through the
continuation class's `invokeSuspend` entry point, and with the `label`
unknown it carries a tainted `L$0` spill from one suspension to the reload
of a different variable from the same field at the next; drop that entry
point (`baseline --no-invokesuspend-entry`, a diagnostic) and the false
positives disappear on raw bytecode. On those packages FlowDroid's IFDS
work also drops 40–70× (e.g. 4990 → 74 edge propagations). No wall-time
claim is made: on the small packages the time is scene-setup overhead, and
on the two closure-capture packages it is dominated by FlowDroid's
path-reconstruction phase hitting its timeout in both modes (the IFDS solve
itself takes ~15 s either way).

The harness additionally writes the reconstructed bodies out as `.class`
files (`--emit-classes`, via Soot's ASM backend), which is the input any
other bytecode-consuming tool (FindSecBugs, Joern, Doop) needs. CodeQL is
not such a tool — its Java/Kotlin extractor works from source — so the
relevant CodeQL experiment is the opposite one: on the Kotlin *source* it
should show no spill-slot false positive, which would place the imprecision
squarely in bytecode-level analysis. Two things it does
*not* show: `benchmark/concurrency/*` and `benchmark/flow/*` are 0/0 in both
modes because `kotlinx.coroutines` is kept out of the Soot scene, so
`async`/`await`/channels are opaque to FlowDroid regardless of
reconstruction; and per-package wall time is not a meaningful comparison
below a second (fixed scene-setup overhead dominates).

## Status, limitations, and open work

What is established, with the evidence behind it:

- **Reconstruction works on every shape in the suite except `finally`.**
  36 of 37 suspend state machines across 36 benchmark files reconstruct;
  the one decline is a suspend call inside `finally`, which kotlinc
  compiles to a self-referential trap the walk deliberately refuses
  (`docs/exception-handling/README.md`). Each shape was checked against
  `javap` before being trusted.
- **It removes real false positives from a production analysis.** FlowDroid
  on raw coroutine bytecode reports 3 flows that do not exist (spill-slot
  conflation, `benchmark/spill-slot/`); on reconstructed bytecode it does
  not, and it loses no true flow. The mechanism was confirmed by ablating
  the resumption entry point.
- **It shrinks what an analysis has to look at.** −78.8% statements on
  reconstructed methods for the in-house CPG; 40–70× fewer IFDS edge
  propagations for FlowDroid on the affected packages.

What is not established, in the order it matters:

1. **Generality.** Every subject is a hand-written single-method file, and
   only one compiler/target (`kotlinc 2.4.20`, bytecode 52) has been
   checked. How often real code produces the spill-slot shape, what
   fraction of real state machines reconstruct, and what new decline
   reasons appear are all unmeasured. Shapes known to be unexercised: a
   user `when` inside a suspend body, `suspendCoroutine` /
   `suspendCancellableCoroutine`, tail-call suspend functions, `inline` /
   `crossinline` suspend lambdas, `CancellationException` under
   try/catch, and anything post-R8/D8.
2. **Soundness of the walk** rests on per-benchmark hand-verification
   against `javap` plus an ablation. The branch/loop walk and bridge-`goto`
   synthesis have no unit tests, and the classic-Soot port has none at
   all. A mechanical check that each reconstruction is the
   bookkeeping-quotient of its original — every real statement kept once,
   every edge backed by an original path — would replace the hand-checking
   and has not been written.
3. **A second external tool.** Only FlowDroid has been run. The
   reconstructed `.class` files a second bytecode-level tool needs
   (FindSecBugs, Joern, Doop) are emitted, but no such comparison exists
   yet, and it has not been checked that the emitted classes verify.
4. **Cost.** No defensible wall-time result: the harness's per-package
   timings are dominated by scene setup on small inputs and by FlowDroid's
   path-builder timeout (identical in both modes) on large ones. Edge
   propagations are the only cost figure reported.
5. **What FlowDroid cannot see here, regardless of reconstruction.**
   `kotlinx.coroutines` is excluded from the Soot scene (its frontend
   crashes on some of the library's bodies), so `async`/`await`,
   `Channel`, and `Flow` are opaque and `benchmark/concurrency/*` and
   `benchmark/flow/*` report nothing in either mode. The taint-wrapper
   rules for Kotlin's stdlib cover 25 `kotlin.text` helpers; taint through
   any other stdlib helper is silently dropped in both modes — a rule that
   fails to match looks exactly like a true negative.
6. **The in-house analyzer** is a measurement client, not a competitor to
   FlowDroid: field writes have no kill (`FieldAliasTracker`), container-
   and callback-mediated flow is unmodeled, and coroutine-ABI detection is
   string-based. Its numbers should not be read as precision/recall of a
   taint tool.
7. **Exceptional edges are visible in the CPG but are not control
   dependencies** (Choi et al., PASTE 1999, is the relevant model), and the
   AST layer is exported to JSON only, not to the DOT views.

## License

BSD 3-Clause — see [LICENSE](LICENSE). Copyright Marcio Aparecido de Godoi
Junior, Laboratório de Sistemas Computacionais (LSC), UNICAMP.
