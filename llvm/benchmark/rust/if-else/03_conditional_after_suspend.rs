// expect: FLOW
// mirrors: benchmark/if-else/03_conditional_after_suspend.kt
include!("../common.rs");

pub async fn conditional_after_suspend() -> i64 {
    let secret = source(); // SOURCE
    let a = identity(secret).await;
    let result = if a != 0 { a } else { 0 };
    sink(result); // SINK
    result
}
#[unsafe(no_mangle)] pub fn entry() -> Boxed<i64> { Box::pin(conditional_after_suspend()) }
