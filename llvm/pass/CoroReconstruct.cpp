//===- CoroReconstruct.cpp - direct-style views of lowered coroutines -----===//
//
// LLVM port of SuspendChainReconstructor.unrollGeneralCase (see README.md,
// "Reconstruction scope"). Input is IR *after* coroutine lowering and before
// general optimization (-O0), which is what rustc emits for every async fn and
// what clang emits after CoroSplit. Two lowering shapes are handled:
//
//  * Rust (frontend-lowered, MIR StateTransform). The poll function's entry
//    block switches on a discriminant byte in the coroutine (case 0 =
//    Unresumed, 1/2 = Returned/Panicked -> panic_const_*_resumed, k>=3 =
//    resume point k). Each await is `poll(inner) -> br Pending ? S : Ready`,
//    where S stores k into the discriminant and returns. As with kotlinc, the
//    resume cases jump *into* the single body copy, so the fast path from case
//    0 contains every user statement. Reconstruction: entry switch -> br
//    case 0, prune every edge into a suspend arm, drop unreachable blocks.
//
//  * C++20 (LLVM CoroSplit, switch ABI). The ramp F keeps the body, with each
//    llvm.coro.suspend folded to `switch i8 -1, label %ret [0: resumed,
//    1: destroy]`; F.resume holds a second copy behind an index switch.
//    Reconstruction: fold each such switch to its case-0 (resumed) target and
//    stub F.resume, whose body is now redundant. F.destroy/F.cleanup (the
//    analog of Rust's drop glue) are left alone.
//
// Nothing is mutated until a quotient check passes, so a declined coroutine is
// left byte-for-byte as the compiler produced it:
//  (1) every block that becomes unreachable is bookkeeping (no user call other
//      than the resumed-after-completion panic),
//  (2) every resume target still has a reachable continuation in the
//      reconstruction (Rust: dispatch jumps into the fast-path copy),
//  (3) C++: every function the .resume clone calls is called by the
//      reconstructed ramp (the ramp really holds the whole body; fails for
//      always-suspending awaiters after optimization, see docs/llvm-probe).
//
// The output is an analysis view, not a drop-in replacement: a reconstructed
// poll never returns Pending. That is the same contract as the Kotlin side's
// emitted .class files.
//===----------------------------------------------------------------------===//

#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/SmallPtrSet.h"
#include "llvm/Analysis/PostDominators.h"
#include "llvm/Demangle/Demangle.h"
#include "llvm/IR/CFG.h"
#include "llvm/IR/Constants.h"
#include "llvm/IR/Function.h"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/Instructions.h"
#include "llvm/IR/Module.h"
#include "llvm/IR/PassManager.h"
#include "llvm/IR/Verifier.h"
#include "llvm/Passes/PassBuilder.h"
#include "llvm/Plugins/PassPlugin.h"
#include "llvm/Support/CommandLine.h"
#include "llvm/Support/FileSystem.h"
#include "llvm/Support/JSON.h"
#include "llvm/Support/raw_ostream.h"
#include "llvm/Transforms/Utils/Local.h"

#include <map>
#include <optional>
#include <set>
#include <string>
#include <vector>

using namespace llvm;

static cl::opt<std::string>
    ReportPath("coro-recon-report",
               cl::desc("Append one JSON object per coroutine to this file"),
               cl::init(""));
static cl::opt<bool>
    DryRun("coro-recon-dry-run",
           cl::desc("Run detection and the quotient check, mutate nothing"),
           cl::init(false));

