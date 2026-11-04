#!/usr/bin/env bash
# FlowDroid baseline with one fresh JVM per benchmark package, for suites too
# large for run_baseline.sh's single JVM. On the 121-program generated suite
# (benchmark-gen/) the single-JVM run died with OutOfMemoryError (-Xmx6g) at
# package 97: memory grows across the per-package Soot scenes. A fresh JVM per
# package also removes cross-package timing drift (CLAUDE.md, Tier B item 6).
#
# Each leaf package directory (one holding .class files) is copied into its own
# class root, the baseline runs on it in both modes, and the per-package
# summaries are merged into <out>/{with,without}-reconstruction/summary.json in
# the same format run_baseline.sh writes. Emitted reconstructed classes are
# merged into <out>/with-reconstruction/classes.
#
# Usage: scripts/run_baseline_per_package.sh <classes-dir> <out-dir> [jobs]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSES="$(cd "$1" && pwd)"
OUT="$(mkdir -p "$2" && cd "$2" && pwd)"
JOBS="${3:-3}"
RUNNER="$ROOT_DIR/build/install/cps-reconstruction/bin/baseline"

KOTLINC_HOME="$(cd -P "$(dirname "$(readlink -f "$(command -v kotlinc)")")/.." && pwd)"
LIB_DIR=""
for candidate in "${KOTLIN_LIB:-}" "$KOTLINC_HOME/lib" "$KOTLINC_HOME/share/kotlin/lib" /usr/share/kotlin/lib /usr/lib/kotlin/lib; do
  if [ -n "$candidate" ] && [ -f "$candidate/kotlin-stdlib.jar" ]; then LIB_DIR="$candidate"; break; fi
done
[ -n "$LIB_DIR" ] || { echo "Could not find kotlin-stdlib.jar — set KOTLIN_LIB" >&2; exit 1; }
COROUTINES_JAR="$(cd "$ROOT_DIR" && ./gradlew -q printBenchmarkClasspath --console=plain 2>/dev/null)"
export LIB_CP="$LIB_DIR/kotlin-stdlib.jar:$COROUTINES_JAR" RUNNER CLASSES OUT

rm -rf "${OUT:?}/pkg" "${OUT:?}/with-reconstruction" "${OUT:?}/without-reconstruction"
mkdir -p "$OUT/pkg"

run_one() {
  rel="$1"; id="${rel//\//.}"; d="$OUT/pkg/$id"
  mkdir -p "$d/root/$rel"
  cp "$CLASSES/$rel"/*.class "$d/root/$rel/"
  "$RUNNER" "$d/root" "$d/with" --lib "$LIB_CP" --emit-classes > "$d/with.log" 2>&1 || echo "FAILED with $rel"
  "$RUNNER" "$d/root" "$d/without" --lib "$LIB_CP" --no-reconstruct > "$d/without.log" 2>&1 || echo "FAILED without $rel"
  echo "done $rel"
}
export -f run_one

(cd "$CLASSES" && find . -name '*.class' -printf '%h\n' | sed 's|^\./||' | sort -u) \
  | xargs -P "$JOBS" -I{} bash -c 'run_one "$@"' _ {}

python3 - "$OUT" <<'EOF'
import json, sys, shutil
from pathlib import Path
out = Path(sys.argv[1])
for mode, sub in (("with", "with-reconstruction"), ("without", "without-reconstruction")):
    merged = None
    for d in sorted((out / "pkg").iterdir()):
        f = d / mode / "summary.json"
        if not f.exists():
            print(f"missing {f}")
            continue
        s = json.loads(f.read_text())
        if merged is None:
            merged = {k: (v if not isinstance(v, (list, int)) or isinstance(v, bool) else type(v)()) for k, v in s.items()}
        for k, v in s.items():
            if isinstance(v, list):
                merged[k] = merged.get(k, []) + v
            elif isinstance(v, int) and not isinstance(v, bool):
                merged[k] = merged.get(k, 0) + v
        cls = d / mode / "classes"
        if cls.exists():
            shutil.copytree(cls, out / sub / "classes", dirs_exist_ok=True)
    (out / sub).mkdir(parents=True, exist_ok=True)
    (out / sub / "summary.json").write_text(json.dumps(merged, indent=2))
    print(f"{sub}: {merged['totalTaintFlows']} flows over {len(merged['packageRuns'])} packages")
EOF
