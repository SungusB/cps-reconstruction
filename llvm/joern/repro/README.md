# Joern (jimple2cpg) reproducers — no coroutines involved

Two plain-Java programs that isolate the Joern behaviors which decide the
Kotlin raw-vs-reconstructed results. Joern master at f7e618c62.

    javac -g:none -d out p/Tgt.java    # or -g, see below
    jimple2cpg out --output tgt.cpg
    joern: cpg.call.nameExact("println").argument.filter(_.argumentIndex > 0)
             .reachableBy(cpg.call.nameExact("getenv"))

1. `Tgt.java` — **jump-target operands are skipped.** `if (r == SENTINEL)
   return ...; String a = (String) r; println(a)` reports 0 flows: the CFG
   edge for the branch goes to the target statement's root
   (`AstForMethodsCreator`, `addEdge(..., targetAsts.last.root.get, CFG)`),
   so the operand `r` is never visited on that edge and receives no reaching
   definition. kotlinc emits this exact shape at every suspension point
   (`if (r != COROUTINE_SUSPENDED) goto L; L: a = (String) r`).
   `../jimple2cpg-cfg-entry.patch` links to the statement's first-evaluated
   node instead; with it, 1 flow.
2. `Ctor.java` — **constructor-mediated flows need a LocalVariableTable.**
   Compiled with `-g:none`, `new Box(getenv(..))` -> `println(b)` reports 0
   flows; with `-g`, 1. Without the table Soot names the receiver `l0` in
   the body while jimple2cpg names parameter 0 `this`, so taint written to
   `l0.v` never leaves the constructor.
