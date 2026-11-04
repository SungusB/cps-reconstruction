// expect: FLOW
// mirrors: benchmark/control/02_ordinary_helper_function.kt
#include "../common.hpp"

__attribute__((noinline)) long helper(long x) { return x; }
void ordinary_helper_function() {
  long secret = source(); // SOURCE
  long result = helper(secret);
  sink(result);           // SINK
}
