# CPS Reconstruction — Project Assessment Summary

*(Written by Claude Fable 5.1, 2026-09-16; updated 2026-09-17 after the FlowDroid baseline run; updated 2026-09-18 with the LLVM direction)*

The assessment is written to `CLAUDE.md` as a new section, "Project assessment (2026-09-16)", grounded in a fresh test run and ablation run rather than the existing notes. Nothing was committed.

## State of reconstruction and unrolling: mechanism is good, evidence is thin

- The single `unrollGeneralCase` path is the right design. It walks the real CFG from the fresh-invocation entry and rewires around dispatch bookkeeping, which is sound because kotlinc emits one body copy and jumps into it at resume points. The `finally` decline is correct and documented against bytecode.
- Live run confirms the documented numbers. 36 benchmark files, 25 flows found with and without reconstruction, 36 state machines reconstruct and 1 declines, reconstructed-method CPG size drops 78.8 percent, chop size identical.
- It is research-artifact grade, not tool grade. Detection relies on string matching in three places, the dispatch switch is picked as "first switch in the body", and real-world shapes like user `when` statements, `suspendCancellableCoroutine`, and post-R8 bytecode are untested. Only one compiler version was ever checked.
- The load-bearing walk has zero unit tests. Half the existing tests cover a function that no longer gates anything.
- The splice retirement and the five spill-slot benchmarks are still uncommitted in the working tree.

## ISSTA feasibility: not submittable now, plausible in roughly 3 to 4 months of focused work

The only measured effect is that the tool's own graph gets smaller. The claims that would carry a paper, that external tools report false positives on raw coroutine bytecode and that reconstruction generalizes to real projects, are both still predictions. The write-up proposes four research questions (real-project coverage rate, CodeQL/FlowDroid precision, analysis cost, mechanical correctness check) and names SCAM, SOAP, and tool-demo tracks as fallbacks.

## Update 2026-09-17: the FlowDroid baseline is done and positive

