// expect: FLOW
// mirrors: benchmark/if-else/02_suspend_in_both_branches.kt
include!("../common.rs");

pub async fn suspend_in_both_branches(flag: bool) -> i64 {
    let secret = source(); // SOURCE
    let result = if flag { identity(secret).await } else { other_identity(secret).await };
    sink(result); // SINK
    result
}
#[unsafe(no_mangle)] pub fn entry(flag: bool) -> Boxed<i64> { Box::pin(suspend_in_both_branches(flag)) }
