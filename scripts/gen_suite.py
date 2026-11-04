#!/usr/bin/env python3
"""Generate the synthetic coroutine taint suite (benchmark-gen/).

One program is written once in a small structured IR and rendered to Kotlin
(suspend fun), Rust (async fn) and C++20 (task<long> coroutine), so the three
languages share test cases and the same ground truth.

Every program has exactly one SOURCE (Kotlin `System.getenv`, Rust/C++
`source()`) and exactly one SINK (Kotlin `println`, Rust/C++ `sink()`), and
every branch, loop condition, loop bound and `when`/`match`/`switch` subject
is a fresh call to `opaque()` / `opaqueInt()`. Those are independent inputs,
so every syntactic path is feasible (`for` loops run 0..3 times, and the
analysis below unrolls them exactly that far).

Ground truth is computed here, statically: a may-taint analysis over the
structured program (gen/kill on variables and on the fields of one local
holder object, union at joins, fixpoint for unbounded loops, exception edges
from each `mayThrow()` to the innermost handler). The framework is
distributive, so the fixpoint is the meet-over-all-paths solution, and since
all paths are feasible it is exact: FLOW iff some execution carries the
source's value into the sink's argument by explicit data flow. Conditions
never depend on the source, so there are no implicit flows. Each program is
then compiled and run by scripts/gen_validate.py (two secrets, many input
vectors) as an independent, dynamic check of this label.

Templates are also given an intended label by hand; the generator aborts if
the analysis disagrees, so a template that does not test what its name says
cannot slip in.

Usage: gen_suite.py [--seed N] [--out benchmark-gen]
"""
import argparse
import json
import random
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# ---------------------------------------------------------------- IR ------
# Expressions
SRC = {"k": "src"}
def V(n): return {"k": "var", "n": n}
def K(i): return {"k": "const", "i": i}
def XF(e): return {"k": "xf", "e": e}                 # taint-preserving pure op
def CS(fn, e): return {"k": "scall", "fn": fn, "e": e}  # suspend call
def CP(fn, e): return {"k": "pcall", "fn": fn, "e": e}  # plain call
def F(f): return {"k": "field", "f": f}
def AB(e): return {"k": "ablock", "e": e}             # Rust async block


# Statements
def D(v, e): return {"k": "decl", "v": v, "e": e}
def A(v, e): return {"k": "assign", "v": v, "e": e}
def SF(f, e): return {"k": "setf", "f": f, "e": e}
def TICK(): return {"k": "tick"}
def SINK(e): return {"k": "sink", "e": e}
def IF(t, e=None): return {"k": "if", "then": t, "else": e or []}
def WHILE(b): return {"k": "while", "body": b}
def DO(b): return {"k": "do", "body": b}
def FOR(b): return {"k": "for", "body": b}
def SW(a, b, c): return {"k": "switch", "arms": [a, b, c]}
def SCOPE(b): return {"k": "scope", "body": b}
def TRY(b, catches, fin=None): return {"k": "try", "body": b, "catches": catches, "fin": fin}
def THROW(kind=0): return {"k": "throw", "kind": kind}
def BRK(): return {"k": "if", "then": [{"k": "break"}], "else": [], "guard": True}
def CONT(): return {"k": "if", "then": [{"k": "continue"}], "else": [], "guard": True}
def RETG(): return {"k": "if", "then": [{"k": "return"}], "else": [], "guard": True}
def RET(): return {"k": "return"}

PASS = {"identity", "other_identity", "pass", "lam"} | {f"relay{d}" for d in range(16)}
DROP = {"konst", "sdrop", "lamdrop"}
SUSP = {"identity", "other_identity", "sdrop", "lam", "lamdrop"} | {f"relay{d}" for d in range(16)}


# ---------------------------------------------------------- analysis ------
def U(a, b):
    if a is None:
        return b
    if b is None:
        return a
    return a | b


def taint(e, S):
    k = e["k"]
    if k == "src":
        return True
    if k == "var":
        return e["n"] in S
    if k == "field":
        return "h." + e["f"] in S
    if k == "const":
        return False
    if k in ("xf", "ablock"):
        return taint(e["e"], S)
    if k in ("scall", "pcall"):
        if e["fn"] in DROP:
            taint(e["e"], S)
            return False
        assert e["fn"] in PASS, e["fn"]
        return taint(e["e"], S)
    raise ValueError(k)


class Ctx:
    def __init__(self):
        self.flow = False      # sink argument tainted on some path
        self.live = False      # some taint is live at the sink on some path
        self.reach = False     # sink reachable
        self.src_reach = False
        self.susp_live = False  # some taint is live across a suspension point
        self.brk, self.cont, self.thr = [], [], []


def has_src(e):
    return e["k"] == "src" or ("e" in e and has_src(e["e"]))


def run_block(b, S, c):
    for st in b:
        if S is None:
            return None
        S = run_stmt(st, S, c)
    return S


def run_loop(body, S, c, kind):
    c.brk.append(None)
    if kind == "for":                      # 0..3 iterations, exactly
        cur, exits = S, S
        for _ in range(3):
            c.cont.append(None)
            out = run_block(body, cur, c)
            cur = U(out, c.cont.pop())
            exits = U(exits, cur)
            if cur is None:
                break
        return U(exits, c.brk.pop())
    head = S
    while True:
        c.cont.append(None)
        out = U(run_block(body, head, c), c.cont.pop())
        new = U(S, out)
        if new == head:
            break
        head = new
    brk = c.brk.pop()
    return U(head, brk) if kind == "while" else U(out, brk)


def run_stmt(st, S, c):
    k = st["k"]
    if "e" in st and S and any(x["k"] in ("scall", "ablock") for x in walk_exprs(st["e"])):
        c.susp_live = True
    if k in ("decl", "assign", "setf"):
        name = st["v"] if k != "setf" else "h." + st["f"]
        if has_src(st["e"]):
            c.src_reach = True
        t = taint(st["e"], S)
        return (S - {name}) | ({name} if t else frozenset())
    if k == "tick":
        if S:
            c.susp_live = True
        return S
    if k == "sink":
        c.reach = True
        if taint(st["e"], S):
            c.flow = True
        if S:
            c.live = True
        return S
    if k == "if":
        return U(run_block(st["then"], S, c), run_block(st["else"], S, c))
    if k in ("while", "do", "for"):
        return run_loop(st["body"], S, c, k)
    if k == "switch":
        out = None
        for arm in st["arms"]:
            out = U(out, run_block(arm, S, c))
        return out
    if k == "scope":
        return run_block(st["body"], S, c)
    if k == "try":
        kinds = {kd for kd, _ in st["catches"]}
        c.thr.append({"kinds": kinds, 0: None, 1: None})
        out = run_block(st["body"], S, c)
        t = c.thr.pop()
        for kd, hb in st["catches"]:
            out = U(out, run_block(hb, t[kd], c))
        if st["fin"] is not None and out is not None:
            out = run_block(st["fin"], out, c)
        return out
    if k == "throw":
        top = c.thr[-1]
        assert st["kind"] in top["kinds"], "mayThrow kind not caught by the innermost try"
        top[st["kind"]] = U(top[st["kind"]], S)
        return S
    if k == "break":
        c.brk[-1] = U(c.brk[-1], S)
        return None
    if k == "continue":
        c.cont[-1] = U(c.cont[-1], S)
        return None
    if k == "return":
        return None
    raise ValueError(k)


