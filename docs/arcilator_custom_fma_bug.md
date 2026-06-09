# Arcilator miscompiles the custom-FMA full SoC (debugging dossier)

Written 2026-06-09. Standalone brief for a fresh debugging session.

> ## ⚠️ RESOLVED 2026-06-09 — THIS WAS NOT AN ARCILATOR BUG (read this first)
>
> The custom-FMA full SoC **renders correctly in arcilator**. With firmware properly
> built and loaded, `useCustomFma=true` + arcilator passes the triangle golden (frame
> done in ~1M cycles) and runs vkcube to completion (~1.6M cycles) — verified 3/3
> deterministic in a clean worktree at HEAD `8969501`, using this dossier's *exact*
> `./arcilator_sim ../../software/borg/triangle.bin triangle` command.
>
> **Root cause of the false signature:** the repro below runs `make arcilator_sim`
> (which does **not** build the firmware) and then a *manual* `./arcilator_sim
> ../../software/borg/triangle.bin`. `triangle.bin` is a **gitignored build artifact**.
> When it is absent (or stale), flash is empty → the CPU never renders → the
> `0x0000DEAD` completion marker is never written → the 12M-cycle watchdog aborts and
> dumps a partial framebuffer, which differs from the golden by **exactly** `573/1024
> px, worst=255 at (25,1)`. Pointing the *passing* custom-FMA binary at a nonexistent
> firmware path reproduces this signature precisely. The verilator "control" used
> `make triangle`, which **does** build firmware → PASS. That asymmetric methodology —
> not the FMA — created the apparent arcilator/verilator discrepancy.
>
> Everything else falls out of this: the "invariance" to the FMA's logic/value/
> structure is exactly what a firmware-independent hang predicts, and the standalone
> harnesses (`fma_cosim`, `core_harness`) passed because they drive operands via
> C++/MMIO and need no firmware.
>
> **Takeaways:** (1) always build firmware before a manual `./arcilator_sim` — use
> `make triangle` / `make vkcube`, which build the `.bin` as a dependency; (2) a
> 12M-cycle abort + `573/(25,1)` means **empty flash**, not a codegen bug; (3)
> arcilator is safe to use for custom-FMA simulation. The historical investigation
> notes below are kept for the record only.
>
> ---

## TL;DR

The Borg SoC, built with `BorgConfig.useCustomFma = true` (use the in-tree
`BorgFp16Fma` instead of Berkeley HardFloat `MulAddRecFN`), **renders wrong and
hangs in arcilator** but is **correct in verilator**. The custom FMA is proven
bit-identical to HardFloat (30k RTL co-sim, 400k standalone-arcilator co-sim,
verilator golden). The failure is **invariant to the FMA's logic, value, and
structure**, so it is almost certainly an **upstream CIRCT/arcilator whole-design
codegen/scheduling bug** triggered only when the custom-FMA design is assembled
into the full SoC. It does **not** affect hardware (yosys/nextpnr synthesize the
verilator-proven RTL). Goal of a new session: get full-SoC observability, capture
the exact wrong value, build a minimal repro, and file/​fix upstream.

Toolchain: `firtool`/`arcilator` report `LLVM version 23.0.0git`, from
`pkgs.circt` in `flake.nix` (nixpkgs-pinned).

## The failure signature (extremely stable)

Full SoC + `useCustomFma=true` + arcilator, `make triangle`:
```
[SIM] ERROR: frame 1 exceeded 12M cycles with no completion marker — aborting
  FAIL  573/1024 pixels differ (max_diff=1, max_fail_pixels=2, worst=255 at (25, 1))
```
Exactly **573/1024** pixels wrong, **worst pixel always at (25,1)**, and it never
writes the `0x0000DEAD` completion marker (→ 12M-cycle watchdog abort). This exact
signature is **invariant** across every variant tried (see "Ruled out").

