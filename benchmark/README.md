# Benchmark suite

Hand-written Kotlin files exercising each category from Phase 1/3. Every
file's ground-truth annotation below is a **proposal** — none of it is
authoritative until confirmed by hand, per the project's standing rule that
ground truth is never self-certified by the tool or its author.

## Ground-truth annotation convention

Each file carries a header comment block:

```kotlin
// GROUND TRUTH (proposed — pending confirmation)
// expect: FLOW | UNSUPPORTED | NO-FLOW
// note: <one line on why>
```

- **`FLOW`** — a source-to-sink flow should be detected. Source and sink call
  sites are marked inline with `// SOURCE` / `// SINK` comments so the
  expected flow is unambiguous even with multiple candidate statements.
- **`UNSUPPORTED`** — the file's suspend function contains real control flow
  between suspension points (conditional, loop, try/catch) that
  `SuspendChainReconstructor` must decline to unroll. The correct tool
  behavior is to leave the method untouched and report it via
  `general-case-unsupported` — *not* to report a wrong or missing flow, and
  *not* to crash.
- **`NO-FLOW`** — no source-to-sink flow exists (negative control); the tool
  must not report one.
- **`NO-FLOW (tool limitation, not a real absence of taint)`** — a rarer,
  deliberately distinct variant: taint genuinely does reach the sink at
  runtime, but through a mechanism this tool's architecture can't detect at
  all (see `benchmark/flow/*.kt`, where the taint is carried by a
  `Channel`/`Flow` container or a collector callback rather than a direct
  value or def-use chain). Reported the same as ordinary `NO-FLOW` today,
  but the ground-truth comment must say why, so it isn't mistaken for a
  genuine negative control.

**A note on exact numbers cited in individual files' header comments:**
several files' notes cite specific "chop size" figures from a real
`scripts/run_ablation.sh` run at the time they were written (e.g. "10→6
chop size"). A `TaintSlicer` bug fixed later in the same overall session
(it treated CDG edges as taint-propagating — see `CLAUDE.md`) changed chop
sizes across the whole suite, generally toward chop-size parity
with/without reconstruction. Those specific chop-size numbers are stale as
a result and shouldn't be trusted without re-running the harness;
**statement-count** numbers cited alongside them are unaffected by that fix
and remain accurate. This wasn't worth a mechanical sweep through every
file to fix a number that was only ever meant to be illustrative — re-run
`scripts/run_ablation.sh` for current figures instead of trusting a
hardcoded one.

Every benchmark file declares its own `package benchmark.<category>.<name>`
so the whole suite can be compiled together in one `kotlinc` invocation
without top-level-function name collisions (several files reuse names like
`identity` or `bar` for their suspend helpers).

`benchmark/concurrency/*.kt` and `benchmark/flow/*.kt` additionally need
`kotlinx-coroutines-core` on `kotlinc`'s classpath (they're the only files
in the suite that import `kotlinx.coroutines`). `scripts/run_ablation.sh`
resolves this automatically via the `printBenchmarkClasspath` Gradle task —
no separate setup needed to run the suite.

## Categories

| Directory | Tests | Expected |
|---|---|---|
| `straight-line/` | Suspend chains with no control flow between suspension points | `FLOW` |
| `if-else/` | Suspend calls inside a conditional | `FLOW` (reconstructed) |
| `loops/` | Suspend calls inside `while`/`for` | `FLOW` (reconstructed) |
| `try-catch/` | Suspend calls inside a protected (trap-covered) region | `FLOW` or `UNSUPPORTED` (mixed — a single-level try/catch reconstructs, including multiple independent blocks, multiple catch clauses, and nesting inside a branch/loop; `finally`'s nested trap still doesn't, see individual files) |
| `nested-lambda/` | Closure-captured taint into a nested suspend lambda | `FLOW` or `NO-FLOW` (mixed — see individual files) |
| `concurrency/` | `kotlinx.coroutines` builders: `async`/`await`, `coroutineScope`, `supervisorScope`, `withContext` | `FLOW` (reconstructed) — see individual files for why (each is a single ordinary suspend call from the caller's own state machine's perspective) |
| `flow/` | `Flow`/`Channel`: `flow{}`/`.collect{}`, `Channel.send`/`.receive`, `for (x in channel)` | `NO-FLOW (tool limitation, not a real absence of taint)` — reconstruction itself succeeds cleanly on all 3 (including the suspend-in-loop-condition shape in `03_channel_for_loop.kt`); the taint is container-mediated and this tool has no model for that at all, see individual files and `CLAUDE.md` |
| `spill-slot/` | Kotlin reuses one continuation spill field (`L$n`) for different variables at different suspension points (disjoint scopes: `run{}` blocks, sibling `if`s, sequential loops) and the single `$result` local for every suspension's value — a tainted and an untainted variable share a field, and the sink reloads from it | `NO-FLOW` (`04` is the `FLOW` positive control). Slot layout verified against `javap` per file. `01` caught a real false positive in the now-retired straight-line splice path — see its header |
| `control/` | No coroutines at all — regression check on ordinary taint tracking, plus two adversarial cases (`04`, `05`) that found and confirmed a general (non-coroutine) `TaintSlicer` false-positive bug, since fixed — see `CLAUDE.md` | `FLOW` or `NO-FLOW` |
