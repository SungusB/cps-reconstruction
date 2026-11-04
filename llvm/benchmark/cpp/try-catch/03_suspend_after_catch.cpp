// expect: FLOW
// mirrors: benchmark/try-catch/03_suspend_after_catch.kt
#include "../common.hpp"

task<long> suspend_after_catch() {
  long secret = source(); // SOURCE
  long parsed = secret;
  try {
    may_throw();
    parsed = secret + 1;
  } catch (const Failure &) {
    parsed = -1;
  }
  long result = co_await identity(parsed);
  sink(result); // SINK
  co_return result;
}
