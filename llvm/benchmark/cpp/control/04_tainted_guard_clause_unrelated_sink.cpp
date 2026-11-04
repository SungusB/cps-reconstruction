// expect: NO-FLOW
// mirrors: benchmark/control/04_tainted_guard_clause_unrelated_sink.kt
#include "../common.hpp"

long ordinary_tainted_guard_clause() {
  long secret = source(); // SOURCE
  if (secret == 0) return 0;
  long result = 7;
  sink(result);           // SINK (control-dependent only)
  return result;
}
