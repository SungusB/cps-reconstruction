// expect: NO-FLOW
// mirrors: benchmark/control/03_no_taint.kt
include!("../common.rs");

#[unsafe(no_mangle)] pub fn no_taint() -> i64 {
    let x = 21;
    x * 2
}
