// expect: FLOW
// mirrors: benchmark/loops/02_for_loop.kt
include!("../common.rs");

pub async fn for_loop(count: i32) -> i64 {
    let secret = source(); // SOURCE
    let mut result = secret;
    for _ in 0..count {
        result = identity(result).await;
    }
    sink(result); // SINK
    result
}
#[unsafe(no_mangle)] pub fn entry(count: i32) -> Boxed<i64> { Box::pin(for_loop(count)) }
