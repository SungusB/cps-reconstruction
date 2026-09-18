# Graphviz output for every benchmark

One directory per benchmark category (mirroring `benchmark/`), each with a
`with-reconstruction/` and a `without-reconstruction/` half holding the
`ReconstructionCli` output for that category's methods:

- `<method>.dot` — the full CPG of the method: CFG (blue), DDG (green),
  CDG (dotted red) and exception-table traps (dashed orange) on the body the
  taint analysis actually saw — the reconstructed one on the `with` side, the
  raw kotlinc state machine on the `without` side.
- `<method>_slice.dot` — the same statements with the taint slice
  highlighted: sources red, sinks orange, chop yellow, DDG/CDG edges only
  between chop nodes.

Names are `_<file-stem>Kt_<method>` (kotlinc's facade class + method);
lambda bodies appear as `..Kt$<method>$1_invokeSuspend`. The CLI exports a
method whenever it contains a source or a sink, so the `expect: NO-FLOW`
benchmarks are here too — `spill-slot/01`–`03` in particular, where the
raw state machine's `L$0` reload (the FlowDroid false positive) is visible
on the `without` side and absent on the `with` side. The one benchmark with
no files is `control/03_no_taint.kt`, which has neither.

Render with `dot -Tsvg <file> -o out.svg`. Regenerate everything with
`bash scripts/run_ablation.sh && bash scripts/collect_dots.sh`; the
second script wipes and rebuilds this directory from `build/ablation/`.
