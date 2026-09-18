# Reconstructing suspend calls under try/catch

This document covers one previously out-of-scope case in
`SuspendChainReconstructor`: a suspend call protected by a `try`/`catch`.
It was fully declined before this work (`benchmark/try-catch/*` all expected
`UNSUPPORTED`); it is now reconstructed for the case that actually occurs in
practice — a single, non-nested protected region — and still honestly
declined for the one shape verified to need a different argument: a suspend
call inside `finally`. See `SuspendChainReconstructor.kt`'s `unrollGeneralCase`
doc comment for the implementation-level version of this; this document is
the fuller write-up: the mechanism, the literature it connects to, the exact
scope boundary, the tests, and the evaluation numbers.

## 1. The mechanism, verified against compiled bytecode

Nothing below is inferred from the Kotlin language spec or assumed from the
existing `unrollGeneralCase` argument — it's read directly off `javap -v`
output for the three `benchmark/try-catch/*.kt` files, compiled with
`kotlinc 2.4.20` targeting JVM bytecode 52 (Java 8), the same way this
project's other structural claims about coroutine codegen have been
verified (see `CLAUDE.md`'s standing conventions).

### 1.1 A single try/catch around a suspend call (`01_suspend_in_try.kt`)

```kotlin
suspend fun suspendInTry(): String {
    val secret = System.getenv("SECRET") // SOURCE
    var result = "default"
    try {
        result = identity(secret)
    } catch (e: Exception) {
        result = "error"
    }
    println(result) // SINK
    return result
}
```

The compiler emits **two** exception-table entries for this method, both
targeting the same handler:

```
Exception table:
   from    to  target type
     102   137   179   Class java/lang/Exception   <- case 0 (fast path)
     164   176   179   Class java/lang/Exception   <- case 1 (resumed path)
```

The first entry protects case 0's copy of the try body — `checkNotNull`,
the spill writes, and the `invokestatic identity(...)` call itself (offset
134, inside `[102,137)`). The second protects case 1's copy — just the
`ResultKt.throwOnFailure($result)` intrinsic call the compiler inserts to
rethrow a failed resumption, plus the checkcast that follows. Both land on
the same handler at offset 179 (`astore_3; ldc "error"; astore_2`), and both
paths converge at the same offset 172/183 once the try/catch is behind them
— confirming, at the bytecode level, the general case's existing argument
that the resumed path reconverges onto the exact same downstream code as
the fast path.

This is exactly the shape `unrollGeneralCase` already walks: it starts at
case 0's entry and never crosses into case 1 (reached only through the
`label`-dispatch switch), so only the **first** exception-table entry is
ever relevant to the reconstructed body. The second is bookkeeping for a
code path the general-case walk doesn't visit at all, and needs no special
handling — it simply isn't reachable from the walk's entry point.

### 1.2 A try/catch that doesn't wrap the suspend call (`03_suspend_after_catch.kt`)

```kotlin
suspend fun suspendAfterCatch(): String {
    val secret = System.getenv("SECRET") // SOURCE
    var parsed = secret
    try {
        parsed = secret.trim()
    } catch (e: Exception) {
        parsed = "error"
    }
    val result = identity(parsed)
    println(result) // SINK
    return result
}
```

One exception-table entry (`101 to 117 -> 120, Class java.lang.Exception`),
protecting only the `secret.trim()` call — an ordinary, non-suspending
method call entirely unrelated to the suspend call that follows it. Before
this work, `findUnsupportedControlFlow` declined the *whole case block* the
moment it saw any trap-covered statement, regardless of whether that
statement had anything to do with suspension. This is the more general bug
that fix incidentally corrects: a try/catch anywhere in a suspend function,
touching a suspend call or not, no longer forces a decline by itself.

### 1.3 A suspend call inside `finally` (`02_suspend_in_finally.kt`) — still declined

