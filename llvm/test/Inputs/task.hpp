// Minimal eager task + runtime-ready awaiter for the C++ lit tests.
#include <coroutine>
#include <exception>
struct task {
  struct promise_type {
    int v = 0;
    task get_return_object() { return {std::coroutine_handle<promise_type>::from_promise(*this)}; }
    std::suspend_never initial_suspend() noexcept { return {}; }
    std::suspend_always final_suspend() noexcept { return {}; }
    void return_value(int x) { v = x; }
    void unhandled_exception() { std::terminate(); }
  };
  std::coroutine_handle<promise_type> h;
};
struct aw {
  bool ready; int val;
  bool await_ready() const noexcept { return ready; }
  void await_suspend(std::coroutine_handle<>) noexcept {}
  int await_resume() const noexcept { return val; }
};
extern aw fetch(int);
extern void sink(int);
extern int source();
