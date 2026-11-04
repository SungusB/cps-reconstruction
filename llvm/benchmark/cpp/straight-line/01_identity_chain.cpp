// expect: FLOW
// mirrors: benchmark/straight-line/01_identity_chain.kt
#include "../common.hpp"

task<long> identity_chain() {
  long secret = source(); // SOURCE
  long a = co_await identity(secret);
  long b = co_await identity(a);
  sink(b); // SINK
  co_return b;
}
