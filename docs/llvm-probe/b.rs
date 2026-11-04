#![crate_type = "lib"]
use std::future::Future;
use std::pin::Pin;

#[inline(never)]
pub async fn identity(x: i32) -> i32 { std::hint::black_box(x) }

pub async fn chain(secret: i32, flag: bool) -> i32 {
    let mut r = 0;
    if flag {
        r = identity(secret).await;
    }
    r = identity(r + 1).await;
    r
}

pub fn make(secret: i32, flag: bool) -> Pin<Box<dyn Future<Output = i32>>> {
    Box::pin(chain(secret, flag))
}
