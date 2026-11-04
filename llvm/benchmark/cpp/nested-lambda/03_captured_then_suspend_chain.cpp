// expect: FLOW
// mirrors: benchmark/nested-lambda/03_captured_then_suspend_chain.kt
#include "../common.hpp"

void captured_then_chain() {
  long secret = source(); // SOURCE
  auto block = [secret]() -> task<void> {
    long a = co_await identity(secret);
    long b = co_await identity(a);
    sink(b);              // SINK
  };
  block();
}
