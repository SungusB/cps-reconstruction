// expect: FLOW
// mirrors: benchmark/loops/02_for_loop.kt
#include "../common.hpp"

task<long> for_loop(int count) {
  long secret = source(); // SOURCE
  long result = secret;
  for (int i = 0; i < count; i++) {
    result = co_await identity(result);
  }
  sink(result); // SINK
  co_return result;
}
