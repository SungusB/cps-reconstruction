// Quotient check, C++ side: a call that exists only in the .resume clone
// means the ramp does not hold the whole body, so the pass must decline.
// RUN: rm -f %t.json
// RUN: %clangxx -O0 %s -o %t.ll
// RUN: awk '/^define .*@_Z5chainb.resume\(/{inres=1} inres && /^[0-9a-z.]+:/ && !done {print; print "  call void @injected(i32 7)"; done=1; next} {print} END {print "declare void @injected(i32)"}' %t.ll > %t.mut.ll
// RUN: %recon -coro-recon-report=%t.json -S %t.mut.ll -o %t.out.ll
// RUN: FileCheck --check-prefix=REPORT %s < %t.json
// RUN: FileCheck %s < %t.out.ll
// REPORT: "demangled":"chain(bool)"{{.*}}"lost":["call to injected exists only in _Z5chainb.resume"]{{.*}}"status":"declined"
// CHECK-LABEL: define {{.*}}@_Z5chainb(
// CHECK: switch i8 -1
#include "Inputs/task.hpp"
extern void other(int);
task chain(bool c) {
  int secret = source();
  int a = co_await fetch(secret);
  other(a);
  co_return a;
}
