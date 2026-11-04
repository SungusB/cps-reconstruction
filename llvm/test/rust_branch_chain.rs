// Two awaits, one under an `if`: the dispatch switch goes, both Pending arms
// go, and the fast path keeps source -> await -> if -> await -> sink.
// RUN: rm -f %t.json
// RUN: %rustc %s -o %t.ll
// RUN: %recon -coro-recon-report=%t.json -S %t.ll -o %t.out.ll
// RUN: FileCheck --check-prefix=REPORT %s < %t.json
// RUN: FileCheck %s < %t.out.ll
// RUN: llc -O0 -filetype=obj %t.out.ll -o %t.o
// REPORT: "demangled":"rust_branch_chain::chain::{closure#0}"{{.*}}"resumePoints":2,"status":"reconstructed","suspends":2
// CHECK-LABEL: define {{.*}}@_RNCNvC{{.*}}5chain0B3_(
// CHECK-NEXT: start:
// CHECK-NOT: switch
// CHECK-NOT: panic_const_async_fn_resumed
// CHECK: call i64 @source()
// CHECK: br i1 %{{.*}}, label
// CHECK: call void @sink(
// CHECK: {{^}}}
unsafe extern "C" { safe fn source() -> i64; safe fn sink(x: i64); }
pub struct Yield(bool);
impl std::future::Future for Yield {
    type Output = i64;
    fn poll(mut self: std::pin::Pin<&mut Self>, _: &mut std::task::Context<'_>) -> std::task::Poll<i64> {
        if self.0 { std::task::Poll::Ready(1) } else { self.0 = true; std::task::Poll::Pending }
    }
}
pub async fn fetch(x: i64) -> i64 { Yield(false).await + x }
pub async fn chain(c: bool) {
    let secret = source();
    let a = fetch(secret).await;
    if c { let b = fetch(a).await; sink(b) }
}
#[unsafe(no_mangle)] pub fn entry(c: bool) -> std::pin::Pin<Box<dyn std::future::Future<Output = ()>>> { Box::pin(chain(c)) }