def analyze(body):
    c = Ctx()
    run_block(body, frozenset(), c)
    return c


# ------------------------------------------------------------ metrics -----
def walk_exprs(e):
    yield e
    if "e" in e:
        yield from walk_exprs(e["e"])


def blocks_of(st):
    k = st["k"]
    if k == "if":
        return [st["then"], st["else"]]
    if k in ("while", "do", "for", "scope"):
        return [st["body"]]
    if k == "switch":
        return st["arms"]
    if k == "try":
        return [st["body"]] + [hb for _, hb in st["catches"]] + ([st["fin"]] if st["fin"] is not None else [])
    return []


def walk_stmts(b, depth=0):
    for st in b:
        yield st, depth
        for sub in blocks_of(st):
            yield from walk_stmts(sub, depth + 1)


def metrics(body):
    stmts = susp = 0
    depth = 0
    fns, feats = set(), set()
    for st, d in walk_stmts(body):
        stmts += 1
        depth = max(depth, d)
        k = st["k"]
        feats.add({"decl": "assign", "setf": "field"}.get(k, k))
        if k == "tick":
            susp += 1
        if "e" in st:
            for e in walk_exprs(st["e"]):
                if e["k"] == "scall":
                    susp += 1
                    fns.add(e["fn"])
                elif e["k"] == "pcall":
                    fns.add(e["fn"])
                elif e["k"] == "field":
                    feats.add("field")
                elif e["k"] == "ablock":
                    susp += 1
                    feats.add("ablock")
    feats.discard("assign")
    return {"stmts": stmts, "suspensions": susp, "depth": depth, "fns": sorted(fns),
            "features": sorted(feats - {"tick", "sink"})}


def uses_field(body):
    for st, _ in walk_stmts(body):
        if st["k"] == "setf":
            return True
        if "e" in st and any(e["k"] == "field" for e in walk_exprs(st["e"])):
            return True
    return False


def relay_depth(fns):
    ds = [int(f[5:]) for f in fns if f.startswith("relay")]
    return max(ds) if ds else -1


# ------------------------------------------------------------- render -----
HEADER = """GENERATED by scripts/gen_suite.py (seed {seed}); regenerate, do not edit.
expect: {expect}
family: {family}   case: {cid}   size: {size}
stats: {stmts} statements, {susp} suspension points, nesting depth {depth}
truth: exact static may-taint over all paths (every condition is an
       independent opaque() input); checked dynamically by
       scripts/gen_validate.py (benchmark-gen/validation.json)
note: {note}"""


def header(meta, seed, prefix="// "):
    h = HEADER.format(seed=seed, expect=meta["expect"], family=meta["family"], cid=meta["id"],
                      size=meta["size"], stmts=meta["stmts"], susp=meta["suspensions"],
                      depth=meta["depth"], note=meta["note"])
    return "\n".join(prefix + l if l else prefix.rstrip() for l in h.splitlines()) + "\n"


class Kt:
    ext = "kt"

    @staticmethod
    def expr(e):
        k = e["k"]
        if k == "src":
            return 'System.getenv("SECRET")'
        if k == "var":
            return e["n"]
        if k == "const":
            return f'"k{e["i"]}"'
        if k == "xf":
            return f"({Kt.expr(e['e'])}).trim()"
        if k in ("scall", "pcall"):
            return f"{e['fn']}({Kt.expr(e['e'])})"
        if k == "field":
            return f"h.{e['f']}"
        raise ValueError(k)

    @staticmethod
    def block(b, ind, out, ctr):
        p = "    " * ind
        for st in b:
            k = st["k"]
            tag = "  // SOURCE" if "e" in st and has_src(st["e"]) else ""
            if k == "decl":
                out.append(f"{p}var {st['v']}: String = {Kt.expr(st['e'])}{tag}")
            elif k == "assign":
                out.append(f"{p}{st['v']} = {Kt.expr(st['e'])}{tag}")
            elif k == "setf":
                out.append(f"{p}h.{st['f']} = {Kt.expr(st['e'])}{tag}")
            elif k == "tick":
                out.append(f"{p}tick()")
            elif k == "sink":
                out.append(f"{p}println({Kt.expr(st['e'])})  // SINK{tag}")
            elif k == "if":
                if st.get("guard"):
                    word = st["then"][0]["k"]
                    out.append(f"{p}if (opaque()) {word}")
                    continue
                out.append(f"{p}if (opaque()) {{")
                Kt.block(st["then"], ind + 1, out, ctr)
                if st["else"]:
                    out.append(f"{p}}} else {{")
                    Kt.block(st["else"], ind + 1, out, ctr)
                out.append(f"{p}}}")
            elif k == "while":
                out.append(f"{p}while (opaque()) {{")
                Kt.block(st["body"], ind + 1, out, ctr)
                out.append(f"{p}}}")
            elif k == "do":
                out.append(f"{p}do {{")
                Kt.block(st["body"], ind + 1, out, ctr)
                out.append(f"{p}}} while (opaque())")
            elif k == "for":
                ctr[0] += 1
                out.append(f"{p}for (i{ctr[0]} in 0 until opaqueInt()) {{")
                Kt.block(st["body"], ind + 1, out, ctr)
                out.append(f"{p}}}")
            elif k == "switch":
                out.append(f"{p}when (opaqueInt()) {{")
                for lab, arm in zip(("0", "1", "else"), st["arms"]):
                    out.append(f"{p}    {lab} -> {{")
                    Kt.block(arm, ind + 2, out, ctr)
                    out.append(f"{p}    }}")
                out.append(f"{p}}}")
            elif k == "scope":
                out.append(f"{p}run {{")
                Kt.block(st["body"], ind + 1, out, ctr)
                out.append(f"{p}    Unit")
                out.append(f"{p}}}")
            elif k == "try":
                ctr[0] += 1
                out.append(f"{p}try {{")
                Kt.block(st["body"], ind + 1, out, ctr)
                for kd, hb in st["catches"]:
                    exc = "IllegalStateException" if kd == 0 else "IllegalArgumentException"
                    out.append(f"{p}}} catch (e{ctr[0]}_{kd}: {exc}) {{")
                    Kt.block(hb, ind + 1, out, ctr)
                if st["fin"] is not None:
                    out.append(f"{p}}} finally {{")
                    Kt.block(st["fin"], ind + 1, out, ctr)
                out.append(f"{p}}}")
            elif k == "throw":
                out.append(f"{p}{'mayThrow' if st['kind'] == 0 else 'mayThrow2'}()")
            elif k == "return":
                out.append(f"{p}return")
            else:
                raise ValueError(k)

    @staticmethod
    def render(prog, meta, seed):
        fns = set(meta["fns"])
        o = [header(meta, seed), f"package {meta['kt_package']}", ""]
        o += ["// Inputs for the dynamic check (scripts/gen_validate.py); opaque to the analyses.",
              "object Env {", "    @JvmField var bits: String = \"\"", "    @JvmField var pos: Int = 0", "}", "",
              "fun opaque(): Boolean {", "    val p = Env.pos", "    if (p >= Env.bits.length) return false",
              "    Env.pos = p + 1", "    return Env.bits[p] == '1'", "}", "",
              "fun opaqueInt(): Int = (if (opaque()) 2 else 0) + (if (opaque()) 1 else 0)", "",
              "fun mayThrow() { if (opaque()) throw IllegalStateException(\"boom\") }",
              "fun mayThrow2() { if (opaque()) throw IllegalArgumentException(\"bang\") }", "",
              "suspend fun tick(): Int = 1", "suspend fun identity(x: String): String = x",
              "suspend fun other_identity(x: String): String = x", ""]
        if "pass" in fns:
            o.append("fun pass(x: String): String = x")
        if "konst" in fns:
            o.append('fun konst(x: String): String = "kc"')
        if "sdrop" in fns:
            o.append('suspend fun sdrop(x: String): String { tick(); return "kd" }')
        for d in range(relay_depth(fns) + 1):
            o.append(f"suspend fun relay{d}(x: String): String {{ tick(); return {'x' if d == 0 else f'relay{d - 1}(x)'} }}")
        if prog["field"]:
            o.append('class H { var f0: String = "c"; var f1: String = "c" }')
        o += ["", "suspend fun entry() {"]
        if prog["field"]:
            o.append("    val h = H()")
        if "lam" in fns:
            o.append("    val lam: suspend (String) -> String = { s -> tick(); s }")
        if "lamdrop" in fns:
            o.append('    val lamdrop: suspend (String) -> String = { s -> tick(); "kl" }')
        Kt.block(prog["body"], 1, o, [0])
        o.append("}")
        return "\n".join(o) + "\n"


