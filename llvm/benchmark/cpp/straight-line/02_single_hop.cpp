// expect: FLOW
// mirrors: benchmark/straight-line/02_single_hop.kt
#include "../common.hpp"

inline Ident passthrough(long x) { return {x}; }
task<long> single_hop() {
  long secret = source(); // SOURCE
  long result = co_await passthrough(secret);
  sink(result); // SINK
  co_return result;
}
