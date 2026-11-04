// expect: NO-FLOW
// mirrors: benchmark/spill-slot/03_sequential_loops.kt
#include "../common.hpp"

task<long> sequential_loops() {
  long len = 0;
  for (int i = 0; i < 1; i++) {
    long secret = source(); // SOURCE
    co_await tick();
    len += secret;
  }
  for (int j = 0; j < 1; j++) {
    long safe = 7;
    co_await tick();
    sink(safe);             // SINK (should NOT be flagged)
  }
  co_return len;
}
