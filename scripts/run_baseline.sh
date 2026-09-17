#!/usr/bin/env bash
# External-baseline harness: FlowDroid on the benchmark suite, with and
# without the classic-Soot port of suspend-chain reconstruction applied to
# the bodies it analyzes. This is the experiment the benchmark/spill-slot/
# family was written for.
#
# Reuses the classes scripts/run_ablation.sh compiled (build/ablation/classes)
# when present, otherwise compiles the suite itself. Expect ~10 minutes: the
# two benchmark/nested-lambda packages route taint through kotlin.coroutines'
# resumeWith dispatch loop and each takes FlowDroid several minutes.
#
# Usage: scripts/run_baseline.sh [benchmark-dir]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BENCHMARK_DIR="${1:-$ROOT_DIR/benchmark}"
CLASSES_DIR="$ROOT_DIR/build/ablation/classes"
WORK_DIR="$ROOT_DIR/build/baseline"
WITH_DIR="$WORK_DIR/with-reconstruction"
WITHOUT_DIR="$WORK_DIR/without-reconstruction"

# Library classpath for Soot: kotlin-stdlib + kotlinx-coroutines-core-jvm.
# Override with KOTLIN_LIB=<dir>; otherwise probe the kotlinc install.
KOTLINC_HOME="$(cd -P "$(dirname "$(readlink -f "$(command -v kotlinc)")")/.." && pwd)"
LIB_DIR=""
for candidate in "${KOTLIN_LIB:-}" "$KOTLINC_HOME/lib" "$KOTLINC_HOME/share/kotlin/lib" /usr/share/kotlin/lib /usr/lib/kotlin/lib; do
  if [ -n "$candidate" ] && [ -f "$candidate/kotlin-stdlib.jar" ]; then LIB_DIR="$candidate"; break; fi
done
[ -n "$LIB_DIR" ] || { echo "Could not find kotlin-stdlib.jar — set KOTLIN_LIB to the directory holding it" >&2; exit 1; }
COROUTINES_JAR="$LIB_DIR/kotlinx-coroutines-core-jvm.jar"
if [ ! -f "$COROUTINES_JAR" ]; then
  COROUTINES_JAR="$(cd "$ROOT_DIR" && ./gradlew -q printBenchmarkClasspath --console=plain 2>/dev/null)"
fi
LIB_CP="$LIB_DIR/kotlin-stdlib.jar:$COROUTINES_JAR"

if [ ! -d "$CLASSES_DIR" ]; then
  echo "[*] Compiling benchmark suite from $BENCHMARK_DIR ..."
  mkdir -p "$CLASSES_DIR"
  mapfile -t KOTLIN_FILES < <(find "$BENCHMARK_DIR" -name "*.kt" | sort)
  kotlinc "${KOTLIN_FILES[@]}" -cp "$LIB_CP" -d "$CLASSES_DIR"
fi

rm -rf "$WITH_DIR" "$WITHOUT_DIR"
mkdir -p "$WITH_DIR" "$WITHOUT_DIR"

echo "[*] Building the tool (gradle installDist) ..."
(cd "$ROOT_DIR" && ./gradlew installDist --console=plain -q 2>/dev/null || gradle installDist --console=plain -q)
RUNNER="$ROOT_DIR/build/install/cps-reconstruction/bin/baseline"

echo "[*] FlowDroid WITH reconstruction (Soot port) ..."
"$RUNNER" "$CLASSES_DIR" "$WITH_DIR" --lib "$LIB_CP" --emit-classes > "$WITH_DIR/stdout.log" 2>&1 || true

echo "[*] FlowDroid WITHOUT reconstruction ..."
"$RUNNER" "$CLASSES_DIR" "$WITHOUT_DIR" --lib "$LIB_CP" --no-reconstruct > "$WITHOUT_DIR/stdout.log" 2>&1 || true

echo ""
echo "==================== FlowDroid baseline summary ===================="
SOOTUP_SUMMARY="$ROOT_DIR/build/ablation/with-reconstruction/summary.json"
if [ -f "$SOOTUP_SUMMARY" ]; then
  python3 "$ROOT_DIR/scripts/compare_baseline.py" "$WITH_DIR/summary.json" "$WITHOUT_DIR/summary.json" "$SOOTUP_SUMMARY"
else
  python3 "$ROOT_DIR/scripts/compare_baseline.py" "$WITH_DIR/summary.json" "$WITHOUT_DIR/summary.json"
fi
