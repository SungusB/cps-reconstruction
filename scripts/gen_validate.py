#!/usr/bin/env python3
"""Dynamic check of the generated suite's ground truth (benchmark-gen/).

Every program is compiled for real (kotlinc, rustc, clang++ -O0) and run with
two different secrets (SOURCE values) over a list of input vectors. A vector
fixes every opaque()/opaqueInt()/mayThrow() decision; once its bits run out
every decision is `false`, so every run terminates. A flow is *witnessed*
when, for some vector, the sequence of values printed by the SINK differs
between the two secrets. No condition reads the source, so a difference can
only come from explicit data flow:

  static NO-FLOW, witnessed      -> the generator is wrong (hard error)
  static FLOW, witnessed         -> confirmed
  static FLOW, not witnessed     -> unconfirmed (no vector reached the path)

This is two-run non-interference testing of the compiled programs, coroutine
machinery included, and does not use gen_suite.py's analysis.

Vectors: the same random set for every program, plus *directed* vectors from
a reference interpreter of the IR (`simulate` below, which models how many
input bits each language consumes: C++ awaiters call opaque() in
await_ready, Kotlin/Rust awaits consume none). The interpreter only proposes
vectors; a flow counts as confirmed only when the real binary's two runs
differ. The interpreter is itself checked: for every program and every
vector it predicts whether the sink sees the secret, and that prediction is
compared with the binary's outcome (`modelMismatches`, expected 0).

Writes benchmark-gen/validation.json and leaves the raw Kotlin classes in
build/gen/kotlin/classes for the tool harnesses.

Usage: gen_validate.py [--vectors N] [--only kotlin|rust|cpp]
"""
import argparse
import json
import os
import random
import shutil
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GEN = ROOT / "benchmark-gen"
WORK = ROOT / "build" / "gen"
SECRETS = {"kotlin": ("  alpha-secret ", " omega-secret  "), "rust": ("1111", "2222"), "cpp": ("1111", "2222")}
DROP = {"konst", "sdrop", "lamdrop"}

RT_C = r"""
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static char bits[1 << 16];
static size_t len, pos;
static long secret;
long source(void) { return secret; }
void sink(long x) { printf("%ld\n", x); }
bool opaque(void) { return pos < len && bits[pos++] == '1'; }
long opaque_int(void) { long a = opaque(); long b = opaque(); return a * 2 + b; }
bool rt_next(void) {
  static int init;
  if (!init) { const char *s = getenv("SECRET"); secret = s ? atol(s) : 0; init = 1; }
  if (!fgets(bits, sizeof bits, stdin)) return false;
  len = strcspn(bits, "\r\n"); pos = 0;
  printf("@\n");
  return true;
}
void rt_done(void) { printf("#\n"); fflush(stdout); }
"""

RS_MAIN = r"""
unsafe extern "C" { fn rt_next() -> bool; fn rt_done(); }
fn main() {
    let mut cx = std::task::Context::from_waker(std::task::Waker::noop());
    while unsafe { rt_next() } {
        let mut f = entry();
        let mut polls = 0u64;
        while f.as_mut().poll(&mut cx).is_pending() {
            polls += 1;
            if polls > 50_000_000 { println!("!stuck"); break; }
        }
        unsafe { rt_done() };
    }
}
"""

CC_MAIN = r"""
#include "common.hpp"
#include <cstdio>
#include <deque>
extern "C" bool rt_next();
extern "C" void rt_done();
static std::deque<std::coroutine_handle<>> q;
extern "C" void schedule(void *p) { q.push_back(std::coroutine_handle<>::from_address(p)); }
void may_throw() { if (opaque()) throw IllegalState{}; }
void may_throw2() { if (opaque()) throw Failure{}; }
task<long> run_case();
int main() {
  while (rt_next()) {
    auto t = run_case();
    while (!q.empty()) { auto h = q.front(); q.pop_front(); h.resume(); }
    if (!t.h.done()) std::printf("!notdone\n");
    rt_done();
  }
}
"""


# ------------------------------------------------- reference interpreter --
class _Brk(Exception): pass
class _Cont(Exception): pass
class _Ret(Exception): pass
class _Thrown(Exception):
    def __init__(self, kind):
        self.kind = kind


class _Budget(Exception): pass