```kotlin
suspend fun suspendInFinally(): String {
    val secret = System.getenv("SECRET") // SOURCE
    var result = "default"
    try {
        result = "unchanged"
    } finally {
        result = identity(secret)
    }
    println(result) // SINK
    return result
}
```

```
Exception table:
   from    to  target type
     106   110   185   any
     185   186   185   any
```

The first entry is ordinary `finally` compilation (JVM bytecode has no
`jsr`/`ret` on modern targets, so `finally` is duplicated at every exit and
also reachable via a catch-`any` handler): it protects only the try body
(`result = "unchanged"`) and targets the handler that runs the finally
logic — which is where the suspend call actually lives.

The second entry is the one that matters: `[185,186) -> 185`, a **trap whose
protected range is the handler's own first instruction, targeting itself**.
This is Kotlin's way of making sure that if suspending *inside* the finally
logic and later resuming with a failure, the failure still routes to the
same finally-runner rather than escaping uncaught. Structurally, this means
the handler is not just "real code to walk into" the way `01`'s catch block
is — it is itself exceptionally protected, i.e. a nested/self-referential
handler scope.

`unrollGeneralCase`'s fix declines exactly this shape: before resolving an
exceptional edge's handler, it checks whether the handler itself has any
exceptional successors, and bails if so. Soundly reconstructing a nested
handler scope would need an argument about what "protected" means once the
protecting statement is itself inside the thing being protected — a
different and harder problem than single-level trap preservation, and one
this change deliberately doesn't attempt.

## 2. Related work

There is no existing work that targets exactly this problem — reconstructing
control flow (exceptional or otherwise) from a compiler-lowered Kotlin
coroutine state machine for static analysis — which is the gap this project
fills. The pieces below are the adjacent literature the design connects to,
cited for what they actually establish, not as prior solutions to this
problem.

- **The state machine is a defunctionalized continuation, with an explicit
  exception continuation.** Reynolds' defunctionalization ("Definitional
  Interpreters for Higher-Order Programming Languages", ACM Annual
  Conference 1972; reprinted with commentary in *Higher-Order and Symbolic
  Computation* 11(4), 1998) is the general technique of replacing closures
  with a tagged data type dispatched by a switch — which is precisely what
  `label` and the `tableswitch` are: `label` is the tag, each case block is
  a defunctionalized closure. Appel's *Compiling with Continuations*
  (Cambridge University Press, 1992) develops CPS conversion including
  exception handling as an explicit second continuation threaded alongside
  the normal one. The duplicated-trap structure in §1.1/§1.3 is exactly that
  second continuation made concrete as a JVM exception-table entry: the
  "exception continuation" for a suspended computation is "resume via the
  same catch handler," encoded as a trap on the resumption case block. This
  framing is why the fix could be designed *before* looking at bytecode —
  and why the finally case (§1.3) was worth checking specifically, since a
  self-referential exception continuation is a known harder case in that
  literature, not a Kotlin-specific oddity.
- **Modeling exceptional control flow as CFG edges is an established,
  imprecision-prone practice this project's `EdgeKind.EXCEPTIONAL` extends.**
  Soot's own IR — the direct ancestor of SootUp, which this project builds
  on (Vallée-Rai, Co, Gagnon, Hendren, Lam, Sundaresan, "Soot: A Java
  Bytecode Optimization Framework", CASCON 1999) — represents exception
  handlers via an `ExceptionalUnitGraph` alongside the ordinary
  `BriefUnitGraph`, i.e. as a *separate* graph view rather than fusing
  exceptional and normal edges into one CFG. `CpgData.exceptionalEdges` (see
  §4) follows the same separation for the same reason: naively merging
  exceptional edges into the CDG/DDG's normal-flow-only computation risks
  exactly the imprecision Choi, Grove, Hind, and Sarkar analyze directly in
  "Efficient and Precise Modeling of Exceptions for the Analysis of Java
  Programs" (ACM SIGPLAN-SIGSOFT Workshop on Program Analysis for Software
  Tools and Engineering, 1999) — treating every trapped statement as able to
  jump to its handler at any point (rather than only after statements that
  can actually throw the declared type) overapproximates control dependence
  enough to matter for a control-dependence-based slicer like this project's
  `TaintSlicer`. §5 states plainly that this project currently takes the
  cheaper, visualization-only side of that tradeoff, not the precise one.
