define internal { i32, i32 } @_RNCNvCs8985DmDssy5_1b5chain0B3_(ptr noundef nonnull align 4 captures(none) %_1, ptr noalias nofree readnone align 8 captures(none) %_2) unnamed_addr #1 personality ptr @rust_eh_personality {
start:
  %_0.i.i38 = alloca [4 x i8], align 4
  %_0.i.i = alloca [4 x i8], align 4
  %0 = getelementptr inbounds nuw i8, ptr %_1, i64 4
  %1 = load i8, ptr %0, align 4, !range !4, !noundef !5
  switch i8 %1, label %default.unreachable48 [
    i8 0, label %bb36
    i8 1, label %panic
    i8 2, label %panic1
    i8 3, label %bb5
    i8 4, label %bb15
  ]

default.unreachable48:                            ; preds = %bb15, %bb5, %start
  unreachable

bb36:                                             ; preds = %start
  %2 = getelementptr inbounds nuw i8, ptr %_1, i64 5
  %3 = load i8, ptr %2, align 1, !range !6, !noundef !5
  %flag = trunc nuw i8 %3 to i1
  br i1 %flag, label %bb5.thread, label %bb15.thread

bb15.thread:                                      ; preds = %bb36, %bb10
  %r.sroa.0.0 = phi i32 [ %11, %bb10 ], [ 1, %bb36 ]
; call b::identity
  %4 = call i64 @_RNvCs8985DmDssy5_1b8identity(i32 noundef %r.sroa.0.0)
  %5 = getelementptr inbounds nuw i8, ptr %_1, i64 8
  store i64 %4, ptr %5, align 4
  br label %bb19

bb5.thread:                                       ; preds = %bb36
  %secret = load i32, ptr %_1, align 4, !noundef !5
; call b::identity
  %6 = tail call i64 @_RNvCs8985DmDssy5_1b8identity(i32 noundef %secret)
  %7 = getelementptr inbounds nuw i8, ptr %_1, i64 8
  store i64 %6, ptr %7, align 4
  br label %bb10

panic:                                            ; preds = %start
; call core::panicking::panic_const::panic_const_async_fn_resumed
  tail call void @_RNvNtNtCsgxBkk5gSRhY_4core9panicking11panic_const28panic_const_async_fn_resumed(ptr noalias nofree noundef readonly align 8 captures(address, read_provenance) dereferenceable(24) @alloc_fc89e3b43950d7fff8d9f1a94b04546d) #9
  unreachable

panic1:                                           ; preds = %start
; call core::panicking::panic_const::panic_const_async_fn_resumed_panic
  tail call void @_RNvNtNtCsgxBkk5gSRhY_4core9panicking11panic_const34panic_const_async_fn_resumed_panic(ptr noalias nofree noundef readonly align 8 captures(address, read_provenance) dereferenceable(24) @alloc_fc89e3b43950d7fff8d9f1a94b04546d) #9
  unreachable

bb5:                                              ; preds = %start
  %.phi.trans.insert = getelementptr inbounds nuw i8, ptr %_1, i64 12
  %.pre = load i8, ptr %.phi.trans.insert, align 4, !range !7
  switch i8 %.pre, label %default.unreachable48 [
    i8 0, label %bb10
    i8 1, label %panic.i
    i8 2, label %panic1.i
  ]

panic.i:                                          ; preds = %bb5
; invoke core::panicking::panic_const::panic_const_async_fn_resumed
  invoke void @_RNvNtNtCsgxBkk5gSRhY_4core9panicking11panic_const28panic_const_async_fn_resumed(ptr noalias nofree noundef readonly align 8 captures(address, read_provenance) dereferenceable(24) @alloc_452e2932cac15f84c9fdc2f858d93931) #9
          to label %.noexc unwind label %cleanup3

.noexc:                                           ; preds = %panic.i
  unreachable

panic1.i:                                         ; preds = %bb5
; invoke core::panicking::panic_const::panic_const_async_fn_resumed_panic
  invoke void @_RNvNtNtCsgxBkk5gSRhY_4core9panicking11panic_const34panic_const_async_fn_resumed_panic(ptr noalias nofree noundef readonly align 8 captures(address, read_provenance) dereferenceable(24) @alloc_452e2932cac15f84c9fdc2f858d93931) #9
          to label %.noexc37 unwind label %cleanup3

