// expect: NO-FLOW
// mirrors: benchmark/control/05_tainted_branch_no_early_exit.kt
#include "../common.hpp"

long ordinary_tainted_branch_no_early_exit() {
  long secret = source(); // SOURCE
  long result;
  if (secret == 0) result = 1; else result = 2;
  sink(result);           // SINK (control-dependent only)
  return result;
}
