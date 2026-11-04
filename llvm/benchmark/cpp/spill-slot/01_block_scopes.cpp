// expect: NO-FLOW
// mirrors: benchmark/spill-slot/01_run_block_scopes.kt
#include "../common.hpp"

task<long> block_scopes() {
  long len;
  {
    long secret = source(); // SOURCE
    co_await tick();        // suspension 1: secret lives in the frame
    len = secret + 1;
  }
  {
    long safe = 7;
    co_await tick();        // suspension 2: safe lives in the frame
    sink(safe);             // SINK (should NOT be flagged)
  }
  co_return len;
}
