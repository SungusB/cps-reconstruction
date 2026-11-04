# Defects found while building and evaluating the artifact

Every defect below was found by running a benchmark and investigating a
result that did not match ground truth, or a run that did not finish. Each
entry states what was observed, the verified cause, the evidence (benchmark
or repro), what was done, and **what it did to the numbers the project
reports**. Nothing here is inferred from reading code alone; where a
mechanism is plausible but unverified, the entry says so.

Three groups:

- **A — this project's analysis and reconstruction.** What a reader needs to
  judge whether the reported results can be trusted.
- **B — third-party tools** (SootUp, Soot, FlowDroid, Joern, Ghidra). What a
  reader needs to judge comparisons against those tools, and what anyone
  building on them should know.
- **C — evaluation harness.** Defects that blocked or silently corrupted a run.

The cross-cutting lesson is in the last section: most of these defects were
**silent**. They produced a plausible number (a true negative, a flow-count
tie, a size reduction) rather than an error, and were only caught by a
benchmark written to exercise exactly that mechanism.

---

## A. This project's analysis and reconstruction

### A1. Reconstruction dropped parameter identity statements → lost real flows *(2026-10-19, fixed)*

- **Observed.** `benchmark/straight-line/04_sink_inside_suspending_callee.kt`
  (`expect: FLOW`: the secret is passed to a suspend callee that suspends,
  then prints its parameter) reported **0 flows with reconstruction, 1
  without**. This was the first flow found *only without* reconstruction, and
  it contradicted the project's stated claim that reconstruction never loses a
  flow.
- **Cause.** `SuspendChainReconstructor.unrollGeneralCase` walks from the
  `label == 0` entry of the dispatch switch. The `this`/parameter identity
  statements (`x := @parameter0`) sit in the preamble *before* the switch, so
  the walk never reached them. Every reconstructed body had **no parameter
  locals at all** (`Body.getParameterLocals()` empty), so every
  inter-procedural summary computed on it (`InterProceduralAnalyzer`:
  `paramToSinks`, `paramToReturn`, `paramToFields`) silently lost its
  parameters. The classic-Soot port (`SootSuspendChainReconstructor`) keeps the
  preamble and was never affected, so FlowDroid and Joern results were not
  affected either.
- **Why it went unnoticed.** The only positive control for "sink inside a
  callee" (`straight-line/03`) has a callee with no suspension point. That
  callee is not a state machine and is never reconstructed.
- **Fix.** The identity statements are kept as a straight-line prefix before
  the entry.
- **Effect on reported numbers.** Hand-written suite: 26/26 flows in both
  modes (was 25/25 before `04` existed). Reconstructed-method statement count
  is 303 vs 1301 (−76.7%), previously reported as 276 vs 1301 (−78.8%). The
  old figure was slightly flattering: the "without" side always counted the
  identity statements and the "with" side did not.
