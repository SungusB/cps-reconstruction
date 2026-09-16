#!/usr/bin/env bash
# Phase 4 ablation harness.
#
# Compiles the benchmark suite, then runs the reconstruction tool against it
# twice — once with SuspendChainReconstructor enabled, once with
# --no-reconstruct — and reports the flows found in each mode. The
# difference between the two totals is the paper's core evaluation number:
# how many flows are recovered *only* because of suspend-chain
# reconstruction.
#
# Usage: scripts/run_ablation.sh [benchmark-dir]
# Defaults to ./benchmark if no directory is given.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BENCHMARK_DIR="${1:-$ROOT_DIR/benchmark}"
WORK_DIR="$ROOT_DIR/build/ablation"
CLASSES_DIR="$WORK_DIR/classes"
WITH_DIR="$WORK_DIR/with-reconstruction"
WITHOUT_DIR="$WORK_DIR/without-reconstruction"

rm -rf "$WORK_DIR"
mkdir -p "$CLASSES_DIR" "$WITH_DIR" "$WITHOUT_DIR"

echo "[*] Resolving kotlinx-coroutines-core for benchmark/concurrency/*.kt ..."
COROUTINES_CP="$(cd "$ROOT_DIR" && ./gradlew -q printBenchmarkClasspath --console=plain 2>/dev/null || gradle -q printBenchmarkClasspath --console=plain)"

echo "[*] Compiling benchmark suite from $BENCHMARK_DIR ..."
mapfile -t KOTLIN_FILES < <(find "$BENCHMARK_DIR" -name "*.kt" | sort)
if [ "${#KOTLIN_FILES[@]}" -eq 0 ]; then
  echo "No .kt files found under $BENCHMARK_DIR" >&2
  exit 1
fi
kotlinc "${KOTLIN_FILES[@]}" -cp "$COROUTINES_CP" -d "$CLASSES_DIR"

echo "[*] Building the reconstruction tool (gradle installDist) ..."
(cd "$ROOT_DIR" && ./gradlew installDist --console=plain -q 2>/dev/null || gradle installDist --console=plain -q)

RUNNER="$ROOT_DIR/build/install/cps-reconstruction/bin/cps-reconstruction"
if [ ! -x "$RUNNER" ]; then
  echo "Could not find installed runner at $RUNNER — check the installDist output directory name." >&2
  exit 1
fi

echo "[*] Running WITH reconstruction ..."
"$RUNNER" "$CLASSES_DIR" "$WITH_DIR" > "$WITH_DIR/stdout.log" 2>&1 || true

echo "[*] Running WITHOUT reconstruction (--no-reconstruct) ..."
"$RUNNER" "$CLASSES_DIR" "$WITHOUT_DIR" --no-reconstruct > "$WITHOUT_DIR/stdout.log" 2>&1 || true

echo ""
echo "==================== Ablation summary ===================="
python3 "$ROOT_DIR/scripts/compare_ablation.py" "$WITH_DIR/summary.json" "$WITHOUT_DIR/summary.json"