class Rs:
    ext = "rs"

    @staticmethod
    def expr(e):
        k = e["k"]
        if k == "src":
            return "source()"
        if k == "var":
            return e["n"]
        if k == "const":
            return str(100 + e["i"])
        if k == "xf":
            return f"({Rs.expr(e['e'])} ^ 0x5a)"
        if k == "scall":
            return f"{e['fn']}({Rs.expr(e['e'])}).await"
        if k == "pcall":
            return f"{e['fn']}({Rs.expr(e['e'])})"
        if k == "field":
            return f"h.{e['f']}"
        if k == "ablock":
            return f"async move {{ tick().await; {Rs.expr(e['e'])} }}.await"
        raise ValueError(k)

    @staticmethod
    def block(b, ind, out, ctr):
        p = "    " * ind
        for st in b:
            k = st["k"]
            tag = "  // SOURCE" if "e" in st and has_src(st["e"]) else ""
            if k == "decl":
                out.append(f"{p}let mut {st['v']}: i64 = {Rs.expr(st['e'])};{tag}")
            elif k == "assign":
                out.append(f"{p}{st['v']} = {Rs.expr(st['e'])};{tag}")
            elif k == "setf":
                out.append(f"{p}h.{st['f']} = {Rs.expr(st['e'])};{tag}")
            elif k == "tick":
                out.append(f"{p}tick().await;")
            elif k == "sink":
                out.append(f"{p}sink({Rs.expr(st['e'])});  // SINK{tag}")
            elif k == "if":
                if st.get("guard"):
                    word = {"break": "break;", "continue": "continue;", "return": "return 0;"}[st["then"][0]["k"]]
                    out.append(f"{p}if opaque() {{ {word} }}")
                    continue
                out.append(f"{p}if opaque() {{")
                Rs.block(st["then"], ind + 1, out, ctr)
                if st["else"]:
                    out.append(f"{p}}} else {{")
                    Rs.block(st["else"], ind + 1, out, ctr)
                out.append(f"{p}}}")
            elif k == "while":
                out.append(f"{p}while opaque() {{")
                Rs.block(st["body"], ind + 1, out, ctr)
                out.append(f"{p}}}")
            elif k == "do":
                out.append(f"{p}loop {{")
                Rs.block(st["body"], ind + 1, out, ctr)
                out.append(f"{p}    if !opaque() {{ break; }}")
                out.append(f"{p}}}")
            elif k == "for":
                out.append(f"{p}for _ in 0..opaque_int() {{")
                Rs.block(st["body"], ind + 1, out, ctr)
                out.append(f"{p}}}")
            elif k == "switch":
                out.append(f"{p}match opaque_int() {{")
                for lab, arm in zip(("0", "1", "_"), st["arms"]):
                    out.append(f"{p}    {lab} => {{")
                    Rs.block(arm, ind + 2, out, ctr)
                    out.append(f"{p}    }}")
                out.append(f"{p}}}")
            elif k == "scope":
                out.append(f"{p}{{")
                Rs.block(st["body"], ind + 1, out, ctr)
                out.append(f"{p}}}")
            elif k == "return":
                out.append(f"{p}return 0;")
            else:
                raise ValueError(f"{k} has no Rust rendering")

    @staticmethod
    def render(prog, meta, seed):
        fns = set(meta["fns"])
        o = [header(meta, seed), "#![allow(unused)]", 'include!("../common.rs");', ""]
        if "pass" in fns:
            o.append("fn pass(x: i64) -> i64 { x }")
        if "konst" in fns:
            o.append("fn konst(x: i64) -> i64 { 905 }")
        if "sdrop" in fns:
            o.append("async fn sdrop(x: i64) -> i64 { tick().await; 906 }")
        for d in range(relay_depth(fns) + 1):
            o.append(f"async fn relay{d}(x: i64) -> i64 {{ tick().await; {'x' if d == 0 else f'relay{d - 1}(x).await'} }}")
        if prog["field"]:
            o.append("struct H { f0: i64, f1: i64 }")
        o += ["", "pub async fn run_case() -> i64 {"]
        if prog["field"]:
            o.append("    let mut h = H { f0: 0, f1: 0 };")
        if "lam" in fns:
            o.append("    let lam = |s: i64| async move { tick().await; s };")
        if "lamdrop" in fns:
            o.append("    let lamdrop = |s: i64| async move { tick().await; 907 };")
        Rs.block(prog["body"], 1, o, [0])
        o += ["    0", "}", "#[unsafe(no_mangle)] pub fn entry() -> Boxed<i64> { Box::pin(run_case()) }"]
        return "\n".join(o) + "\n"


