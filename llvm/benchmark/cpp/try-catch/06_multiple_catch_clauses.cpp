// expect: FLOW
// mirrors: benchmark/try-catch/06_multiple_catch_clauses.kt
#include "../common.hpp"

task<long> multiple_catch_clauses() {
  long secret = source(); // SOURCE
  long result = 0;
  try {
    may_throw();
    result = co_await identity(secret);
  } catch (const IllegalState &) {
    result = -1;
  } catch (const Failure &) {
    result = -2;
  }
  sink(result); // SINK
  co_return result;
}
