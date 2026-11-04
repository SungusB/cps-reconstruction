// expect: FLOW
// mirrors: benchmark/loops/01_while_loop.kt
#include "../common.hpp"

task<long> while_loop(int count) {
  long secret = source(); // SOURCE
  long result = secret;
  int i = 0;
  while (i < count) {
    result = co_await identity(result);
    i++;
  }
  sink(result); // SINK
  co_return result;
}
