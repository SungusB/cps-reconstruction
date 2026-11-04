// A coroutine without awaits dispatches with `br i1`, not `switch`.
// RUN: rm -f %t.json
// RUN: %rustc %s -o %t.ll
// RUN: %recon -coro-recon-report=%t.json -S %t.ll -o %t.out.ll
// RUN: FileCheck --check-prefix=REPORT %s < %t.json
// RUN: FileCheck %s < %t.out.ll
// REPORT: "demangled":"rust_no_await::identity::{closure#0}"{{.*}}"resumePoints":0,"status":"reconstructed","suspends":0
// CHECK-LABEL: define {{.*}}@_RNCNvC{{.*}}8identity0B3_(
// CHECK-NEXT: start:
// CHECK-NEXT: br label
// CHECK-NOT: panic_const_async_fn_resumed
// CHECK: {{^}}}
pub async fn identity(x: i64) -> i64 { x }
#[unsafe(no_mangle)] pub fn entry(x: i64) -> std::pin::Pin<Box<dyn std::future::Future<Output = i64>>> { Box::pin(identity(x)) }