class Cc:
    ext = "cpp"

    @staticmethod
    def expr(e):
        k = e["k"]
        if k == "src":
            return "source()"
        if k == "var":
            return e["n"]
        if k == "const":
            return str(100 + e["i"])
        if k == "xf":
            return f"({Cc.expr(e['e'])} ^ 0x5a)"
        if k == "scall":
            return f"(co_await {e['fn']}({Cc.expr(e['e'])}))"
        if k == "pcall":
            return f"{e['fn']}({Cc.expr(e['e'])})"
        if k == "field":
            return f"h.{e['f']}"
        raise ValueError(k)

    @staticmethod
    def block(b, ind, out, ctr):
        p = "  " * ind
        for st in b:
            k = st["k"]
            tag = "  // SOURCE" if "e" in st and has_src(st["e"]) else ""
            if k == "decl":
                out.append(f"{p}long {st['v']} = {Cc.expr(st['e'])};{tag}")
            elif k == "assign":
                out.append(f"{p}{st['v']} = {Cc.expr(st['e'])};{tag}")
            elif k == "setf":
                out.append(f"{p}h.{st['f']} = {Cc.expr(st['e'])};{tag}")
            elif k == "tick":
                out.append(f"{p}co_await tick();")
            elif k == "sink":
                out.append(f"{p}sink({Cc.expr(st['e'])});  // SINK{tag}")
            elif k == "if":
                if st.get("guard"):
                    word = {"break": "break;", "continue": "continue;", "return": "co_return 0;"}[st["then"][0]["k"]]
                    out.append(f"{p}if (opaque()) {{ {word} }}")
                    continue
                out.append(f"{p}if (opaque()) {{")
                Cc.block(st["then"], ind + 1, out, ctr)
                if st["else"]:
                    out.append(f"{p}}} else {{")
                    Cc.block(st["else"], ind + 1, out, ctr)
                out.append(f"{p}}}")
            elif k == "while":
                out.append(f"{p}while (opaque()) {{")
                Cc.block(st["body"], ind + 1, out, ctr)
                out.append(f"{p}}}")
            elif k == "do":
                out.append(f"{p}do {{")
                Cc.block(st["body"], ind + 1, out, ctr)
                out.append(f"{p}}} while (opaque());")
            elif k == "for":
                ctr[0] += 1
                i = ctr[0]
                out.append(f"{p}for (long i{i} = 0, n{i} = opaque_int(); i{i} < n{i}; ++i{i}) {{")
                Cc.block(st["body"], ind + 1, out, ctr)
                out.append(f"{p}}}")
            elif k == "switch":
                out.append(f"{p}switch (opaque_int()) {{")
                for lab, arm in zip(("case 0:", "case 1:", "default:"), st["arms"]):
                    out.append(f"{p}{lab} {{")
                    Cc.block(arm, ind + 1, out, ctr)
                    out.append(f"{p}}} break;")
                out.append(f"{p}}}")
            elif k == "scope":
                out.append(f"{p}{{")
                Cc.block(st["body"], ind + 1, out, ctr)
                out.append(f"{p}}}")
            elif k == "try":
                assert st["fin"] is None, "C++ has no finally"
                out.append(f"{p}try {{")
                Cc.block(st["body"], ind + 1, out, ctr)
                for kd, hb in st["catches"]:
                    out.append(f"{p}}} catch (const {'IllegalState' if kd == 0 else 'Failure'} &) {{")
                    Cc.block(hb, ind + 1, out, ctr)
                out.append(f"{p}}}")
            elif k == "throw":
                out.append(f"{p}{'may_throw' if st['kind'] == 0 else 'may_throw2'}();")
            elif k == "return":
                out.append(f"{p}co_return 0;")
            else:
                raise ValueError(k)

    @staticmethod
    def render(prog, meta, seed):
        fns = set(meta["fns"])
        o = [header(meta, seed), '#include "../common.hpp"', ""]
        if "pass" in fns:
            o.append("long pass(long x) { return x; }")
        if "konst" in fns:
            o.append("long konst(long) { return 905; }")
        if "sdrop" in fns:
            o.append("task<long> sdrop(long) { co_await tick(); co_return 906; }")
        for d in range(relay_depth(fns) + 1):
            o.append(f"task<long> relay{d}(long x) {{ co_await tick(); co_return {'x' if d == 0 else f'co_await relay{d - 1}(x)'}; }}")
        if prog["field"]:
            o.append("struct H { long f0 = 0, f1 = 0; };")
        o += ["", "task<long> run_case() {"]
        if prog["field"]:
            o.append("  H h;")
        if "lam" in fns:
            o.append("  auto lam = [](long s) -> task<long> { co_await tick(); co_return s; };")
        if "lamdrop" in fns:
            o.append("  auto lamdrop = [](long) -> task<long> { co_await tick(); co_return 907; };")
        Cc.block(prog["body"], 1, o, [0])
        o += ["  co_return 0;", "}"]
        return "\n".join(o) + "\n"


# --------------------------------------------------------- templates ------
def ident(e, i=0):
    """Alternate the pass-through suspend helpers so chains are not uniform."""
    return CS(("identity", "other_identity")[i % 2], e if i % 3 != 2 else XF(e))


ALL = ("kotlin", "rust", "cpp")
KC = ("kotlin", "cpp")
KO = ("kotlin",)
RO = ("rust",)