The Soot port of the walk plus an in-process FlowDroid harness (`scripts/run_baseline.sh`) now exist and run over the whole suite. FlowDroid reports 20 flows with reconstruction vs 23 without; the 3 extra are exactly the `spill-slot/01`–`03` `NO-FLOW` benchmarks, no real flow is lost, and the mechanism (resumption via the `invokeSuspend` entry point through an unknown `label`) was confirmed by a diagnostic that removes that entry point. Reconstructed bodies are also emitted as `.class` files, so the CodeQL half of the experiment is unblocked. Three harness bugs were found and fixed on the way (Soot releasing bodies after class emission, FlowDroid's constant propagation deleting a source-holding branch, and Kotlin taint-wrapper rules keyed on the `StringsKt` facade instead of the declaring class). All uncommitted. Details and exact numbers: `CLAUDE.md`, "FlowDroid external baseline — DONE".

## Work remaining, ranked (original 2026-09-16 text)

~~The gate on everything is getting reconstructed bodies into class files.~~ Done, see above. Next: CodeQL on the emitted classes, then a mechanical quotient check between original and reconstructed CFGs to replace hand-verification, then a JVM-only real-world corpus, then threats to validity and a second compiler version.

## Rust transfer: the technique ports, the problem mostly does not

rustc's coroutine lowering has the same three ingredients (discriminant, per-variant saved locals, `SwitchInt` at resume entry into a single body copy), and MIR is an easier target than JVM bytecode. But for the local crate rustc already exposes pre-lowering MIR, so reconstruction only matters for tools consuming `optimized_mir` from dependency crates, LLVM IR, or binaries. That niche is real. Two points strengthen the case there: Rust deliberately overlaps saved locals by liveness, so the spill-slot conflation is the normal case, and MIR marks cleanup blocks syntactically, which is easier than JVM exception tables. No Rust toolchain is installed on this machine, so none of this was checked against MIR dumps. The half-day verification step is spelled out in `CLAUDE.md`. C++20 coroutines are flagged as a case where the fast-path argument does not hold after LLVM's split.

## Update 2026-09-18: direction change — journal, LLVM, general languages

The professor's steer: the project has better potential as a journal paper (TOSEM/TSE) than an ISSTA submission, but it has to be done on LLVM, for languages in general. Assessment, grounded in a probe run the same day (`docs/llvm-probe/`), not just argued:

**"General case on LLVM" is three lowering shapes, not one port — and that taxonomy is the journal contribution.**

| Shape | Producers | Structure | Status |
|---|---|---|---|
| A. Frontend-lowered switch | rustc (MIR `StateTransform`), Kotlin/Native (Kotlin's own CPS lowering, LLVM backend); same shape as kotlinc/JVM and C# `MoveNext` off-LLVM | discriminant `switch` at entry, single body copy, resume cases jump in | **Verified for rustc at -O0** (`rustc 1.98.1`, two-await `async fn`, one await under an `if`): dispatch switch on `gep self,4`, states 1/2 are the "resumed after completion" dead paths, 3/4 jump into the one body copy, fast path from state 0 complete, unwind/Poisoned paths syntactically marked. The existing walk ports ~1:1. |
| B. LLVM `CoroSplit`, switch ABI | C++20 `co_await` | ramp truncated at first suspend + `f.resume` clone with index switch + destroy/cleanup clones | Unverified (no `clang` on this machine; `pacman -S clang llvm` gives 22.1). Port = ramp fast path ++ resume clone: a two-function stitch, days not weeks. |
| C. LLVM `CoroSplit`, retcon/async ABI | Swift `async` | no switch: one function per suspend point chained via continuation function pointers, heap async context | Unverified and genuinely new — literal CPS in machine code; reconstruction is an actual un-CPS pass (inlining a continuation chain). Hardest, and the part a JVM-only paper can't say. |

Kotlin/Native is the bridge: same source, same benchmark suite, two backends — shows the technique is about the lowering, not the platform. Four frontends, one algorithm parameterized by shape, one quotient check as the correctness argument across all of them.

**Carries over:** the fast-path argument and the decline criterion (it becomes the definition of shape A vs. B/C); the evaluation design (explanation-size ablation, the `spill-slot/` family — stronger on LLVM since `CoroFrame` and rustc's layout pack disjoint-lifetime saved values into one slot by design; external-tool false-positive experiment; quotient check); the benchmark suite as a spec to re-express per language; the SCAM 2022 gap and the FlowDroid result as the JVM instance.

**Thrown away:** SootUp/Soot, the CPG, `TaintSlicer`, `FieldAliasTracker`. Don't rebuild them. The walk becomes an `opt` pass (C++, `-load-pass-plugin`) whose output is legal IR; measurement clients are Phasar (IFDS/IDE on LLVM — the FlowDroid analog) and SVF. "Emit reconstructed code" is free.

**Two risks a journal reviewer raises first:**
1. Optimized IR. Verified: at -O2 LLVM's `JumpThreading` clones each await site into a fast-path copy and a switch-reached copy (`chain_poll_O2.ll`). The walk still finds a complete body in the example, but the "exactly once" quotient property fails. Need a stated input point: pre-optimization IR (or a pipeline position before jump threading), or merge clones by `!dbg`. Applies to all three shapes.
2. Evaluation maturity — a journal wants more real subjects, not fewer. Upside: LLVM corpora are cheap (tokio/hyper, cppcoro/folly, swift-nio, Kotlin/Native stdlib) with no Android/R8 problem.

**First week, in order:**
1. `sudo pacman -S clang llvm`; C++ probe (`clang++ -std=c++20 -S -emit-llvm`, before/after `-O1`) — confirm the ramp/resume split and where the index switch lives. Shape C via `swiftc` (AUR) or LLVM's own `llvm/test/Transforms/Coroutines/` retcon inputs.
2. Kotlin/Native toolchain; compile `benchmark/straight-line/01` to LLVM IR and check shape A holds — cheapest strong result, the answer is already known for the same source on the JVM.
3. `opt` pass skeleton + shape-A walk on `docs/llvm-probe/b.rs` at -O0. A night.
4. Phasar over raw vs. reconstructed IR for a Rust port of `spill-slot/01` — first LLVM-side RQ2 data point.

Steps 1–2 are half a day each and are the CLAUDE.md "before believing any of the above" checks.

Also done 2026-09-18 (committed as `1c49c7a`): `docs/dot/` now holds the raw vs. reconstructed `.dot` for every benchmark (156 files, `scripts/collect_dots.sh`); `ReconstructionCli` exports for any method with a source or sink, so `NO-FLOW` benchmarks are visualized too; the stale `docs/exception-handling/dot/` was removed.

---

Full detail (brittleness list, ranked work items with time estimates, per-language mechanics) is in `CLAUDE.md` under "Project assessment (2026-09-16)".
