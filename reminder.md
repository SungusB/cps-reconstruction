# CPS Reconstruction — Project Assessment Summary

*(Written by Claude Fable 5.1, 2026-09-16)*

The assessment is written to `CLAUDE.md` as a new section, "Project assessment (2026-09-16)", grounded in a fresh test run and ablation run rather than the existing notes. Nothing was committed.

## State of reconstruction and unrolling: mechanism is good, evidence is thin

- The single `unrollGeneralCase` path is the right design. It walks the real CFG from the fresh-invocation entry and rewires around dispatch bookkeeping, which is sound because kotlinc emits one body copy and jumps into it at resume points. The `finally` decline is correct and documented against bytecode.
- Live run confirms the documented numbers. 36 benchmark files, 25 flows found with and without reconstruction, 36 state machines reconstruct and 1 declines, reconstructed-method CPG size drops 78.8 percent, chop size identical.
- It is research-artifact grade, not tool grade. Detection relies on string matching in three places, the dispatch switch is picked as "first switch in the body", and real-world shapes like user `when` statements, `suspendCancellableCoroutine`, and post-R8 bytecode are untested. Only one compiler version was ever checked.
- The load-bearing walk has zero unit tests. Half the existing tests cover a function that no longer gates anything.
- The splice retirement and the five spill-slot benchmarks are still uncommitted in the working tree.

## ISSTA feasibility: not submittable now, plausible in roughly 3 to 4 months of focused work

The only measured effect is that the tool's own graph gets smaller. The claims that would carry a paper, that external tools report false positives on raw coroutine bytecode and that reconstruction generalizes to real projects, are both still predictions. The write-up proposes four research questions (real-project coverage rate, CodeQL/FlowDroid precision, analysis cost, mechanical correctness check) and names SCAM, SOAP, and tool-demo tracks as fallbacks.

## Work remaining, ranked

The gate on everything is getting reconstructed bodies into class files. Checked the SootUp 2.0.0 jars directly: there is no bytecode writer, only a Jimple printer, so the route is Jimple text into classic Soot, or a port of the walk to classic Soot. Next is a mechanical quotient check between original and reconstructed CFGs to replace hand-verification, then a JVM-only real-world corpus, then threats to validity and a second compiler version.

## Rust transfer: the technique ports, the problem mostly does not

rustc's coroutine lowering has the same three ingredients (discriminant, per-variant saved locals, `SwitchInt` at resume entry into a single body copy), and MIR is an easier target than JVM bytecode. But for the local crate rustc already exposes pre-lowering MIR, so reconstruction only matters for tools consuming `optimized_mir` from dependency crates, LLVM IR, or binaries. That niche is real. Two points strengthen the case there: Rust deliberately overlaps saved locals by liveness, so the spill-slot conflation is the normal case, and MIR marks cleanup blocks syntactically, which is easier than JVM exception tables. No Rust toolchain is installed on this machine, so none of this was checked against MIR dumps. The half-day verification step is spelled out in `CLAUDE.md`. C++20 coroutines are flagged as a case where the fast-path argument does not hold after LLVM's split.

---

Full detail (brittleness list, ranked work items with time estimates, per-language mechanics) is in `CLAUDE.md` under "Project assessment (2026-09-16)".