namespace {

using Edge = std::pair<const BasicBlock *, const BasicBlock *>;

struct Report {
  std::string Lang, Function, Status, Reason;
  unsigned InstBefore = 0, InstAfter = 0, BlocksBefore = 0, BlocksAfter = 0;
  unsigned Suspends = 0, ResumePoints = 0;
  std::vector<std::string> Lost; // non-empty only on a failed quotient check
};

unsigned countInsts(const Function &F) {
  unsigned N = 0;
  for (const BasicBlock &BB : F)
    N += BB.size();
  return N;
}

/// A call that belongs to the program, not to the lowering: anything that is
/// not an LLVM intrinsic and not inline asm.
const Function *userCallee(const Instruction &I) {
  auto *CB = dyn_cast<CallBase>(&I);
  if (!CB || CB->isInlineAsm())
    return nullptr;
  const Function *F = CB->getCalledFunction();
  if (F && F->isIntrinsic())
    return nullptr;
  return F; // nullptr for indirect calls, handled by hasUserCall
}

bool hasUserCall(const BasicBlock &BB) {
  for (const Instruction &I : BB)
    if (auto *CB = dyn_cast<CallBase>(&I))
      if (!CB->isInlineAsm() &&
          !(CB->getCalledFunction() && CB->getCalledFunction()->isIntrinsic()))
        return true;
  return false;
}

/// rustc's "resumed after completion / after panic" traps.
bool isResumedPanicCallee(const Function *F) {
  if (!F)
    return false;
  StringRef N = F->getName();
  return N.contains("panic_const") &&
         (N.contains("resumed") || N.contains("gen_fn_none"));
}

bool reachesResumedPanic(const BasicBlock *BB) {
  SmallVector<const BasicBlock *, 8> Work{BB};
  SmallPtrSet<const BasicBlock *, 8> Seen{BB};
  for (unsigned Steps = 0; !Work.empty() && Steps < 8; ++Steps) {
    const BasicBlock *Cur = Work.pop_back_val();
    for (const Instruction &I : *Cur)
      if (isResumedPanicCallee(userCallee(I)))
        return true;
    for (const BasicBlock *S : successors(Cur))
      if (Seen.insert(S).second)
        Work.push_back(S);
  }
  return false;
}

bool onlyResumedPanicCalls(const BasicBlock &BB) {
  for (const Instruction &I : BB)
    if (auto *CB = dyn_cast<CallBase>(&I)) {
      const Function *F = CB->getCalledFunction();
      if (CB->isInlineAsm() || !F)
        return false;
      if (!F->isIntrinsic() && !isResumedPanicCallee(F))
        return false;
    }
  return true;
}

/// Blocks reachable from the entry, with Override replacing the successor
/// list of selected blocks and Pruned edges removed.
DenseSet<const BasicBlock *>
reachable(const Function &F,
          const std::map<const BasicBlock *, std::vector<const BasicBlock *>>
              &Override,
          const std::set<Edge> &Pruned) {
  DenseSet<const BasicBlock *> Seen;
  SmallVector<const BasicBlock *, 32> Work{&F.getEntryBlock()};
  Seen.insert(&F.getEntryBlock());
  while (!Work.empty()) {
    const BasicBlock *BB = Work.pop_back_val();
    std::vector<const BasicBlock *> Succs;
    auto It = Override.find(BB);
    if (It != Override.end())
      Succs = It->second;
    else
      for (const BasicBlock *S : successors(BB))
        Succs.push_back(S);
    for (const BasicBlock *S : Succs)
      if (!Pruned.count({BB, S}) && Seen.insert(S).second)
        Work.push_back(S);
  }
  return Seen;
}

void dropIncoming(BasicBlock *Succ, BasicBlock *Pred) {
  for (PHINode &PN : Succ->phis())
    for (int Idx; (Idx = PN.getBasicBlockIndex(Pred)) >= 0;)
      PN.removeIncomingValue(Idx, /*DeletePHIIfEmpty=*/false);
}

/// Replace BB's terminator with `br Target`, fixing PHIs of the dropped
/// successors.
void redirectTo(BasicBlock *BB, BasicBlock *Target) {
  Instruction *T = BB->getTerminator();
  SmallPtrSet<BasicBlock *, 4> Dropped;
  for (BasicBlock *S : successors(BB))
    if (S != Target)
      Dropped.insert(S);
  for (BasicBlock *S : Dropped)
    dropIncoming(S, BB);
  // Keep exactly one PHI entry for Target if the old terminator had several
  // edges to it (switch with duplicate destinations).
  for (PHINode &PN : Target->phis()) {
    int First = PN.getBasicBlockIndex(BB);
    for (int I = PN.getNumIncomingValues() - 1; I > First; --I)
      if (PN.getIncomingBlock(I) == BB)
        PN.removeIncomingValue(I, false);
  }
  Value *Cond = nullptr;
  if (auto *SI = dyn_cast<SwitchInst>(T))
    Cond = SI->getCondition();
  else if (auto *CB = dyn_cast<CondBrInst>(T))
    Cond = CB->getCondition();
  UncondBrInst::Create(Target, T->getIterator());
  T->eraseFromParent();
  // The dispatch load and its casts (or the Pending test) are dead now.
  if (Cond)
    RecursivelyDeleteTriviallyDeadInstructions(Cond);
}

std::string describe(const BasicBlock *BB) {
  std::string S;
  raw_string_ostream OS(S);
  if (BB->hasName())
    OS << BB->getName();
  else
    BB->printAsOperand(OS, false);
  for (const Instruction &I : *BB)
    if (const Function *F = userCallee(I)) {
      OS << " calls " << demangle(F->getName().str());
      break;
    }
  return S;
}

// ---------------------------------------------------------------------------
// Rust: frontend-lowered switch shape.
// ---------------------------------------------------------------------------

struct RustShape {
  const Value *DiscBase = nullptr;
  int64_t DiscOffset = 0;
  BasicBlock *Fresh = nullptr;                // case 0
  std::map<uint64_t, BasicBlock *> Resumes;   // case k>=3 (or non-panic k)
};

/// The discriminant load behind a dispatch condition: `switch (zext load)`,
/// or for a coroutine without awaits `br (trunc load)` / `br (icmp eq load, 0)`.
const LoadInst *discLoad(const Value *V) {
  while (auto *C = dyn_cast<CastInst>(V))
    V = C->getOperand(0);
  if (auto *Cmp = dyn_cast<ICmpInst>(V)) {
    if (!Cmp->isEquality())
      return nullptr;
    V = isa<ConstantInt>(Cmp->getOperand(1)) ? Cmp->getOperand(0)
                                             : Cmp->getOperand(1);
    while (auto *C = dyn_cast<CastInst>(V))
      V = C->getOperand(0);
  }
  return dyn_cast<LoadInst>(V);
}

std::optional<RustShape> matchRust(Function &F, const DataLayout &DL) {
  if (F.isDeclaration() || F.arg_empty() ||
      !F.getArg(0)->getType()->isPointerTy())
    return std::nullopt;
  Instruction *T = F.getEntryBlock().getTerminator();
  auto *SI = dyn_cast<SwitchInst>(T);
  auto *BI = dyn_cast<CondBrInst>(T);
  if (!SI && !BI)
    return std::nullopt;
  const LoadInst *LI = discLoad(SI ? SI->getCondition() : BI->getCondition());
  if (!LI)
    return std::nullopt;
  APInt Off(DL.getIndexTypeSizeInBits(LI->getPointerOperandType()), 0);
  const Value *Base = LI->getPointerOperand()->stripAndAccumulateConstantOffsets(
      DL, Off, /*AllowNonInbounds=*/true);
  if (Base != F.getArg(0))
    return std::nullopt;

  RustShape R;
  R.DiscBase = Base;
  R.DiscOffset = Off.getSExtValue();
  bool SawPanicCase = false;
  if (BI) {
    // No awaits: Unresumed vs. Returned only.
    for (BasicBlock *S : successors(BI->getParent())) {
      if (reachesResumedPanic(S))
        SawPanicCase = true;
      else if (!R.Fresh)
        R.Fresh = S;
      else
        return std::nullopt; // two non-panic targets: not a dispatch
    }
  } else {
    for (auto &Case : SI->cases()) {
      uint64_t K = Case.getCaseValue()->getZExtValue();
      BasicBlock *Dest = Case.getCaseSuccessor();
      if (K == 0)
        R.Fresh = Dest;
      else if (reachesResumedPanic(Dest))
        SawPanicCase = true;
      else
        R.Resumes[K] = Dest;
    }
  }
  // The resumed-after-completion trap is what distinguishes a coroutine
  // dispatch from an ordinary `match` on the first field of a struct.
  if (!R.Fresh || !SawPanicCase)
    return std::nullopt;
  return R;
}

/// Does I store a constant into the discriminant slot? Returns the constant.
std::optional<uint64_t> discStore(const Instruction &I, const RustShape &R,
                                  const DataLayout &DL) {
  auto *St = dyn_cast<StoreInst>(&I);
  if (!St)
    return std::nullopt;
  auto *C = dyn_cast<ConstantInt>(St->getValueOperand());
  if (!C)
    return std::nullopt;
  APInt Off(DL.getIndexTypeSizeInBits(St->getPointerOperandType()), 0);
  const Value *Base = St->getPointerOperand()->stripAndAccumulateConstantOffsets(
      DL, Off, true);
  if (Base != R.DiscBase || Off.getSExtValue() != R.DiscOffset)
    return std::nullopt;
  return C->getZExtValue();
}

Report reconstructRust(Function &F, RustShape &R) {
  const DataLayout &DL = F.getDataLayout();
  Report Rep;
  Rep.Lang = "rust";
  Rep.Function = F.getName().str();
  Rep.InstBefore = countInsts(F);
  Rep.BlocksBefore = F.size();
  Rep.ResumePoints = R.Resumes.size();

  // Suspend arms: blocks post-dominated by a block that writes a resume-point
  // value into the discriminant. Their entry edges are the Pending decisions.
  PostDominatorTree PDT(F);
  std::set<Edge> Pruned;
  std::vector<std::pair<BasicBlock *, BasicBlock *>> PrunedMut;
  SmallPtrSet<const BasicBlock *, 16> ArmBlocks;
  for (BasicBlock &S : F) {
    bool IsSuspend = false;
    for (const Instruction &I : S)
      if (auto K = discStore(I, R, DL); K && R.Resumes.count(*K))
        IsSuspend = true;
    if (!IsSuspend)
      continue;
    ++Rep.Suspends;
    SmallPtrSet<const BasicBlock *, 8> Arm;
    for (BasicBlock &B : F)
      if (PDT.dominates(&S, &B))
        Arm.insert(&B);
    for (const BasicBlock *B : Arm) {
      // An arm is bookkeeping only: setting Poll::Pending and the state.
      if (hasUserCall(*B)) {
        Rep.Status = "declined";
        Rep.Reason = "suspend arm contains a user call: " + describe(B);
        return Rep;
      }
      ArmBlocks.insert(B);
    }
    for (const BasicBlock *X : Arm)
      for (const BasicBlock *D : predecessors(X))
        if (!Arm.count(D)) {
          Pruned.insert({D, X});
          PrunedMut.push_back(
              {const_cast<BasicBlock *>(D), const_cast<BasicBlock *>(X)});
        }
  }
  if (Rep.Suspends == 0 && !R.Resumes.empty()) {
    Rep.Status = "declined";
    Rep.Reason = "resume cases but no store of a resume-point value found";
    return Rep;
  }

  // Quotient check, before touching anything.
  auto Before = reachable(F, {}, {});
  auto After = reachable(F, {{&F.getEntryBlock(), {R.Fresh}}}, Pruned);
  for (const BasicBlock &B : F) {
    if (!Before.count(&B) || After.count(&B))
      continue;
    if (!onlyResumedPanicCalls(B))
      Rep.Lost.push_back(describe(&B));
  }
  for (auto &[K, Dest] : R.Resumes) {
    // Follow the resume entry through bookkeeping until it meets the
    // fast-path copy of the body.
    const BasicBlock *Cur = Dest;
    SmallPtrSet<const BasicBlock *, 8> Seen;
    while (Cur && !After.count(Cur) && Seen.insert(Cur).second &&
           !hasUserCall(*Cur))
      Cur = Cur->getSingleSuccessor();
    if (!Cur || !After.count(Cur))
      Rep.Lost.push_back("resume point " + std::to_string(K) +
                         " has no continuation on the fast path");
  }
  for (auto &[D, X] : PrunedMut)
    if (succ_size(D) < 2) {
      Rep.Lost.push_back("pending decision " + describe(D) +
                         " has no ready successor");
      break;
    }
  if (!Rep.Lost.empty()) {
    Rep.Status = "declined";
    Rep.Reason = "quotient check failed";
    return Rep;
  }
  if (DryRun) {
    Rep.Status = "reconstructible";
    return Rep;
  }

  // Mutate: Pending decisions first (they never involve the entry block).
  std::map<BasicBlock *, SmallPtrSet<BasicBlock *, 2>> DropBy;
  for (auto &[D, X] : PrunedMut)
    DropBy[D].insert(X);
  for (auto &[D, Xs] : DropBy) {
    Instruction *T = D->getTerminator();
    BasicBlock *Keep = nullptr;
    for (BasicBlock *S : successors(D))
      if (!Xs.count(S)) {
        Keep = S;
        break;
      }
    bool AllToKeep = true;
    for (BasicBlock *S : successors(D))
      if (!Xs.count(S) && S != Keep)
        AllToKeep = false;
    if (AllToKeep) {
      redirectTo(D, Keep);
    } else if (auto *SW = dyn_cast<SwitchInst>(T)) {
      for (BasicBlock *X : Xs)
        dropIncoming(X, D);
      for (auto It = SW->case_begin(); It != SW->case_end();)
        It = Xs.count(It->getCaseSuccessor()) ? SW->removeCase(It) : ++It;
      if (Xs.count(SW->getDefaultDest()))
        SW->setDefaultDest(Keep);
    } else {
      Rep.Status = "declined";
      Rep.Reason = "unsupported pending terminator: " + describe(D);
      return Rep; // unreachable in practice: checked shapes are br/switch
    }
  }
  redirectTo(&F.getEntryBlock(), R.Fresh);
  removeUnreachableBlocks(F);
  if (verifyFunction(F, &errs()))
    reportFatalInternalError("coro-reconstruct produced invalid IR in " +
                             F.getName());
  Rep.Status = "reconstructed";
  Rep.InstAfter = countInsts(F);
  Rep.BlocksAfter = F.size();
  return Rep;
}

// ---------------------------------------------------------------------------
// C++: CoroSplit switch-ABI shape (ramp + .resume clone).
// ---------------------------------------------------------------------------

/// `switch i8 -1, label %suspendRet [i8 0, %resumed; i8 1, %destroy]` -- what
/// CoroSplit leaves of llvm.coro.suspend in the ramp at -O0.
BasicBlock *caseDest(const SwitchInst *SI, uint64_t V) {
  for (auto &Case : SI->cases())
    if (Case.getCaseValue()->getZExtValue() == V)
      return const_cast<BasicBlock *>(Case.getCaseSuccessor());
  return nullptr;
}

bool isFoldedSuspend(const SwitchInst *SI) {
  auto *C = dyn_cast<ConstantInt>(SI->getCondition());
  if (!C || !C->isMinusOne() || C->getBitWidth() != 8)
    return false;
  return caseDest(SI, 0) && caseDest(SI, 1);
}

std::set<const Function *> calleesIn(const Function &F,
                                     const DenseSet<const BasicBlock *> &Live) {
  std::set<const Function *> Out;
  for (const BasicBlock &BB : F)
    if (Live.count(&BB))
      for (const Instruction &I : BB)
        if (const Function *C = userCallee(I))
          Out.insert(C);
  return Out;
}

Report reconstructCxx(Function &Ramp, Function &Resume) {
  Report Rep;
  Rep.Lang = "cxx";
  Rep.Function = Ramp.getName().str();
  Rep.InstBefore = countInsts(Ramp) + countInsts(Resume);
  Rep.BlocksBefore = Ramp.size() + Resume.size();

  std::vector<SwitchInst *> Folded;
  std::map<const BasicBlock *, std::vector<const BasicBlock *>> Override;
  for (BasicBlock &BB : Ramp)
    if (auto *SI = dyn_cast<SwitchInst>(BB.getTerminator());
        SI && isFoldedSuspend(SI)) {
      Folded.push_back(SI);
      Override[&BB] = {caseDest(SI, 0)};
    }
  Rep.Suspends = Folded.size();
  if (Folded.empty()) {
    Rep.Status = "declined";
    Rep.Reason = "no folded llvm.coro.suspend in the ramp (optimized IR?)";
    return Rep;
  }

  // Original feasible paths: the constant -1 always takes the default.
  std::map<const BasicBlock *, std::vector<const BasicBlock *>> AsCompiled;
  for (SwitchInst *SI : Folded)
    AsCompiled[SI->getParent()] = {SI->getDefaultDest()};
  auto Before = reachable(Ramp, AsCompiled, {});
  auto After = reachable(Ramp, Override, {});
  for (const BasicBlock &B : Ramp)
    if (Before.count(&B) && !After.count(&B) && hasUserCall(B))
      Rep.Lost.push_back(describe(&B));

  // The ramp must now cover everything the resume clone can do.
  auto ResumeLive = reachable(Resume, {}, {});
  auto RampCallees = calleesIn(Ramp, After);
  for (const Function *C : calleesIn(Resume, ResumeLive))
    if (!RampCallees.count(C))
      Rep.Lost.push_back("call to " + demangle(C->getName().str()) +
                         " exists only in " + Resume.getName().str());
  if (!Rep.Lost.empty()) {
    Rep.Status = "declined";
    Rep.Reason = "quotient check failed";
    return Rep;
  }
  if (DryRun) {
    Rep.Status = "reconstructible";
    return Rep;
  }

  for (SwitchInst *SI : Folded)
    redirectTo(SI->getParent(),
               const_cast<BasicBlock *>(Override[SI->getParent()].front()));
  removeUnreachableBlocks(Ramp);

  // Stub the clone: keep the symbol (the frame still stores its address).
  Resume.deleteBody();
  Resume.setLinkage(GlobalValue::InternalLinkage);
  BasicBlock *Stub = BasicBlock::Create(Resume.getContext(), "stub", &Resume);
  IRBuilder<> B(Stub);
  if (Resume.getReturnType()->isVoidTy())
    B.CreateRetVoid();
  else
    B.CreateRet(Constant::getNullValue(Resume.getReturnType()));

  if (verifyFunction(Ramp, &errs()) || verifyFunction(Resume, &errs()))
    reportFatalInternalError("coro-reconstruct produced invalid IR in " +
                             Ramp.getName());
  Rep.Status = "reconstructed";
  Rep.InstAfter = countInsts(Ramp);
  Rep.BlocksAfter = Ramp.size();
  return Rep;
}

void emit(const Report &R) {
  json::Object O{{"lang", R.Lang},
                 {"function", R.Function},
                 {"demangled", demangle(R.Function)},
                 {"status", R.Status},
                 {"reason", R.Reason},
                 {"instBefore", R.InstBefore},
                 {"instAfter", R.InstAfter},
                 {"blocksBefore", R.BlocksBefore},
                 {"blocksAfter", R.BlocksAfter},
                 {"suspends", R.Suspends},
                 {"resumePoints", R.ResumePoints}};
  if (!R.Lost.empty()) {
    json::Array A;
    for (auto &L : R.Lost)
      A.push_back(L);
    O["lost"] = std::move(A);
  }
  std::string Line;
  raw_string_ostream(Line) << json::Value(std::move(O)) << "\n";
  if (ReportPath.empty()) {
    errs() << Line;
    return;
  }
  std::error_code EC;
  raw_fd_ostream OS(ReportPath, EC, sys::fs::OF_Append);
  if (EC)
    reportFatalUsageError(Twine("cannot open ") + ReportPath + ": " +
                          EC.message());
  OS << Line;
}

struct CoroReconstructPass : PassInfoMixin<CoroReconstructPass> {
  PreservedAnalyses run(Module &M, ModuleAnalysisManager &) {
    bool Changed = false;
    std::vector<Function *> Fns;
    for (Function &F : M)
      Fns.push_back(&F);
    for (Function *F : Fns) {
      if (F->isDeclaration())
        continue;
      if (Function *Resume = M.getFunction((F->getName() + ".resume").str());
          Resume && !Resume->isDeclaration()) {
        Report R = reconstructCxx(*F, *Resume);
        Changed |= R.Status == "reconstructed";
        emit(R);
        continue;
      }
      if (auto Shape = matchRust(*F, M.getDataLayout())) {
        Report R = reconstructRust(*F, *Shape);
        Changed |= R.Status == "reconstructed";
        emit(R);
      }
    }
    return Changed ? PreservedAnalyses::none() : PreservedAnalyses::all();
  }
  static bool isRequired() { return true; }
};

} // namespace

extern "C" LLVM_ATTRIBUTE_WEAK PassPluginLibraryInfo llvmGetPassPluginInfo() {
  return {LLVM_PLUGIN_API_VERSION, "CoroReconstruct", "0.1",
          [](PassBuilder &PB) {
            PB.registerPipelineParsingCallback(
                [](StringRef Name, ModulePassManager &MPM,
                   ArrayRef<PassBuilder::PipelineElement>) {
                  if (Name != "coro-reconstruct")
                    return false;
                  MPM.addPass(CoroReconstructPass());
                  return true;
                });
          }};
}
