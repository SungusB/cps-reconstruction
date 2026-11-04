// expect: FLOW
// mirrors: benchmark/control/01_direct_flow.kt
include!("../common.rs");

#[unsafe(no_mangle)] pub fn direct_flow() {
    let secret = source(); // SOURCE
    sink(secret);          // SINK
}