def templates():
    T = []

    def t(family, name, langs, expect, note, body, size=""):
        T.append(dict(family=family, name=name, langs=langs, intended=expect, note=note, body=body, size=size or "-"))

    # chain: n suspend hops carrying the taint
    for n in (1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64):
        b = [D("a", K(0)), A("a", SRC)]
        for i in range(n):
            b += [A("a", ident(V("a"), i)), TICK()]
        b.append(SINK(V("a")))
        t("chain", f"chain_n{n}", ALL, "FLOW", f"{n} suspend hops carry the source to the sink", b, f"n={n}")
    for n in (1, 4, 16, 64):
        b = [D("a", K(0)), D("b", K(1)), A("a", SRC)]
        for i in range(n):
            b += [A("a", ident(V("a"), i)), TICK()]
        b += [A("b", CS("identity", V("a"))), TICK(), A("b", K(2)), SINK(V("b"))]
        t("chainkill", f"chainkill_n{n}", ALL, "NO-FLOW",
          f"{n} hops, then the sink's variable is overwritten after its last suspension; `a` stays tainted", b, f"n={n}")

    # branch
    s = [D("s", SRC), D("r", K(0))]
    t("branch", "then_only", ALL, "FLOW", "suspend call only in the then-branch", s + [IF([A("r", CS("identity", V("s")))]), SINK(V("r"))])
    t("branch", "both_branches", ALL, "FLOW", "different suspend helpers in both branches",
      s + [IF([A("r", CS("identity", V("s")))], [A("r", CS("other_identity", XF(V("s"))))]), SINK(V("r"))])
    t("branch", "kill_one_branch", ALL, "FLOW", "taint survives the branch that does not overwrite",
      s + [A("r", CS("identity", V("s"))), IF([A("r", K(3)), TICK()], [TICK()]), SINK(V("r"))])
    t("branch", "kill_both_branches", ALL, "NO-FLOW", "both branches overwrite the sink's variable, around suspensions",
      s + [A("r", CS("identity", V("s"))), IF([A("r", K(3)), TICK()], [TICK(), A("r", K(4))]), SINK(V("r"))])
    t("branch", "sink_in_then", ALL, "FLOW", "the sink sits inside a branch, its argument is a suspend call",
      s + [IF([TICK(), SINK(CS("identity", V("s")))])])
    t("branch", "taint_else_sink_then", ALL, "NO-FLOW", "taint is produced in the else-branch, the sink is in the then-branch",
      s + [IF([TICK(), SINK(V("r"))], [A("r", CS("identity", V("s"))), TICK()])])
    t("branch", "nested3_pass", ALL, "FLOW", "three nested ifs, taint assigned at the innermost level",
      s + [IF([IF([IF([A("r", CS("identity", V("s"))), TICK()])], [TICK()])]), SINK(V("r"))])
    t("branch", "nested3_kill_all", ALL, "NO-FLOW", "every path through three nested ifs overwrites the sink's variable",
      s + [A("r", CS("identity", V("s"))), IF([IF([A("r", K(5)), TICK()], [A("r", K(6)), TICK()])], [TICK(), A("r", K(7))]), SINK(V("r"))])

    # loops
    for kk, kind in ((1, WHILE), (2, WHILE), (3, FOR), (4, DO)):
        b = [D("c0", SRC)] + [D(f"c{j}", K(j)) for j in range(1, kk + 1)]
        body = []
        for j in range(kk, 0, -1):
            body += [A(f"c{j}", CS("identity", V(f"c{j - 1}"))), TICK()]
        b += [kind(body), SINK(V(f"c{kk}"))]
        t("loop", f"carried_{kk}_{kind.__name__.lower()}", ALL, "FLOW",
          f"loop-carried chain: the taint needs {kk} iteration(s) of a {kind.__name__.lower()} loop to reach the sink's variable", b, f"k={kk}")
    t("loop", "for_use_then_kill", ALL, "FLOW", "first iteration copies the taint out, then the source variable is overwritten",
      [D("x", SRC), D("y", K(0)), FOR([A("y", CS("identity", V("x"))), TICK(), A("x", K(2))]), SINK(V("y"))])
    t("loop", "kill_at_iteration_end", ALL, "NO-FLOW", "taint produced and overwritten inside every iteration; a copy stays tainted",
      [D("t", K(0)), D("keep", K(1)), WHILE([A("t", CS("identity", SRC)), TICK(), A("keep", V("t")), A("t", K(2))]), SINK(V("t"))])
    t("loop", "break_with_taint", ALL, "FLOW", "break leaves the loop before the overwrite",
      [D("x", K(0)), WHILE([A("x", CS("identity", SRC)), TICK(), BRK(), A("x", K(2))]), SINK(V("x"))])
    t("loop", "continue_with_taint", ALL, "FLOW", "continue skips the overwrite; the next iteration copies the taint",
      [D("x", K(0)), D("y", K(1)), WHILE([A("y", V("x")), A("x", CS("identity", SRC)), TICK(), CONT(), A("x", K(2))]), SINK(V("y"))])
    t("loop", "dowhile_kill", ALL, "NO-FLOW", "do-while: the loop head only ever sees the overwritten value",
      [D("x", K(0)), D("z", K(1)), D("w", K(2)),
       DO([A("z", V("x")), A("x", CS("identity", SRC)), TICK(), A("w", V("x")), A("x", K(3))]), SINK(V("z"))])
    t("loop", "nested_while_for", ALL, "FLOW", "taint enters at the end of the outer body and is copied by the inner for loop on the next round",
      [D("a", K(0)), D("b", K(1)), WHILE([FOR([A("b", CS("identity", V("a"))), TICK()]), A("a", SRC)]), SINK(V("b"))])

    # spill-slot (benchmark/spill-slot/ generalised)
    for k in (2, 3, 5, 8):
        b = [D("len", K(0)), SCOPE([D("secret", SRC), TICK(), A("len", XF(V("secret")))])]
        for i in range(2, k + 1):
            sc = [D(f"safe{i}", K(10 + i)), TICK()]
            if i == k:
                sc.append(SINK(V(f"safe{i}")))
            b.append(SCOPE(sc))
        t("spillslot", f"disjoint_scopes_{k}", ALL, "NO-FLOW",
          f"{k} disjoint scopes, each saving one local across a suspension; the sink prints the last scope's constant", b, f"k={k}")
    for k in (2, 5):
        b = [D("len", K(0)), SCOPE([D("secret", SRC), TICK(), A("len", XF(V("secret")))])]
        for i in range(2, k + 1):
            sc = [D(f"safe{i}", K(10 + i)), TICK()]
            if i == k:
                sc.append(SINK(V("len")))
            b.append(SCOPE(sc))
        t("spillslot", f"positive_control_{k}", ALL, "FLOW", f"as disjoint_scopes_{k}, but the sink prints the secret-derived value", b, f"k={k}")
    for k in (2, 3):
        b = [D("len", K(0)), IF([D("secret", SRC), TICK(), A("len", XF(V("secret")))])]
        for i in range(2, k + 1):
            b.append(IF([D(f"safe{i}", K(10 + i)), TICK()] + ([SINK(V(f"safe{i}"))] if i == k else [])))
        t("spillslot", f"sibling_ifs_{k}", ALL, "NO-FLOW", f"{k} sibling if-blocks with block-local saved variables", b, f"k={k}")
    t("spillslot", "sequential_while", ALL, "NO-FLOW", "two sequential loops with loop-local saved variables",
      [D("len", K(0)), WHILE([D("secret", SRC), TICK(), A("len", XF(V("secret")))]), WHILE([D("safe", K(9)), TICK(), SINK(V("safe"))])])
    t("spillslot", "sequential_for", ALL, "NO-FLOW", "two sequential for loops with loop-local saved variables",
      [D("len", K(0)), FOR([D("secret", SRC), TICK(), A("len", XF(V("secret")))]), FOR([D("safe", K(9)), TICK(), SINK(V("safe"))])])
    t("spillslot", "nested_scopes", ALL, "NO-FLOW", "disjoint scopes nested inside an outer scope",
      [D("len", K(0)), SCOPE([SCOPE([D("secret", SRC), TICK(), A("len", XF(V("secret")))]), SCOPE([D("safe", K(9)), TICK(), SINK(V("safe"))])])])

    # shared result slot (benchmark/spill-slot/05 generalised)
    for n in (2, 4, 8, 16):
        b = [D("a1", CS("identity", SRC))]
        for i in range(2, n + 1):
            b += [TICK(), D(f"a{i}", CS("identity", K(i)))]
        b.append(SINK(V(f"a{n}")))
        t("sharedresult", f"untainted_last_{n}", ALL, "NO-FLOW",
          f"{n} suspend results; only the first is tainted, the sink prints the last", b, f"n={n}")
    for n in (4, 16):
        b = [D("a1", CS("identity", SRC))]
        for i in range(2, n + 1):
            b += [TICK(), D(f"a{i}", CS("identity", K(i)))]
        b.append(SINK(V("a1")))
        t("sharedresult", f"tainted_first_{n}", ALL, "FLOW", f"{n} suspend results; the sink prints the tainted first one", b, f"n={n}")

    # when / match / switch (a user switch next to the dispatch switch)
    s = [D("s", SRC), D("r", K(0))]
    t("whenswitch", "susp_in_arm", ALL, "FLOW", "suspend call in one arm of a user switch",
      s + [SW([A("r", CS("identity", V("s")))], [TICK()], [A("r", K(2))]), SINK(V("r"))])
    t("whenswitch", "kill_all_arms", ALL, "NO-FLOW", "every arm overwrites the sink's variable",
      s + [A("r", CS("identity", V("s"))), SW([A("r", K(2)), TICK()], [TICK(), A("r", K(3))], [A("r", K(4))]), SINK(V("r"))])
    t("whenswitch", "sink_in_arm", ALL, "FLOW", "the sink sits in a switch arm",
      s + [SW([TICK()], [SINK(CS("identity", V("s")))], [TICK()])])
    t("whenswitch", "switch_in_loop", ALL, "FLOW", "switch inside a loop; two arms in sequence across iterations carry the taint",
      s + [D("q", K(1)), WHILE([SW([A("r", V("q"))], [A("q", CS("identity", V("s"))), TICK()], [TICK()])]), SINK(V("r"))])
    t("whenswitch", "nested_switch_kill", ALL, "NO-FLOW", "nested switches; every path overwrites the sink's variable",
      s + [SW([SW([A("r", CS("identity", V("s")))], [TICK()], [TICK()]), A("r", K(5))], [TICK()], [A("r", K(6))]), SINK(V("r"))])

    # suspend helpers with their own state machines
    for d in (1, 2, 4, 8):
        t("helper", f"relay_depth_{d}", ALL, "FLOW", f"taint passes through {d} nested suspend helpers, each suspending first",
          [D("s", SRC), D("r", CS(f"relay{d - 1}", V("s"))), SINK(V("r"))], f"d={d}")
    t("helper", "sdrop", ALL, "NO-FLOW", "suspend helper suspends and returns a constant",
      [D("s", SRC), D("r", CS("sdrop", V("s"))), TICK(), SINK(V("r"))])
    t("helper", "relay_of_sdrop", ALL, "NO-FLOW", "constant-returning helper inside a relay chain",
      [D("s", SRC), D("r", CS("relay3", CS("sdrop", V("s")))), SINK(V("r"))])
    t("helper", "plain_pass", ALL, "FLOW", "ordinary function around a suspend call",
      [D("s", SRC), D("r", CP("pass", CS("identity", XF(V("s"))))), TICK(), SINK(V("r"))])
    t("helper", "plain_konst", ALL, "NO-FLOW", "ordinary constant-returning function around a suspend call",
      [D("s", SRC), D("r", CP("konst", CS("identity", V("s")))), TICK(), SINK(V("r"))])

    # fields of one local holder object
    s = [D("s", SRC), D("r", K(0))]
    t("field", "field_carry", ALL, "FLOW", "taint stored in a field across a suspension",
      s + [SF("f0", V("s")), TICK(), SINK(F("f0"))])
    t("field", "other_field", ALL, "NO-FLOW", "taint in f0, the sink reads f1",
      s + [SF("f0", V("s")), SF("f1", K(1)), TICK(), SINK(F("f1"))])
    t("field", "field_strong_update", ALL, "NO-FLOW", "the tainted field is overwritten after the suspension",
      s + [SF("f0", CS("identity", V("s"))), TICK(), SF("f0", K(2)), SINK(F("f0"))])
    t("field", "field_loop_shift", ALL, "FLOW", "f0 -> f1 shift inside a loop needs two iterations",
      s + [WHILE([SF("f1", F("f0")), SF("f0", CS("identity", V("s"))), TICK()]), SINK(F("f1"))])
    t("field", "field_branch", ALL, "FLOW", "field tainted on one branch",
      s + [IF([SF("f0", V("s"))], [SF("f0", K(3))]), TICK(), A("r", F("f0")), SINK(V("r"))])

    # suspend lambdas / coroutine lambdas / async closures
    s = [D("s", SRC), D("r", K(0))]
    t("lambda", "lambda_pass", ALL, "FLOW", "suspending lambda returns its argument", s + [A("r", CS("lam", V("s"))), SINK(V("r"))])
    t("lambda", "lambda_drop", ALL, "NO-FLOW", "suspending lambda returns a constant", s + [A("r", CS("lamdrop", V("s"))), SINK(V("r"))])
    t("lambda", "lambda_in_loop", ALL, "FLOW", "lambda call in a loop, taint arrives on the second iteration",
      s + [D("q", K(1)), WHILE([A("r", CS("lam", V("q"))), A("q", V("s")), TICK()]), SINK(V("r"))])

    # early return
    t("earlyreturn", "guard_return", ALL, "FLOW", "an early return that may not be taken",
      [D("x", SRC), TICK(), RETG(), SINK(V("x"))])
    t("earlyreturn", "return_else", ALL, "NO-FLOW", "the only path to the sink overwrites first; the other returns",
      [D("x", CS("identity", SRC)), D("keep", V("x")), IF([A("x", K(1)), TICK()], [TICK(), RET()]), SINK(V("x"))])
    t("earlyreturn", "return_in_loop", ALL, "NO-FLOW", "leaving the loop with the taint means returning; normal exit overwrites",
      [D("x", K(0)), D("keep", K(1)), WHILE([A("x", CS("identity", SRC)), A("keep", V("x")), TICK(), RETG(), A("x", K(2))]), SINK(V("x"))])

    # exceptions (Kotlin + C++; C++ allows no co_await in a handler)
    s = [D("x", K(0)), D("y", K(1)), D("keep", K(2))]
    t("trycatch", "susp_in_try", KC, "FLOW", "suspend call in a try body, sink after",
      s + [TRY([THROW(), A("x", CS("identity", SRC)), TICK()], [(0, [A("x", K(3))])]), SINK(V("x"))])
    t("trycatch", "exceptional_flow", KC, "FLOW", "the overwrite is skipped when mayThrow throws",
      s + [TRY([A("x", CS("identity", SRC)), TICK(), THROW(), A("x", K(3))], [(0, [A("y", K(4))])]), SINK(V("x"))])
    t("trycatch", "exceptional_kill", KC, "NO-FLOW", "both the normal path and the handler overwrite",
      s + [TRY([A("x", CS("identity", SRC)), A("keep", V("x")), TICK(), THROW(), A("x", K(3))], [(0, [A("x", K(4))])]), SINK(V("x"))])
    t("trycatch", "two_catches_one_leaks", KC, "FLOW", "two handlers; the second leaves the tainted value",
      s + [TRY([A("x", CS("identity", SRC)), TICK(), THROW(0), THROW(1), A("x", K(3))], [(0, [A("x", K(4))]), (1, [A("y", K(5))])]), SINK(V("x"))])
    t("trycatch", "two_catches_both_kill", KC, "NO-FLOW", "two handlers, both overwrite",
      s + [TRY([A("x", CS("identity", SRC)), A("keep", V("x")), TICK(), THROW(0), THROW(1), A("x", K(3))],
               [(0, [A("x", K(4))]), (1, [A("x", K(5))])]), SINK(V("x"))])
    t("trycatch", "try_in_loop", KC, "FLOW", "caught exception keeps the taint for the next iteration's copy",
      s + [WHILE([TRY([A("y", V("x")), A("x", CS("identity", SRC)), TICK(), THROW(), A("x", K(3))], [(0, [A("keep", K(6))])])]), SINK(V("y"))])
    t("trycatch", "nested_try_kill", KC, "NO-FLOW", "inner try overwrites on both paths; outer handler sees a constant",
      s + [TRY([TRY([A("x", CS("identity", SRC)), A("keep", V("x")), THROW(0), A("x", K(3))], [(0, [A("x", K(4))])]), TICK(), THROW(1)],
               [(1, [A("y", K(5))])]), SINK(V("x"))])
    t("trycatch", "sink_in_catch", KC, "FLOW", "the sink is in the handler",
      s + [TRY([A("x", CS("identity", SRC)), TICK(), THROW(), A("x", K(3))], [(0, [SINK(V("x"))])])])
    t("trycatch", "susp_in_catch", KO, "FLOW", "suspend calls inside the handler (not allowed in C++)",
      s + [TRY([A("x", CS("identity", SRC)), THROW(), A("x", K(3))], [(0, [TICK(), A("x", CS("identity", V("x")))])]), SINK(V("x"))])
    t("trycatch", "susp_in_catch_kill", KO, "NO-FLOW", "suspend calls inside the handler, which overwrites",
      s + [TRY([A("x", CS("identity", SRC)), A("keep", V("x")), THROW(), A("x", K(3))], [(0, [TICK(), A("x", CS("identity", K(7)))])]), SINK(V("x"))])
    t("tryfinally", "finally_suspend", KO, "FLOW", "suspend call in finally (kotlinc duplicates the finally block)",
      s + [TRY([A("x", CS("identity", SRC)), THROW()], [(0, [A("y", K(4))])], [TICK()]), SINK(V("x"))])
    t("tryfinally", "finally_kill", KO, "NO-FLOW", "finally overwrites on every path, around a suspension",
      s + [TRY([A("x", CS("identity", SRC)), A("keep", V("x")), THROW()], [(0, [A("y", K(4))])], [A("x", K(5)), TICK()]), SINK(V("x"))])

    # Rust async blocks
    s = [D("s", SRC), D("r", K(0))]
    t("asyncblock", "block_pass", RO, "FLOW", "async block awaited in place", s + [A("r", AB(V("s"))), SINK(V("r"))])
    t("asyncblock", "block_const", RO, "NO-FLOW", "async block returns a constant", s + [A("r", AB(K(3))), TICK(), SINK(V("r"))])
    t("asyncblock", "block_in_loop", RO, "FLOW", "async block in a loop, taint arrives on the second iteration",
      s + [D("q", K(1)), WHILE([A("r", AB(V("q"))), A("q", CS("identity", V("s"))), TICK()]), SINK(V("r"))])
    t("asyncblock", "nested_blocks", RO, "FLOW", "async block inside an async block", s + [A("r", AB(AB(XF(V("s"))))), SINK(V("r"))])
    t("asyncblock", "block_scopes", RO, "NO-FLOW", "spill-slot shape with async blocks in each scope",
      [D("len", K(0)), SCOPE([D("secret", AB(SRC)), TICK(), A("len", XF(V("secret")))]), SCOPE([D("safe", AB(K(9))), TICK(), SINK(V("safe"))])])
    t("asyncblock", "block_kill", RO, "NO-FLOW", "async block result overwritten after a suspension",
      s + [A("r", AB(V("s"))), D("keep", V("r")), TICK(), A("r", K(4)), SINK(V("r"))])
    return T


