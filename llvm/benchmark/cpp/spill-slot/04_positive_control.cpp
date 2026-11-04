// expect: FLOW
// mirrors: benchmark/spill-slot/04_positive_control.kt
#include "../common.hpp"

task<long> positive_control() {
  long carried;
  {
    long secret = source(); // SOURCE
    co_await tick();
    carried = secret;
  }
  {
    long safe = 7;
    co_await tick();
    sink(safe);
    sink(carried);          // SINK
  }
  co_return carried;
}
