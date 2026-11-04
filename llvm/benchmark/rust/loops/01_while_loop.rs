// expect: FLOW
// mirrors: benchmark/loops/01_while_loop.kt
include!("../common.rs");

pub async fn while_loop(count: i32) -> i64 {
    let secret = source(); // SOURCE
    let mut result = secret;
    let mut i = 0;
    while i < count {
        result = identity(result).await;
        i += 1;
    }
    sink(result); // SINK
    result
}
#[unsafe(no_mangle)] pub fn entry(count: i32) -> Boxed<i64> { Box::pin(while_loop(count)) }
