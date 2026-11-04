// expect: FLOW
// mirrors: benchmark/if-else/01_suspend_in_then_branch.kt
include!("../common.rs");

pub async fn suspend_in_then_branch(flag: bool) -> i64 {
    let secret = source(); // SOURCE
    let mut result = 0;
    if flag {
        result = identity(secret).await;
    }
    sink(result); // SINK
    result
}
#[unsafe(no_mangle)] pub fn entry(flag: bool) -> Boxed<i64> { Box::pin(suspend_in_then_branch(flag)) }
