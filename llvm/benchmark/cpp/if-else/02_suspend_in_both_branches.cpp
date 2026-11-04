// expect: FLOW
// mirrors: benchmark/if-else/02_suspend_in_both_branches.kt
#include "../common.hpp"

task<long> suspend_in_both_branches(bool flag) {
  long secret = source(); // SOURCE
  long result;
  if (flag) result = co_await identity(secret);
  else      result = co_await other_identity(secret);
  sink(result); // SINK
  co_return result;
}
