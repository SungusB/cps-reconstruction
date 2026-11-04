// expect: NO-FLOW
// mirrors: benchmark/nested-lambda/02_no_capture.kt
#include "../common.hpp"

void no_capture() {
  long secret = source(); // SOURCE, deliberately unused below
  if (secret == -1) std::terminate();
  auto block = []() -> task<void> {
    sink(42);             // SINK (should NOT be flagged)
    co_return;
  };
  block();
}
