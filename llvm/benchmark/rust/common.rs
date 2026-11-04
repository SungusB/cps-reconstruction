// Shared prelude, include!()d by every Rust benchmark so each file is one
// self-contained crate. Mirrors the Kotlin suite's helpers:
//   suspend fun identity(x) = x   -> async fn identity (never actually pends)
//   suspend fun tick(): Int = 1   -> async fn tick     (really suspends once)
use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Poll};

unsafe extern "C" {
    /// SOURCE
    pub safe fn source() -> i64;
    /// SINK
    pub safe fn sink(x: i64);
    /// Runtime-only condition, so no branch is constant-folded away.
    pub safe fn opaque() -> bool;
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