- **Full generality (arbitrary nesting, `finally`, rethrow) is a known-hard
  structuring problem, not an oversight.** Cifuentes' decompilation thesis
  ("Reverse Compilation Techniques", PhD thesis, Queensland University of
  Technology, 1994) and Yakdan, Eschweiler, Gerhards-Padilla, and Smith's
  "No More Gotos: Decompilation Using Pattern-Independent Control-Flow
  Structuring and Semantics-Preserving Transformations" (NDSS 2015) both
  treat recovering structured exception handling from an unstructured
  control-flow graph as materially harder than recovering structured
  branches/loops — the latter paper's own evaluation section reports
  exception handling as one of the shapes its otherwise general approach
  still doesn't fully structure. That matches this project's own experience
  directly: branches and loops (already handled by `unrollGeneralCase`
  before this change) needed no new soundness argument beyond walking the
  real CFG, while a *nested* exception scope did.
- **JVM exception-table semantics** are normative, not academic, but worth
  citing precisely since the whole argument rests on them: *The Java
  Virtual Machine Specification*, §2.10 ("Exceptions") and §4.10.2.4 ("Exception
  Handlers") define a trap as a `(start_pc, end_pc, handler_pc, catch_type)`
  tuple with no nesting representation of its own — nesting or
  self-reference (as in §1.3) is expressed by two independent trap entries
  that happen to overlap, not by a first-class "nested trap" construct. That
  is exactly why detecting the finally shape is a check over the *handler's
  own* exceptional successors rather than something read off one trap entry
  directly.
- **Kotlin's own coroutine lowering design** — the `label`/spill-field/
  `ContinuationImpl` machinery this whole project reverses — is specified
  informally in Kotlin's KEEP-0002 coroutines proposal (the Kotlin Evolution
  and Enhancement Process repository, `Kotlin/KEEP` on GitHub) and
  implemented in `kotlin-stdlib`'s `kotlin.coroutines.jvm.internal` package
  (`BaseContinuationImpl`, `ResultKt.throwOnFailure`, `SpillingKt`). These
  aren't academic sources, but they're the authoritative ground truth for
  *why* the bytecode in §1 looks the way it does (e.g. `throwOnFailure` is
  the stdlib function actually named in the disassembly), and this project
  already relies on recognizing those exact symbols
  (`isBookkeepingCallName`, `isDispatchField`).

## 3. The fix

`SuspendChainReconstructor.unrollGeneralCase`'s BFS previously declined the
whole method the instant it visited any statement with a non-empty
`graph.exceptionalSuccessors(stmt)`. It now calls a new, separately
unit-tested function, `resolvePreservableExceptionalEdges`, at that point
instead:

1. For each `(exceptionType, handler)` pair on the statement's exceptional
   successors: if `handler` itself has any exceptional successors, decline
   the whole method (the §1.3 shape).
2. Otherwise, resolve `handler` through the same bookkeeping-skipping
   resolution (`resolveGeneralCaseTarget`) a normal successor already gets —
   this is what lets `01`'s handler (a real catch block) resolve immediately,
   and would skip past any purely-compiler-generated handler prologue if one
   existed.
3. If resolution succeeds, the edge is preserved: the resolved handler is
   queued for the same walk everything else gets (so its own statements and
   locals end up in the reconstructed body), and a `PendingTrap` is recorded.
4. After the walk finishes, every `PendingTrap` becomes a real
   `MutableStmtGraph.addExceptionalEdge` call on the freshly built body —
   the reconstructed body carries an honest trap, not a flattened
   approximation of one.