# ------------------------------------------------------------ random ------
SIZES = {  # statements budget, max nesting depth, top-level variable pool
    "S": (8, 2, 3), "M": (32, 3, 5), "L": (100, 4, 8), "XL": (300, 4, 12),
}


class Gen:
    def __init__(self, rng, size, allow_try, allow_field):
        self.rng = rng
        self.budget, self.maxd, self.npool = SIZES[size]
        self.allow_try, self.allow_field = allow_try, allow_field
        self.left = self.budget
        self.nlocal = 0
        self.consts = 10

    def const(self):
        self.consts += 1
        return K(self.consts)

    def expr(self, vis, d=0, susp=True):
        r = self.rng.random()
        if r < 0.45 or d >= 2:
            if self.allow_field and self.rng.random() < 0.15:
                return F(self.rng.choice(["f0", "f1"]))
            return V(self.rng.choice(vis))
        if r < 0.6:
            return self.const()
        if r < 0.7:
            return XF(self.expr(vis, d + 1, susp))
        if r < 0.92 and susp:
            fn = self.rng.choices(["identity", "other_identity", "relay0", "relay1", "sdrop"], [5, 3, 2, 1, 1])[0]
            return CS(fn, self.expr(vis, d + 1, susp))
        return CP(self.rng.choice(["pass", "pass", "konst"]), self.expr(vis, d + 1, susp))

    def block(self, vis, depth, ctx, n):
        out, vis = [], list(vis)
        for _ in range(n):
            if self.left <= 0:
                break
            self.left -= 1
            st = self.stmt(vis, depth, ctx)
            out.append(st)
            if st["k"] == "decl":
                vis.append(st["v"])
        return out

    def sub(self, vis, depth, ctx, **upd):
        c = dict(ctx, **upd)
        n = self.rng.randint(1, max(1, min(6, self.left)))
        return self.block(vis, depth + 1, c, n)

    def stmt(self, vis, depth, ctx):
        rng = self.rng
        susp = not ctx["no_susp"]
        opts = [("assign", 30), ("decl", 6), ("tick", 10 if susp else 0)]
        if depth < self.maxd and self.left > 2:
            opts += [("if", 9), ("while", 3), ("for", 3), ("do", 2), ("switch", 3), ("scope", 4)]
            if self.allow_try and not ctx["in_handler"]:
                opts.append(("try", 4))
        if self.allow_field:
            opts.append(("setf", 5))
        if ctx["loop"] and not ctx["loopctl_blocked"]:
            opts += [("break", 2), ("continue", 2 if ctx["loop"] != "do" else 0)]
        if not ctx["loopctl_blocked"]:
            opts.append(("return", 1))
        if ctx["try"] is not None:
            opts.append(("throw", 6))
        kind = rng.choices([o for o, _ in opts], [w for _, w in opts])[0]
        if kind == "assign":
            return A(rng.choice(vis), self.expr(vis, susp=susp))
        if kind == "decl":
            self.nlocal += 1
            return D(f"l{self.nlocal}", self.expr(vis, susp=susp))
        if kind == "setf":
            return SF(rng.choice(["f0", "f1"]), self.expr(vis, susp=susp))
        if kind == "tick":
            return TICK()
        if kind == "if":
            return IF(self.sub(vis, depth, ctx), self.sub(vis, depth, ctx) if rng.random() < 0.5 else [])
        if kind in ("while", "for", "do"):
            body = self.sub(vis, depth, ctx, loop=kind, loopctl_blocked=False)
            return {"while": WHILE, "for": FOR, "do": DO}[kind](body)
        if kind == "switch":
            # C++ `break` inside a switch leaves the switch, not the loop
            return SW(*[self.sub(vis, depth, ctx, loopctl_blocked=True) for _ in range(3)])
        if kind == "scope":
            # Kotlin renders a scope as an inline `run {}` lambda: keep
            # break/continue/return out of it
            return SCOPE(self.sub(vis, depth, ctx, loopctl_blocked=True))
        if kind == "try":
            kinds = [0] if rng.random() < 0.6 else [0, 1]
            body = self.sub(vis, depth, ctx, **{"try": kinds})
            catches = [(kd, self.sub(vis, depth, ctx, in_handler=True, no_susp=True, **{"try": None})) for kd in kinds]
            return TRY(body, catches)
        if kind == "throw":
            return THROW(rng.choice(ctx["try"]))
        if kind == "break":
            return BRK()
        if kind == "continue":
            return CONT()
        if kind == "return":
            return RETG()
        raise ValueError(kind)

    def program(self):
        pool = [f"v{i}" for i in range(self.npool)]
        ctx = {"loop": None, "loopctl_blocked": False, "try": None, "in_handler": False, "no_susp": False}
        body = [D(v, K(i)) for i, v in enumerate(pool)]
        while self.left > 0:
            body += self.block(pool, 0, ctx, self.left)
        return body, pool


