#!/usr/bin/env bash
# Compiles and runs the SootUp 2.0.0 repros against the project's own
# dependency jars (build/install, from `./gradlew installDist`).
#   EdgeOrder.kt                  same two edges, two insertion orders -> one valid, one invalid graph
#   EdgeOrderPermutations.kt      same four edges, all 24 orders -> 20 rejected, 4 accepted
#   IterateReconstructedBodies.kt times Body.getStmts()/getParameterLocals() on every
#                                 reconstructed body of a class dir (10 s cap each);
#                                 used to find the fall-through-cycle hang
# Usage: docs/bugs-found/repro/sootup/run.sh [classes-dir-for-IterateReconstructedBodies]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
HERE="$ROOT/docs/bugs-found/repro/sootup"
OUT="$ROOT/build/sootup-repro"
CP=$(ls "$ROOT"/build/install/cps-reconstruction/lib/*.jar | tr '\n' ':')
mkdir -p "$OUT"
kotlinc "$HERE"/*.kt -cp "$CP" -d "$OUT" -nowarn
java -cp "$OUT:$CP" repro.edgeorder.EdgeOrderKt
java -cp "$OUT:$CP" repro.edgeorderperms.EdgeOrderPermutationsKt
if [ $# -ge 1 ]; then java -cp "$OUT:$CP" repro.iterate.IterateReconstructedBodiesKt "$1"; fi
