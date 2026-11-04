// expect: FLOW
// mirrors: benchmark/control/01_direct_flow.kt
#include "../common.hpp"

void direct_flow() {
  long secret = source(); // SOURCE
  sink(secret);           // SINK
}