def insertion_points(b, vis, ctx, out):
    """Every (block, index) where a statement may go, with the variables in
    scope there. Guard ifs (`if (opaque()) break`) are not entered."""
    vis = list(vis)
    for i in range(len(b) + 1):
        out.append((b, i, list(vis), ctx))
        if i < len(b):
            st = b[i]
            if st["k"] == "decl":
                vis.append(st["v"])
            if st.get("guard"):
                continue
            sub = dict(ctx)
            if st["k"] == "try":
                insertion_points(st["body"], vis, sub, out)
                for _, hb in st["catches"]:
                    insertion_points(hb, vis, dict(ctx, handler=True), out)
                continue
            for blk in blocks_of(st):
                insertion_points(blk, vis, sub, out)


def place(rng, body, pool, want, allow_field):
    """Insert one source assignment and one sink into a generated body so the
    exact label is `want` (and, for NO-FLOW, taint is live at the sink)."""
    import copy
    for _ in range(400):
        b = copy.deepcopy(body)
        pts = []
        insertion_points(b, pool, {"handler": False}, pts)
        sb, si, svis, sctx = rng.choice(pts)
        e = rng.choice([SRC, SRC, XF(SRC)] + ([] if sctx["handler"] else [CS("identity", SRC)]))
        sb.insert(si, A(rng.choice(svis), e))
        pts = []
        insertion_points(b, pool, {"handler": False}, pts)
        kb, ki, kvis, kctx = rng.choice(pts)
        arg = F(rng.choice(["f0", "f1"])) if allow_field and rng.random() < 0.2 else V(rng.choice(kvis))
        kb.insert(ki, SINK(arg))
        c = analyze(b)
        if not c.reach or not c.src_reach:
            continue
        got = "FLOW" if c.flow else "NO-FLOW"
        if got == want and c.susp_live and (want == "FLOW" or c.live):
            return b
    return None