.noexc37:                                         ; preds = %panic1.i
  unreachable

cleanup3:                                         ; preds = %panic1.i, %panic.i
  %8 = landingpad { ptr, i32 }
          cleanup
  br label %bb31

bb10:                                             ; preds = %bb5.thread, %bb5
  %9 = getelementptr inbounds nuw i8, ptr %_1, i64 12
  %_9 = getelementptr inbounds nuw i8, ptr %_1, i64 8
  %x.i = load i32, ptr %_9, align 4, !noundef !5
  call void @llvm.lifetime.start.p0(ptr nonnull %_0.i.i)
  store i32 %x.i, ptr %_0.i.i, align 4
  call void asm sideeffect "", "r,~{memory}"(ptr nonnull %_0.i.i) #10, !srcloc !8
  %10 = load i32, ptr %_0.i.i, align 4, !noundef !5
  call void @llvm.lifetime.end.p0(ptr nonnull %_0.i.i)
  store i8 1, ptr %9, align 4
  %11 = add i32 %10, 1
  br label %bb15.thread

bb31:                                             ; preds = %cleanup8, %cleanup3
  %.pn34.pn = phi { ptr, i32 } [ %12, %cleanup8 ], [ %8, %cleanup3 ]
  store i8 2, ptr %0, align 4
  resume { ptr, i32 } %.pn34.pn

bb15:                                             ; preds = %start
  %.phi.trans.insert46 = getelementptr inbounds nuw i8, ptr %_1, i64 12
  %.pre47 = load i8, ptr %.phi.trans.insert46, align 4, !range !7
  switch i8 %.pre47, label %default.unreachable48 [
    i8 0, label %bb19
    i8 1, label %panic.i40
    i8 2, label %panic1.i39
  ]

panic.i40:                                        ; preds = %bb15
; invoke core::panicking::panic_const::panic_const_async_fn_resumed
  invoke void @_RNvNtNtCsgxBkk5gSRhY_4core9panicking11panic_const28panic_const_async_fn_resumed(ptr noalias nofree noundef readonly align 8 captures(address, read_provenance) dereferenceable(24) @alloc_452e2932cac15f84c9fdc2f858d93931) #9
          to label %.noexc43 unwind label %cleanup8

.noexc43:                                         ; preds = %panic.i40
  unreachable

panic1.i39:                                       ; preds = %bb15
; invoke core::panicking::panic_const::panic_const_async_fn_resumed_panic
  invoke void @_RNvNtNtCsgxBkk5gSRhY_4core9panicking11panic_const34panic_const_async_fn_resumed_panic(ptr noalias nofree noundef readonly align 8 captures(address, read_provenance) dereferenceable(24) @alloc_452e2932cac15f84c9fdc2f858d93931) #9
          to label %.noexc44 unwind label %cleanup8

.noexc44:                                         ; preds = %panic1.i39
  unreachable

cleanup8:                                         ; preds = %panic1.i39, %panic.i40
  %12 = landingpad { ptr, i32 }
          cleanup
  br label %bb31

bb19:                                             ; preds = %bb15.thread, %bb15
  %13 = getelementptr inbounds nuw i8, ptr %_1, i64 12
  %_18 = getelementptr inbounds nuw i8, ptr %_1, i64 8
  %x.i41 = load i32, ptr %_18, align 4, !noundef !5
  call void @llvm.lifetime.start.p0(ptr nonnull %_0.i.i38)
  store i32 %x.i41, ptr %_0.i.i38, align 4
  call void asm sideeffect "", "r,~{memory}"(ptr nonnull %_0.i.i38) #10, !srcloc !8
  %14 = load i32, ptr %_0.i.i38, align 4, !noundef !5
  call void @llvm.lifetime.end.p0(ptr nonnull %_0.i.i38)
  store i8 1, ptr %13, align 4
  %15 = insertvalue { i32, i32 } { i32 0, i32 poison }, i32 %14, 1
  store i8 1, ptr %0, align 4
  ret { i32, i32 } %15
}
