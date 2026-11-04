// expect: FLOW
// mirrors: benchmark/try-catch/01_suspend_in_try.kt
#include "../common.hpp"

task<long> suspend_in_try() {
  long secret = source(); // SOURCE
  long result = 0;
  try {
    may_throw();
    result = co_await identity(secret);
  } catch (const Failure &) {
    result = -1;
  }
  sink(result); // SINK
  co_return result;
}