# -------------------------------------------------------------- main ------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=20261019)
    ap.add_argument("--out", default=str(ROOT / "benchmark-gen"))
    a = ap.parse_args()
    rng = random.Random(a.seed)
    out = Path(a.out)

    cases = []
    for t in templates():
        c = analyze(t["body"])
        got = "FLOW" if c.flow else "NO-FLOW"
        if got != t["intended"] or not c.reach:
            raise SystemExit(f"template {t['family']}/{t['name']}: intended {t['intended']}, analysis says {got} (reach={c.reach})")
        if got == "NO-FLOW" and not c.live:
            raise SystemExit(f"template {t['family']}/{t['name']}: NO-FLOW but no taint is live at the sink (trivial negative)")
        cases.append(dict(t, expect=got, live=c.live, kind="template"))

    # Random programs: (size, count, languages, allow try)
    plan = [("S", 6, ALL, False), ("M", 6, ALL, False), ("L", 6, ALL, False), ("XL", 6, ALL, False),
            ("M", 2, KC, True), ("L", 2, KC, True)]
    for size, count, langs, allow_try in plan:
        for j in range(count):
            want = "FLOW" if j % 2 == 0 else "NO-FLOW"
            allow_field = rng.random() < 0.4
            for _ in range(200):
                g = Gen(rng, size, allow_try, allow_field)
                body, pool = g.program()
                if allow_try and not any(st["k"] == "try" for st, _ in walk_stmts(body)):
                    continue
                placed = place(rng, body, pool, want, allow_field)
                if placed is not None:
                    break
            else:
                raise SystemExit(f"could not generate a {size} {want} program")
            c = analyze(placed)
            fam = "randomexc" if allow_try else "random"
            cases.append(dict(family=fam, name=f"{size.lower()}_{j + 1:02d}", langs=langs, expect=want, live=c.live,
                              note=f"random structured program, size class {size}", body=placed, size=size, kind="random"))

    if out.exists():
        shutil.rmtree(out)
    manifest = []
    for i, cs in enumerate(cases, 1):
        cid = f"{i:03d}"
        prog = {"body": cs["body"], "field": uses_field(cs["body"])}
        m = metrics(cs["body"])
        meta = dict(id=cid, family=cs["family"], name=cs["name"], expect=cs["expect"], note=cs["note"], size=cs["size"],
                    kind=cs["kind"], langs=list(cs["langs"]), liveAtSink=cs["live"], **m,
                    kt_package=f"benchmarkgen.{cs['family']}.c{cid}")
        meta["files"] = {}
        for lang, R in (("kotlin", Kt), ("rust", Rs), ("cpp", Cc)):
            if lang not in cs["langs"]:
                continue
            rel = Path(lang) / cs["family"] / f"{cid}_{cs['name']}.{R.ext}"
            (out / rel).parent.mkdir(parents=True, exist_ok=True)
            (out / rel).write_text(R.render(prog, meta, a.seed))
            meta["files"][lang] = str(rel)
        manifest.append(meta)

    for lang, src in (("rust", "common.rs"), ("cpp", "common.hpp")):
        shutil.copy(ROOT / "scripts" / "gen" / src, out / lang / src)
    (out / "manifest.json").write_text(json.dumps(manifest, indent=1))
    # The IR itself, for gen_validate.py's directed witness search.
    (out / "programs.json").write_text(json.dumps({f"{i:03d}": cs["body"] for i, cs in enumerate(cases, 1)}))
    by = {}
    for m in manifest:
        for l in m["langs"]:
            by.setdefault(l, [0, 0])[0 if m["expect"] == "FLOW" else 1] += 1
    print(f"wrote {len(manifest)} programs to {out}")
    for l, (f, n) in sorted(by.items()):
        print(f"  {l:6}: {f + n} cases ({f} FLOW, {n} NO-FLOW)")


if __name__ == "__main__":
    main()
