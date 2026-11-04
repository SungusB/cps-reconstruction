// C++20 at -O0: every folded llvm.coro.suspend in the ramp goes to its
// resumed successor, and the .resume clone becomes a stub.
// RUN: rm -f %t.json
// RUN: %clangxx -O0 %s -o %t.ll
// RUN: %recon -coro-recon-report=%t.json -S %t.ll -o %t.out.ll
// RUN: FileCheck --check-prefix=REPORT %s < %t.json
// RUN: FileCheck %s < %t.out.ll
// RUN: llc -O0 -filetype=obj %t.out.ll -o %t.o
// REPORT: "demangled":"chain(bool)"{{.*}}"status":"reconstructed","suspends":4
// CHECK-LABEL: define {{.*}}@_Z5chainb(
// CHECK-NOT: switch i8 -1
// CHECK: {{call|invoke}} {{.*}}@_Z6sourcev()
// CHECK: {{call|invoke}} {{.*}}@_Z4sinki(
// CHECK-LABEL: define internal void @_Z5chainb.resume(
// CHECK-NEXT: stub:
// CHECK-NEXT: ret void
#include "Inputs/task.hpp"
task chain(bool c) {
  int secret = source();
  int a = co_await fetch(secret);
  if (c) { int b = co_await fetch(a); sink(b); }
  co_return a;
}