def simulate(body, bits, lang, limit=2_000_000):
    """Run the IR on one input vector with taint tracking.
    -> (sink reached, sink saw a tainted value)."""
    cpp = lang == "cpp"
    pos = [0]
    steps = [0]
    env = {}
    seen = [False, False]

    def take():
        steps[0] += 1
        if steps[0] > limit:
            raise _Budget()
        if pos[0] < len(bits):
            pos[0] += 1
            return bits[pos[0] - 1] == "1"
        return False

    def takeint():
        a = take()
        b = take()
        return 2 * a + b

    def ev(e):
        k = e["k"]
        if k == "src":
            return True
        if k == "var":
            return env[e["n"]]
        if k == "field":
            return env.get("h." + e["f"], False)
        if k == "const":
            return False
        if k in ("xf", "ablock"):
            return ev(e["e"])
        t = ev(e["e"])
        if k == "scall" and cpp:  # awaiter readiness bits, in evaluation order
            fn = e["fn"]
            n = int(fn[5:]) + 1 if fn.startswith("relay") else 1
            for _ in range(n):
                take()
        return False if e["fn"] in DROP else t

    def run(b):
        for st in b:
            steps[0] += 1
            if steps[0] > limit:
                raise _Budget()
            k = st["k"]
            if k in ("decl", "assign"):
                env[st["v"]] = ev(st["e"])
            elif k == "setf":
                env["h." + st["f"]] = ev(st["e"])
            elif k == "tick":
                if cpp:
                    take()
            elif k == "sink":
                seen[0] = True
                if ev(st["e"]):
                    seen[1] = True
            elif k == "if":
                run(st["then"] if take() else st["else"])
            elif k == "while":
                while take():
                    try:
                        run(st["body"])
                    except _Cont:
                        continue
                    except _Brk:
                        break
            elif k == "do":
                while True:
                    try:
                        run(st["body"])
                    except _Brk:
                        break
                    if not take():
                        break
            elif k == "for":
                for _ in range(takeint()):
                    try:
                        run(st["body"])
                    except _Cont:
                        continue
                    except _Brk:
                        break
            elif k == "switch":
                v = takeint()
                run(st["arms"][min(v, 2)])
            elif k == "scope":
                run(st["body"])
            elif k == "try":
                try:
                    run(st["body"])
                except _Thrown as t:
                    hb = dict(st["catches"]).get(t.kind)
                    if hb is None:
                        raise
                    run(hb)
                if st["fin"] is not None:
                    run(st["fin"])
            elif k == "throw":
                if take():
                    raise _Thrown(st["kind"])
            elif k == "break":
                raise _Brk()
            elif k == "continue":
                raise _Cont()
            elif k == "return":
                raise _Ret()

    try:
        run(body)
    except _Ret:
        pass
    return seen[0], seen[1]


def directed(body, lang, want_taint, rng, seconds=20.0, need=2):
    """Search vectors the interpreter predicts reach the sink (with the
    secret, if want_taint). Random vectors of varying density and length."""
    found, t0 = [], time.time()
    while time.time() - t0 < seconds and len(found) < need:
        p = rng.choice((0.05, 0.2, 0.4, 0.5, 0.6, 0.8, 0.9, 0.97))
        n = rng.choice((8, 32, 128, 512, 2048))
        v = "".join("1" if rng.random() < p else "0" for _ in range(n))
        try:
            reached, tainted = simulate(body, v, lang)
        except _Budget:
            continue
        if reached and (tainted or not want_taint):
            found.append(v)
    return found


def _scall_bits(e):
    """Input bits a C++ expression consumes in awaiter readiness checks."""
    n = 0
    while True:
        if e["k"] == "scall":
            fn = e["fn"]
            n += int(fn[5:]) + 1 if fn.startswith("relay") else 1
        if "e" not in e:
            return n
        e = e["e"]


def _taint(e, S):
    k = e["k"]
    if k == "src":
        return True
    if k == "var":
        return e["n"] in S
    if k == "field":
        return "h." + e["f"] in S
    if k == "const":
        return False
    t = _taint(e["e"], S)
    return False if k in ("scall", "pcall") and e["fn"] in DROP else t


