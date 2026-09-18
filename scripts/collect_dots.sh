#!/usr/bin/env bash
# Copies the Graphviz output of the last `scripts/run_ablation.sh` run into
# `docs/dot/<benchmark-category>/{with,without}-reconstruction/`, so every
# benchmark's raw and reconstructed CPG is checked in as evidence, not left
# as a regenerate-only build artifact.
#
# `ReconstructionCli` writes one flat `cfg/` and `slices/` directory with
# names like `_01_async_awaitKt_asyncAwait.dot`; the benchmark category is
# recovered by looking the `<stem>Kt` prefix up under `benchmark/*/<stem>.kt`.
# Methods that don't map to a benchmark file (none today) are reported and
# skipped. `benchmark/control/03_no_taint.kt` has neither a source nor a sink,
# so the CLI exports nothing for it and it never appears here.
#
# Usage: scripts/collect_dots.sh            (after scripts/run_ablation.sh)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ABLATION_DIR="$ROOT_DIR/build/ablation"
DOCS_DIR="$ROOT_DIR/docs/dot"

if [ ! -d "$ABLATION_DIR/with-reconstruction/cfg" ]; then
  echo "No ablation output under $ABLATION_DIR — run scripts/run_ablation.sh first." >&2
  exit 1
fi

rm -rf "$DOCS_DIR"
copied=0
for mode in with-reconstruction without-reconstruction; do
  for src in "$ABLATION_DIR/$mode/cfg"/*.dot "$ABLATION_DIR/$mode/slices"/*_slice.dot; do
    name="$(basename "$src")"
    stem="${name#_}"           # drop the leading underscore kotlinc adds to digit-initial file names
    stem="${stem%%Kt*}"        # keep everything before the "Kt" facade-class suffix
    kt="$(find "$ROOT_DIR/benchmark" -name "$stem.kt" | head -n1)"
    if [ -z "$kt" ]; then
      echo "[!] no benchmark file for $name — skipped" >&2
      continue
    fi
    category="$(basename "$(dirname "$kt")")"
    dest="$DOCS_DIR/$category/$mode"
    mkdir -p "$dest"
    cp "$src" "$dest/$name"
    copied=$((copied + 1))
  done
done

echo "[*] copied $copied .dot files into $DOCS_DIR"
find "$DOCS_DIR" -mindepth 1 -maxdepth 1 -type d | sort | while read -r d; do
  printf '    %-16s %s files\n' "$(basename "$d")" "$(find "$d" -name '*.dot' | wc -l)"
done