`unrollCoroutine`'s routing also changed: previously, a `findUnsupportedControlFlow`
reason containing `"try/catch"` skipped the general-case attempt entirely
(a deliberate hard decline, back when `unrollGeneralCase` had no way to
handle it). Every reason now gets the same general-case attempt branches
and loops already did — `unrollGeneralCase`'s own exceptional-edge logic is
what decides whether that attempt succeeds. (Since then the routing has been
simplified further: `unrollGeneralCase` is now the *only* reconstruction —
the straight-line splice path it used to fall back from was retired after
`benchmark/spill-slot/01_run_block_scopes.kt` showed it produced a false
positive, see the top-level `README.md`. `findUnsupportedControlFlow` is
kept only to label a decline with a reason. None of the try/catch numbers
in §7 are affected; those methods were already on the general-case walk.)

Both changes are in `src/main/kotlin/com/infinity/cps/reconstruction/reconstruct/SuspendChainReconstructor.kt`.

## 4. Making trap structure visible: `EdgeKind.EXCEPTIONAL`

Before this change, `CpgData` never looked at `StmtGraph.exceptionalSuccessors`
at all — a method's traps were invisible to the CPG and to every DOT export,
whether or not reconstruction touched them. `CpgData.exceptionalEdges` is a
new, separate edge set (kept out of `cfgSuccessors`/`cfgPredecessors`, and
therefore out of the CDG/DDG computation built over them — see the
imprecision tradeoff cited in §2) populated directly from
`stmtGraph.exceptionalSuccessors` for every statement. `CpgExporter` renders
it as an orange dashed edge labeled with the exception type name, alongside
the existing blue/CFG, green/DDG, dotted-red/CDG edges.

This is what makes `docs/dot/try-catch/with-reconstruction/_01_suspend_in_tryKt_suspendInTry.dot`
(§6) show the preserved trap explicitly: three orange edges, one per
non-bookkeeping statement inside the original protected range
(`checkNotNull(secret)`, the suspend call itself, and the checkcast/assign
immediately after it — all three were individually trap-covered in the raw
bytecode, confirming the reconstruction preserved the *entire* protected
region, not just the suspend call statement), all landing on the same
resolved catch-handler entry node.

## 5. Known limitation surfaced by this work: exceptional edges aren't control dependencies (yet)

The `01` DOT file (§6) shows zero CDG edges into the catch block. That's a
direct consequence of §4's design choice — `cfgSuccessors`/`cfgPredecessors`
only see normal flow, so the Cytron et al. dominance-frontier CDG
construction this project already uses (see top-level `README.md`) has no
notion that the catch block's statements are dependent on anything. The DDG
still gets the right answer for taint purposes here, because `result`'s two
definitions (the real value and the `"error"` literal) both reach the
sink via ordinary def-use edges regardless of *how* the catch block is
reached — but a query that actually needs "is statement X control-dependent
on possibly-throwing statement Y" would get a false negative today. Choi et
al. (§2) treat this precisely; adopting a similar treatment (making a
trapped statement control-dependent on its handler) is future work, not
something this change attempts.

## 6. Tests

