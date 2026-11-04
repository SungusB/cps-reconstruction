// expect: FLOW
// mirrors: benchmark/nested-lambda/03_captured_then_suspend_chain.kt
include!("../common.rs");

#[unsafe(no_mangle)] pub fn captured_then_chain() -> Boxed<()> {
    let secret = source(); // SOURCE
    Box::pin(async move {
        let a = identity(secret).await;
        let b = identity(a).await;
        sink(b);           // SINK
    })
}
