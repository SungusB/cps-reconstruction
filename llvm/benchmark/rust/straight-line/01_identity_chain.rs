// expect: FLOW
// mirrors: benchmark/straight-line/01_identity_chain.kt
include!("../common.rs");

pub async fn identity_chain() -> i64 {
    let secret = source(); // SOURCE
    let a = identity(secret).await;
    let b = identity(a).await;
    sink(b); // SINK
    b
}
#[unsafe(no_mangle)] pub fn entry() -> Boxed<i64> { Box::pin(identity_chain()) }
