// GROUND TRUTH (confirmed)
// expect: NO-FLOW
// note: SPILL-SLOT CONFLATION, inline-block-scope variant. `secret` and
//       `safe` live in disjoint `run {}` scopes, so kotlinc gives them the
//       same JVM local slot and therefore the SAME continuation spill field
//       (verified against javap, kotlinc 2.4.20: both are `astore_3`, both
//       spilled to `L$0`). At suspension 1 `L$0` holds the tainted `secret`;
//       at suspension 2 it holds the untainted `safe`; the case-2 resume
//       path reloads `safe` from `L$0` and prints it. No taint reaches the
//       sink. A field-based taint analysis that doesn't strongly update
//       `L$0`, or merges states at the dispatch switch, reports a false
//       positive here. After reconstruction the spill fields don't exist,
//       so the false positive is impossible by construction.
//       The general rule (from 01-04's bytecode): at each suspension point
//       kotlinc spills every in-scope local, in slot order, to `L$0, L$1,
//       ...` (`I$n`/`Z$n` for primitives), nulling dead ones via
//       `nullOutSpilledVariable`; so the same field index carries different
//       variables whenever the in-scope set differs between suspension
//       points — which is exactly what disjoint scopes produce.
//       WHAT THIS FILE FOUND: this project's own tool reported the false
//       positive (line 30 -> line 37) WITH reconstruction and not without.
//       The then-existing straight-line "splice" path concatenated the
//       switch's case blocks without actually removing the `L$n`
//       spill/reload statements (its filter matched a string SootUp never
//       renders), so after splicing, the tainted spill at suspension 1
//       reached the reload at suspension 2 through one shared field — and
//       `FieldAliasTracker.findFieldDdgEdges` adds a field DDG edge from
//       any write to any reachable read with no kill on intervening writes,
//       so the `L$0 = safe` write in between didn't stop it. Fixed by
//       retiring the splice path: every chain now goes through the
//       general-case fast-path walk, which never emits spill fields.
//       Verified via scripts/run_ablation.sh: the false positive is gone,
//       all real flows unchanged, 02/03 (which were already on the
//       general-case walk) were never affected.
//       EXTERNAL BASELINE (FlowDroid 2.14.1, scripts/run_baseline.sh): on
//       the raw bytecode FlowDroid reports exactly the predicted false
//       positive (this file, 02 and 03; 04's real flow is found either
//       way); on the reconstructed bytecode it does not. Verified mechanism:
//       the continuation class's `invokeSuspend` is an entry point (that is
//       how the runtime resumes), so the tainted `L$0` store from one
//       invocation reaches the case-2 reload through the dispatch switch on
//       the next; `baseline --no-invokesuspend-entry` removes the false
//       positive on raw bytecode, confirming it needs the resumption path.
//       A second bytecode-level tool (FindSecBugs / Joern / Doop — not
//       CodeQL, which analyzes source) on the emitted `.class` files is
//       still to be run.
package benchmark.spillslot.runblockscopes

suspend fun tick(): Int = 1

suspend fun runBlockScopes(): Int {
    val len = run {
        val secret = System.getenv("SECRET") // SOURCE
        tick()                               // suspension 1: secret -> L$0
        secret.length
    }
    run {
        val safe = "constant"
        tick()                               // suspension 2: safe -> L$0
        println(safe)                        // SINK (should NOT be flagged)
    }
    return len
}
