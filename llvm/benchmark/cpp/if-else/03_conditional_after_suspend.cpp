// expect: FLOW
// mirrors: benchmark/if-else/03_conditional_after_suspend.kt
#include "../common.hpp"

task<long> conditional_after_suspend() {
  long secret = source(); // SOURCE
  long a = co_await identity(secret);
  long result = a != 0 ? a : 0;
  sink(result); // SINK
  co_return result;
}
