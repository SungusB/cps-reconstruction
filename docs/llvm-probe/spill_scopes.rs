// Rust port of benchmark/spill-slot/01: two locals in disjoint scopes, each live across an await.
use std::future::Future; use std::pin::Pin;
#[inline(never)] fn source() -> String { "s".into() }
#[inline(never)] fn safe_val() -> String { "ok".into() }
#[inline(never)] fn sink(_: &str) {}
async fn tick() -> i32 { 1 }
async fn scopes() {
    { let secret = source(); tick().await; drop(secret); }
    { let safe = safe_val(); tick().await; sink(&safe); }
}
pub fn make() -> Pin<Box<dyn Future<Output = ()>>> { Box::pin(scopes()) }
fn main() { let _ = make(); }
