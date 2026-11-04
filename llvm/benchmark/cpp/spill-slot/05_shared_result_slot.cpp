// expect: NO-FLOW
// mirrors: benchmark/spill-slot/05_shared_result_slot.kt
#include "../common.hpp"

task<long> shared_result_slot() {
  long secret = source();            // SOURCE
  long echoed = co_await identity(secret);
  long n = co_await tick();
  sink(n);                           // SINK (should NOT be flagged)
  co_return echoed + n;
}
