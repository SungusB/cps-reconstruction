// expect: NO-FLOW
// mirrors: (no Kotlin counterpart) — negative control for the analysis, not
// for reconstruction: a memory slot written with the source, overwritten with
// a constant, then read into the sink. No coroutine involved. A tool without
// strong updates on memory reports a flow here; that is the same imprecision
// that makes rustc's shared saved-local slots (spill-slot/*) look tainted.
#include "../common.hpp"

struct Slot { long v; };
__attribute__((noinline)) void overwritten_field(Slot *s) {
  s->v = source(); // SOURCE
  s->v = 7;        // kills the taint
  sink(s->v);      // SINK (should NOT be flagged)
}
