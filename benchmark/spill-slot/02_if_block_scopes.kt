// GROUND TRUTH (confirmed)
// expect: NO-FLOW
// note: SPILL-SLOT CONFLATION, sibling-`if`-block variant (no inline
//       lambda involved; real branching between the suspension points).
//       `secret` and `safe` are declared in two
//       sequential `if` blocks, share a JVM slot, and are spilled to the
//       same field `L$0` at their respective suspension points (verified
//       against javap). The sink prints the untainted `safe` reloaded from
//       `L$0` on the case-2 resume path. See 01 for the full argument.
package benchmark.spillslot.ifblockscopes

suspend fun tick(): Int = 1

suspend fun ifBlockScopes(flag: Boolean): Int {
    var len = 0
    if (flag) {
        val secret = System.getenv("SECRET") // SOURCE
        tick()                               // suspension 1: secret -> L$0
        len = secret.length
    }
    if (!flag) {
        val safe = "constant"
        tick()                               // suspension 2: safe -> L$0
        println(safe)                        // SINK (should NOT be flagged)
    }
    return len
}
