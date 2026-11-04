// expect: NO-FLOW
// mirrors: benchmark/spill-slot/01_run_block_scopes.kt
include!("../common.rs");

// rustc packs saved locals by liveness: `secret` (Suspend0) and `safe`
// (Suspend1) share one byte range of the coroutine (see -Zprint-type-sizes).
pub async fn block_scopes() -> i64 {
    let len = {
        let secret = source(); // SOURCE
        tick().await;          // suspension 1: secret saved across it
        secret + 1
    };
    {
        let safe = 7;
        tick().await;          // suspension 2: safe saved in the same bytes
        sink(safe);            // SINK (should NOT be flagged)
    }
    len
}
#[unsafe(no_mangle)] pub fn entry() -> Boxed<i64> { Box::pin(block_scopes()) }
