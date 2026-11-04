// expect: FLOW
// mirrors: benchmark/nested-lambda/01_closure_capture_to_sink.kt
#include "../common.hpp"

// `runSuspend { println(secret) }`: a coroutine lambda capturing the source.
void closure_capture_to_sink() {
  long secret = source(); // SOURCE
  auto block = [secret]() -> task<void> {
    sink(secret);         // SINK
    co_return;
  };
  block();
}
