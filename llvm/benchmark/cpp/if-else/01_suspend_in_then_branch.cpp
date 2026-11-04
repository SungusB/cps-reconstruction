// expect: FLOW
// mirrors: benchmark/if-else/01_suspend_in_then_branch.kt
#include "../common.hpp"

task<long> suspend_in_then_branch(bool flag) {
  long secret = source(); // SOURCE
  long result = 0;
  if (flag) {
    result = co_await identity(secret);
  }
  sink(result); // SINK
  co_return result;
}
