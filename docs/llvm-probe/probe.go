package main

import "iter"

//go:noinline
func source() int { return 42 }
//go:noinline
func sink(int) {}
//go:noinline
func fetch(x int) int { return x + 1 }

// goroutine + channel: the "async" idiom
func chanFlow(c bool) {
	ch := make(chan int)
	go func() { ch <- fetch(source()) }()
	a := <-ch
	if c {
		sink(a)
	}
}

func seq(n int) iter.Seq[int] {
	return func(yield func(int) bool) {
		for i := 0; i < n; i++ {
			if !yield(i) { return }
		}
	}
}

// range-over-func: compiler rewrites the loop body into a closure
func rangeFlow(n int) int {
	secret := source()
	for x := range seq(n) {
		if x == 3 { sink(secret); break }
		if x == 5 { return x }
	}
	return 0
}

func main() { chanFlow(true); rangeFlow(10) }
