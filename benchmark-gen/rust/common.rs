// Prelude for the generated Rust suite (benchmark-gen/rust), include!()d by
// every file. Same API as llvm/benchmark/rust/common.rs plus opaque_int().
//   identity / other_identity: async fns that never pend
//   tick: pends once (a real suspension), then returns 1
use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll};

unsafe extern "C" {
    /// SOURCE
    pub safe fn source() -> i64;
    /// SINK
    pub safe fn sink(x: i64);
    /// Independent run-time inputs: every branch/loop decision.
    pub safe fn opaque() -> bool;
    pub safe fn opaque_int() -> i64;
}

/// Pending on the first poll, Ready on the second: a real suspension.
pub struct YieldOnce(bool);
impl Future for YieldOnce {
    type Output = ();
    fn poll(mut self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<()> {
        if self.0 { Poll::Ready(()) } else { self.0 = true; Poll::Pending }
    }
}

pub async fn tick() -> i64 { YieldOnce(false).await; 1 }
pub async fn identity(x: i64) -> i64 { x }
pub async fn other_identity(x: i64) -> i64 { x }

pub type Boxed<T> = Pin<Box<dyn Future<Output = T>>>;
