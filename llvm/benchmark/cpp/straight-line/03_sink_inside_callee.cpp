// expect: FLOW
// mirrors: benchmark/straight-line/03_sink_inside_callee.kt
#include "../common.hpp"

task<void> log_it(long x) {
  long prefixed = x;
  sink(prefixed); // SINK
  co_return;
}
task<void> sink_inside_callee() {
  long secret = source(); // SOURCE
  long a = secret;
  co_await log_it(a);
}
