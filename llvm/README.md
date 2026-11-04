# LLVM port: coroutine reconstruction for Rust and C++20

This directory ports the Kotlin/JVM reconstruction
(`SuspendChainReconstructor.unrollGeneralCase`, see the top-level
`README.md`) to LLVM IR, so the same idea covers two more languages whose
compilers lower coroutines to state machines: **Rust** (`async fn` /
`async {}`) and **C++20** (`co_await`). It is one `opt` pass plugin, a port of
the benchmark suite, and a harness that runs Joern on raw vs. reconstructed
code.

```
llvm/
  pass/          CoroReconstruct.cpp — the pass (opt -load-pass-plugin)
  test/          lit tests (real rustc/clang output, FileCheck)
  benchmark/     rust/ and cpp/ ports of benchmark/*.kt, same ground truth
  joern/         Joern taint queries + link stubs for the binary frontend
  scripts/       build_llvm_suite.py, run_joern.py, run_joern_kotlin.py,
                 compare_joern.py
```

## Two lowering shapes, one pass

Both compilers produce what kotlinc produces: a body that can be entered at
the start or at any resume point, with a dispatch on a state value. What
differs is where the dispatch lives.

| | Rust (rustc, MIR `StateTransform`) | C++20 (clang, LLVM `CoroSplit`, switch ABI) |
|---|---|---|
| state | discriminant byte inside the coroutine | index field in the heap frame |
| dispatch | `switch` on the discriminant at the entry of the poll function: 0 = Unresumed, 1/2 = Returned/Panicked (→ `panic_const_async_fn_resumed*`), k≥3 = resume point k | `.resume` clone: `switch` on the index; the ramp `F` runs the body from the start |
| suspend point | `br i1 %pending, %S, %ready` where `S` stores k into the discriminant and returns `Pending` | in the ramp, `llvm.coro.suspend` folded to `switch i8 -1, label %ret [0: resumed, 1: destroy]` |
| body copies | one (resume cases jump *into* it — same as kotlinc) | two at -O0 (ramp and `.resume` each hold the whole body) |
| reconstruction | entry `switch` → `br case0`; prune every edge into a suspend arm; drop unreachable blocks | fold each `switch i8 -1` to its case-0 (resumed) successor; stub `.resume` |

Verified against real compiler output (rustc 1.98.1, clang 23.1.3), not
assumed: `docs/llvm-probe/` and the lit tests compile real sources.

**Input point: -O0, after coroutine lowering.** rustc always lowers in MIR,
so its -O0 IR is post-lowering; clang's -O0 pipeline still runs `CoroSplit`.
After optimization the shapes break in two documented ways, and the pass
declines rather than guesses:
- Rust -O2: `JumpThreading` duplicates each await into a fast-path copy and a
  switch-reached copy (`docs/llvm-probe/chain_poll_O2.ll`).
- C++ -O2: the folded suspends are simplified away, and with an
  always-suspending awaiter (`await_ready() { return false; }`, the lazy
  `task<T>` idiom) the ramp stops at the first `co_await` and the rest lives
  only in `.resume` — that case would need a ramp-prefix + resume-from-index
  stitch, not a walk.

The C++ fast path is a property of the *awaiter*, not of the ABI: kotlinc
always emits the "didn't suspend" path because any suspend call may return
a value directly; C++ has it only when `await_ready()` can be true. At -O0
every `await_ready()` is an opaque call, so the path is always there.

## The quotient check (built in, runs before any mutation)

The Kotlin side's soundness evidence is hand-checking `javap`. Here the pass
checks, per coroutine, before touching it:

1. every block that becomes unreachable is bookkeeping — no user call other
   than the resumed-after-completion panic;
2. Rust: every resume target, followed through bookkeeping-only blocks,
   meets a block that is still reachable — i.e. "dispatch jumps into the
   fast-path copy" is checked, not assumed;
