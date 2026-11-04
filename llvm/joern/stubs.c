/* Definitions for the benchmark prelude's extern "C" API, linked into every
 * shared object handed to ghidra2cpg. ghidra2cpg only gives a call arguments
 * when it can see the callee's parameters (a thunk with a known signature, or
 * a function it can decompile), so an undefined `sink` would have none. The
 * bodies are opaque to the analysis question; only the signatures matter. */
#include <stdbool.h>
volatile long g;
__attribute__((noinline)) long source(void) { return g; }
__attribute__((noinline)) void sink(long x) { g = x; }
__attribute__((noinline)) bool opaque(void) { return g != 0; }
__attribute__((noinline)) long opaque_int(void) { return g & 3; }
__attribute__((noinline)) void schedule(void *p) { g = (long)p; }
