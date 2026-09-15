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

Every benchmark file declares its own `package benchmark.<category>.<name>`
so the whole suite can be compiled together in one `kotlinc` invocation
without top-level-function name collisions (several files reuse names like
`identity` or `bar` for their suspend helpers).

## Categories

| Directory | Tests | Expected |
|---|---|---|
| `straight-line/` | Suspend chains with no control flow between suspension points | `FLOW` |
| `if-else/` | Suspend calls inside a conditional | `UNSUPPORTED` |
| `loops/` | Suspend calls inside `while`/`for` | `UNSUPPORTED` |
| `try-catch/` | Suspend calls inside a protected (trap-covered) region | `UNSUPPORTED` |
| `nested-lambda/` | Closure-captured taint into a nested suspend lambda | `FLOW` or `NO-FLOW` (mixed — see individual files) |
| `control/` | No coroutines at all — regression check on ordinary taint tracking | `FLOW` or `NO-FLOW` |
