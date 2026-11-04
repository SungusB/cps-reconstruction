// Prelude for the generated C++ suite (benchmark-gen/cpp). Same API as
// llvm/benchmark/cpp/common.hpp, except that task<T> resumes its awaiter
// when it completes (the original resumes the awaiter immediately, which is
// only correct for helpers that suspend at most once), plus opaque_int(),
// may_throw2() and a second exception type.
//   identity / other_identity / tick: awaiters whose readiness is a run-time
//   input (opaque()), so every co_await keeps both the "completed
//   synchronously" and the "suspended" path.
#pragma once
#include <coroutine>
#include <exception>

extern "C" long source();         // SOURCE
extern "C" void sink(long);       // SINK
extern "C" bool opaque();         // independent run-time input
extern "C" long opaque_int();     // independent run-time input, 0..3
extern "C" void schedule(void *); // where a suspended coroutine's handle goes
void may_throw();                 // throws IllegalState when an input says so
void may_throw2();                // throws Failure when an input says so
struct IllegalState {};
struct Failure {};

struct Ident {
  long v;
  bool await_ready() const noexcept { return opaque(); }
  void await_suspend(std::coroutine_handle<> h) const noexcept { schedule(h.address()); }
  long await_resume() const noexcept { return v; }
};
inline Ident identity(long x) { return {x}; }
inline Ident other_identity(long x) { return {x}; }
inline Ident tick() { return {1}; }

/// Eager task: runs until its first real suspension when called; when it
/// finishes it schedules whoever awaited it.
template <class T> struct task {
  struct promise_type {
    T value{};
    std::coroutine_handle<> cont;
    task get_return_object() { return {std::coroutine_handle<promise_type>::from_promise(*this)}; }
    std::suspend_never initial_suspend() noexcept { return {}; }
    struct final_awaiter {
      bool await_ready() const noexcept { return false; }
      void await_suspend(std::coroutine_handle<promise_type> h) const noexcept {
        if (h.promise().cont) schedule(h.promise().cont.address());
      }
      void await_resume() const noexcept {}
    };
    final_awaiter final_suspend() noexcept { return {}; }
    void return_value(T v) { value = v; }
    void unhandled_exception() { std::terminate(); }
  };
  std::coroutine_handle<promise_type> h;
  bool await_ready() const noexcept { return h.done(); }
  void await_suspend(std::coroutine_handle<> c) const noexcept { h.promise().cont = c; }
  T await_resume() const noexcept { return h.promise().value; }
};
