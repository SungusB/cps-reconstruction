define { i32, i32 } @_RNCNvCshapY4OLqbUi_1a5chain0B3_(ptr align 4 %_1, ptr align 8 %_2) unnamed_addr #1 personality ptr @rust_eh_personality {
start:
  %0 = alloca [8 x i8], align 8
  %1 = alloca [8 x i8], align 8
  %2 = alloca [8 x i8], align 8
  %3 = alloca [8 x i8], align 8
  %4 = alloca [8 x i8], align 8
  %5 = alloca [8 x i8], align 8
  %6 = alloca [16 x i8], align 8
  %_task_context = alloca [8 x i8], align 8
  %_21 = alloca [8 x i8], align 4
  %_17 = alloca [8 x i8], align 4
  %_16 = alloca [8 x i8], align 4
  %_8 = alloca [8 x i8], align 4
  %_7 = alloca [8 x i8], align 4
  %_6 = alloca [8 x i8], align 4
  %r = alloca [4 x i8], align 4
  %_0 = alloca [8 x i8], align 4
  store ptr %_2, ptr %_task_context, align 8
  %7 = getelementptr inbounds i8, ptr %_1, i64 4
  %8 = load i8, ptr %7, align 4
  %_30 = zext i8 %8 to i32
  switch i32 %_30, label %bb7 [
    i32 0, label %bb28
    i32 1, label %bb27
    i32 2, label %bb26
    i32 3, label %bb24
    i32 4, label %bb25
  ]

bb7:                                              ; preds = %start
  unreachable

bb28:                                             ; preds = %start
  %secret = load i32, ptr %_1, align 4
  %9 = getelementptr inbounds i8, ptr %_1, i64 5
  %10 = load i8, ptr %9, align 1
  %flag = trunc nuw i8 %10 to i1
  store i32 0, ptr %r, align 4
  br i1 %flag, label %bb1, label %bb11

bb27:                                             ; preds = %bb27, %start
  br i1 false, label %bb27, label %panic

bb26:                                             ; preds = %bb26, %start
  br i1 false, label %bb26, label %panic1

bb24:                                             ; preds = %start
  %_15 = load ptr, ptr %_task_context, align 8
  store ptr %_15, ptr %_task_context, align 8
  br label %bb4

bb25:                                             ; preds = %start
  %_28 = load ptr, ptr %_task_context, align 8
  store ptr %_28, ptr %_task_context, align 8
  br label %bb15

bb11:                                             ; preds = %bb10, %bb28
  %_19 = load i32, ptr %r, align 4
  %11 = call { i32, i1 } @llvm.sadd.with.overflow.i32(i32 %_19, i32 1)
  %_20.0 = extractvalue { i32, i1 } %11, 0
  %_20.1 = extractvalue { i32, i1 } %11, 1
  br i1 %_20.1, label %panic3, label %bb12

bb1:                                              ; preds = %bb28
; invoke a::identity
  %12 = invoke i64 @_RNvCshapY4OLqbUi_1a8identity(i32 %secret)
          to label %bb2 unwind label %cleanup

bb23:                                             ; preds = %bb21, %bb22, %cleanup
  %13 = getelementptr inbounds i8, ptr %_1, i64 4
  store i8 2, ptr %13, align 4
  %14 = load ptr, ptr %6, align 8
  %15 = getelementptr inbounds i8, ptr %6, i64 8
  %16 = load i32, ptr %15, align 8
  %17 = insertvalue { ptr, i32 } poison, ptr %14, 0
  %18 = insertvalue { ptr, i32 } %17, i32 %16, 1
  resume { ptr, i32 } %18

cleanup:                                          ; preds = %bb19, %bb13, %bb12, %panic3, %bb9, %bb2, %bb1
  %19 = landingpad { ptr, i32 }
          cleanup
  %20 = extractvalue { ptr, i32 } %19, 0
  %21 = extractvalue { ptr, i32 } %19, 1
  store ptr %20, ptr %6, align 8
  %22 = getelementptr inbounds i8, ptr %6, i64 8
  store i32 %21, ptr %22, align 8
  br label %bb23

