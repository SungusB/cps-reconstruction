// expect: FLOW
// mirrors: benchmark/spill-slot/04_positive_control.kt
include!("../common.rs");

pub async fn positive_control() -> i64 {
    let carried = {
        let secret = source(); // SOURCE
        tick().await;
        secret
    };
    {
        let safe = 7;
        tick().await;
        sink(safe);
        sink(carried);         // SINK
    }
    carried
}
#[unsafe(no_mangle)] pub fn entry() -> Boxed<i64> { Box::pin(positive_control()) }
