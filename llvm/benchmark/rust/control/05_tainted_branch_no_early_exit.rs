// expect: NO-FLOW
// mirrors: benchmark/control/05_tainted_branch_no_early_exit.kt
include!("../common.rs");

#[unsafe(no_mangle)] pub fn ordinary_tainted_branch_no_early_exit() -> i64 {
    let secret = source(); // SOURCE
    let result;
    if secret == 0 { result = 1; } else { result = 2; }
    sink(result);          // SINK (control-dependent only)
    result
}
