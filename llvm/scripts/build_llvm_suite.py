#!/usr/bin/env python3
"""Compile the Rust/C++ ports of the benchmark suite to LLVM IR, run the
coro-reconstruct pass, and lower raw and reconstructed IR to object files.

Layout (one directory per benchmark):
  build/llvm/<lang>/<category>/<name>/
      raw.ll   recon.ll      IR as the compiler emitted it / after the pass
      raw.o    recon.o       llc -O0 objects (inputs for binary-level tools)
      report.jsonl           one pass report per coroutine in the module
      src.<ext>              copy of the benchmark source (+ prelude), for
                             source-level tools

Input point: -O0 after coroutine lowering (rustc lowers in MIR; clang's -O0
pipeline still runs CoroSplit). See llvm/README.md for why.

Usage: build_llvm_suite.py [--only rust|cpp] [filter-substring]
Overrides for another suite: LLVM_BENCH (default llvm/benchmark), LLVM_OUT
(default build/llvm).
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BENCH = Path(os.environ.get("LLVM_BENCH", ROOT / "llvm" / "benchmark")).resolve()
OUT = Path(os.environ.get("LLVM_OUT", ROOT / "build" / "llvm")).resolve()
PLUGIN = ROOT / "build" / "llvm-pass" / "CoroReconstruct.so"


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        sys.exit(f"FAILED: {' '.join(map(str, cmd))}\n{r.stderr}")
    return r


def emit_ir(lang, src, out_ll):
    if lang == "rust":
        run(["rustc", "--edition", "2024", "--crate-type=lib", "-C", "opt-level=0",
             "-C", "debuginfo=0", "-C", "panic=unwind", "--emit=llvm-ir",
             str(src), "-o", str(out_ll)])
    else:
        run(["clang++", "-std=c++20", "-O0", "-fno-discard-value-names", "-S",
             "-emit-llvm", str(src), "-o", str(out_ll)])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["rust", "cpp"])
    ap.add_argument("filter", nargs="?", default="")
    a = ap.parse_args()
    if not PLUGIN.exists():
        sys.exit(f"{PLUGIN} missing: cmake -S llvm/pass -B build/llvm-pass -G Ninja && ninja -C build/llvm-pass")

    rows = []
    for lang, ext in (("rust", "rs"), ("cpp", "cpp")):
        if a.only and a.only != lang:
            continue
        for src in sorted((BENCH / lang).glob(f"*/*.{ext}")):
            rel = src.relative_to(BENCH / lang)
            if a.filter not in str(rel):
                continue
            d = OUT / lang / rel.parent / rel.stem
            if d.exists():
                shutil.rmtree(d)
            d.mkdir(parents=True)
            shutil.copy(src, d / f"src.{ext}")
            shutil.copy(BENCH / lang / ("common.rs" if lang == "rust" else "common.hpp"), d.parent)
            emit_ir(lang, src, d / "raw.ll")
            run(["opt", f"-load-pass-plugin={PLUGIN}", "-passes=coro-reconstruct",
                 f"-coro-recon-report={d / 'report.jsonl'}", "-S",
                 str(d / "raw.ll"), "-o", str(d / "recon.ll")])
            for v in ("raw", "recon"):
                run(["llc", "-O0", "-filetype=obj", str(d / f"{v}.ll"), "-o", str(d / f"{v}.o")])
            reports = [json.loads(l) for l in (d / "report.jsonl").read_text().splitlines()] \
                if (d / "report.jsonl").exists() else []
            expect = next((l.split(":", 1)[1].strip() for l in src.read_text().splitlines()
                           if l.startswith("// expect:")), "?")
            rows.append({"lang": lang, "bench": str(rel.with_suffix("")), "expect": expect,
                         "reports": reports})
            st = ", ".join(f"{r['demangled'].split('::{')[0].split('(')[0][-28:]}={r['status']}"
                           for r in reports) or "no coroutines"
            print(f"{lang:4} {str(rel):55} {st}")
    # A filtered run updates its rows in place instead of dropping the rest.
    old = json.loads((OUT / "suite.json").read_text()) if (OUT / "suite.json").exists() else []
    fresh = {(r["lang"], r["bench"]) for r in rows}
    rows = [r for r in old if (r["lang"], r["bench"]) not in fresh] + rows
    rows.sort(key=lambda r: (r["lang"], r["bench"]))
    (OUT / "suite.json").write_text(json.dumps(rows, indent=1))
    print(f"\nwrote {OUT / 'suite.json'}")


if __name__ == "__main__":
    main()
