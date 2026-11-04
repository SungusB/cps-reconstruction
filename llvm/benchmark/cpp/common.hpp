// Shared prelude for every C++ benchmark. Mirrors the Kotlin suite's helpers:
//   suspend fun identity(x) = x / tick() = 1  -> an awaiter whose readiness is
//   only known at run time (opaque()), so every co_await keeps both the
//   "completed synchronously" and the "suspended" path, as kotlinc's
//   COROUTINE_SUSPENDED check does.
#pragma once
#include <coroutine>
#include <exception>

extern "C" long source();         // SOURCE
extern "C" void sink(long);       // SINK
extern "C" bool opaque();         // runtime-only condition
extern "C" void schedule(void *); // where a suspended coroutine's handle goes
void may_throw();                 // an ordinary call with an exceptional edge
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

/// Eager task (runs until its first real suspension when called), awaitable.
template <class T> struct task {
  struct promise_type {
    T value{};
    task get_return_object() { return {std::coroutine_handle<promise_type>::from_promise(*this)}; }
    std::suspend_never initial_suspend() noexcept { return {}; }
    std::suspend_always final_suspend() noexcept { return {}; }
    void return_value(T v) { value = v; }
    void unhandled_exception() { std::terminate(); }
  };
  std::coroutine_handle<promise_type> h;
  bool await_ready() const noexcept { return h.done(); }
  void await_suspend(std::coroutine_handle<> c) const noexcept { schedule(c.address()); }
  T await_resume() const noexcept { return h.promise().value; }
};
template <> struct task<void> {
  struct promise_type {
    task get_return_object() { return {std::coroutine_handle<promise_type>::from_promise(*this)}; }
    std::suspend_never initial_suspend() noexcept { return {}; }
    std::suspend_always final_suspend() noexcept { return {}; }
    void return_void() {}
    void unhandled_exception() { std::terminate(); }
  };
  std::coroutine_handle<promise_type> h;
  bool await_ready() const noexcept { return h.done(); }
  void await_suspend(std::coroutine_handle<> c) const noexcept { schedule(c.address()); }
  void await_resume() const noexcept {}
};
