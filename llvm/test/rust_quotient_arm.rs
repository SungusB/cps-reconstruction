// Quotient check: a user call injected into a suspend arm (the block that
// stores the resume-point value into the discriminant) must make the pass
// decline and leave the function untouched.
// RUN: rm -f %t.json
// RUN: %rustc %s -o %t.ll
// RUN: sed -E 's/^(  store i8 3, ptr %[0-9]+, align 1)$/\1\n  call void @sink(i64 99)/' %t.ll > %t.mut.ll
// RUN: grep -c 'call void @sink(i64 99)' %t.mut.ll | FileCheck --check-prefix=MUTATED %s
// RUN: %recon -coro-recon-report=%t.json -S %t.mut.ll -o %t.out.ll
// RUN: FileCheck --check-prefix=REPORT %s < %t.json
// RUN: FileCheck %s < %t.out.ll
// MUTATED: 1
// REPORT: "demangled":"rust_quotient_arm::chain::{closure#0}"{{.*}}"reason":"suspend arm contains a user call:{{.*}}sink{{.*}}"status":"declined"
// CHECK-LABEL: define {{.*}}@_RNCNvC{{.*}}5chain0B3_(
// CHECK: switch i32
// CHECK: panic_const_async_fn_resumed
unsafe extern "C" { safe fn source() -> i64; safe fn sink(x: i64); }
pub struct Yield(bool);
impl std::future::Future for Yield {
    type Output = i64;
    fn poll(mut self: std::pin::Pin<&mut Self>, _: &mut std::task::Context<'_>) -> std::task::Poll<i64> {
        if self.0 { std::task::Poll::Ready(1) } else { self.0 = true; std::task::Poll::Pending }
    }
}
pub async fn chain() { let s = source(); let a = Yield(false).await; sink(s + a) }
#[unsafe(no_mangle)] pub fn entry() -> std::pin::Pin<Box<dyn std::future::Future<Output = ()>>> { Box::pin(chain()) }