def witness(body, lang, want_taint=True, max_states=3_000_000):
    """Shortest input vector driving the IR to the sink (with the secret in
    its argument, if want_taint): breadth-first search over (CFG node, taint
    set, remaining iterations of the enclosing for loops). Complete for the
    IR, unlike random search; `None` if no such path exists within the
    state budget."""
    cpp = lang == "cpp"
    nodes = []

    def new(**kw):
        nodes.append(kw)
        return len(nodes) - 1

    EXIT = new(kind="exit", fors=())

    def block(b, nxt, cx):
        for st in reversed(b):
            nxt = stmt(st, nxt, cx)
        return nxt

    def stmt(st, nxt, cx):
        k = st["k"]
        f = cx["fors"]
        if k in ("decl", "assign", "setf", "sink", "tick"):
            bits = (1 if k == "tick" else 0) + (_scall_bits(st["e"]) if "e" in st else 0)
            return new(kind="simple", st=st, next=nxt, bits=bits if cpp else 0, fors=f)
        if k == "if":
            return new(kind="br", succ=[block(st["else"], nxt, cx), block(st["then"], nxt, cx)], fors=f)
        if k == "while":
            head = new(kind="br", succ=None, fors=f)
            nodes[head]["succ"] = [nxt, block(st["body"], head, dict(cx, brk=nxt, cont=head))]
            return head
        if k == "do":
            cond = new(kind="br", succ=None, fors=f)
            entry = block(st["body"], cond, dict(cx, brk=nxt, cont=cond))
            nodes[cond]["succ"] = [nxt, entry]
            return entry
        if k == "for":
            loop = len(nodes)
            inner = f + (loop,)
            head = new(kind="forhead", loop=loop, body=None, exit=nxt, fors=inner)
            nodes[head]["body"] = block(st["body"], head, dict(cx, brk=nxt, cont=head, fors=inner))
            return new(kind="forinit", loop=loop, head=head, fors=f)
        if k == "switch":
            arms = [block(a, nxt, cx) for a in st["arms"]]
            return new(kind="br2", succ=[arms[0], arms[1], arms[2], arms[2]], fors=f)
        if k == "scope":
            return block(st["body"], nxt, cx)
        if k == "try":
            after = block(st["fin"], nxt, cx) if st["fin"] is not None else nxt
            handlers = dict(cx["handlers"])
            for kd, hb in st["catches"]:
                handlers[kd] = block(hb, after, cx)
            return block(st["body"], after, dict(cx, handlers=handlers))
        if k == "throw":
            return new(kind="throw", ok=nxt, exc=cx["handlers"][st["kind"]], fors=f)
        if k == "break":
            return cx["brk"]
        if k == "continue":
            return cx["cont"]
        if k == "return":
            return EXIT
        raise ValueError(k)

    entry = block(body, EXIT, {"fors": (), "handlers": {}, "brk": None, "cont": None})

    def go(t, S, ctrs):
        keep = set(nodes[t]["fors"])
        return (t, S, tuple(c for c in ctrs if c[0] in keep))

    start = (entry, frozenset(), ())
    parent = {start: None}
    frontier = [start]
    while frontier:
        nxt_frontier = []
        for state in frontier:
            n, S, ctrs = state
            nd = nodes[n]
            k = nd["kind"]
            succ = []
            if k == "simple":
                st = nd["st"]
                if st["k"] == "sink" and (_taint(st["e"], S) or not want_taint):
                    out, cur = [], state
                    while parent[cur] is not None:
                        cur, b = parent[cur]
                        out.append(b)
                    return "".join(reversed(out)) + "0" * nd["bits"]
                if st["k"] in ("decl", "assign", "setf"):
                    name = st["v"] if st["k"] != "setf" else "h." + st["f"]
                    S2 = (S - {name}) | ({name} if _taint(st["e"], S) else frozenset())
                else:
                    S2 = S
                succ.append(("0" * nd["bits"], go(nd["next"], S2, ctrs)))
            elif k == "br":
                succ += [("0", go(nd["succ"][0], S, ctrs)), ("1", go(nd["succ"][1], S, ctrs))]
            elif k == "br2":
                succ += [(format(v, "02b"), go(nd["succ"][v], S, ctrs)) for v in range(4)]
            elif k == "forinit":
                succ += [(format(v, "02b"), go(nd["head"], S, ctrs + ((nd["loop"], v),))) for v in range(4)]
            elif k == "forhead":
                r = dict(ctrs)[nd["loop"]]
                if r > 0:
                    c2 = tuple((l, (x - 1 if l == nd["loop"] else x)) for l, x in ctrs)
                    succ.append(("", go(nd["body"], S, c2)))
                else:
                    succ.append(("", go(nd["exit"], S, ctrs)))
            elif k == "throw":
                succ += [("0", go(nd["ok"], S, ctrs)), ("1", go(nd["exc"], S, ctrs))]
            for b, s2 in succ:
                if s2 not in parent:
                    parent[s2] = (state, b)
                    nxt_frontier.append(s2)
            if len(parent) > max_states:
                return None
        frontier = nxt_frontier
    return None