The 12M-cycle watchdog is in `simulation/arcilator/main.cpp` (added during this
investigation; verilator's `main.cpp` already had a cycle cap). Without it the run
spins at 100% CPU forever.

## What is PROVEN correct (the RTL is not the bug)

| Check | Engine | Result |
| --- | --- | --- |
| `BorgFp16FmaTests.vs_hardfloat` — 30k cases, BorgFp16Fma vs HardFloat `MulAddRecFN` | EphemeralSimulator | ✓ identical |
| `BorgFp16FmaTests` structured/random/realistic/inf/pipeline_hold — 100k+ cases vs exact BigInt FMA oracle | EphemeralSimulator | ✓ |
| **Standalone arcilator co-sim** (`FmaArcTop`): BorgFp16Fma vs HardFloat, 400k cases | **arcilator** | ✓ identical |
| **Standalone arcilator `BorgCore`** harness: ADD/MUL/FMA/FNEG with real values | **arcilator** | ✓ correct (0x4500/0x4a00/0x4700/0xc000) |
| Full SoC triangle render with custom FMA | **verilator** | ✓ golden PASS |
| `BorgCoreTests.custom_fma_path` end-to-end in the core | EphemeralSimulator | ✓ |

So: the FMA alone is correct in arcilator, **`BorgCore` is correct in arcilator**,
and the full SoC is correct in verilator. **Only the full SoC in arcilator fails.**
That makes it a scale/whole-design arcilator bug, not an RTL or FMA-arithmetic bug.

## What is RULED OUT (failure is invariant to all of these)

Every one of these still produced the identical 573/(25,1)/hang in the full SoC +
arcilator:

- The real custom FMA (correct math).
- **Forced constant output**: `io.out := "h3c00".U` → so the FMA's *value is
  irrelevant* to the failure.
- 1-register passthrough stub: `io.out := RegEnable(io.a, io.pipeEn)`.
- Combinational `RawModule` submodule + register moved to the parent `BorgCore`
  (i.e. structurally mirroring HardFloat's preMul/postMul + parent-reg).
- Fully **inline** in `wireFma`, no submodule at all.
- arcilator `--inline` removed.
- arcilator `--detect-enables=false --detect-resets=false`.
- 8192-bit dynamic-shift intermediate (was a real latent issue; clamped — no change
  to the failure).
- `Log2` replaced with an explicit priority-mux chain — no change.
- Harness `load_luts()` writing the rcp LUT into model memory: the arcilator-header
  memory accessors are regenerated per build (offsets track), so this is not a
  wrong-address corruption.

The ONLY thing that changes pass↔fail is `useCustomFma` false↔true. `false`
(HardFloat: `MulAddRecFN` + `preMul`/`postMul`/`round` submodules + `toPostMul_reg`
/`mulAddResult_reg` registers in `BorgCore`) → works. `true` (any of the above) →
fails identically.

## The structural difference between true and false

`hardware/borg/src/BorgCore.scala`, `wireFma()`:
- `useCustomFma=false`: instantiate HardFloat `MulAddRecFNToRaw_preMul`/`_postMul`
  (RawModules) + `RoundRawFNToRecFN`; pipeline registers `toPostMul_reg` +
  `mulAddResult_reg` live in `BorgCore`; recode via `recFNFromFN`/`fNFromRecFN`.
- `useCustomFma=true`: instantiate `BorgFp16Fma` (one submodule); pipeline register
  inside it; no recode; the HardFloat modules/registers are absent.

The custom-FMA SoC therefore has a different module set + state layout (HardFloat
preMul/postMul/round gone, BorgFp16Fma added, two `BorgCore` registers removed).
Memory set is otherwise identical (neither path adds memories). The bug is sensitive
to this whole-design shape at SoC scale (but NOT at `BorgCore` scale — `BorgCore`
alone is correct in arcilator).

## How to reproduce (≈4 min)

1. Edit `asic/tt/src/BorgArcSimTop.scala`, add inside the class body:
   ```scala
   override def BORG_CFG: borg.BorgConfig = borg.BorgConfig.Default.copy(useCustomFma = true)
   ```
2. Build + run (cwd matters — the arcilator Makefile is in `simulation/arcilator`):
   ```bash
   cd simulation/arcilator
   rm -f triangle_00.ppm borg.mlir arc.ll
   make arcilator_sim
   ./arcilator_sim ../../software/borg/triangle.bin triangle      # hangs → watchdog abort
   python3 ../../scripts/compare_ppm.py triangle_00.ppm ../../simulation/golden/triangle_00.ppm --max-diff 1 --max-fail-pixels 2
   ```
3. Control (same config, verilator → PASS):
   ```bash
   # add the same override to asic/tt/src/BorgSimTop.scala, then:
   cd simulation/verilator && make triangle
   ```
4. Revert the overrides afterward (default is HardFloat).

Pitfalls learned the hard way:
- Don't pipe `make` through `| tail` before `&&` — the pipeline exit status hides
  `make`'s failure and you'll golden-compare a **stale** ppm (false PASS). Run the
  sim directly and check `$?`.
- The arcilator FIRRTL is gated on Chisel-source mtimes via `.verilog_sim_stamp`;
  it does regenerate on edits (verified), but if in doubt `rm -f` the build
  products and check `out/hardware/borg/firrtl_sim/BorgArcSimTop.fir` mtime.

## Suggested next steps (in priority order)

1. **Full-SoC observability — the smoking gun.** In `BorgCore.wireFma` (custom
   branch) wrap the FMA result and its muxed operands in `dontTouch`ed named wires,
   rebuild arcilator with `--observe-named-values` (and/or `--observe-wires`), and
   either VCD-dump the first few thousand cycles (the model exposes `vcd()`) or read
   the observed state from the C++ harness. Capture the FMA operands+result for the
   first few shader ops *inside the SoC* and compare to the BigInt oracle. This tells
   you whether the FMA output itself is wrong in-SoC (vs a consumer/scheduling
   problem) and gives the exact diverging value.
2. **Bisect the hierarchy.** `BorgCore` alone is correct in arcilator; the full SoC
   is wrong. Arcilate the intermediate levels — `Borg` (GPU peripheral), then
   `MinimalSoC`/`Project` — to find the smallest assembly that fails. That assembly
   + its FIRRTL is the minimal upstream repro.
3. **Toolchain bump.** `nix flake update` (bumps `pkgs.circt`), then restart Claude
   in this dir so the new firtool/arcilator are on PATH. May already be fixed upstream.
4. **File a CIRCT issue** with the minimal FIRRTL + "correct in verilator + arcilator
   standalone, wrong in arcilator full SoC, invariant to the replaced module's logic."

## Standalone harnesses (recreated as committed tooling)

These bypass the full render so you can compare BorgFp16Fma against HardFloat
directly inside arcilator, fast:

- `asic/tt/src/FmaArcTop.scala` — emits FIRRTL for a co-sim top with both FMAs.
  Drive with `simulation/arcilator/debug/fma_cosim.cpp` (400k cases → 0 diverge).
- `asic/tt/src/BorgCoreArcMain.scala` — emits FIRRTL for a standalone `BorgCore`
  (custom FMA). Drive with `simulation/arcilator/debug/core_harness.cpp` (ADD/MUL/
  FMA/FNEG via MMIO → all correct).
- `simulation/arcilator/debug/README.md` — exact build/run commands.

Both harnesses pass in arcilator, which is the core paradox: the pieces are correct
in arcilator, only the whole SoC is not.

## Key files

- `hardware/borg/src/BorgFp16Fma.scala` — the custom FMA (verified correct).
- `hardware/borg/src/BorgCore.scala` `wireFma()` — the true/false branch.
- `hardware/borg/src/BorgConfig.scala` — `useCustomFma` flag (default false).
- `hardware/borg/test/src/BorgFp16FmaTests.scala` — co-sim + oracle tests.
- `asic/tt/src/BorgArcSimTop.scala` / `BorgSimTop.scala` — sim tops (set the flag here).
- `simulation/arcilator/main.cpp` — watchdog; `Makefile` — arcilator invocation.