3. C++: every function the `.resume` clone calls is also called by the
   reconstructed ramp — i.e. the ramp really holds the whole body;
4. every Pending decision keeps a Ready successor.

If any check fails the coroutine is reported `declined` with the reason and
left byte-for-byte as compiled. The output is always `verifyFunction`-clean
and lowers with `llc`. `llvm/test/rust_quotient_arm.rs` and
`cxx_quotient_resume.cpp` inject a violation and check that it is caught.

The reconstructed code is an **analysis view**: a reconstructed poll never
returns `Pending`, and a stubbed `.resume` does nothing. Same contract as
the Kotlin side's emitted `.class` files.

## Results (2026-10-19)

All numbers from `compare_joern.py` on a live run; every mechanism below was
traced to a concrete path and, where possible, confirmed by a counterfactual
(a one-change experiment that makes the effect disappear).

**Reconstruction coverage.** Every coroutine in both ports reconstructs
(23 Rust files, 28 C++ files; `build/llvm/suite.json`), output verifies and
lowers with `llc`. Optimized C++ declines with a reason (lit test).

**Joern, binary frontend (`ghidra2cpg`), raw vs. reconstructed objects.**
Ground truth is program semantics (`compare_joern.py: semantic_truth`).

| | raw | reconstructed | source frontend (reference) |
|---|---|---|---|
| C++ (28 files) | TP 6, FP 1, FN 13 | TP 6, FP 1, FN 13 | `c2cpg`: TP 7, FN 12 |
| Rust (23 files) | TP 2, FP 0, FN 12 | TP 4, FP 3, FN 10 | `rust2cpg`: TP 14, FN 0 |
| graph size (AST nodes, user methods) | C++ 90 330 / Rust 22 051 | C++ −19.2% / Rust +10.4% | |

What each difference is, verified:

- **Rust raw: the coroutine body is invisible.** rustc gives the dispatch
  `switch` an `unreachable` default, so LLVM emits the jump table without a
  bounds check (`movzbl; lea table; movslq; add; jmp *%rax`, no `cmp/ja`);
  Ghidra's switch recovery needs a bound, gives up, and never disassembles
  the body behind it (a 153-instruction poll becomes a 10-instruction
  function in the CPG). All 12 coroutine FLOW cases are FN for that reason,
  and the raw "TN"s on coroutine benchmarks are blindness, not precision.
  After reconstruction the entry is a direct jump and the whole body is
  there (hence +10.4% nodes).
- **Rust reconstructed: 2 flows recovered** (`loops/01`, `loops/02`); the
  rest stay FN because the value travels through the awaited future's
  memory and comes back in `RDX`, which ghidra2cpg's register-text dataflow
  does not follow.
- **Rust reconstructed: 3 FPs (`spill-slot/01–03`) are a ghidra2cpg CFG
  artifact, not a reconstruction effect.** ghidra2cpg links every
  instruction to the next one *by address* (`FunctionPass.handleBody`), so a
  call to a noreturn function (`panic_const_add_overflow`, from `secret + 1`
  in debug Rust) falls through into the next block in layout, skipping the
  store that overwrites rustc's shared saved-local slot (both `secret` and
  `safe` live at the same frame offset — verified with
  `-Zprint-type-sizes`). Counterfactual: the same benchmark with
  `wrapping_add` (no panic block) reports 0 flows. The shared slot is
  real; the path to it is not.
- **C++: verdicts identical, alerts duplicated on raw.** The raw object has
  the body twice (ramp and `.resume`), and every flow in it is reported
  twice — once per copy (verified by address: the second report's method is
  `_Z…v.resume`). Reconstructed: once. `straight-line/03` 12 → 6 distinct
  reports, `01` 2 → 1, etc.
- **C++ `spill-slot/05` FP (both modes)**: ghidra2cpg decomposes
  `MOV [RAX],RCX` so that storing a tainted value *through* `RAX` taints
  `RAX` itself; -O0 stack traffic then carries it to `sink`. Unrelated to
  coroutines.