- `SuspendChainReconstructorTest.ResolvePreservableExceptionalEdges` (new,
  4 tests) — unit tests for `resolvePreservableExceptionalEdges` against
  small hand-built `MutableBlockStmtGraph` fixtures, the same style
  `findUnsupportedControlFlow`'s existing tests already use:
  - a single-level resolvable trap is preserved;
  - a statement with no exceptional successors preserves nothing (and
    doesn't decline);
  - a handler that is itself exceptionally protected — the §1.3 shape — is
    declined;
  - a handler that resolves to nothing real is declined.
- `benchmark/try-catch/01_suspend_in_try.kt` and `03_suspend_after_catch.kt`
  — ground truth updated from `UNSUPPORTED` to `FLOW (reconstructed)`,
  confirmed against actual tool output (§7), not just updated on paper.
  `02_suspend_in_finally.kt` keeps `UNSUPPORTED`, with its note updated to
  state *why*, confirmed against the exact bytecode in §1.3.
- A separate, pre-existing bug this work exposed and fixed: `ReconstructionCli.sliceAndExport`
  combined `TaintSlicer.slice()`, `.findInterProceduralFlows()`, and
  `.findCapturedFieldFlows()` results with a plain `addAll` and no
  deduplication. Every reconstructed body before this change had exactly one
  path from any source to any sink, so the same `(source, sink)` pair could
  never be found twice by two different passes. Once `01`'s reconstructed
  body had two real paths (through the try body and through the catch
  handler) to the same sink, both passes started reporting the identical
  `(source, sink)` pair, and total flow counts for `01` (and, transitively,
  for any run's aggregate) would have been silently inflated. Fixed by
  deduplicating `slice.taintFlows` by `(sourceIdx, sinkIdx, category)` right
  after combining the three passes. Verified this doesn't change the
  *delta* the ablation reports for any pre-existing benchmark (all of which
  had one path per source/sink pair either way — see §7).

## 7. Evaluation

Ran via the project's existing ablation harness, unmodified:
`scripts/run_ablation.sh benchmark/try-catch` (compiles the three files,
runs the tool with and without `SuspendChainReconstructor`, diffs
`summary.json`). Full command output is reproducible; the numbers below are
copied directly from a real run, not computed by hand.

### Flow-count parity (expected to hold — see top-level `README.md`'s "What the ablation actually measures")

| Method | With | Without | Delta |
|---|---|---|---|
| `suspendInTry` (01) | 1 | 1 | +0 |
| `suspendInFinally` (02) | 1 | 1 | +0 |
| `suspendAfterCatch` (03) | 1 | 1 | +0 |

Total flows: 3 with, 3 without. This is the expected result, for the same
reason it holds for every other category: the "didn't suspend" fast path is
always a complete, walkable source-to-sink path regardless of reconstruction.
(Before the dedup fix in §6, `01` spuriously showed 2 vs. 1 — a duplicate
finding, not a second real flow; see §6 for why.)

### Explanation size — the metric that actually reflects reconstruction's effect

**Updated in a later session** (see `CLAUDE.md`): the `chopSize` numbers
originally reported here were measured before a `TaintSlicer` bug was found
and fixed — it treated control-dependence (CDG) edges as taint-propagating,
which inflated the *unreconstructed* chop with spurious paths through the
same suspend-check `if`s reconstruction strips. Re-run after the fix, chop
size is now identical with and without reconstruction for every method in
this category; `statementCount` is the metric that actually demonstrates
reconstruction's effect, both then and now. Numbers below are from the
post-fix run, reproduced the same way as originally (`scripts/run_ablation.sh
benchmark/try-catch`), not hand-computed:

| Method | Stmts (with) | Stmts (without) | Chop (with) | Chop (without) |
|---|---|---|---|---|
| `suspendInTry` (01) | 13 | 60 | 4 | 4 |
| `suspendInFinally` (02, still declined) | 88 | 88 | 4 | 4 |
| `suspendAfterCatch` (03) | 16 | 63 | 7 | 7 |

Reconstructed methods only (01 and 03; 02 is identical on both sides,
confirming it's genuinely untouched rather than partially/incorrectly
transformed):

- statements: 29 with reconstruction vs. 123 without (**−76.4%**)
- chop size: 11 with reconstruction vs. 11 without (**+0.0%**, expected
  post-fix — see the update note above, not a sign reconstruction has no
  effect on explanation size; statement count is the metric that shows that)

This statement-count reduction is directly comparable to the general-case
(branch/loop) numbers already in the top-level `README.md` (e.g. `singleHop`
49→23 statements) — the exceptional-edge case shrinks by roughly the same
order of magnitude as ordinary general-case reconstruction, which is what
"this is dispatch-bookkeeping removal, not a fundamentally different
transformation" should look like.

Running the harness against the whole `benchmark/` suite shows the fix
causing no regression to any previously-established *flow* number:
flow-count parity still holds everywhere. The exact "N of M methods
reconstruct" and total suite size have both changed since this section was
first written, as more benchmark categories were added in later sessions —
see `CLAUDE.md`'s "Current status" for the up-to-date figures rather than
treating the numbers above as anything beyond this one category's own
evaluation.

## 8. `.dot` files

The Graphviz output for this category lives in `docs/dot/try-catch/` (see
`docs/dot/README.md`), alongside every other benchmark category — it used to
be kept here, under `docs/exception-handling/dot/`, but that copy only
covered `01`–`03` and predated the `TaintSlicer` CDG fix (its `_slice.dot`
chops were the inflated ones), so it was regenerated and moved rather than
patched:

```
docs/dot/try-catch/
  with-reconstruction/
    _01_suspend_in_tryKt_suspendInTry.dot            CFG+DDG+CDG+exceptional edges, reconstructed body
    _01_suspend_in_tryKt_suspendInTry_slice.dot       taint slice highlighted on the same body
    _02_suspend_in_finallyKt_suspendInFinally.dot     identical to "without" — confirms untouched
    _02_suspend_in_finallyKt_suspendInFinally_slice.dot
    _03_suspend_after_catchKt_suspendAfterCatch.dot
    _03_suspend_after_catchKt_suspendAfterCatch_slice.dot
    _04_multiple_independent_try_catchKt_multipleTryCatchBlocks{,_slice}.dot
    _05_try_catch_in_loopKt_tryCatchInLoop{,_slice}.dot
    _06_multiple_catch_clausesKt_multipleCatchClauses{,_slice}.dot
  without-reconstruction/
    (same twelve files, raw un-reconstructed bodies — same names, same command with --no-reconstruct)
```

Open any `.dot` file with a Graphviz viewer (`dot -Tsvg <file> -o out.svg`,
or any online/editor Graphviz preview) to render it. To regenerate from
scratch: `bash scripts/run_ablation.sh && bash scripts/collect_dots.sh`
(the raw output is under `build/ablation/{with,without}-reconstruction/{cfg,slices}/`).

The single most legible comparison for the paper is
`with-reconstruction/_01_suspend_in_tryKt_suspendInTry.dot` (13 nodes, reads
as straight-line source with one real branch and the orange exceptional
edges going into the catch block) against
`without-reconstruction/_01_suspend_in_tryKt_suspendInTry.dot` (60 nodes of
label/spill/switch dispatch bookkeeping around the same logic).

## 9. Scope, precisely

**Handled:** a suspend call inside, or textually near, a single (non-nested)
`try`/`catch`, whether or not the protected region actually contains the
suspend call itself.

**Still declined, confirmed why (§1.3):** a suspend call inside `finally` —
verified to compile to a nested/self-referential trap, a materially
different and harder shape, not an unhandled instance of the same one.

**Also handled, confirmed by new benchmarks (not just structural argument):**
multiple independent (non-nested) try/catch blocks in the same method
(`benchmark/try-catch/04_multiple_independent_try_catch.kt` — 92→21
statements, 10→6 chop size without/with reconstruction); a try/catch nested
inside a loop body (`benchmark/try-catch/05_try_catch_in_loop.kt` — 76→20
statements, 10→5 chop size; the same code path also covers a try/catch
nested inside an `if` branch, since the general-case walk doesn't
special-case where a protected statement sits in the CFG); and multiple
`catch` clauses on the same protected range
(`benchmark/try-catch/06_multiple_catch_clauses.kt` — 64→17 statements, 6→4
chop size — `exceptionalSuccessors` returns a `Map<ClassType, Stmt>`, and
`resolvePreservableExceptionalEdges`'s per-type loop preserves every entry,
exactly as expected structurally). All three were re-verified via
`scripts/run_ablation.sh`, not assumed from the structural argument alone.
