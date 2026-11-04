// expect: FLOW
// mirrors: benchmark/loops/03_do_while_loop.kt
include!("../common.rs");

pub async fn do_while_loop(count: i32) -> i64 {
    let secret = source(); // SOURCE
    let mut result = secret;
    let mut i = 0;
    loop {
        result = identity(result).await;
        i += 1;
        if !(i < count) { break; }
    }
    sink(result); // SINK
    result
}
#[unsafe(no_mangle)] pub fn entry(count: i32) -> Boxed<i64> { Box::pin(do_while_loop(count)) }
