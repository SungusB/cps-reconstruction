# LLVM-IR probe: does the kotlinc state-machine shape exist on LLVM?

Checked 2026-09-18 with `rustc 1.98.1` (stable); no `clang` on the machine,
so only the Rust→LLVM route is verified here — C++20 coroutines through
`CoroSplit` are still unverified (see `CLAUDE.md`, "Future directions").

    rustc -C opt-level=0 --emit=llvm-ir -C debuginfo=0 --edition 2021 b.rs
    rustc -C opt-level=2 --emit=llvm-ir -C debuginfo=0 --edition 2021 b.rs

`b.rs` is the two-await `chain` with one await under an `if`; `make()` boxes
it as `dyn Future` so the generated `poll` is emitted standalone instead of
being inlined into a driver. `chain_poll_O0.ll` / `chain_poll_O2.ll` are the
`poll` bodies (`@_RNC…5chain0B3_`) cut out of the two outputs.

Findings, read directly off the IR:

- **-O0 is kotlinc's shape.** Entry block loads the discriminant
  (`gep self, 4`), `switch i32` with 0 → fresh body (`bb28`), 1/2 →
  "resumed after completion / panic" dead paths (≙ Kotlin's
  `IllegalStateException` path), 3/4 → resume blocks that jump *into* the
  single body copy (`bb24 → bb4`, `bb25 → bb15`). Each await is
  `invoke poll → br i1 (Pending) → {store i8 N (label write), ret} | {load
  result, continue}`; the fast path from state 0 reaches every user
  statement. Unwinding is explicit (`invoke … unwind label %cleanup`,
  `store i8 2` = Poisoned), so the bookkeeping to elide is syntactically
  marked rather than an exception-table range.
- **-O2 breaks the single-copy property.** `JumpThreading` duplicated each
  await site into a fast-path copy (`bb5.thread`, `bb15.thread`, reached
  from entry) and a switch-reached copy (`bb5`, `bb15`, each with its own
  nested switch on the inner future's state). The fast-path walk from state
  0 still covers the whole user body in this example, but the "every
  statement appears exactly once" quotient check would fail, so a port must
  either analyze pre-optimization IR or accept duplicated copies as the
  same source statement (via `!dbg`). Same situation as post-R8/D8 on
  Android.

## 2026-10-19 additions (clang 23.1.3, go 1.27.2, rustc 1.98.1)

- `chain.cpp` — C++20 two-`co_await` coroutine, one under an `if`.
  `clang++ -std=c++20 -O{0,2} -S -emit-llvm`. At -O0 (and at -O2 with a
  runtime-decided `await_ready`) the ramp `_Z5chainb` holds every user call
  (`source`, both `fetch`, both `sink`) — suspend points become
  `switch i8 -1` to the return block. With `await_ready(){return false;}`
  at -O2 the ramp stops after the first `fetch`; the rest is only in
  `_Z5chainb.resume`.
- `probe.go` — goroutine+channel lowers to plain runtime calls; range-over-func
  lifts the loop body into a closure `rangeFlow-range1`.
  `go build -gcflags='-S -l'`.
- `spill_scopes.rs` — Rust port of `benchmark/spill-slot/01`.
  `-Zprint-type-sizes` puts `secret` (Suspend0) and `safe` (Suspend1) at
  the same offset; MIR keeps them as distinct variant fields.
