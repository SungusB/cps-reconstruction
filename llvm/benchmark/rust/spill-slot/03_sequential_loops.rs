// expect: NO-FLOW
// mirrors: benchmark/spill-slot/03_sequential_loops.kt
include!("../common.rs");

pub async fn sequential_loops() -> i64 {
    let mut len = 0;
    for _ in 0..1 {
        let secret = source(); // SOURCE
        tick().await;
        len += secret;
    }
    for _ in 0..1 {
        let safe = 7;
        tick().await;
        sink(safe);            // SINK (should NOT be flagged)
    }
    len
}
#[unsafe(no_mangle)] pub fn entry() -> Boxed<i64> { Box::pin(sequential_loops()) }
