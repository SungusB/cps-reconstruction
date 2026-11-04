// expect: NO-FLOW
// mirrors: benchmark/control/04_tainted_guard_clause_unrelated_sink.kt
include!("../common.rs");

#[unsafe(no_mangle)] pub fn ordinary_tainted_guard_clause() -> i64 {
    let secret = source(); // SOURCE
    if secret == 0 {
        return 0;
    }
    let result = 7;
    sink(result);          // SINK (control-dependent only)
    result
}
