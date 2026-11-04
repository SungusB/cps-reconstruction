// expect: NO-FLOW
// mirrors: benchmark/spill-slot/02_if_block_scopes.kt
include!("../common.rs");

pub async fn if_block_scopes(flag: bool) -> i64 {
    let mut len = 0;
    if flag {
        let secret = source(); // SOURCE
        tick().await;
        len = secret + 1;
    }
    if !flag {
        let safe = 7;
        tick().await;
        sink(safe);            // SINK (should NOT be flagged)
    }
    len
}
#[unsafe(no_mangle)] pub fn entry(flag: bool) -> Boxed<i64> { Box::pin(if_block_scopes(flag)) }
