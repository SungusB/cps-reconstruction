// After optimization the ramp no longer has folded suspends to follow (and
// with an always-suspending awaiter it is truncated): decline, don't guess.
// RUN: rm -f %t.json
// RUN: %clangxx -O2 %s -o %t.ll
// RUN: %recon -coro-recon-report=%t.json -S %t.ll -o %t.out.ll
// RUN: FileCheck --check-prefix=REPORT %s < %t.json
// REPORT: "demangled":"chain(bool)"{{.*}}"reason":"no folded llvm.coro.suspend in the ramp (optimized IR?)"{{.*}}"status":"declined"
#include "Inputs/task.hpp"
task chain(bool c) {
  int secret = source();
  int a = co_await fetch(secret);
  if (c) { int b = co_await fetch(a); sink(b); }
  co_return a;
}
