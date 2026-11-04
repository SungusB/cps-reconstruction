// expect: NO-FLOW
// mirrors: benchmark/nested-lambda/02_no_capture.kt
include!("../common.rs");

#[unsafe(no_mangle)] pub fn no_capture() -> Boxed<()> {
    let secret = source(); // SOURCE, deliberately unused below
    assert!(secret != i64::MIN);
    Box::pin(async move {
        sink(42);          // SINK (should NOT be flagged)
    })
}