bb2:                                              ; preds = %bb1
  store i64 %12, ptr %5, align 8
  call void @llvm.memcpy.p0.p0.i64(ptr align 4 %_7, ptr align 8 %5, i64 8, i1 false)
  call void @llvm.memcpy.p0.p0.i64(ptr align 8 %4, ptr align 4 %_7, i64 8, i1 false)
  %23 = load i64, ptr %4, align 8
; invoke <a::identity::{closure#0} as core::future::into_future::IntoFuture>::into_future
  %24 = invoke i64 @_RNvXNtNtCsgxBkk5gSRhY_4core6future11into_futureNCNvCshapY4OLqbUi_1a8identity0NtB2_10IntoFuture11into_futureBN_(i64 %23)
          to label %bb3 unwind label %cleanup

bb3:                                              ; preds = %bb2
  store i64 %24, ptr %3, align 8
  call void @llvm.memcpy.p0.p0.i64(ptr align 4 %_6, ptr align 8 %3, i64 8, i1 false)
  %25 = getelementptr inbounds i8, ptr %_1, i64 8
  call void @llvm.memcpy.p0.p0.i64(ptr align 4 %25, ptr align 4 %_6, i64 8, i1 false)
  br label %bb4

bb4:                                              ; preds = %bb24, %bb3
  %_10 = getelementptr inbounds i8, ptr %_1, i64 8
  br label %bb5

panic:                                            ; preds = %bb27
; call core::panicking::panic_const::panic_const_async_fn_resumed
  call void @_RNvNtNtCsgxBkk5gSRhY_4core9panicking11panic_const28panic_const_async_fn_resumed(ptr align 8 @alloc_cb90d5b0453b6a43d7640f92abc503ba) #17
  unreachable

panic1:                                           ; preds = %bb26
; call core::panicking::panic_const::panic_const_async_fn_resumed_panic
  call void @_RNvNtNtCsgxBkk5gSRhY_4core9panicking11panic_const34panic_const_async_fn_resumed_panic(ptr align 8 @alloc_cb90d5b0453b6a43d7640f92abc503ba) #17
  unreachable

bb22:                                             ; preds = %cleanup2
  %26 = getelementptr inbounds i8, ptr %_1, i64 8
; invoke core::ptr::drop_glue::<a::identity::{closure#0}>
  invoke void @_RINvNtCsgxBkk5gSRhY_4core3ptr9drop_glueNCNvCshapY4OLqbUi_1a8identity0EBF_(ptr align 4 %26) #14
          to label %bb23 unwind label %terminate

cleanup2:                                         ; preds = %bb5
  %27 = landingpad { ptr, i32 }
          cleanup
  %28 = extractvalue { ptr, i32 } %27, 0
  %29 = extractvalue { ptr, i32 } %27, 1
  store ptr %28, ptr %6, align 8
  %30 = getelementptr inbounds i8, ptr %6, i64 8
  store i32 %29, ptr %30, align 8
  br label %bb22

bb5:                                              ; preds = %bb4
  %_12 = load ptr, ptr %_task_context, align 8
; invoke a::identity::{closure#0}
  %31 = invoke { i32, i32 } @_RNCNvCshapY4OLqbUi_1a8identity0B3_(ptr align 4 %_10, ptr align 8 %_12)
          to label %bb6 unwind label %cleanup2

bb6:                                              ; preds = %bb5
  %32 = extractvalue { i32, i32 } %31, 0
  %33 = extractvalue { i32, i32 } %31, 1
  store i32 %32, ptr %_8, align 4
  %34 = getelementptr inbounds i8, ptr %_8, i64 4
  store i32 %33, ptr %34, align 4
  %35 = load i32, ptr %_8, align 4
  %36 = getelementptr inbounds i8, ptr %_8, i64 4
  %37 = load i32, ptr %36, align 4
  %_13 = zext i32 %35 to i64
  %38 = trunc nuw i64 %_13 to i1
  br i1 %38, label %bb8, label %bb9

bb8:                                              ; preds = %bb6
  store i32 1, ptr %_0, align 4
  %39 = getelementptr inbounds i8, ptr %_1, i64 4
  store i8 3, ptr %39, align 4
  %40 = load i32, ptr %_0, align 4
  %41 = getelementptr inbounds i8, ptr %_0, i64 4
  %42 = load i32, ptr %41, align 4
  %43 = insertvalue { i32, i32 } poison, i32 %40, 0
  %44 = insertvalue { i32, i32 } %43, i32 %42, 1
  ret { i32, i32 } %44

bb9:                                              ; preds = %bb6
  %45 = getelementptr inbounds i8, ptr %_8, i64 4
  %result = load i32, ptr %45, align 4
  %46 = getelementptr inbounds i8, ptr %_1, i64 8
; invoke core::ptr::drop_glue::<a::identity::{closure#0}>
  invoke void @_RINvNtCsgxBkk5gSRhY_4core3ptr9drop_glueNCNvCshapY4OLqbUi_1a8identity0EBF_(ptr align 4 %46)
          to label %bb10 unwind label %cleanup

bb10:                                             ; preds = %bb9
  store i32 %result, ptr %r, align 4
  br label %bb11

bb12:                                             ; preds = %bb11
; invoke a::identity
  %47 = invoke i64 @_RNvCshapY4OLqbUi_1a8identity(i32 %_20.0)
          to label %bb13 unwind label %cleanup

panic3:                                           ; preds = %bb11
; invoke core::panicking::panic_const::panic_const_add_overflow
  invoke void @_RNvNtNtCsgxBkk5gSRhY_4core9panicking11panic_const24panic_const_add_overflow(ptr align 8 @alloc_0df8a7b0261b9ad1c2ec39bae4a55611) #18
          to label %unreachable unwind label %cleanup

unreachable:                                      ; preds = %panic3
  unreachable

bb13:                                             ; preds = %bb12
  store i64 %47, ptr %2, align 8
  call void @llvm.memcpy.p0.p0.i64(ptr align 4 %_17, ptr align 8 %2, i64 8, i1 false)
  call void @llvm.memcpy.p0.p0.i64(ptr align 8 %1, ptr align 4 %_17, i64 8, i1 false)
  %48 = load i64, ptr %1, align 8
; invoke <a::identity::{closure#0} as core::future::into_future::IntoFuture>::into_future
  %49 = invoke i64 @_RNvXNtNtCsgxBkk5gSRhY_4core6future11into_futureNCNvCshapY4OLqbUi_1a8identity0NtB2_10IntoFuture11into_futureBN_(i64 %48)
          to label %bb14 unwind label %cleanup

bb14:                                             ; preds = %bb13
  store i64 %49, ptr %0, align 8
  call void @llvm.memcpy.p0.p0.i64(ptr align 4 %_16, ptr align 8 %0, i64 8, i1 false)
  %50 = getelementptr inbounds i8, ptr %_1, i64 8
  call void @llvm.memcpy.p0.p0.i64(ptr align 4 %50, ptr align 4 %_16, i64 8, i1 false)
  br label %bb15

bb15:                                             ; preds = %bb25, %bb14
  %_23 = getelementptr inbounds i8, ptr %_1, i64 8
  br label %bb16

terminate:                                        ; preds = %bb21, %bb22
  %51 = landingpad { ptr, i32 }
          filter [0 x ptr] zeroinitializer
; call core::panicking::panic_in_cleanup
  call void @_RNvNtCsgxBkk5gSRhY_4core9panicking16panic_in_cleanup() #16
  unreachable

bb21:                                             ; preds = %cleanup4
  %52 = getelementptr inbounds i8, ptr %_1, i64 8
; invoke core::ptr::drop_glue::<a::identity::{closure#0}>
  invoke void @_RINvNtCsgxBkk5gSRhY_4core3ptr9drop_glueNCNvCshapY4OLqbUi_1a8identity0EBF_(ptr align 4 %52) #14
          to label %bb23 unwind label %terminate

cleanup4:                                         ; preds = %bb16
  %53 = landingpad { ptr, i32 }
          cleanup
  %54 = extractvalue { ptr, i32 } %53, 0
  %55 = extractvalue { ptr, i32 } %53, 1
  store ptr %54, ptr %6, align 8
  %56 = getelementptr inbounds i8, ptr %6, i64 8
  store i32 %55, ptr %56, align 8
  br label %bb21

bb16:                                             ; preds = %bb15
  %_25 = load ptr, ptr %_task_context, align 8
; invoke a::identity::{closure#0}
  %57 = invoke { i32, i32 } @_RNCNvCshapY4OLqbUi_1a8identity0B3_(ptr align 4 %_23, ptr align 8 %_25)
          to label %bb17 unwind label %cleanup4

bb17:                                             ; preds = %bb16
  %58 = extractvalue { i32, i32 } %57, 0
  %59 = extractvalue { i32, i32 } %57, 1
  store i32 %58, ptr %_21, align 4
  %60 = getelementptr inbounds i8, ptr %_21, i64 4
  store i32 %59, ptr %60, align 4
  %61 = load i32, ptr %_21, align 4
  %62 = getelementptr inbounds i8, ptr %_21, i64 4
  %63 = load i32, ptr %62, align 4
  %_26 = zext i32 %61 to i64
  %64 = trunc nuw i64 %_26 to i1
  br i1 %64, label %bb18, label %bb19

bb18:                                             ; preds = %bb17
  store i32 1, ptr %_0, align 4
  %65 = getelementptr inbounds i8, ptr %_1, i64 4
  store i8 4, ptr %65, align 4
  %66 = load i32, ptr %_0, align 4
  %67 = getelementptr inbounds i8, ptr %_0, i64 4
  %68 = load i32, ptr %67, align 4
  %69 = insertvalue { i32, i32 } poison, i32 %66, 0
  %70 = insertvalue { i32, i32 } %69, i32 %68, 1
  ret { i32, i32 } %70

bb19:                                             ; preds = %bb17
  %71 = getelementptr inbounds i8, ptr %_21, i64 4
  %result5 = load i32, ptr %71, align 4
  %72 = getelementptr inbounds i8, ptr %_1, i64 8
; invoke core::ptr::drop_glue::<a::identity::{closure#0}>
  invoke void @_RINvNtCsgxBkk5gSRhY_4core3ptr9drop_glueNCNvCshapY4OLqbUi_1a8identity0EBF_(ptr align 4 %72)
          to label %bb20 unwind label %cleanup

bb20:                                             ; preds = %bb19
  store i32 %result5, ptr %r, align 4
  %_29 = load i32, ptr %r, align 4
  %73 = getelementptr inbounds i8, ptr %_0, i64 4
  store i32 %_29, ptr %73, align 4
  store i32 0, ptr %_0, align 4
  %74 = getelementptr inbounds i8, ptr %_1, i64 4
  store i8 1, ptr %74, align 4
  %75 = load i32, ptr %_0, align 4
  %76 = getelementptr inbounds i8, ptr %_0, i64 4
  %77 = load i32, ptr %76, align 4
  %78 = insertvalue { i32, i32 } poison, i32 %75, 0
  %79 = insertvalue { i32, i32 } %78, i32 %77, 1
  ret { i32, i32 } %79
}

; a::identity::{closure#0}
; Function Attrs: inlinehint nonlazybind uwtable
define { i32, i32 } @_RNCNvCshapY4OLqbUi_1a8identity0B3_(ptr align 4 %_1, ptr align 8 %_2) unnamed_addr #1 personality ptr @rust_eh_personality {
start:
  %0 = alloca [16 x i8], align 8
  %1 = getelementptr inbounds i8, ptr %_1, i64 4
  %2 = load i8, ptr %1, align 4
  %_5 = zext i8 %2 to i32
  switch i32 %_5, label %bb5 [
    i32 0, label %bb6
    i32 1, label %bb4
    i32 2, label %bb3
  ]

bb5:                                              ; preds = %start
  unreachable

bb6:                                              ; preds = %start
  %x = load i32, ptr %_1, align 4
; invoke core::hint::black_box::<i32>
  %_4 = invoke i32 @_RINvNtCsgxBkk5gSRhY_4core4hint9black_boxlECshapY4OLqbUi_1a(i32 %x)
          to label %bb1 unwind label %cleanup

bb4:                                              ; preds = %bb4, %start
  br i1 false, label %bb4, label %panic

bb3:                                              ; preds = %bb3, %start
  br i1 false, label %bb3, label %panic1

bb2:                                              ; preds = %cleanup
  %3 = getelementptr inbounds i8, ptr %_1, i64 4
  store i8 2, ptr %3, align 4
  %4 = load ptr, ptr %0, align 8
  %5 = getelementptr inbounds i8, ptr %0, i64 8
  %6 = load i32, ptr %5, align 8
  %7 = insertvalue { ptr, i32 } poison, ptr %4, 0
  %8 = insertvalue { ptr, i32 } %7, i32 %6, 1
  resume { ptr, i32 } %8

cleanup:                                          ; preds = %bb6
  %9 = landingpad { ptr, i32 }
          cleanup
  %10 = extractvalue { ptr, i32 } %9, 0
  %11 = extractvalue { ptr, i32 } %9, 1
  store ptr %10, ptr %0, align 8
  %12 = getelementptr inbounds i8, ptr %0, i64 8
  store i32 %11, ptr %12, align 8
  br label %bb2

bb1:                                              ; preds = %bb6
  %13 = getelementptr inbounds i8, ptr %_1, i64 4
  store i8 1, ptr %13, align 4
  %14 = insertvalue { i32, i32 } { i32 0, i32 poison }, i32 %_4, 1
  ret { i32, i32 } %14

panic:                                            ; preds = %bb4
; call core::panicking::panic_const::panic_const_async_fn_resumed
  call void @_RNvNtNtCsgxBkk5gSRhY_4core9panicking11panic_const28panic_const_async_fn_resumed(ptr align 8 @alloc_6a1958e80fe0b467a1daca746182bf4e) #17
  unreachable

panic1:                                           ; preds = %bb3
; call core::panicking::panic_const::panic_const_async_fn_resumed_panic
  call void @_RNvNtNtCsgxBkk5gSRhY_4core9panicking11panic_const34panic_const_async_fn_resumed_panic(ptr align 8 @alloc_6a1958e80fe0b467a1daca746182bf4e) #17
  unreachable
}

; <core::task::wake::RawWaker>::NOOP::VTABLE::{closure#0}
; Function Attrs: inlinehint nonlazybind uwtable
define { ptr, ptr } @_RNCNvNvMNtNtCsgxBkk5gSRhY_4core4task4wakeNtB6_8RawWaker4NOOP6VTABLE0CshapY4OLqbUi_1a(ptr %_1, ptr %_2) unnamed_addr #1 {
start:
  ret { ptr, ptr } { ptr @alloc_2d62d3955f3ee2697ac011fbaa57e997, ptr null }
}

; <core::task::wake::RawWaker>::NOOP::VTABLE::{closure#2}
; Function Attrs: inlinehint nonlazybind uwtable
define void @_RNCNvNvMNtNtCsgxBkk5gSRhY_4core4task4wakeNtB6_8RawWaker4NOOP6VTABLEs0_0CshapY4OLqbUi_1a(ptr %_1, ptr %_2) unnamed_addr #1 {
start:
  ret void
}

; <core::task::wake::RawWaker>::NOOP::VTABLE::{closure#3}
; Function Attrs: inlinehint nonlazybind uwtable
define void @_RNCNvNvMNtNtCsgxBkk5gSRhY_4core4task4wakeNtB6_8RawWaker4NOOP6VTABLEs1_0CshapY4OLqbUi_1a(ptr %_1, ptr %_2) unnamed_addr #1 {
start:
  ret void
}

; <core::task::wake::RawWaker>::NOOP::VTABLE::{closure#1}
; Function Attrs: inlinehint nonlazybind uwtable
define void @_RNCNvNvMNtNtCsgxBkk5gSRhY_4core4task4wakeNtB6_8RawWaker4NOOP6VTABLEs_0CshapY4OLqbUi_1a(ptr %_1, ptr %_2) unnamed_addr #1 {
start:
  ret void
}

; a::chain
; Function Attrs: nonlazybind uwtable
