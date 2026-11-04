// expect: NO-FLOW
// mirrors: benchmark/spill-slot/05_shared_result_slot.kt
include!("../common.rs");

pub async fn shared_result_slot() -> i64 {
    let secret = source();              // SOURCE
    let echoed = identity(secret).await;
    let n = tick().await;
    sink(n);                            // SINK (should NOT be flagged)
    echoed + n
}
#[unsafe(no_mangle)] pub fn entry() -> Boxed<i64> { Box::pin(shared_result_slot()) }
