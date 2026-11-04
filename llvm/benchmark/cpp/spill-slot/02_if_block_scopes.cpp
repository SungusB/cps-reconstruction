// expect: NO-FLOW
// mirrors: benchmark/spill-slot/02_if_block_scopes.kt
#include "../common.hpp"

task<long> if_block_scopes(bool flag) {
  long len = 0;
  if (flag) {
    long secret = source(); // SOURCE
    co_await tick();
    len = secret + 1;
  }
  if (!flag) {
    long safe = 7;
    co_await tick();
    sink(safe);             // SINK (should NOT be flagged)
  }
  co_return len;
}
