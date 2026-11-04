// expect: FLOW
// mirrors: benchmark/try-catch/04_multiple_independent_try_catch.kt
#include "../common.hpp"

task<long> multiple_try_catch_blocks() {
  long secret = source(); // SOURCE
  long a = 0;
  try {
    may_throw();
    a = co_await identity(secret);
  } catch (const Failure &) {
    a = -1;
  }
  long b = 0;
  try {
    may_throw();
    b = co_await identity(a);
  } catch (const Failure &) {
    b = -2;
  }
  sink(b); // SINK
  co_return b;
}
