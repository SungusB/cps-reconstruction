// expect: FLOW
// mirrors: benchmark/nested-lambda/01_closure_capture_to_sink.kt
include!("../common.rs");

// `runSuspend { println(secret) }`: an async block capturing the source.
#[unsafe(no_mangle)] pub fn closure_capture_to_sink() -> Boxed<()> {
    let secret = source(); // SOURCE
    Box::pin(async move {
        sink(secret);      // SINK
    })
}