- **It also masked a false positive.**
  `benchmark/control/09_tainted_branch_in_suspend_callee_sink.kt` reported a
  false positive only *without* reconstruction (A2's mechanism). With
  reconstruction it looked clean, but only because the callee's summary had
  no parameters.

### A2. Inter-procedural summaries propagated taint over control dependence *(2026-10-19, fixed)*

- **Observed.** `benchmark/control/07_tainted_branch_in_callee_sink.kt` (the
  callee only *branches* on the secret, and both arms print a literal; no
  explicit flow) reported a flow in both modes. This is the inter-procedural
  twin of the intra-procedural bug fixed earlier (A4).
- **Cause.** `InterProceduralAnalyzer.computeSummary` built its forward
  adjacency from `ddgEdges` **and** `cdgEdges`. "The sink executes under a
  branch on the parameter" therefore became `paramToSinks`, which the caller
  reports as an explicit flow. The earlier fix removed CDG edges from
  `TaintSlicer.slice()` only. The project notes recorded that the other two
  passes were checked and are DDG-only, which is true of their own adjacency,
  but the summaries they consume were not checked.
- **Fix.** DDG edges only, matching `TaintSlicer.slice()`.
- **Effect.** No previously reported flow changed. No earlier benchmark
  had a callee whose sink was control-dependent on a parameter.

### A3. Parameter uses found by substring match on rendered Jimple *(2026-10-19, fixed)*

- **Observed.** `benchmark/control/08_short_param_name_callee_sink.kt` (the
  helper ignores its parameter `p` and prints a literal; no flow of any kind)
  reported a flow in both modes.
- **Cause.** `collectParamToSinksAndReturn` found a parameter's start
  statements with `stmtLabels[i].contains(paramName)`. SootUp keeps Kotlin's
  local names, so `p` occurs in `virtualinvoke $stack1.<java.io.PrintStream:
  void println(...)>("constant")` (via `PrintStream` and `println`), which made
  the sink itself a start point. Any parameter whose name is a substring of
  another label behaves the same way.
- **Fix.** Start points are statements that define or use the parameter
  `Local` itself.
- **Effect.** No previously reported flow changed.

### A4. The intra-procedural slicer propagated taint over control dependence *(2026-09-15, fixed)*

- **Observed.** `benchmark/flow/02`, `03`, then the coroutine-free
  `benchmark/control/04_tainted_guard_clause_unrelated_sink.kt` and
  `05_tainted_branch_no_early_exit.kt`, reported flows whose "tainted"
  statement was a string literal.
- **Cause.** `TaintSlicer.slice()` used DDG and CDG edges together, treating an
  implicit flow (Denning 1976) as an explicit one.
- **Effect on reported numbers, the important part.** The project's headline
  "chop size" reduction (e.g. `try-catch`: 11 vs 15, −26.7%) **was mostly this
  bug**. The unreconstructed chop was inflated by spurious paths through the
  very suspend-check `if`s that reconstruction removes. After the fix, chop
  size is identical in both modes (104/104). **A defect in the measurement
  client manufactured an ablation result.** Statement count was unaffected and
  is the metric now reported.

### A5. Straight-line "splice" reconstruction kept spill traffic → false positive *(2026-09-16, path retired)*

- **Observed.** `benchmark/spill-slot/01_run_block_scopes.kt` (`NO-FLOW`)
  reported a flow with reconstruction only.
- **Cause.** kotlinc spills every in-scope local, in slot order, to `L$0,
  L$1, …`, so two variables in disjoint scopes share a spill field (verified
  against `javap`). The old splice path concatenated the switch's case blocks
  but never removed spill writes and reloads: its filter matched the substring
  `"fieldput"`, which SootUp's Jimple never renders. That made a tainted
  `L$0 = secret` at suspension 1 reach `safe = L$0` at suspension 2.
- **Fix.** The splice path was removed. `unrollGeneralCase` is the only
  reconstruction path.

### A6. Combined flow passes double-counted a flow *(2026-09-15, fixed)*

- **Observed.** The first reconstructed body with two real source-to-sink
  paths (suspend call under `try`/`catch`) reported 2 flows instead of 1.
- **Cause.** `ReconstructionCli.sliceAndExport` combined three flow-finding
  passes with `addAll`. Latent until a body had more than one path.
- **Fix.** Deduplicated by `(sourceIdx, sinkIdx, category)`. An earlier
  "26/26" flow figure had been inflated by this bug.

### A7. Captured-variable flows could never be reported *(2026-09-16, fixed)*

- **Observed.** `benchmark/nested-lambda/01`, `03` (`FLOW`, a source captured
  into a suspend lambda) reported 0 flows.
- **Cause.** `TaintSlicer.findCapturedFieldFlows` kept only `new Closure(...)`
  sites that were in the source's DDG forward slice. A `new` expression has no
  operands, so it is never in a forward slice. The mechanism was non-functional
  from the day it was written.
- **Fix.** All `new` sites are scanned; the existing argument-taint check does
  the filtering.

### A8. The walk built fall-through cycles → SootUp iteration never terminated *(2026-10-19, fixed)*

- **Observed.** The in-house analyzer did not finish on the generated suite
  (`benchmark-gen/`, 121 programs): runs were left for 25+ minutes in the
  inter-procedural phase. Stack samples (`jstack`, every 20 s) put 11 of 12
  samples in SootUp's `Body.getParameterLocals()` → `BlockGraphIterator`.
- **Isolated.** Timing that call on all 1,747 *raw* bodies (including
  9,683-statement ones) took **22 ms in total**. On the 186 *reconstructed*
  bodies, **6 never finished** under a 10 s cap, the smallest with 22 nodes
  (`spillslot/043_sequential_while`, two sequential `while` loops). The other
  180 took ~0 ms. So this was non-termination, not slowness.
- **Cause, read off the dumped graph.** SootUp lays a body out along its
  fall-through edges: each statement may have at most one fall-through
  predecessor, and those edges must be acyclic. The walk's bookkeeping
  (`hasPlainPredecessor`) recorded only plain edges out of non-branching
  statements, but successor 0 of a `JIfStmt` is a fall-through too. In
  `043` the second loop's back-edge was therefore wired as a plain edge into a
  head whose other predecessor was a branch edge, closing the cycle
  `head → if (succ 0) → body → head`. SootUp's `build()` accepted the graph,
  and its block iterator then never ended.
- **Fix.** `if`-successor-0 edges count as fall-through. Any fall-through edge
  that would give its target a second fall-through predecessor, or close a
  cycle, goes through a synthesized `goto`. After the fix all 186 + 38
  reconstructed bodies iterate in ~1 ms total
  (`repro/sootup/IterateReconstructedBodies.kt`). The hand-written suite's
  numbers are byte-identical before and after; none of its shapes triggered it.
- **Not affected:** the Soot port, which lays out its unit chain explicitly
  and bridges every fall-through it cannot place.
- **Why it matters.** It was invisible on the 41 hand-written benchmarks
  and showed up only on generated programs with more varied loop
  placement. That is an argument for the generated suite.

### A11. Field-alias edges cost roughly cubic time in method size *(2026-10-19, fixed)*

- **Observed.** On the generated suite the in-house analyzer took 100 s with
  reconstruction and 715 s without. All stack samples of the slow run were in
  `FieldAliasTracker.findFieldDdgEdges` / `addLocalAliasEdges` → `isReachable`.
- **Cause.** A fresh CFG BFS for every (write, read) pair of a field and every
  (local copy, field read) pair, plus `edge !in edges` on a `List`. Raw
  generated bodies reach ~9,700 statements and hundreds of spill-field
  accesses, which is exactly the traffic reconstruction removes.
- **Fix.** One memoized BFS per source statement and a set for
  deduplication. `summary.json` is identical before and after on both suites,
  and all 94 hand-written CPG exports are equal as graphs (label-keyed,
  order-independent). Raw generated-suite run: 715 s → 220 s.
- **Not a reconstruction result.** The with/without wall-time gap was an
  artifact of this implementation, and no wall-time claim is made from it.

### A12. The Soot port's reconstructed bodies skip Soot's `jb` normalization *(2026-10-19, diagnosed; fix available, not yet the default)*

- **Observed, two symptoms.** (1) 1 of 438 emitted generated-suite classes
  fails `java -Xverify:all` (`randomexc/c121`, `VerifyError: Bad local
  variable type`). The trap `257–284 → 404` starts at `ldc "k11"`, before
  `astore 10`, and the handler reads local 10. (2) FlowDroid loses 2 real flows
  with reconstruction (B19).
- **Cause.** Bodies loaded from bytecode go through Soot's `jb` pack, including
  the trap tightener (ranges start at the first instruction that can throw)
  and copy propagation. The port builds its reconstructed bodies in memory and
  never runs those passes. For (1): Soot splits `v2` into separate locals
  (its exceptional CFG does not treat the non-throwing `v2 = "k11"` as a throw
  point, so the handler's read is reached only by that definition), but the
  emitted trap still starts *at* that statement. kotlinc's own trap also
  starts there, but kotlinc keeps `v2` in one slot that is assigned before the
  `try`, so it verifies. The program is semantically fine (the `ldc` cannot
  throw); the verifier is conservative.
- **Verified fix.** `baseline --normalize` (diagnostic) runs `TrapTightener`,
  `CopyPropagator` and `DeadAssignmentEliminator` on reconstructed bodies.
  `c121`'s classes then verify (6/6) with its verdict unchanged, and both B19
  flows are found. **Not yet the default:** making it so changes the FlowDroid
  and Joern inputs, so every FlowDroid/Joern number would need to be rerun.
  Until then the reported FlowDroid figures include the 2 B19 false negatives,
  and "all emitted classes verify" holds for the hand-written suite (83/83)
  but not for the generated one (437/438).

### A9. Field writes have no kill *(known, open)*

`FieldAliasTracker.findFieldDdgEdges` links every write of field F to every
reachable read of F, with no strong updates. This is what let A5's false
positive through. Generated `field/067_field_strong_update` is now a false positive in both
modes for exactly this reason. Two related imprecisions surfaced on the same
suite: the tracker ignores the base object, so every `Ref$ObjectRef.element`
(the box for each captured `var`) is one "field" (generated `random/107`);
and a method call is treated as redefining its receiver, so `h.setF0(secret)`
taints `h` (generated `field/066`). It is the
imprecision the `spill-slot/` family predicts for field-based external tools
on raw bytecode, which FlowDroid confirmed (B7).

### A10. A summary is silently empty if `getParameterLocals()` throws *(latent, open)*

`InterProceduralAnalyzer.computeSummary` wraps `body.parameterLocals` in
`catch (e: Exception) { return summary }`. SootUp builds that list with
`List.add(paramIndex, local)`, which throws `IndexOutOfBoundsException`
whenever a higher-index identity statement comes first in iteration order.
Checked on every raw and reconstructed body in both suites: it never throws
today. If it ever does, the result is an empty summary with no diagnostic.

---

## B. Third-party tools

### SootUp 2.0.0

- **B1. `MutableJavaView.replaceClass()` is not reliably visible to later
  `getMethod()` calls** (2026-09-15). The trigger varies run to run, which is
  consistent with a hash-order-dependent cache. Worked around by never
  re-querying the view for a reconstructed body
  (`getReconstructedBodies()` first). Clean repro exists; not yet filed
  upstream.
- **B2. `MutableBlockStmtGraph` result depends on edge insertion order**
  (2026-10-19, minimized). Putting a plain edge into a statement whose
  outgoing branch edge already exists drops that branch edge. The same two
  edges (`nop → goto`, `goto → return`) give a valid graph in one order and
  `JGoto must have '1' outgoing flow but has '0'` in the other. A four-edge
  loop inserted in all 24 orders: **20 rejected, 4 accepted**. Repro:
  `repro/sootup/EdgeOrder.kt`, `EdgeOrderPermutations.kt` (`run.sh`). Hit
  twice by the reconstruction (the identity prefix in A1, the bridge `goto`s
  in A8). Worked around by always putting a statement's incoming fall-through
  edge before its outgoing ones.
- **B3. A body with a fall-through cycle passes `build()` validation and then
  its iterator does not terminate** (2026-10-19). Seen on 6 reconstructed
  bodies (A8). Not minimized: a 4-node fall-through cycle, when accepted,
  iterates fine. The input was invalid (our defect A8), but SootUp neither
  rejected it nor terminated.

### Soot 4.6 / FlowDroid 2.14.1

- **B4. Soot's frontend drops a trap whose protected range cannot throw.**
  This silently turned `try-catch/02_suspend_in_finally.kt` into a trap-free
  method in Soot's view. The benchmark now calls `plain(…)` in the try body.
- **B5. Soot's ASM frontend crashes on some `kotlinx.coroutines` bodies,** so
  the library is phantom in the FlowDroid scene and `async`/`await`/
  `Channel`/`Flow` are opaque (0/0 in both modes for `concurrency/*`,
  `flow/*`).
- **B6. `PackManager.writeOutput()` releases every body it writes** unless
  `set_no_writeout_body_releasing(true)`. FlowDroid then failed with `No
  method source set` right after class emission.
- **B7. FlowDroid's default constant propagation deleted the branch holding
  the source** (`ifelse/01`, `spill-slot/02` reported "No sources found"). Fixed
  with `CodeEliminationMode.NoCodeElimination`.
- **B8. `EasyTaintWrapper` rules keyed on the `kotlin.text.StringsKt` facade
  never match.** Soot resolves a call to the declaring class deep in the
  facade's superclass chain. `secret.trim()` silently dropped taint. Fixed by
  keying every rule on its declaring class. **A missing rule looks exactly
  like a true negative.**
- **B9. FlowDroid's path-reconstruction phase runs even under `NoPaths`** and,
  on the two `nested-lambda` packages, runs until FlowDroid's low-memory
  watcher stops it, in both modes. **Correction 2026-10-19:** the project
  notes said the 300 s timeout stopped it. The log says "Path reconstruction
  terminated due to low memory" after a completed 15–22 s IFDS solve. Either
  way, wall-time differences there measure how long a stuck path builder ran
  before being stopped. No wall-time claim is made anywhere.
- **B18. FlowDroid reports results from a solve stopped for low memory as if
  it had completed.** On the generated suite, 12 raw-bytecode packages hit the
  6 GB memory watcher ("Running out of memory, solvers terminated") and **4
  of those solves really were cut short** (`InfoflowResults.getTerminationState()
  == 2`: `random/107`, `110`, `112`, `117`). The log warning alone does not
  tell: 8 of the 12 completed (state 0) despite it. The harness now records
  `terminationState` per package. **No reconstructed solve was cut short (0 of
  115).** On those packages the raw solve propagated 30–45M IFDS edges, against
  13K–3.6M after reconstruction, and needed ~5.9 GB of heap against
  0.15–0.8 GB (FlowDroid's own "maximum memory consumption" figure).
- **B19. FlowDroid does not connect a heap write and a heap read made through
  two local copies of the same object when both copies are taken before the
  write.** Found as the only 2 flows lost *with* reconstruction on the
  generated suite (`random/094_s_01`, `098_s_05`, `FLOW`). In both, a boxed
  captured `var` (`Ref$ObjectRef`) is written through one local copy and read
  through another. Verified by experiment: running Soot's `CopyPropagator` on
  the reconstructed bodies (`baseline --normalize`, diagnostic) unifies
  the copies and FlowDroid finds both flows. On raw bytecode the flow survives
  through the spill-field route. Bodies loaded from bytecode get copy
  propagation from Soot's `jb` pack; the port's in-memory reconstructed bodies
  skip it. So this is a FlowDroid alias limitation that the harness exposes by
  handing it un-normalized bodies; see A12 for the shared cause and the
  verified fix, which is not yet the default.

### Joern (master `f7e618c62`)

- **B10. jimple2cpg: a branch edge goes to the target statement's root,
  skipping its operands.** kotlinc's `if (r != COROUTINE_SUSPENDED) goto L;
  L: a = (String) r` triggers it at every suspension point. This is why
  upstream Joern finds 9 vs 25 of 29 real Kotlin flows (raw vs reconstructed),
  and 3 vs 59 of 64 on the generated suite:
  **the gain is reconstruction sidestepping a Joern defect, not reconstruction
  adding information.** Reproduced in plain Java, confirmed by patching Joern
  (`llvm/joern/jimple2cpg-cfg-entry.patch`), after which both modes find the
  same flows. Repro: `llvm/joern/repro/`.
- **B11. jimple2cpg loses constructor-mediated flows when a class has no
  LocalVariableTable.** The emitted classes originally had none
  (`concurrency/*` went TP → FN on reconstructed). Fixed on our side
  (`jb use-original-names`, `write_local_annotations`).
- **B12. No `<operator>.equals` / `<operator>.notEquals` semantics:** taint
  hops through the shared `COROUTINE_SUSPENDED` sentinel local (the one false
  positive on raw code with patched Joern, `spill-slot/05`).
- **B13. ghidra2cpg links instructions by address**, so a call to a noreturn
  function (`panic_const_add_overflow`) falls through into the next block in
  layout, skipping a kill. This causes 3 Rust false positives on reconstructed
  binaries; the `wrapping_add` counterfactual removes them.
- **B14. Ghidra cannot recover rustc's dispatch jump table** (an `unreachable`
  default means there's no bounds check), so raw Rust coroutine bodies are
  **invisible** at binary level. A raw "TN" there is blindness, not precision.
- **B15. ghidra2cpg decomposes `MOV [RAX],RCX` so that storing a tainted value
  through `RAX` taints `RAX`** (C++ `spill-slot/05` false positive, both modes).
- **B16. c2cpg cannot parse `co_await`;** the C++ source-level column is
  mostly false negatives for that reason.
- **B17. ghidra2cpg does not follow a value through an awaited future's
  memory and back in `RDX`.** Most Rust/C++ binary-level flows are missed in
  both modes. On the generated suite, where nearly every flow crosses an
  `.await` of `identity()`, binary-level recall is near zero (C++ 4/62, Rust
  0 → 7/58).

Details for B10–B17: `llvm/README.md`.

---

## C. Evaluation harness

- **C1. Joern refused a relative manifest path** (`os.Path` requires an
  absolute one). `run_joern.py` with a relative `LLVM_OUT` (as used for the
  generated suite) failed after parsing every CPG. Fixed: every env-provided
  path in `llvm/scripts/*.py` is `.resolve()`d.
- **C2. `compare_joern.py` hard-coded `build/llvm` and `build/joern-kotlin`,**
  so it could not tabulate another suite, and it crashed with a `KeyError`
  when the patched-Joern results predated a newly added benchmark. Both fixed
  (env overrides; missing patched rows shown as `-`).
- **C3. A leftover sbt JVM (3 GB, from building Joern) got the FlowDroid JVM
  OOM-killed.** Kill build JVMs before evaluation runs.
- **C4. A run that is merely slow and a run that never terminates look the
  same.** A8 was first taken for a cut-off session. Per-method time caps and
  periodic stack samples (`IterateReconstructedBodies.kt`, `jstack`) told
  them apart within minutes.
- **C5. One FlowDroid JVM for a whole suite runs out of heap.**
  `run_baseline.sh` analyzes every package in one JVM (`-Xmx6g`); on the
  115-package generated suite it died with `OutOfMemoryError` at package 97,
  because memory grows across the per-package Soot scenes. Fixed for large
  suites by `scripts/run_baseline_per_package.sh` (one fresh JVM per package,
  merged summaries).
- **C6. Parallel JVMs on a 15 GB machine get OOM-killed by the kernel.**
  Three per-package FlowDroid JVMs plus the in-house analyzer: **13 processes
  killed** (`journalctl -k | grep Killed`), all 12 raw-bytecode FlowDroid runs
  of the largest generated programs plus the analyzer. The reconstructed
  runs of the same packages survived the same load. The symptom is exit 137
  and a log that just stops. Rerun one at a time, they all completed (see
  B18).
- **C7. Exported CPGs are not deterministic run to run.** SootUp's generated
  local names (`#lN`, `$stackN`) and statement order vary: 1 of 47 exports
  differed byte-wise between two runs of the same build, while
  `summary.json` was identical. FlowDroid's IFDS edge counts also vary by about
  1% between runs (multi-threaded solver). Compare exports order-independently
  and report IFDS counts as approximate.

---

## What this means for the paper

1. **Silent failure is the dominant failure mode.** A1, A3, A7, B4, B5,
   B8, B14, B17, B18 and B19 all produced a plausible negative or a partial
   result, not an error. Every one
   was found by a benchmark written to exercise exactly that mechanism, never
   by aggregate numbers. The evaluation therefore needs a positive control per
   mechanism (sink in a reconstructed callee, captured variable, each stdlib
   taint-wrapper rule, …), not just more programs.
2. **Defects in the measurement client can manufacture ablation results.**
   A4 produced a −26.7% chop-size "improvement" that was entirely a bug in the
   analyzer used to measure it.
3. **Defects in comparison tools can masquerade as reconstruction effects.**
   B10 turned a Joern CFG bug into a 9 → 25 recall "gain". B14 made raw
   binaries look precise. Each external-tool difference needs its mechanism
   verified (patch the tool, ablate an entry point, run a counterfactual)
   before it is attributed to reconstruction.
4. **Cost is real but must be measured carefully.** On the generated suite
   FlowDroid's raw-bytecode solves needed ~5.9 GB and 30–45M IFDS edges on
   the largest programs, 4 of them were cut short by memory, and none of the
   reconstructed ones were (B18). But the in-house analyzer's 7× wall-time gap
   was an implementation artifact (A11), and FlowDroid's wall time is
   dominated by path building on other packages (B9). Report edge counts,
   heap and termination state, not wall time.
5. **Reconstruction itself had two defects that the hand-written suite could
   not reveal** (A1, A8): one lost real flows, one hung the analysis. Both
   were found only by new benchmark shapes (a suspending callee with a sink;
   generated loop placements). That supports a generated suite with
   independently validated ground truth (`benchmark-gen/`, checked
   dynamically by `scripts/gen_validate.py`), and a mechanical quotient check
   over every reconstructed body, still open for the JVM walk.
