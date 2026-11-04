// expect: FLOW
// mirrors: benchmark/straight-line/03_sink_inside_callee.kt
include!("../common.rs");

pub async fn log_it(x: i64) {
    let prefixed = x;
    sink(prefixed); // SINK
}
pub async fn sink_inside_callee() {
    let secret = source(); // SOURCE
    let a = secret;
    log_it(a).await;
}
#[unsafe(no_mangle)] pub fn entry() -> Boxed<()> { Box::pin(sink_inside_callee()) }
