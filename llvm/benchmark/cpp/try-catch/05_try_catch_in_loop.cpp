// expect: FLOW
// mirrors: benchmark/try-catch/05_try_catch_in_loop.kt
#include "../common.hpp"

task<long> try_catch_in_loop(int count) {
  long secret = source(); // SOURCE
  long result = secret;
  int i = 0;
  while (i < count) {
    try {
      may_throw();
      result = co_await identity(result);
    } catch (const Failure &) {
      result = -1;
    }
    i++;
  }
  sink(result); // SINK
  co_return result;
}
