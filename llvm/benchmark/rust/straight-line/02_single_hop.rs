// expect: FLOW
// mirrors: benchmark/straight-line/02_single_hop.kt
include!("../common.rs");

pub async fn passthrough(x: i64) -> i64 { x }
pub async fn single_hop() -> i64 {
    let secret = source(); // SOURCE
    let result = passthrough(secret).await;
    sink(result); // SINK
    result
}
#[unsafe(no_mangle)] pub fn entry() -> Boxed<i64> { Box::pin(single_hop()) }