# ------------------------------------------------------------- running ----
def sh(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        raise RuntimeError(f"{' '.join(map(str, cmd))}\n{r.stdout[-3000:]}\n{r.stderr[-3000:]}")
    return r


def base_vectors(n, length, seed=7):
    rng = random.Random(seed)
    ps = (0.1, 0.3, 0.5, 0.7, 0.9, 0.97)
    out = ["", "1" * 64, "1" * length]
    while len(out) < n:
        p = ps[len(out) % len(ps)]
        out.append("".join("1" if rng.random() < p else "0" for _ in range(length)))
    return out


def parse(stdout):
    """-> list per vector of the sink values printed, plus anomaly lines."""
    runs, cur, bad = [], None, []
    for line in stdout.splitlines():
        if line == "@":
            cur = []
        elif line == "#":
            runs.append(cur)
            cur = None
        elif line.startswith("!"):
            bad.append(line)
        elif cur is not None:
            cur.append(line)
    return runs, bad


def judge(a, b, vecs, body, lang, ndirected):
    (ra, ba), (rb, bb) = a, b
    if len(ra) != len(vecs) or len(rb) != len(vecs):
        return {"error": f"expected {len(vecs)} runs, got {len(ra)}/{len(rb)}", "anomalies": (ba + bb)[:5]}
    diff = [i for i in range(len(vecs)) if ra[i] != rb[i]]
    mism = []
    for i, v in enumerate(vecs):
        try:
            reached, tainted = simulate(body, v, lang)
        except _Budget:
            continue
        if tainted != (i in diff) or reached != bool(ra[i]):
            mism.append(i)
    return {"witnessed": bool(diff), "firstVector": diff[0] if diff else None, "vectorsDiffering": len(diff),
            "vectors": len(vecs), "directedVectors": ndirected, "sinkReached": any(ra) or any(rb),
            "witnessedByDirected": bool(diff) and min(diff) >= len(vecs) - ndirected,
            "modelMismatches": len(mism), "anomalies": sorted(set(ba + bb))[:5]}


def plan_vectors(lang, cases, programs, base, jobs):
    """Per case: the shared vectors plus directed ones where the shared set
    does not already (per the interpreter) witness the flow / reach the sink."""
    def one(m):
        body = programs[m["id"]]
        want_taint = m["expect"] == "FLOW"
        ok = False
        for v in base:
            try:
                r, t = simulate(body, v, lang)
            except _Budget:
                continue
            if r and (t or not want_taint):
                ok = True
                break
        if ok:
            return m["id"], []
        w = witness(body, lang, want_taint)
        if w is not None:
            # padded with zeros: once the path is done every decision is false
            return m["id"], [w]
        return m["id"], directed(body, lang, want_taint, random.Random(int(m["id"]) * 31 + len(lang)))
    with ThreadPoolExecutor(jobs) as ex:
        return dict(ex.map(one, cases))


def run_twice(cmd, lang, vecfile, cwd=None):
    out = []
    for s in SECRETS[lang]:
        with open(vecfile) as fin:
            r = subprocess.run(cmd, stdin=fin, capture_output=True, text=True,
                               env=dict(os.environ, SECRET=s), cwd=cwd, timeout=1800)
        if r.returncode != 0:
            return None, f"exit {r.returncode}: {r.stderr[-500:]}"
        out.append(r.stdout)
    return out, None


def kotlin(cases, programs, vecs_of, extras):
    classes, drv = WORK / "kotlin" / "classes", WORK / "kotlin" / "driver"
    for d in (classes, drv):
        if d.exists():
            shutil.rmtree(d)
        d.mkdir(parents=True)
    print(f"[kotlin] compiling {len(cases)} files ...", flush=True)
    sh(["kotlinc", *[str(GEN / m["files"]["kotlin"]) for m in cases], "-d", str(classes), "-nowarn"])
    src = ["package benchmarkgen.driver", "import kotlin.coroutines.*", "",
           "lateinit var lines: List<Pair<String, String>>", "",
           "fun drive(id: String, set: (String) -> Unit, body: suspend () -> Unit) {",
           '    println("@@ $id")',
           '    for ((who, v) in lines) {',
           '        if (who != "*" && who != id) continue',
           "        set(v)",
           '        println("@")',
           "        var done = false",
           '        body.startCoroutine(Continuation(EmptyCoroutineContext) { r -> r.exceptionOrNull()?.let { println("!exc $it") }; done = true })',
           '        if (!done) println("!notdone")',
           '        println("#")',
           "    }", "}", "",
           "fun main() {",
           '    lines = generateSequence(::readLine).map { val i = it.indexOf(\' \'); it.substring(0, i) to it.substring(i + 1) }.toList()']
    for m in cases:
        p = m["kt_package"]
        src.append(f'    drive("{m["id"]}", {{ b -> {p}.Env.bits = b; {p}.Env.pos = 0 }}) {{ {p}.entry() }}')
    src.append("}")
    (drv / "Driver.kt").write_text("\n".join(src) + "\n")
    sh(["kotlinc", str(drv / "Driver.kt"), "-cp", str(classes), "-d", str(drv), "-nowarn"])
    kotlinc = Path(shutil.which("kotlinc") or "kotlinc").resolve()
    stdlib = next(p for p in (Path(os.environ.get("KOTLIN_LIB", "/nonexistent")) / "kotlin-stdlib.jar",
                              kotlinc.parent.parent / "lib" / "kotlin-stdlib.jar",
                              kotlinc.parent.parent / "share" / "kotlin" / "lib" / "kotlin-stdlib.jar",
                              Path("/usr/share/kotlin/lib/kotlin-stdlib.jar")) if p.exists())
    vf = WORK / "kotlin" / "vectors.txt"
    base = vecs_of(None)
    vf.write_text("".join(f"* {v}\n" for v in base) +
                  "".join(f"{cid} {v}\n" for cid, ex in extras.items() for v in ex))
    print("[kotlin] running ...", flush=True)
    outs, err = run_twice(["java", "-Xss64m", "-cp", f"{classes}:{drv}:{stdlib}", "benchmarkgen.driver.DriverKt"], "kotlin", vf)
    if err:
        sys.exit(f"kotlin driver failed: {err}")
    split = []
    for o in outs:
        per, cur = {}, None
        for line in o.splitlines(keepends=True):
            if line.startswith("@@ "):
                cur = line[3:].strip()
                per[cur] = []
            elif cur is not None:
                per[cur].append(line)
        split.append(per)
    return {m["id"]: judge(parse("".join(split[0].get(m["id"], []))), parse("".join(split[1].get(m["id"], []))),
                           vecs_of(m["id"]), programs[m["id"]], "kotlin", len(extras[m["id"]]))
            for m in cases}


def native(lang, cases, programs, vecs_of, extras, jobs):
    base = WORK / lang
    if base.exists():
        shutil.rmtree(base)
    base.mkdir(parents=True)
    (base / "rt.c").write_text(RT_C)
    sh(["clang", "-O0", "-c", str(base / "rt.c"), "-o", str(base / "rt.o")])
    prelude = (GEN / lang / ("common.rs" if lang == "rust" else "common.hpp")).read_text()
    if lang == "cpp":
        shutil.copy(GEN / "cpp" / "common.hpp", base / "common.hpp")
        (base / "driver.cpp").write_text(CC_MAIN)
        sh(["clang++", "-std=c++20", "-O0", "-c", str(base / "driver.cpp"), "-o", str(base / "driver.o")])
    print(f"[{lang}] compiling and running {len(cases)} programs ...", flush=True)

    def one(m):
        d = base / m["id"]
        d.mkdir()
        text = (GEN / m["files"][lang]).read_text()
        exe = d / "prog"
        try:
            if lang == "rust":
                (d / "main.rs").write_text(text.replace('include!("../common.rs");', prelude) + RS_MAIN)
                sh(["rustc", "--edition", "2024", "-C", "opt-level=0", "-C", f"link-arg={base / 'rt.o'}",
                    str(d / "main.rs"), "-o", str(exe)])
            else:
                (d / "case.cpp").write_text(text.replace('#include "../common.hpp"', '#include "common.hpp"'))
                sh(["clang++", "-std=c++20", "-O0", f"-I{base}", str(d / "case.cpp"), str(base / "driver.o"),
                    str(base / "rt.o"), "-o", str(exe)])
        except RuntimeError as e:
            return m["id"], {"error": "compile: " + str(e)[-1500:]}
        vecs = vecs_of(m["id"])
        (d / "vectors.txt").write_text("".join(v + "\n" for v in vecs))
        outs, err = run_twice([str(exe)], lang, d / "vectors.txt")
        if err:
            return m["id"], {"error": err}
        return m["id"], judge(parse(outs[0]), parse(outs[1]), vecs, programs[m["id"]], lang, len(extras[m["id"]]))

    with ThreadPoolExecutor(jobs) as ex:
        return dict(ex.map(one, cases))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vectors", type=int, default=128)
    ap.add_argument("--length", type=int, default=2048)
    ap.add_argument("--jobs", type=int, default=8)
    ap.add_argument("--only", choices=["kotlin", "rust", "cpp"])
    a = ap.parse_args()
    manifest = json.loads((GEN / "manifest.json").read_text())
    programs = json.loads((GEN / "programs.json").read_text())
    WORK.mkdir(parents=True, exist_ok=True)
    base = base_vectors(a.vectors, a.length)

    vf = GEN / "validation.json"
    res = json.loads(vf.read_text())["results"] if vf.exists() else {}
    for lang in ("kotlin", "rust", "cpp"):
        if a.only and a.only != lang:
            continue
        cases = [m for m in manifest if lang in m["langs"]]
        print(f"[{lang}] planning directed vectors ...", flush=True)
        extras = plan_vectors(lang, cases, programs, base, a.jobs)
        print(f"[{lang}] directed vectors for {sum(1 for e in extras.values() if e)} case(s)", flush=True)
        vecs_of = lambda cid, ex=extras: base + (ex[cid] if cid else [])
        res[lang] = kotlin(cases, programs, vecs_of, extras) if lang == "kotlin" else native(lang, cases, programs, vecs_of, extras, a.jobs)

    bad = False
    summary = {}
    for lang, rs in res.items():
        s = {"cases": 0, "confirmed": 0, "confirmedByDirected": 0, "unconfirmed": [], "contradicted": [], "errors": [],
             "sinkNeverReached": [], "anomalies": [], "modelMismatches": 0}
        for m in manifest:
            if m["id"] not in rs:
                continue
            s["cases"] += 1
            r = rs[m["id"]]
            tag = f"{m['id']}_{m['name']}"
            if "error" in r:
                s["errors"].append(tag)
                continue
            s["modelMismatches"] += r["modelMismatches"]
            if r["anomalies"]:
                s["anomalies"].append(tag)
            if not r["sinkReached"]:
                s["sinkNeverReached"].append(tag)
            if r["witnessed"] and m["expect"] == "NO-FLOW":
                s["contradicted"].append(tag)
            elif r["witnessed"]:
                s["confirmed"] += 1
                s["confirmedByDirected"] += r["witnessedByDirected"]
            elif m["expect"] == "FLOW":
                s["unconfirmed"].append(tag)
        summary[lang] = s
        bad |= bool(s["contradicted"] or s["errors"])
        print(f"{lang:6}: {s['cases']} cases; FLOW confirmed {s['confirmed']} ({s['confirmedByDirected']} by directed vectors), "
              f"unconfirmed {len(s['unconfirmed'])} {s['unconfirmed'][:6]}; contradicted {len(s['contradicted'])} {s['contradicted'][:6]}; "
              f"errors {len(s['errors'])} {s['errors'][:4]}; sink never reached {len(s['sinkNeverReached'])} {s['sinkNeverReached'][:4]}; "
              f"model mismatches {s['modelMismatches']}; anomalies {len(s['anomalies'])}")
    vf.write_text(json.dumps({"vectors": a.vectors, "length": a.length, "secrets": SECRETS, "summary": summary, "results": res}, indent=1))
    print(f"wrote {vf}")
    if bad:
        sys.exit(1)


if __name__ == "__main__":
    main()
