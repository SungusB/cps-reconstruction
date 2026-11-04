// expect: FLOW
// mirrors: benchmark/control/02_ordinary_helper_function.kt
include!("../common.rs");

#[inline(never)] pub fn helper(x: i64) -> i64 { x }
#[unsafe(no_mangle)] pub fn ordinary_helper_function() {
    let secret = source(); // SOURCE
    let result = helper(secret);
    sink(result);          // SINK
}
