// expect: FLOW
// mirrors: benchmark/loops/03_do_while_loop.kt
#include "../common.hpp"

task<long> do_while_loop(int count) {
  long secret = source(); // SOURCE
  long result = secret;
  int i = 0;
  do {
    result = co_await identity(result);
    i++;
  } while (i < count);
  sink(result); // SINK
  co_return result;
}
