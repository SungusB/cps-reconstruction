# lit config for the coro-reconstruct pass. Run: lit -v llvm/test
# Tests compile tiny sources with the real rustc/clang, so they pin the pass
# to the IR shapes those compilers actually emit.
import os
import lit.formats

config.name = "CoroReconstruct"
config.test_format = lit.formats.ShTest(False)
config.suffixes = [".rs", ".cpp"]
config.test_source_root = os.path.dirname(__file__)
root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
config.test_exec_root = os.path.join(root, "build", "llvm-test")
plugin = os.path.join(root, "build", "llvm-pass", "CoroReconstruct.so")
config.substitutions.append(("%recon", f"opt -load-pass-plugin={plugin} -passes=coro-reconstruct"))
config.substitutions.append(("%rustc", "rustc --edition 2024 --crate-type=lib -C opt-level=0 -C debuginfo=0 --emit=llvm-ir"))
config.substitutions.append(("%clangxx", "clang++ -std=c++20 -S -emit-llvm"))
config.environment["PATH"] = os.environ["PATH"]
