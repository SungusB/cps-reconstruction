// expect: NO-FLOW
// mirrors: benchmark/control/03_no_taint.kt
#include "../common.hpp"

long no_taint() {
  long x = 21;
  return x * 2;
}