- **`c2cpg` does not parse C++20 `co_await`**: `a = co_await f(x)` becomes
  `a = co_await` (an identifier) plus a disconnected `f(x)` call, so source-
  level Joern misses most C++ coroutine flows. `rust2cpg` handles `.await`
  and finds all 14.

**Joern on the Kotlin suite (`jimple2cpg`), raw vs. reconstructed classes**
— the second bytecode-level tool after FlowDroid. 36 packages, 28 real
flows by semantic truth. "fixed" = the same run with one CFG edge fixed in
`jimple2cpg` (`joern/jimple2cpg-cfg-entry.patch`), as a counterfactual.

| Joern | raw TP / FP / FN | reconstructed TP / FP / FN |
|---|---|---|
| upstream (f7e618c62) | 8 / 0 / 20 | **24 / 0 / 4** |
| fixed jimple2cpg | 25 / 1 / 3 | 25 / **0** / 3 |
| AST nodes (user methods) | 10 522 | 7 622 (−27.6%) |

- **Upstream: reconstruction recovers 16 real flows and adds no FP.** The
  cause is a jimple2cpg defect that kotlinc's lowering triggers at *every*
  suspension point: the CFG edge for a branch goes to the target
  statement's root node, skipping its operands, so they receive no reaching
  definition. kotlinc's fast path is exactly
  `if (r != COROUTINE_SUSPENDED) goto L; L: a = (String) r`. Reproduced in
  8 lines of plain Java (`joern/repro/Tgt.java`), and confirmed causally:
  with the one-edge fix, raw bytecode recovers the same flows.
- **With Joern fixed, reconstruction is recall-neutral (25 = 25, no flow
  lost in any package) and removes one FP** (`spillslot/sharedresultslot`).
  Path: kotlinc keeps one `COROUTINE_SUSPENDED` sentinel local and compares
  it with every suspend call's result; Joern has no semantics for
  `<operator>.notEquals` and lets the operands taint each other, so the
  tainted `identity(secret)` result taints the sentinel at the first check
  and the sentinel taints `tick()`'s result at the second. Coroutine-ABI
  specific; reconstruction removes the checks.
- **An artifact defect this exposed, now fixed:** the reconstructed classes
  were emitted without a LocalVariableTable. jimple2cpg then binds a
  constructor's receiver as `l0` while naming parameter 0 `this`, and loses
  every flow through a captured lambda field (`concurrency/*` went TP → FN
  on reconstructed classes; `joern/repro/Ctor.java` reproduces it with
  `javac -g:none`). `BaselineCli` now keeps original names and writes the
  table; FlowDroid's numbers are unchanged (20 vs 23, the same 3
  `spill-slot` packages), and all 77 emitted classes pass `-Xverify:all`.

Joern has no usable LLVM-IR frontend: its `llvm` language shells out to the
external llvm2cpg, which predates opaque pointers (LLVM 15+). Hence the
binary route above.

## Running it

```sh
cmake -S llvm/pass -B build/llvm-pass -G Ninja -DCMAKE_CXX_COMPILER=clang++
ninja -C build/llvm-pass
lit -v llvm/test                              # 6 tests
python3 llvm/scripts/build_llvm_suite.py      # build/llvm/<lang>/<cat>/<name>/
python3 llvm/scripts/run_joern.py --jobs 4    # needs Joern staged in ~/joern
python3 llvm/scripts/run_joern_kotlin.py      # needs run_ablation + run_baseline first
python3 llvm/scripts/compare_joern.py
```

Joern notes: build with `sbt stage` (sbt 2.0.10); `ghidra2cpg` needs JDK 21+
(Ghidra's class files are version 65), `jimple2cpg` needs a JDK whose own
class files Soot's ASM can read (17 works, 27 does not), so the scripts set
`JAVA_HOME` per frontend.
