# Borg GPU — Development Roadmap

## Phase 1: Foundation & First Silicon

Target: March 2026 — TTIHP26a shuttle

- [x] Borg FP16 shader processor (ADD, MUL, FMA, FNEG, FSTEP)
- [x] RV32I CPU (Chisel rewrite) — original TinyQV-derived core, **since replaced by the Hutt core**
- [x] Triangle rendering pipeline (vertex/rasterize/fragment)
- [x] SPIR-B runtime shader loading
- [x] Per-vertex color interpolation
- [x] Dynamic framebuffer resolution
- [x] Z-buffer (firmware, per-pixel depth testing)
- [x] Texture mapping (firmware, UV interpolation + PSRAM sampling)
- [x] FPGA validation on pico-ice
- [x] GDS submission (4×2 tiles, IHP SG13G2) — [TinyTapeout (tt06)](https://app.tinytapeout.com/projects/3645)
- [x] 32-bit RISC-V instructions & 32-entry register file

## Phase 2: GPU Autonomy & Fidelity ✅ (largely complete)

Target: **~June 2026** — move the rendering inner loop from firmware into
hardware, step by step. Each step produces a measurable speed-up, can be
tested against the existing `triangle.py` golden image, and fits on iCE40.

### Current Architecture

The CPU drives every pixel: ~7–9 `borg_run()` MMIO round-trips per pixel
(3 edge tests + 3–6 fragment channels). Dominated by MMIO overhead, not
compute.

### PSRAM Access by Step

| Step | CPU → PSRAM | GPU → PSRAM | Contention |
| ---- | ----------- | ----------- | ---------- |
| Current | R/W every pixel | None | Low (CPU is sole user) |
| 0–10 | R/W every pixel (unchanged) | None | Low |
| **11 (tile buffer)** | **Between tiles only** | **Write** (tile flush) | **Low** |
| 12–13 | Between tiles/triangles | Write (flush) | Low |
| **19 (texel fetch)** | Between triangles | **Read + Write** | **None** — CPU out of loop |
| 20–25 | Submit + wait only | Full owner | None |

### Step 0: ~~Nibble-Serial FMA~~ (removed 2026-03-23)

Replaced the combinational 11×11 HardFloat `MulAddRecFN` with a multi-cycle
nibble-serial implementation. Saved ~215 LUTs but added pipeline complexity
(fma_inflight/ready handshake, timing issues with register file expansion).
**Removed** in favour of the simpler combinational FMA — the 4% LUT savings
was not worth the ongoing complexity. See [A4_experiments.md](A4_experiments.md).

### Step 1: Hardware Edge Function Unit ✅ (2026-03-19)

Batch all 3 edge functions (`e = dx·dpy − (−dy)·dpx`) into a single trigger,
reusing the FMA sequentially. Eliminates 2 of 3 `borg_run()` MMIO round-trips
per pixel. Widened register file (8→16), IMEM (6→8), and address bus (6→7 bits).

### Step 2: Multiple Triangles (Firmware) ✅ (2026-03-20)

Loop over a multi-triangle mesh in firmware (e.g. 12-triangle cube via
repeated `borg_cmd_draw()` calls). Already works for 2 triangles in
`borg_triangle.c`; extending to N is trivial.

### Step 3: 32-bit RISC-V Hardware Expansion ✅ (2026-03-23)

Expanded the register file from 16 to 32 entries and instruction memory
from 8 to 32 slots.  Transitioned the instruction format from custom 16-bit
to standard 32-bit RISC-V (R-type / R4-type).  This expansion provides
enough capacity to run the full `vkcube` perspective projection vertex
shader in a single pass.

### Step 4: Larger Register File + IMEM ✅ (2026-03-23)

Expanded to 32 registers and 32 IMEM slots.

### Step 5: Perspective Projection (Hardware Shader) ✅ (2026-03-24)

4×4 MVP matrix multiply per vertex (~16 FMA per vertex). Transforms from
model space to clip space natively. Thanks to the expanded
32-entry register file, this entire operation is now loaded and executed
natively as a single shader program on the Borg GPU. (Perspective divide is
currently mapped to a fast firmware soft-float via `borg_fp16_rcp`).

### Step 6: Hardware Reciprocal (RCP) Unit ✅ (2026-03-24)

Combinational FP16 reciprocal using a 17-entry VecInit LUT with linear
interpolation (~0.05% accuracy).  Mapped to `FRCP` instruction (funct7=0x0A).
Eliminated the firmware Newton-Raphson loop, replacing 4 MMIO round-trips per
W-divide with a single instruction.  Also fixed vkcube bottom-face rendering:
added explicit back-face culling (positive-area skip) and negative-Z pixel
discard to prevent FP16 edge-test precision leaks from corrupting the Z-buffer.

### Step 7: Triangle Clipping (Hardware Shader) ✅ (2026-03-27)

Near/far plane Sutherland–Hodgman clipping in firmware: vertices classified
against near (z ≥ 0) and far (z ≤ w) planes, intersection computed via
hardware FRCP + FMUL, clipped polygon fan-triangulated and rasterized.
Verified with vkcube at Z=0.0 (near-clipped), Z=0.5 (visible), Z=1.5
(far-clipped).  Also refactored vkcube: compact indexed geometry, mat4
helpers, fp16_from_float(), and Vulkan-style BorgShaderModule API.

### Step 8: Hardware Fragment Interpolation ✅ (2026-03-27)

Batch up to 6 fragment channel computations
(`channel = (e0·c0 + e1·c1 + e2·c2) · inv_area` for R, G, B, Z, U, V) through
the FMA with a single trigger. Eliminates 3–6 more round-trips per pixel.

### Step 9: Arcilator and Verilator ✅ (2026-03-29)

Added cycle-accurate C++ simulation models via arcilator and verilator.

### Step 10: Pixel Iterator (Hardware Rasterizer) ✅ (2026-04-05)

Counter-based x/y walker that evaluates edge functions (Step 1) and triggers
fragment interpolation (Step 9) for each inside pixel. CPU submits one triangle
instead of driving every pixel. **This is the key transition from
"ALU co-processor" to "rasterizer."** Estimate: 1–2 weeks.

- **Step 10.1: Dual Shader IMEM Residency** ✅ (2026-03-27)
- **Step 10.2: Bounding Box Early-Out** ✅ (2026-03-27)
- **Step 10.3: Hardware Counter Iterator** ✅ (2026-03-27)
- **Step 10.4: Hardware Edge Bounding Box Evaluation**
  - **10.4.1: Edge Sign Evaluation & Inside Flag** ✅ (2026-03-30): Snoop FPU writes to `r0/1/2` to latch edge function signs and expose a unified `inside_flag` via the `BORG_ITER` MMR.
  - **10.4.2: Rasterizer Auto-Execution** ✅ (2026-03-31): Auto-trigger the shader at `PC=0` on iterator advance, stalling the CPU until completion.
- **Step 10.5: Hardware Coordinate Expansion (int-to-fp16)** ✅ (2026-04-01)
  - **10.5.1: Hardware `coordLut` and MMIO Verification** ✅ (2026-03-31): Convert 6-bit int iterator coords into FP16 pixel centers mapped to `r30` and `r31`. Verify via MMIO reads against software computations without altering the running edge shader.
  - **10.5.2: FPU Coordinate Expansion Pipeline** ✅ (2026-04-01): Pass negative vertex coordinates (`-v.x`, `-v.y`) as uniforms into `rasterize.s`. Rewrite the shader to compute `dpx = px - vx` natively using `fadd.s` with `r30/r31`, keeping pixel accuracy.
  - **10.5.3: Software Delta Decommissioning** ✅ (2026-04-01): Remove legacy firmware `compute_pixel_deltas`. Validate `make triangle` produces the pixel-perfect rendering using strictly hardware coordinate expansion.
- **Step 10.6: CPU-Drawn Pixel Dispatch**
  - **10.6.1: Fragment Shader Register Alignment** ✅ (2026-04-01): Recompile `frag.s` so it reads edge values directly from r0/r1/r2 (rasterizer output slots) instead of separate attribute registers. Remove the firmware register copy. No hardware changes.
  - **10.6.2: Chained Shader Trigger (Hardware)** ✅ (2026-04-01): Add `frag_start_pc` register and phase FSM (`IDLE→RAST→FRAG`) to BorgRasterizer/BorgCore. Fix edge-sign snooping convention (positive=inside, negative=outside). Add attribute copy from rasterizer output regs to fragment attribute regs.
  - **10.6.3: Linear Scan Register Allocation** ✅ (2026-04-01)
    - `rasterize.s`: 18 → 17 registers (dpx/dpy reused across edges).
    - `vert.s`: 29 → 24 registers.
    - `shader.frag`: 29/30 registers (28 I/O vregs must be live simultaneously; uniforms persist).
    - Verified pixel-perfect against `golden.ppm`.

  - **10.6.4: Uniform Buffer (Replaces 64-GPR Expansion)**: Add a separate 32-entry × 16-bit read-only uniform buffer to solve rasterizer/fragment shader state clobbering. This follows the universal GPU pattern (PowerVR shared registers, VideoCore IV streaming FIFO, Mali Bifrost fast constant storage, Adreno constant RAM) rather than doubling the GPR file. Adds ~512 flip-flops on ASIC (+11%) vs. ~1,536 for 64 GPRs (+21%), preserves RISC-V 5-bit register encoding, and maps naturally to Vulkan UBOs/push constants. See [A5_register_architecture.md](A5_register_architecture.md) for the full design rationale, GPU architecture survey, and area analysis.
    - **10.6.4.1: Hardware Uniform Buffer** ✅ (2026-04-04): Add 32-entry register-based uniform buffer (~512 FFs). Decode `funct3[1:0]` to select which operand reads from the uniform buffer (`00`=all GPR, `01`=rs1, `10`=rs2, `11`=rs3). Integrate the read mux into the operand-resolution stage alongside the existing `coordLut` injection. Add MMIO write path (4-byte addressed, 32 entries). To fit in the 9-bit address space, shrink IMEM from 64→56 slots (shaders total ~50 instructions, no functional impact). MMIO loading is scaffolding — Step 21 (DMA) replaces it.
    - **10.6.4.2: Compiler Uniform Support** ✅ (2026-04-05): Update `borg_backend.py` to distinguish uniform vs. GPR virtual registers and emit the appropriate `funct3` bits. Update `Instructions.scala` encoding functions to accept the uniform operand flag. The register allocator assigns uniforms to the uniform buffer and temporaries/I/O to GPRs separately.
    - **10.6.4.3: Shader Reallocation** ✅ (2026-04-05): Rebuild `rasterize.s` (12 uniforms → buffer, ~8 GPRs) and `shader.frag` (19 uniforms → buffer, ~13 GPRs). Combined: 31 of 32 uniform slots used, ~16 of 30 GPRs used. Verify pixel-perfect against `golden.ppm`.
  - **10.6.5: Firmware Auto-Chain Integration** ✅ (2026-04-05): Rewrote `shade_tiles()` to load all 31 uniforms once per triangle (rast u0–u11 + frag u12–u30) and rely on the hardware FSM for autonomous RAST→FRAG chaining via `BORG_FRAG_PC`. Eliminated the per-pixel `borg_run_fragment()` call and per-pixel uniform reloading (~34 MMIO round-trips per inside pixel → ~8). Key changes:
    - **Compiler**: Added `--uniform-base N` flag to `borg_backend.py` for non-overlapping uniform allocation across shader stages. Fragment shader compiled with `--uniform-base 12`.

    - **Register-level ABI**: `spirv_compiler.py` emits `@borg bind` for fragment Input variables (e0→r0, e1→r1, e2→r2), matching rasterizer output slots. User writes GLSL; the system compiler enforces the convention — analogous to GPU varying linkage.

    - **Firmware**: Uniform setup moved from per-pixel to per-triangle. Inner loop reduced to iterator advance + result readback. `borg_run_fragment()` retained for debug/fallback only.

    - **Verified** pixel-perfect triangle rendering in Verilator (11.1M cycles).

  - **10.6.6: Debug Rendering Regression (Mostly Black with few pixels)**
    - *10.6.6.1: Verify rasterizer edge calculations and vertex mapping differences between software and hardware.*
    - *10.6.6.2: Ensure the fragment shader temporaries do not corrupt the edge signs monitored by `BorgRasterizer.scala` via `io.pipeWriteEn` snooping.*
    - *10.6.6.3: Resolve rendering issues caused by negation mismatches (`BORG_FP16_NEG`) in software versus hardware shader `fadd.s` operations.*
    - *10.6.6.4: Clean up unused logging code and legacy tile pixel writers.*
  - **10.6.7: Cross-Language Structural Reflection** ✅ (2026-04-06)

  - *Step 10 (now)*: CPU → MMIO writes → on-chip buffer (32 entries, scaffolding)
  - *Step 21 (GPU DMA)*: GPU fetches uniforms, IMEM, and registers from PSRAM autonomously

### Step 11: On-Chip Tile Buffer (BRAM)

4×4 pixel tile buffer in Block RAM (RGB + Z). Rasterizer writes on-chip; a
burst flush writes the completed tile to PSRAM. Eliminates per-pixel PSRAM
round-trips. Tile-based approach matches mobile GPU architecture (Mali,
PowerVR, Adreno). Estimate: 1–2 weeks.

- **Step 11.1: Standalone `BorgTileBuffer` Module** ✅ (2026-04-06)
- **Step 11.2: MMIO Wiring** ✅ (2026-04-06)
- **Step 11.2.5: Hardware Types & Decoupled Bus Reflection** ✅ (2026-04-06)
  - **`ColorZ` Bundle**: Replaced 8 discrete RGBZ ports inside `BorgTileBuffer` with a cleanly casted 64-bit `.asUInt()` unified structure.
  - **Instruction Bundling**: Stripped primitive tuple decodes inside `BorgCore` in favor of `FpuOpFlags` and `RegIndices` bundles cleanly routing down into the pipelined FSM execution blocks.
  - **`BorgBusIO` Layer**: Substituted ad-hoc MMIO wires (`address`, `data_in`, `is_writing`, `is_reading`) with a unified internal `BorgBusIO`, cleanly bridging dependencies into cleanly typed abstractions over standard sub-modules.
- **Step 11.3: Auto-Write from Fragment Shader** ✅ (2026-04-06)

- **Step 11.4: Firmware Tile-Loop Restructuring** ✅ (2026-04-06)

### Step 12: Hardware Z-Buffer Unit ✅ (2026-04-07)

FP16 comparator at the tile buffer write port — depth test in hardware instead
of firmware. 2-cycle read→compare→conditional-write state machine inside
`BorgTileBuffer`. Unsigned integer comparison (valid for positive FP16).
Rasterizer's `sTileWrite` phase asserts `zTestEn` and waits for `zTestBusy`.
Firmware `shade_and_write_pixel` no longer does per-pixel negative-Z guard
(hardware handles it). PSRAM Z-buffer write retained for cross-triangle ordering.
Verified: all Chisel tests, Verilator + Arcilator triangle rendering.

### Step 13: Command FIFO ✅ (2026-04-08)

2–4 entry FIFO between CPU and pixel iterator. CPU submits the next triangle
while GPU rasterizes the current one. Embryonic command buffer. Each FIFO entry
includes the uniform buffer snapshot (~64 bytes), bbox (3 bytes), and
frag_pc — roughly 68 bytes per entry, fitting in 1–2 BRAMs for a 4-entry FIFO.
Estimate: 3–5 days.

- **Step 13.1: Standalone `BorgCommandFIFO` Module** ✅ (2026-04-08)
- **Step 13.2: Dual-Page Uniform Buffer** ✅ (2026-04-08)
- **Step 13.3: Hardware FIFO Integration** ✅ (2026-04-08)
- **Step 13.4: Firmware Integration & Synchronization** ✅ (2026-04-08)

### Step 14: SystemRDL Register Description ✅ (2026-04-08)

Replace the hand-maintained `MmioMap.scala` constants and `MmioGenerator.scala`
C header emission with a machine-parsable [SystemRDL](https://github.com/SystemRDL)
register description. SystemRDL is an Accellera standard that serves as a
single source of truth for register maps, automatically generating RTL decode
logic, C/C++ firmware headers, documentation, and verification models from one
specification. This eliminates the recurring class of bugs where hardware MMIO
offsets drift out of sync with firmware `#define`s.
Estimate: 1 week.

- **Step 14.1: RDL Specification** ✅ (2026-04-08): Wrote `hardware/borg/rdl/borg_gpu.rdl`
- **Step 14.2: PeakRDL-chisel Exporter** ✅ (2026-04-08): Developed the custom Scala/Chisel backend publisher plugin for `PeakRDL` (<https://github.com/gonsolo/PeakRDL-chisel>) to directly emit synthesizable Chisel `Module` register blocks from the `.rdl`.
- **Step 14.3: RTL Integration** ✅ (2026-04-08): Wired the generated Chisel module block (`BorgGpuRegs`) into the `BorgBusIO` interface, replacing all manual address decoders and manual Flip-Flops in `Borg.scala` with PeakRDL's register nodes.
- **Step 14.4: Firmware/Backend Integration** ✅ (2026-04-08): Integrated `PeakRDL-cheader` to emit `borg_regs.h` (C headers) and a custom Python emit for `borg_mmio.py`. Completely deleted `MmioMap.scala`. Validated SystemRDL outputs against FPGA LC constraints (5113 LCs) via tied-off read-ports and verified the complete cocotb/Verilator/Arcilator/FPGA software stack.

### Step 15: Interactive Viewer ✅ (2026-04-10)

Implement a workstation-side UI runner that embeds the C++ Verilator simulation. Bridges the simulation's PSRAM output securely to an SDL2/Pygame window, enabling instantaneous visualization and WASD/mouse manipulation of the hardware engine in real-time.

- **Step 15.1: Pygame Binding & UI Rotation** ✅ (2026-04-09): Setup zero-copy nanobind bridge, cleared SPI deadlocks, and successfully bound mouse movement to dynamic hardware rotation rendering.
- **Step 15.2: Fast Memory Simulation** ✅ (2026-04-10): Bypass the QSPI serialization in the simulator by directly hooking the C++ memory models onto the TinyQV memory bus using a fast `TinyQVMemCtrlSim`, accelerating simulation cycles by ~11x (from >20M to 1.75M cycles per frame) for smoother interactive UI framerates.

### Step 16: Texture Fetch Unit ✅ (2026-04-13)

UV-to-texel conversion, Morton addressing, and PSRAM texel read inside the
pixel iterator. By this step the CPU is out of the inner loop, so there is no
bus contention — the failure mode of the earlier texture cache experiment.

- **Step 16.1: rcpLut → BRAM** ✅ (2026-04-11): Migrated `Fp16Rcp` VecInit ROM

- **Step 16.2: Peripheral Bus Widening (11→12 bit)** ✅ (2026-04-11): Widened

- **Step 16.3: FP16→uint6 + Morton Encoding Hardware** ✅ (2026-04-11): Added

### Step 17: LUT Recovery ✅ (2026-04-13)

Before adding any new infrastructure, recover LC headroom from three
low-risk structural changes identified in [A7_lc_savings.md](A7_lc_savings.md).
Target: free ~165–250 LUTs, bringing running total from 5420 to ~5170–5255.

- **Step 17.1: S4 — Remove RDL shadow registers** ✅ (2026-04-12) (~15–20 LUTs)

- **Step 17.2: A4 — Nibble-serial barrel shifter** ❌ abandoned (2026-04-12) (actual: −3 LCs)

- **Step 17.3: Remove C Extension** ✅ (2026-04-12)

- **Step 17.4: Verify all targets** ✅ (2026-04-13)

### Step 18: SoC Project Restructure ✅ (2026-04-13)

Reorganize the Mill build so `soc` is the parent module of both `borg` (GPU)
and `tinyqv` (CPU). This must happen before Step 19 adds new SoC-level files.

- **Step 18.1: Create `hardware/soc/` Mill module** ✅ (2026-04-13)

- **Step 18.2: Verify all targets** ✅ (2026-04-13)

### Step 19: Shared Memory Controller ✅ (2026-04-14)

Extract `TinyQVMemCtrl` to SoC level and add a GPU read port, making Borg an
autonomous bus master for PSRAM reads. This follows the universal GPU memory
architecture pattern: every GPU that shares memory with a CPU is a bus master
issuing its own read/write transactions through a shared interconnect with an
arbiter. The closest existing architecture is Broadcom VideoCore IV (RPi):
VideoCore's TMU = our `sTexFetch`, VPM = our `BorgTileBuffer`, shared SDRAM =
our shared QSPI PSRAM, central bus arbiter = our 2:1 mux.

- **Step 19.1: Extract MemCtrl to SoC level** ✅ *(2026-04-13)*

- **Step 19.2: Wire GPU port to BorgRasterizer** ✅ *(2026-04-14)*

### Step 20: IO Bundle Refactor (Code Quality) ✅ (2026-04-14)

Grouped flat signal clusters into 7 named Chisel Bundles across the hardware
hierarchy: `MemBusIO` (CPU↔MemCtrl data bus, 7 signals), `QspiPinsIO`
(physical QSPI pins, 7 signals), `PipeWriteIO` (pipeline write-back snoop),
`CoreTriggerIO` (rasterizer→core shader trigger), `CoreStatusIO` (core
execution status), `TexConfigIO` (texture fetch configuration), and
`TileWriteIO` (rasterizer→tile buffer writes). Pure refactor — no RTL
behaviour change, no new logic, no LC impact. Replaced dozens of manual
`a := b` wiring assignments with `<>` bulk-connects. Updated all Chisel
tests, C++ simulation harnesses, and `FlatIO`-based ExtModule port names.
Verified: all Chisel tests pass, Verilator/Arcilator triangle OK.

### Step 21: Area Optimizations + Tex Fetch Enable ✅ (2026-04-18)

**Do area optimizations first** to create headroom before adding new features.
The iCE40 is at 5280/5280 (100%) after Step 20. The root cause: 4019 LUTs
use 5280 LCs because ~1261 DFFs can't share cells with their LUTs. DFF
reduction is more effective than LUT reduction.

- **Step 21.0: Area Optimizations** (prerequisite for all new features)
  - ✅ **O1: RDL tile shadow registers → `hw=r`** (~40 LCs saved) *(2026-04-15)*
  - ✅ **O5: Command FIFO 2→1 entries** (~20 LCs saved) *(2026-04-18)*
  - **O6: Fp16Rcp NaN/Inf removal** (~8 LCs saved) — *deferred*
  - ✅ **O7: Remove dead `peekZ` tile buffer port** (~15 LCs saved) *(2026-04-15)*
  - ✅ **O8: Remove duplicate `read_addr_del`** (~6 LCs saved) *(2026-04-15)*
  - Target: **−89 LCs** → running total ~5191

- **Step 21.0.1: Parallel Test Runner** ✅ *(2026-04-15)*

- **Step 21.1: sTexFetch FSM Integration** ✅ *(completed during Step 19.2)*

- **Step 21.2: Tex Config MMIO + Firmware Integration** ✅ *(2026-04-15)* (+10 LCs)

### Dev Infrastructure ✅ (2026-04-18)

Continuous housekeeping work done alongside Steps 21–22. Not a numbered GPU
feature step, but recorded here for traceability.

- **BorgConfig centralized parameterization**: New `BorgConfig` case class
- **`.verilog_stamp` incremental build**: Root `Makefile` skips `generate_verilog`
- **Hardware architecture diagram generator** (`scripts/gen_hw_diagram.py`):
- **nextpnr `--seed 0`**: Pinned the placement RNG seed in `fpga/picoice/Makefile` for
- **CI tool-version diagnostics**: Added "Print tool versions" step to
- **Memory package modularization**: `TinyQVMemCtrl` extracted into a standalone
- **Miscellaneous**: Dead code removal (`LatchReg*`), Chisel test fixes

### Step 22: GPU DMA Engine ✅ (partial, 2026-04-18)

Generalize the GPU read port for bulk transfers. The DMA engine drives the
**same** `gpu_read` port built in Step 19 — `SoCMemCtrl` is unchanged, only
the driver changes.

- **Step 22.1: DMA controller FSM** ✅ (`BorgDMA.scala`, +25 LCs) *(2026-04-18)*
  - Full 2-state FSM (sIdle → sRead) that drives `GpuMemIO` and writes to IMEM or Uniform buffer.
  - Hardware complete; `hasDMA=false` on FPGA until firmware integration (see Step 25.5).

### Step 23: Cross-Target Parity (Arcilator / Verilator / FPGA + Software) ✅ (2026-04-20)

Establish a systematic quality gate that ensures Arcilator, Verilator, and FPGA
always produce identical results to the software reference — so that bugs like
the RP2040 texture heap exhaustion (which was invisible in simulation) are caught
automatically before they can reach hardware. The root cause of such bugs is a
discrepancy between what the software stack does and what each target exercises.
This step closes that gap structurally. Estimate: 3–5 days.

- **Step 23.1: Unified `make` run targets** ✅

- **Step 23.2: Pixel-exact golden comparison on all targets** ✅

- **Step 23.3: Shared software path for texture upload** ✅

- **Step 23.4: `make test-all` target parity enforcement** ✅

### Step 24: Memory Controller Rearchitecture ✅ (2026-04-23)

Unified the memory subsystem by removing the unreliable `MemoryControllerSim` and adopting a single, cycle-accurate `MemoryController` for both FPGA and simulation.

- **Step 24.1: Unified Logic ✅** — SoC uses one `MemoryController` with a clean byte-addressed interface (no base offsets).
- **Step 24.2: QSPI Parity ✅** — Simulators (Verilator/Arcilator) now use high-fidelity QSPI pin simulation, ensuring 100% hardware parity for Flash and PSRAM.
- **Step 24.3: Verification ✅** — Verified pixel-perfect rendering using the new unified path.

### Step 25: PSRAM Write Path + Architecture Decoupling ✅ (2026-04-29)

- **Step 25.1: `GpuMemIO` write signals ✅** (rename done 2026-04-23)

- **Step 25.2: GPU write path + smoke test — one red pixel at (0,0) ✅** (2026-04-23)
  - **Hardware:** Add `wr`/`wdata` to `GpuMemIO`. Update `MemoryController` with QSPI write command (0x02) and priority arbitration (CPU > GPU Write > GPU Read).
  - **Test:** Add one-shot `sGpuWriteTest` to `BorgRasterizer` to write 3 RGB words (1.0, 0.0, 0.0) at the framebuffer base on the first command.
  - **Gate:** `make triangle` shows a red pixel at (0,0) with normal rendering otherwise.

- **Step 25.3: Architecture Decoupling (Rasterizer & Tile Buffer)**

  - **Step 25.3.1: Integration-Level Tile Buffer Regression Test ✅** (2026-04-26)

  - **Step 25.3.2: Remove `sGpuWriteTest` State ✅** (2026-04-26)

  - **Step 25.3.3: Extract `BorgIterator` Module ✅** (2026-04-27)

    - Pop commands from `BorgCommandFIFO` via `Flipped(Decoupled(BorgCommand))`
    - Own `iter_reg`, `shader_iter_reg`, `tile_origin_reg`, `tile_max_reg`
    - Compute `iter_valid` (`iter_reg.y < tile_max_reg.y`)
    - On `advance` pulse: latch `shader_iter_reg`, step `iter_reg.x/y`,

  - **Step 25.3.4: Extract `BorgShaderDispatcher` Logic ✅** (2026-04-27)

    - Phase FSM: `sIdle :: sRast :: sFrag :: sTexFetch :: sTileWrite`
    - `auto_run_stall` register (set on advance, cleared on FSM→sIdle)
    - Edge-sign snooping: `e0/e1/e2_outside` from `PipeWriteIO` when
    - `core_just_finished` detection from `CoreStatusIO`
    - On RAST finish: check `inside_flag` → trigger FRAG or release
    - On FRAG finish: route to `sTexFetch` (if `texConfig.en`) or
    - Fragment output snooping: `frag_r/g/b/z` from `PipeWriteIO` when
    - `coreTrigger` output (valid + pc) to `BorgCore`

  - **Step 25.3.5: Extract `BorgTextureUnit` ✅** (2026-04-28)

  - **Step 25.3.6: Inside-Flag Guard on Tile Write ✅** (2026-04-28)

  - **Step 25.3.7: Isolate `BorgTileFlusher` Module** ✅
    - Activates after all 16 pixels are processed.
    - Shared `GpuMemIO` mux (CPU > DMA > Flusher > TexFetch).
    - Scaffold FSM (sIdle → sBusy → sIdle) for handshake verification.
    - Gated by `cfg.hasFlusher` to save FPGA LCs.

  - **Step 25.3.8: Software/Hardware Flush Toggle ✅** (2026-04-28)
    - `BorgIterator` emits `tileComplete` pulse when the last pixel's advance steps `iter_reg.y ≥ tile_max_reg.y`.
    - `BorgRasterizer` exposes `tileComplete` at its IO boundary.
    - `Borg.scala` `wireFlusher()`: wires `f.io.start := rast.io.tileComplete`; decodes three nogen shadow registers (`FLUSH_FB_BASE`, `FLUSH_ZB_BASE`, `FLUSH_WIDTH`) from the raw bus; connects `f.io.busy → STATUS.flush_busy` (bit 4).
    - `borg.rdl`: added `flush_busy` field to `status_reg_t` and three `nogen` registers (`flush_fb_base`, `flush_zb_base`, `flush_width`) at 0x218–0x220.
    - Firmware: polls `BORG_GPU->status & STATUS_REG_T__FLUSH_BUSY_bm` before the CPU tile-write loop; CPU tile-write path retained as fallback until Step 27.
    - Verified: 195/195 Chisel tests pass; Verilator triangle pixel-perfect against golden (11M cycles).

- **Step 25.4: Autonomous Tile Flushing (Sim/Verilator/Arcilator) ✅** (2026-04-29)

  - **Step 25.4.1: Single-Pixel Hardware Flush ✅** (2026-04-29)
    - Integrated `BorgTileFlusher` hardware path with read-before-write depth-test logic.
    - Unified address arithmetic for hardware and software paths using `PSRAM_OUT_SPI`.
    - Resolved firmware build issues (assert.h stub, header include paths).
    - Validated rendering parity across Verilator and FPGA targets.

  - **Step 25.4.2: Full 16-Pixel Tile Flush ✅** (2026-04-29)
    - `BorgTileFlusher` flushes all 16 tile pixels to PSRAM per tile-complete signal (Sim/Verilator/Arcilator).
    - FPGA CPU-fallback path: firmware reads all 16 pixels via `TILE_CTRL`/`TILE_BZ`/`TILE_RG` MMIO and writes to PSRAM when `FLUSH_BUSY=0` (HW flusher absent).
    - Fixed `generate.py` to parse `borg_layout.h` for `PSRAM_OUT_OFFSET` and `TEX_PSRAM_BYTE_OFFSET` instead of hardcoded stale values (was `0x80100`/`0x80`, now `0x84000`/`0x4000`).
    - Updated `fpga/common/host/render.py` and `scripts/postprocess.py` to decode TBR tiled layout (2 words/pixel, 4×4 tile addressing, `lo={B,Z}` / `hi={R,G}`).
    - Fixed arcilator `marker_offset_word` to use tiled layout (2 words/pixel vs old 4).
    - All 12/12 test suites pass including `render › fpga (hw)`. ✓

### Step 26: DMA Firmware Integration + LUT Recovery ✅ (2026-04-30)

Complete the firmware side of Step 22 and reclaim LC headroom to unblock the
hardware tile flusher. The DMA hardware (`BorgDMA.scala`) is already built
(Step 22.1); only firmware and FPGA config changes remain.

- **Step 26.1: Remove `entry_lo`/`entry_hi` latch registers** ✅ (~64 LCs saved, 2026-04-29)\
  `BorgTileFlusher`: `BorgTileBuffer.readDataHeld` already holds SRAM output stable; removed
  7→6-state FSM and 64 FFs. All 195/195 tests pass.

- **Step 26.2: Replace `tileBase_reg + (word_idx << 2)` adder with running `addrReg`** ✅ (~18 LCs saved, 2026-04-29)\
  `BorgTileFlusher`: removed `tileBase_reg` (20 FFs) and combinational adder; `addrReg`
  initializes to `io.tileBase` and increments +4 per write. All 195/195 tests pass.

- **Step 26.3: Don't latch full `descReg`** ✅ (~34 LCs saved, 2026-04-29)\
  `BorgDMA`: removed `descReg` (34 FFs: baseAddr+length+dest+offset); `io.desc` fields
  wired directly in `sRead` — firmware holds them stable during transfer. All 195/195 tests pass.

- **Step 26.4: Firmware DMA wrapper** ✅ (2026-04-29) — `dma_load_shader()` and
  `dma_load_uniforms()` added to `borg_fpu.c`/`borg_fpu.h`. Programs `DMA_PSRAM` +
  `DMA_CONFIG` (START|LENGTH|DEST|OFFSET) and polls `STATUS_REG_T__DMA_BUSY_bm`.

- **Step 26.5: Remove duplicate tile_bz shadow registers** ✅ (2026-04-30) — `tileShadowB`/
  `tileShadowZ` in `Borg.scala` duplicated `tile_bz_b_reg`/`tile_bz_z_reg` already in the RDL.
  Replaced with direct reads from `rdlRegs.io.hw.tile_bz_b/.tile_bz_z`. Saves ~35 LCs.

- **Step 26.5b: Remove dead `tex_uv` registers** ✅ (2026-04-30) — `tex_uv_u_reg`/`tex_uv_v_reg`
  had no hw output ports, no read-mux arm, firmware never writes them (hardware uses rasterizer UV
  snoop). Removed from `BorgGpuRegs.scala` and `borg.rdl`. Saves ~36 LCs at nextpnr.
  **Budget result: 5255 / 5280 LCs (99%), 25 under budget ✓**

### Step 27: Multi-Target FPGA Directory Restructuring ✅ (2026-04-30)

Reorganized `fpga/` from a flat pico-ice-only layout into a multi-target
hierarchy supporting both pico-ice (iCE40 UP5K) and ULX3S (ECP5-85K).

- **Step 27.1: Subdirectory-based target separation** ✅ — Split `fpga/` into
  `fpga/picoice/` (board-specific build: Makefile, host scripts, PCF, firmware
  cache), `fpga/ulx3s/` (stub for ECP5), and `fpga/common/` (shared host
  scripts: `render.py`, `run_hutt.py`, `usb_recover.sh`, etc.). Top-level
  `fpga/Makefile` is now a dispatcher that forwards `triangle`, `vkcube`,
  `burn`, `borg.bin`, and `clean` to the appropriate board subdirectory.
- **Step 27.2: `BorgConfig.ULX3S` stub** ✅ — `hardware/borg/src/BorgConfig.scala`: new config
  with `coordWidth=9`, `hasDMA=true`, `hasFlusher=true`, `hasImemMmio=false` (ECP5 has no LC
  budget pressure).
- **Step 27.3: `BorgConfig.FPGA` → `BorgConfig.PicoIce` rename** ✅ — Updated in
  `BorgConfig.scala` and `fpga/picoice/soc/src/PicoIce.scala`.
- **Step 27.4: `ulx3s_top` Chisel stub** ✅ — `fpga/ulx3s/soc/src/ULX3S.scala`: compiles and
  emits Verilog via `ULX3SMain` (`make generate_verilog_ulx3s`). Uses plain IO ports (not SB_IO);
  ECP5 TRELLIS_IO/BB primitives and LPF constraints deferred until hardware arrives.
- **Step 27.5: `generate_verilog_ulx3s` target in root `Makefile`** ✅ — Runs `ULX3SMain` at
  `CLOCK_MHZ=25` (ECP5 PLL output); emits Verilog to `out/ulx3s/verilog/`.
- **Step 27.6: Fix mpremote path resolution** ✅ — `render.py` texture and output
  `.bin` paths were relative to the old `fpga/` mount root but now live under
  `fpga/picoice/`. Fixed by deriving paths from the `firmware_bin` argument.
  Updated `fpga_render_test.sh` candidate PPM path to `fpga/picoice/`.
  All 12/12 test suites pass including `render › fpga (hw)`. ✓

### Step 28: Fully Autonomous Hardware Iteration ✅ (2026-05-01)

`BorgConfig.Sim` already has `hasFlusher=true` — development and validation fully possible in
Verilator today. ULX3S provides final hardware confirmation only.

With the hardware flusher active, the CPU no longer touches the tile buffer or PSRAM write path
during rendering. Full autonomy milestone.

- **Step 28.1: `hw_flusher_autonomous` Chisel integration test** ✅ — Added to `BorgTests.scala`.
  Writes a known 16-pixel pattern via MMIO, runs a rast shader through all 16 tile pixels so
  `tileComplete` fires autonomously, drives `io.gpuMem.ready`, and verifies: `FLUSH_BUSY` goes
  high then clears, exactly 32 PSRAM writes issued at correct byte addresses (`tileBase + i*8`
  stride), and all lo/hi word data matches `{B,Z}` / `{R,G}` packing. Tests: 1/1 ✓.

### Step 29: Integrated Vertex + Triangle Setup Sequencer ✅ (2026-05-01)

Pure Chisel RTL — no platform-specific IO. Fully developable and testable in Verilator before
the ULX3S arrives. `hasSequencer=true` on Sim and ULX3S; `false` on PicoIce (LC budget).

`BorgSequencer` FSM replaces what the CPU currently does in `run_vertex_shader()`,
`triangle_setup()`, and `compute_edge_vectors()` / `setup_tile_uniforms()`.
Reuses the existing `BorgDMA` engine and `BorgCore` FPU pipeline — no new arithmetic hardware.
Triangle setup is shader-based (per `docs/A1_bibliography.md`).

**PSRAM descriptor layout (Option A — 25 FP16 words = 50 bytes):**
`pos[3×3] | color[3×3] | uv[3×2] | flags` — post-clip, post-perspective-divide screen triangles.
Clipping remains on CPU (variable-length polygon output is not FSM-friendly).
Evolution path: Option B (VBO + stride) in Step 31; Option C (index buffer) in Phase 2.

- **Step 29.0: Config + MMIO scaffolding** ✅ (2026-05-01) — `hasSequencer` flag
  (`PicoIce=false`, `ULX3S/Sim=true`), `SEQ_DESC_BASE` (0x220 nogen),
  `SEQ_TRIGGER` (0x224 nogen), `STATUS.seq_busy` (bit 5). `BorgSequencer` stub wired
  into `Borg.scala`. `borg_regs.h` struct size → 0x230. All 12/12 suites pass. ✓

- **Step 29.1: BorgSequencer FSM — vertex shader sequencing** ✅ (2026-05-01) —
  7-state FSM: `sIdle → sLoadShader → sWaitDMA → sLoadVert → sWaitDMA →
  sRunVert → sWaitVert → (×3 vertices) → sDone → sIdle`.
  Uses `vertIdx` counter (0–2) and `nextAfterDMA` register to avoid state explosion.
  DMA loads shader into IMEM; for each vertex, DMA loads 3 position words into
  uniform buffer page 0, then `CoreTriggerIO` fires the core at PC=0; `PipeWriteIO`
  snoops clip-space outputs (r0–r3) into 12 shadow registers (`clipRegs`).
  Latched `dmaDescReg` holds descriptor stable for BorgDMA's direct-wire protocol
  (Step 26.3). New MMIO nogen registers: `SEQ_VERT_ADDR` (0x228), `SEQ_VERT_LEN`
  (0x22C). CoreTrigger mux (sequencer > rasterizer) in `wireCore()`.
  DMA mux (sequencer > MMIO) in `wireDMA()`.
  Gate: `BorgSequencerTests.vertex_shader_run` — 196/196 tests pass. ✓

- **Step 29.2: Triangle setup shader** ✅ (2026-05-01) — 23-instruction setup shader
  reads 6 screen-space coords from uniform buffer (u0–u5, loaded by sequencer from
  clipRegs), computes edge vectors via FNEG+ADD pairs, signed area via MUL+FMA,
  and inv_area via FRCP.  Outputs: r0–r5 = 6 edge components, r6 = area, r7 = inv_area.
  FSM extended to 11 states: added `sWriteSetupInputs` (writes 6 clipReg values to
  uniform buffer via new `uniformWrite` port, 6 cycles), `sLoadSetupShader` (DMA loads
  setup shader into IMEM), `sRunSetup`/`sWaitSetup` (runs shader, snoops r0–r7 into
  8-element `setupRegs`).  Uniform write port muxed between DMA and sequencer in
  `wireCore()`.  New MMIO nogen registers: `SEQ_SETUP_ADDR` (0x230), `SEQ_SETUP_LEN`
  (0x234).  `borg_regs.h` struct size → 0x238.
  Gate: `BorgSequencerTests.triangle_setup` — 197/197 tests pass. ✓

- **Step 29.3: Uniform staging** ✅ (2026-05-01) — FSM extended to 12 states:
  `sStageUniforms` writes all 31 physical uniform registers in 31 sequential cycles.
  Physical layout fixed from SPIRB blob parse (`shader_blobs.h`):
    u0–u5 = edge components (from `setupRegs[0–5]`, setup shader outputs);
    u6–u11 = negated vertex positions (FNEG of `clipRegs[v][c]`);
    u12 = inv_area (`setupRegs[7]`);
    u13–u21 = RGB colors in barycentric order (v1,v0,v2) × 3 channels;
    u22–u24 = z_vals (v1,v0,v2);  u25–u30 = 0 (UVs, future).
  Descriptor stride updated to 32 bytes/vertex (`borg_vertex_t` layout: x,y,z,r,g,b,u,v).
  DMA per vertex loads 8 words; `colorRegs[3][4]` (r,g,b,z) populated by snooping
  DMA uniform write stream (`dmaUniformSnoop` port, wired in `wireCore()`).
  `uniformWritePage` ping-pong: sequencer toggles `uniformPage` in `sWaitSetup`;
  page output muxed (sequencer > MMIO) in `wireCore()`.
  Gate: `BorgSequencerTests.sequencer_uniform_staging` — 198/198 tests pass. ✓

- **Step 29.4: Integration test** ✅ (2026-05-01) — `sequencer_full_triangle` verifies
  the complete pipeline: PSRAM descriptor → vertex shader → setup shader → sStageUniforms
  → rasterizer (trivial "always inside") → fragment shader reads staged uniform u14
  (color[0].r = 1.0) → tile buffer pixel RGBZ all match expected values.
  Bug fix: sStageUniforms uniform write address must include `uniformPage` bit
  (`Cat(uniformPage, writeIdx(4,0))`) — without this, staging always wrote to page 0,
  breaking ping-pong.
  Gate: `BorgSequencerTests.sequencer_full_triangle` — 199/199 tests pass. ✓

- **Step 29.5: Firmware auto-detection + golden image** ✅ (2026-05-01) — `borgCmdDraw()` auto-detects
  sequencer via `STATUS.seq_busy` (trigger dummy run during `borgCreateGraphicsPipeline()`).
  When detected, `borgBinRender()` replaces `setup_tile_uniforms()` with sequencer trigger:
  writes vertex descriptor to PSRAM per draw call, triggers `SEQ_TRIGGER`, polls `seq_busy`,
  reloads rast+frag shaders to IMEM (sequencer overwrites with setup shader).
  New PSRAM layout: `SEQ_VERT_SHADER_ADDR` (0x4800), `SEQ_SETUP_SHADER_ADDR` (0x4880),
  `SEQ_DESC_BASE_ADDR` (0x4900, 96-byte stride per draw call).
  Added `PSRAM_OUT_RAW(spi_addr)` macro for raw SPI byte address access.
  PicoIce path unchanged (`has_sequencer=0` → CPU `setup_tile_uniforms()` fallback).
  Gate: `make triangle` + `make vkcube` golden images match baseline; 199/199 Chisel tests pass. ✓

### Step 30: Full Autonomous Triangle Pipeline ✅ (2026-05-02)

Integration of Steps 21–29. CPU submits a triangle descriptor; GPU runs vertex
shader, triangle setup, and uniform staging autonomously. CPU then tiles and
fragment-shades as before. Developable and testable in Verilator (`BorgConfig.Sim`).

The firmware sequencer path is scaffolded (Step 29.5) but blocked on the setup
shader not being embedded in firmware ROM.  The hardware path is fully verified
via `BorgSequencerTests.sequencer_full_triangle`.

- **Step 30.1: Embed setup shader + enable sequencer path** ✅ (2026-05-02) — Hard-coded the
  22-instruction triangle-setup shader in `borg_driver.c`, embedded identity vert
  shader in PSRAM, enabled the sequencer auto-detection path.  The sequencer FSM
  and DMA pipeline are now exercised on every draw call; uniforms are still
  overwritten by the CPU path pending edge normalization.
  Gate: `m test-all` 12/12 green with `has_sequencer=1` active. ✅

- **Step 30.2: Edge normalization + autonomous uniform staging** ✅ (2026-05-02) — Extended
  the setup shader with `inv_width` normalization (31 instructions).  Added
  `sStageUniforms` FSM in `BorgSequencer` to compute and stage all 31 rasterizer
  uniforms autonomously.  Fixed root cause of black screen: `BorgCore` mapped
  r30/r31 → coordX/coordY unconditionally; sequencer's vertex/setup shaders used
  r31 as zero, contaminating vertex positions.  Fix: `seqBusy` signal gates the
  coord mux (r30/r31 return 0 during sequencer shader runs).
  Gate: `m test-all` 12/12 green. ✅

- **Step 30.3: Rename `coordOverride` → `seqBusy`** ✅ (2026-05-02) — Minimal cleanup:
  renamed the external coord-mux gating signal to describe what it *is* rather
  than what it *does*.  No functional change.

- **Step 30.4: Fix textured back-triangle regression** ✅ (2026-05-02) — Firmware-side UV
  staging: render loop explicitly writes u13–u18 (UV interpolation uniforms) and
  enables `tex_config` for textured draw calls, since `sStageUniforms` only stages
  vertex colors.  Eliminated all-red rendering of the back triangle.

- **Step 30.5: Fix uniform page desync (ping-pong disabled)** ✅ (2026-05-02) — Disabling
  `uniformPage` ping-pong in `BorgSequencer.sWaitSetup` eliminated a systematic
  checkerboard rendering failure (every other tile black).  Root cause: the
  alternating page write caused desync with the rasterizer's read page because the
  two sides of the ping-pong never ran concurrently (sequencer completes before
  rasterizer starts — no overlap).  Fix: sequencer always uses page 0; CPU sets
  `current_uniform_page = 0` unconditionally.  Updated `BorgSequencerTests` to
  match.  Fixed two Chisel W005 index-width warnings in `BorgSequencer`.
  Gate: `m test-all` 12/12 green; both triangles pixel-perfect (max_diff=0). ✅

- **Step 30.6: Sequencer path is primary; CPU fallback retained for pico-ice**
  ✅ (2026-05-02) — The sequencer path (`has_sequencer=1`) is now the primary
  render path for Sim/ULX3S. `setup_tile_uniforms()` is retained as a CPU fallback
  for pico-ice (`hasSequencer=false`). Runtime auto-detection selects the correct
  path. Uniform ping-pong page is local to the CPU fallback only.
  Gate: `m test-all` 12/12 green (Sim + FPGA). ✅

### Step 31: Multi-Triangle Autonomous Rendering ✅ (2026-05-03)

Extend Step 30 to process a list of triangle descriptors from PSRAM without
CPU involvement. The GPU reads the next descriptor, runs the full pipeline
(Vertex -> Setup -> Fragment), and signals DONE after the last triangle.
The CPU submits a draw call (base pointer + count) and waits.

- **Step 31.1: Infrastructure** ✅ — 128-byte descriptor layout (96B vertex +
  32B metadata with tile-aligned bbox) + BBox storage registers in sequencer.

- **Step 31.2: Shader Reload** ✅ — Hardware-driven IMEM staging: sequencer
  DMA-loads rast/frag shaders from pre-staged PSRAM between setup and tile
  iteration. Firmware stages shaders via `PSRAM_OUT_RAW` in
  `borgCreateGraphicsPipeline()`.

- **Step 31.3: Multi-Triangle Loop** ✅ — Sequential triangle processing via
  `sNextTriangle`. `triCount=0` guard in `sIdle` prevents the firmware's
  sequencer detection probe from running the full pipeline with garbage data
  (which corrupted the PSRAM firmware binary via tile flushing to `fbBase=0`).

- **Step 31.4: Autonomous Tile Iteration** ✅ (2026-05-03) — Hardware-driven
  bounding box walk with `sClearTile → sEnqueueTile → sIteratePixels →
  sWaitRast → sWaitFlush → sNextTile` loop. Three critical bugs fixed:
  1. **coreTrigger mux deadlock**: Mux select changed from `s.io.busy` to
     `s.io.coreTrigger.valid` so the dispatcher's trigger passes through
     during tile iteration.
  2. **writeIdx collision**: Dedicated `bboxWordIdx` counter for bbox DMA
     snoop, preventing `sStageUniforms` from starting at offset 2.
  3. **seqBusy coord gating**: New `seqShaderActive` output restricts
     `r30/r31=0` to vertex/setup phases only, preserving pixel coordinates
     during tile iteration rast/frag shaders.

Gate: `m test-all` 12/12 green; both triangles pixel-perfect (max_diff=0). ✅

---

### ✅ Step 32: True TBR — Two-Pass Hardware Binning — 2026-05-04

> **Motivation**: `vk_cube` renders 12 triangles with heavy face overlap. In the current
> single-pass sequencer a tile in the centre of the screen is rasterised and flushed to
> PSRAM once per triangle that covers it — up to 8×. A true TBR replaces this with a
> geometry pass that bins all triangles to tiles, then a tile-render pass that flushes
> each tile **exactly once per frame**, eliminating overdraw write bandwidth.

#### 32.0 PSRAM memory layout ✅ (2026-05-04)

Define the two new regions that live after the existing framebuffer:

| Region | Entry size | Total (runtime) | Notes |
| --- | --- | --- | --- |
| Per-tile bin lists | **2 B** (`uint16_t` index) | `numTiles × SEQ_MAX_TRI × 2` | triangle indices; fixed-size rows |
| Per-triangle setup store | 64 B | `SEQ_MAX_TRI × 64` | precomputed edge equations (u0–u11, 24 B) + 40 B reserved for future fields |

> **Scalability notes (applied before hardware):**
>
> - Triangle indices are `uint16_t` (not `uint8_t`) — cap is 65 535, not 255.
> - `SEQ_MAX_TRI = 1024` in `borg_layout.h` is the **PSRAM layout constant** (bin list + setup store sizing). It does **not** allocate RAM.
> - `BORG_MAX_DRAWS = 12` in `borg_driver.c` is the **in-RAM draw-call buffer**; it stays at 12 (constrained by the 0x600-byte PSRAM descriptor window). These two constants are intentionally decoupled.
> - Base addresses (`tbr_bin_base`, `tbr_setup_base`) are computed at runtime in `borgCreateDevice()` after the framebuffer size is known, so they automatically adjust to any resolution.
> - Setup store stride is 64 B (power of 2) → address = `tbr_setup_base + (tri << 6)`, no multiplier needed in hardware.

For vk_cube (12 tri, 10×8 tiles): 80 × 12 × 2 = 1.9 KB bin lists + 768 B setup store.\
For 1 000 tri, 80 tiles: 160 KB bin lists + 64 KB setup store — well within 8 MB PSRAM.

Add constants to `borg_layout.h`; update `borg_driver.c` (`borgCreateDevice` computes bases).

#### 32.1 BorgBinner — bin list writer ✅ (2026-05-04)

New Chisel module `BorgBinner` (`BorgBinner.scala`):

- Inputs: triangle index (`uint16`), setup-phase bbox `{y0,x0,y1,x1}` (tiled), trigger.
- For each tile in bbox: DMA-write triangle index to `binList[tile][count[tile]++]`
  in PSRAM. Per-tile count lives in a `SyncReadMem` SRAM (maxTiles entries × 10 bits).
- FSM: `sIdle → sReadCount → sWaitCount → sWritePsram → sStoreCount → sNextTile → ...`
- Output: `done` strobe when all tiles written.
- Gated behind `hasBinner: Boolean` in `BorgConfig`; disabled on iCE40 (`PicoIce`).
- Wired in `Borg.scala` (`wireBinner()`): sequencer drives all inputs (Step 32.2);
  binner GpuMem added to arb mux at lowest priority (DMA > Flusher > Binner > Rast).
- Unit tests: 5/5 green (idle, single tile, 2×2 bbox, count increment, clear counts).

#### ✅ 32.2 BorgSequencer: geometry pass (Pass 1) — 2026-05-04

Extended `BorgSequencer` FSM with per-triangle geometry pass:

- New states `sBinTri` / `sWaitBinner` inserted after bbox DMA (`sLoadBBox → sWaitDMA → sBinTri → sWaitBinner → sStageUniforms`).
- `clearCounts` pulse fired on `triIdx==0` (frame start) in `sIdle` — runs in parallel
  with the first vertex-shader DMA, adding zero latency.
- New `BorgSequencerIO` ports: `binnerStart`, `binnerTriIndex`, `binnerBbox`,
  `binnerClearCounts` (outputs); `binnerBusy`, `binBase`, `binRowBytes` (inputs).
- Two new MMIO registers: `SEQ_BIN_BASE` (0x260), `SEQ_BIN_ROW_BYTES` (0x264).
- FSM state count: 24 → 26.
- `make test-all` green; lint clean (added `countMem*.sv` suppression).

#### ✅ 32.3 BorgSequencer: tile render pass (Pass 2) — 2026-05-04

Restructured the sequencer into a true two-pass TBR (26 → 33 FSM states):

**Pass 1 (geometry)**: for each triangle: vert+setup+bin → `sStageUniforms` → **`sStoreSetup`** (writes all 31 uniforms to PSRAM at `setupBase + triIdx*128`) → `sNextTriangle`.

**Pass 2 (tile render)**: once after all triangles binned — rast+frag shaders loaded once → `sStartPass2` iterates ALL framebuffer tiles:

- `sReadBinCount` / `sWaitBinCount`: query per-tile triangle count from binner's on-chip SRAM via new `countRead` port.
- `sClearTile` → if count=0: `sWaitFlush` directly (empty tile, only clear color flushed).
- Otherwise: `sReadBinEntry` (DMA 1 word, snoop) → `sWaitBinEntry` → `sLoadTriSetup` (DMA 31 uniforms from setupBase+tri*128) → `sEnqueueTile` → `sIteratePixels` → `sWaitRast` → `sNextBinTri` loop.
- `sWaitFlush` / `sWaitFlushSync` → `sNextRenderTile` → `sDone`.

**New IO**: `setupBase`, `storeActive/Req/Addr/Wdata/Ready`, `countReadAddr/En/Data`, `fbWidthTiles`, `fbHeightTiles`.  
**New MMIO**: `SEQ_SETUP_BASE` (0x268).  
**BorgBinner**: added external `countRead` port (SyncReadMem shared between internal binning and sequencer queries).  
**Arb mux**: binner + seq store unified as "geo engine" (DMA > Flusher > Geo > Rast).  
`make test-all` green (23/23 Chisel tests).

#### ✅ 32.4 Driver integration — 2026-05-04

- **`borgBinRenderAutonomous()`** updated:
  - Removed CPU full-framebuffer clear-fill loop (~16K PSRAM writes eliminated).
  - Added three new MMIO writes before `seq_trigger`: `seq_bin_base`, `seq_bin_row_bytes`, `seq_setup_base`.
  - Function now writes 7 MMIO regs + 1 trigger; both passes run fully autonomously.
- **`TBR_SETUP_ENTRY_BYTES`**: `64` → `128` (31 uniforms × 4B = 124B, padded to 128B power-of-2; stride = `tri << 7`).
- **Documentation**: Added `docs/07_tbr.md` — full TBR architecture chapter covering two-pass design, BorgBinner, FSM state table, PSRAM layout, hardware component diagram, driver API, and performance characteristics.
- `make test-all` green (Verilator triangle + vkcube pixel-perfect); vk_cube renders correctly.

#### ✅ 32.5 vkcube Lighting Fix & PicoIce Updates — 2026-05-06

- **`vkcube` Lighting Fix**: Negated the light direction constants in `borg_vkcube.c` so the light correctly illuminates camera-facing faces instead of back-facing ones.
- **PicoIce Excluded from CI**: Temporarily disabled PicoIce tests in `make test-all` and removed its `HAND_CHISEL` inclusion since `BorgTextureUnit` overflows the iCE40 5280 LCs limit. The primary target is now `BorgConfig.Large` (Sim/ULX3S).

#### ✅ 32.6 Rendering Pipeline Synchronization & Simulator Parity — 2026-05-06

- **Pipeline Drain Gating**: Wired `BorgRasterizer.io.dispatcherPhase` (idle state) back to `BorgSequencer` to fix race conditions. The flusher and DMA uniform loader now wait for `dispatcherIdle` to ensure in-flight shader writes finish before context switching.
- **Lint Cleanups**: Fixed a Verilator `UNUSEDSIGNAL` in `BorgDMA.sv` (forced explicit 6-bit slice) and a Chisel `W004` index width mismatch in `BorgSequencer.scala`.
- **Simulator Parity**: Extracted shared app settings into `simulation/common/sim_app_config.h`. Both Verilator and Arcilator now use identical camera rotation angles for `vkcube`, resulting in a 100% pixel-perfect output match between the two simulators and the golden reference.

---

### Step 36 — ASIC Area Reduction & Tapeout (Target: TTIHP26b, September 2026)

> **Shuttle decision**: TTSKY26b closes 2026-05-11 (5 days). The design at 161%
> utilisation on 8×4 cannot be fixed in time — Phase 1+2 reach ~79% (routing gamble)
> and Phase 3 (custom FMA) alone takes 4–5 days. **Target TTIHP26b (Sep 2026)**
> instead: 4 months to implement all phases, validate gate-level simulation, and
> submit with confidence. IHP also provides silicon-proven 1024×8 SRAM macros.
> The Phase 1+2 work begun now is 100% reusable for IHP.

#### Background: Root Cause Analysis (2026-05-06)

Post-synthesis area: **1,104,576 µm²** (GPL placement). 8×4 usable: 685,137 µm².
Current utilisation: **161%** — fails at global placement.

**73% of the design is `SyncReadMem` synthesised as DFF arrays** by LibreLane's
`memory_map` pass. Sky130 TT has no SRAM macros; IHP has confirmed 1024×8 macros.

| Memory | Bits | DFF Area | % of design |
| --- | --- | --- | --- |
| `coordLutX` (BorgCore) | 8,192 | 174K µm² | 15.8% |
| `coordLutY` (BorgCore) | 8,192 | 174K µm² | 15.8% |
| `countMem` (BorgBinner) | 10,240 | 218K µm² | 19.7% |
| `instructionMemory` | 1,792 | 38K µm² | 3.5% |
| `uniformMem`, `rgbzMem`, regFiles, rcp, etc. | 4,398 | 94K µm² | 8.5% |
| **TOTAL memory** | **32,814** | **698K µm²** | **63.2%** |

> **Die sizes**: IHP and Sky130 use **identical** 8×4 block dimensions on TT
> (1724.16 × 710.64 µm, confirmed from `tt/tech/ihp-sg13g2/def/tt_block_8x4_pgvdd.def`).
> IHP's advantage is SRAM macros and more relaxed timing, not a larger die.

#### Utilisation Projections

| Step | Est. Area | 8×4 Util (Sky/IHP) |
| --- | --- | --- |
| Current | 1,104,576 µm² | 161% ✗ |
| +OPT-1: coordLut → arithmetic | 756,424 µm² | 110% ✗ |
| +OPT-2: countMem → PSRAM | 538,517 µm² | **79%** ✓ |
| +OPT-3: custom FP16 FMA | 458,517 µm² | **67%** ✓ |
| +OPT-4: ASIC config + cleanup | 438,517 µm² | **64%** ✓✓ |
| +OPT-5: shrink IMEM/uniforms/regs/rcp | ~388,000 µm² | **57%** ✓✓ |

---

- **OPT-1: Replace `coordLutX`/`coordLutY` with combinational arithmetic** ✅ (2026-06-01, `0e1519b`) *(`pixelToFP16Half` in `BorgCore.scala`; −31.5% area)*

  Both 512×16 `SyncReadMem` tables map pixel index `x` → FP16 pixel centre `x + 0.5`.
  They were added to save ~100 FPGA LUTs but cost 348,152 µm² as DFFs on ASIC.
  Replace with a 10-bit CLZ (count-leading-zeros) + 10-bit barrel shifter (verified
  correct for all 512 entries):

  ```scala
  val = 2*x + 1            // odd integer 1..1023
  msb = leading_one_pos(val)
  fp16 = ((msb + 14) << 10) | ((val << (10 - msb)) & 0x3FF)
  ```

  New module `IntToFp16Coord` replaces both LUT instances; remove `lutInit` coord
  write port from `BorgCore` IO (already tied off in `Borg.scala`).
  Gate: `m test-all` green; `coordX`/`coordY` match LUT output for x ∈ 0..511.

- **OPT-2: Move `countMem` write-pointers to PSRAM** *(−15 to −20% area, medium risk, ~1–2 days)*

  `countMem` (1024×10 DFF array, 218K µm²) tracks how many triangle indices have
  been written to each tile's PSRAM bin list. It acts as a write-pointer array for Pass 1.

  **Universal approach:** Instead of storing the write-pointers on-chip as DFFs or
  SRAM macros, allocate a 2 KB region at the start of the PSRAM binning area to
  store the 1024 counts. When binning a triangle:
  1. Read the tile's current count from PSRAM.
  2. Write the triangle ID to the tile's bin list in PSRAM.
  3. Write `count + 1` back to the PSRAM count region.

  Pass 2 then reads the final counts from this exact same PSRAM region. This triples
  the PSRAM bandwidth used by the binner, but completely eliminates the 218K µm²
  array from the chip on *both* Sky130 and IHP PDKs.

  Gate: `m test-all` green; binner integration test passes with correct per-tile counts.

- **OPT-3: Custom FP16 FMA — strip IEEE-754 special cases** *(−7% area, medium risk, ~4–5 days)*

  HardFloat `MulAddRecFN_e5_s11` implements full IEEE-754 (NaN, ±∞, subnormals,
  all rounding modes). The GPU never generates these — all values are bounded
  screen-space coordinates. A stripped FMA (normal numbers only, round-to-nearest,
  flush-to-zero on underflow) should be 2–3× smaller.
  New `BorgFp16Fma` Chisel module; keep HardFloat behind a config flag.
  Gate: `m test-all` 100% green; pixel-perfect parity with `BorgCoreTests`.

- **OPT-4: `BorgConfig.ASIC` + ASIC-specific cleanup** *(−2% area, low risk, ~2–3 hours)*

  Add `BorgConfig.ASIC` (`hasImemMmio=false`, `hasCoordLutArith=true`,
  `hasExplicitSRAM=true` for IHP). Wire `gds-sky130` / `gds-ihp` Makefile targets
  to use it. Gate: GDS build uses `BorgConfig.ASIC` without regression.

- **OPT-5: Shrink remaining small memories** *(−5% area, low risk, ~1 day)*

  | Memory | Change | Savings |
  | --- | --- | --- |
  | `instructionMemory` (56×32) | Reduce to 32 entries | −16K µm² |
  | `uniformMem` (64×16) | Reduce to 32 entries | −11K µm² |
  | Register files A/B/C (32×16 each) | Reduce to 16 registers | −16K µm² |
  | `rcpLutA/B` (17×10 each) | Replace with Newton-Raphson combinational | −7K µm² |

  Gate: `m test-all` green; shaders using registers 0–15 unaffected.

#### Utilisation After Each Step

> Both PDKs (IHP and Sky130) have identical 8×4 die dimensions on TT. Because OPT-2 now moves `countMem` to PSRAM completely, the area footprints and savings are identical for both PDKs. ✓ = routable (≤80%), ✓✓ = safe (≤65%), ✗ = fails placement.

| Step | Est. Area | 8×4 Util (Sky/IHP) |
| --- | --- | --- |
| Current | 1,104,576 µm² | 161% ✗ |
| +OPT-1: coordLut → arithmetic | 756,424 µm² | 110% ✗ |
| +OPT-2: countMem → PSRAM | 538,517 µm² | **79%** ✓ |
| +OPT-3: custom FP16 FMA | 458,517 µm² | **67%** ✓ |
| +OPT-4: ASIC config + cleanup | 438,517 µm² | **64%** ✓✓ |
| +OPT-5: shrink IMEM/uniforms/regs/rcp | ~388,000 µm² | **57%** ✓✓ |

Both PDKs reach the safe 8×4 threshold after OPT-4.

#### Implementation Schedule

```text
Now (before May 11):  OPT-1 + OPT-2  →  m test-all  →  synth check
                      (work reusable for IHP regardless of submission)

Jun–Aug 2026:         OPT-3 (custom FMA)  →  m test-all  →  make gds-ihp
                      OPT-4 (ASIC config) →  make gds-ihp  →  check util
                      OPT-5 (shrink mems) →  m test-all  →  make gds-ihp

Sep 2026:             Gate-level simulation  →  submit TTIHP26b 🚀
```

---

## Step 35 — BorgTextureUnit L2 Cache

**Motivation**: The Step 34 FTEX implementation uses an `en` gate in
`BorgShaderDispatcher` to return `(1.0, 1.0, 1.0)` for non-textured draws
instead of fetching from PSRAM. This works but is architecturally unclean: it
conflates a software binding policy with hardware fetch logic.

The correct long-term design (standard for all real GPUs) is a **tiny L2 texture
cache** in `BorgTextureUnit`. This makes even a "white default texture" approach
free (100% hit rate for any constant-address access), and improves real texture
fetch performance via Morton-locality hits. The `en` gate can then be retired.

### Step 35.0 — Cache Design

- **Direct-mapped, 4-entry** cache: 4 × (16-bit tag + 3 × 16-bit RGB) = 28 bytes
  per entry → ~112 bytes total. Fits in FPGA logic (no dedicated BRAM needed).
- Tag = Morton index bits [15:2] (4 entries indexed by bits [1:0]).
- On hit: return cached RGB in 1 cycle (zero PSRAM overhead).
- On miss: fetch from PSRAM, fill cache line.
- Invalidate on `io.texConfig` change (new texture bound).

### Step 35.1 — White Default Texture

- Reserve `TEX_WHITE_ADDR` in PSRAM layout (4 bytes, initialized in `borgCreateDevice`).
- `borg_clear_texture()`: points `tex_config` to `TEX_WHITE_ADDR` (en=true, 1×1).
- Remove `en` gate from `BorgShaderDispatcher` — FTEX always goes through cache.
- Non-textured draws: UV=0 → Morton=0 → cache hit → white → `vertexColor`.

Gate: all hardware tests pass; `vkcube` and `triangle` render correctly;
      Verilator shows ≥50% reduction in PSRAM reads for non-textured frames.

---

### ⏳ ULX3S Hardware Bringup

- **ECP5 synthesis flow** ✅ — `.lpf` pin constraints, `nextpnr-ecp5` Makefile target,
  Yosys elaboration clean for ECP5.

- **~~Enable DMA~~** ✅ — `hasDMA=true` + `hasImemMmio=false` already set in `BorgConfig.ULX3S`
  (Step 27.2). Firmware DMA wrappers (`dma_load_shader`/`dma_load_uniforms`) ready (Step 26.4).

- **~~Enable HW Flusher~~** ✅ — `hasFlusher=true` already set in `BorgConfig.ULX3S` (Step 27.2).
  Firmware auto-detects via `FLUSH_BUSY` bit — no code change needed.

- **Hardware validation of Steps 28–31** on ECP5.

---

### Step ULX3S-1: Onboard SDRAM Verification ✅ (2026-05-08)

The ULX3S v3.1.8 carries an **IS42S16160G-7TL** (32 MB, 16-bit wide, −7 speed grade).
Verified using the `sdram_pnru` controller from `ulx3s-misc` with the `ecp5pll` wrapper
(25 MHz in → 125 MHz core / 125 MHz @90° for SDRAM clock).

- **PLL + UART baseline** ✅ — 125 MHz PLL lock confirmed; `HELLO` printed over FTDI UART.
- **SDRAM init** ✅ — `sdram_pnru` completes 100 µs init sequence; `READY` confirmed over UART.
- **Single write/read** ✅ — Write `0xA5C3` to addr 4, read back: `PASS` confirmed over UART.
- Test source: `fpga/ulx3s/sdram_test/`

---

### Step ULX3S-2: SDRAM Stress Test

Walking-1s pattern across all 16 data bits and multiple row/bank addresses.
Verify that the SDRAM is reliable enough to serve as a GPU framebuffer.

- 16-address walking-1s write pass, then read-verify pass
- Report per-address `PASS` / `FAIL exp=XXXX got=XXXX` over UART
- Repeat continuously; zero errors required across 1 million cycles

---

### Step ULX3S-3: SDRAM Framebuffer Integration ✅ (2026-05-21)

Replace the QSPI PMOD memory path with onboard SDRAM for the ULX3S demo.
The tapeout design retains QSPI; this affects only `BorgConfig.ULX3S`.

> **As built** (diverged from the plan below): onboard SDRAM is driven by
> `SdramBackend` behind the existing `MemoryController` (no separate `sdram_pnru`
> module); the GPU/scanout arbiter is **priority-based** (`scanoutOwns`, GPU
> first) rather than round-robin; boot uses `FlashBootLoader` (flash 0x400000 →
> SDRAM 0x0), not a BRAM ROM; the framebuffer is **128×128 FP16, double-buffered**
> (bufs at SPI 0x85000 / 0xA5004), not 640×480 RGB565. `vkcube` renders correctly
> from SDRAM on hardware — proven by the live HDMI demo (Phase 3).

**Memory map (SDRAM, 32 MB):**

| Region | Size | Notes |
| --- | --- | --- |
| Framebuffer (640×480 RGB565) | 614 KB | 2 bytes/pixel |
| Tile lists (TBR binner) | 2 MB | bin_base |
| Triangle setup store | 128 KB | setup_base |
| Shader / descriptor area | 64 KB | vert/frag/setup shaders |
| Free | ~29 MB | — |

**CPU boot code:** embedded in BRAM as a hardcoded ROM (no QSPI needed at boot).
Small enough for demo firmware; full Linux boot still uses flash (Step 44).

- Update `BorgConfig.ULX3S` memory base addresses to point at SDRAM
- Replace `MemoryController` QSPI path with `sdram_pnru` arbiter
- SDRAM arbiter: round-robin between GPU write port and scanout read port
- Gate: `make vkcube` renders correctly in Verilator; SDRAM stress test passes on hardware

---

### Step ULX3S-4: HDMI/DVI Scanout via GPDI ✅ (2026-05-21)

Drive the ULX3S GPDI connector (HDMI-pinout DVI-D output) with a
TMDS scanout engine reading the SDRAM framebuffer.

**Pixel clock:** 25.175 MHz for 640×480@60Hz (standard VGA timing).
**TMDS:** 10× pixel clock = 251.75 MHz via ECP5 ODDRX2 LVDS DDR output.
**Source:** adapt proven ECP5 HDMI core (e.g. `hdmi.v` from `ulx3s-misc`).

> **As built:** `HdmiScanoutFp16` drives GPDI TMDS at a 25 MHz pixel clock
> (sysClock, 640×480 timing), with the 125 MHz TMDS clock from the PLL. The
> framebuffer is read via a free-running **full-frame BRAM fill** from SDRAM
> rather than the planned "scanout-priority-during-scan / GPU-fills-in-blanking"
> arbiter (that scheme was superseded; the GPU/scanout arbiter is GPU-priority).

- SDRAM arbiter: scanout gets priority during active scan; GPU fills during blanking
- Display timing: standard 640×480@60Hz (800×525 total, 25.175 MHz pixel clock)
- TMDS encoder: 8b/10b + differential output on 4 ECP5 LVDS pairs (3 data + clock)
- Gate: solid-color test pattern visible on monitor before wiring to framebuffer

---

### Step ULX3S-5: Borg GPU Live Demo on Monitor ✅ (2026-05-22)

Full end-to-end: Borg GPU renders `vkcube` → SDRAM framebuffer → HDMI → monitor.
No host PC required after flashing the bitstream.

- GPU renders autonomously (Steps 30–32 sequencer)
- Scanout engine streams framebuffer to HDMI at 60 Hz
- `vkcube` spins in real time on the monitor

> Live cube on HDMI landed 2026-05-22 (`41c314f`); interactive **mouse** control
> was added 2026-06-02 (`1ea9ad4`) — see Phase 3 (HPG demo).

### Step Dependencies

### FPGA LC Budget (pico-ice, iCE40 UP5K — 5280 LCs)

| Step | Change | Est. LCs | Running total | Fits? |
| --- | --- | --- | --- | --- |
| Current (16.3) | — | — | 5268 | ⚠ |
| 17.1 (S4 RDL shadows) | Remove redundant FFs | **−15–20** | ~5250 | ⚠ |
| 17.2 (A4 nibble shifter) | ❌ abandoned | **−3** | ~5265 | ⚠ |
| 17.3 (remove C ext) | Delete RVC decoder | **−84** (actual) | **5184** | ✅ |
| 18 (SoC restructure) | Package move only | +0 | ~5184 | ✅ |
| 19.1 (MemCtrl extract) | GPU port mux | +5–8 | ~5192 | ✅ |
| 19.2 (sTexFetch FSM) | 1 FSM state + addr calc | **+64** (actual) | **5256** | ⚠ |
| 20 (IO bundle refactor) | Pure rename | +0 | **5280** | ⚠ |
| **21.0 (area opts)** | **O1+O5+O6+O7+O8** | **−89** | **5191** | ✅ |
| 21.2 (tex config MMIO) | RDL register | +10 | 5201 | ✅ |
| 22.0 (LUT recovery) | Remove MMIO paths | **−44** | 5157 | ✅ |
| 22.1 (DMA FSM) ✅ | FSM + addr counter | +25 | 5182 | ✅ |
| 23 (unified runtime + tex) | Makefile + tex unification | +0 | 5182 | |
| 24 (MemCtrl rearch) | Unified arbiter logic | +0 | 5197 | ✅ |
| 25 (write path + decoupling) | GPU write + architecture | +15 | 5212 | ✅ |
| 26 (DMA firmware + LUT) | Remove MMIO paths | **−44** | 5168 | ✅ |
| 27 (multi-target dir) | Directory restructure | +0 | 5168 | ✅ |
| 29 (vert seq + tri setup) | Unified FSM | +45 | 5257 | ✅ |
| 30 (pipeline integration) | Wiring + control | +15 | 5272 | ✅ |
| 31 (multi-triangle) | Descriptor reader | +10 | **5282** | ⚠ |
| **Margin** | | | **−2 LCs** | |

- O4: Direct tile buffer write (−40 LCs, medium risk)
- ~~O2: Remove `tex_uv` registers after Step 25 (−20 LCs)~~ — done in Step 26.5b (−36 LCs actual)

### BRAM Budget

| BRAM | Contents | Size | Count |
| --- | --- | --- | --- |
| regFileA/B/C | GPR copies (rs1/rs2/rs3) | 32×16-bit | 3 |
| instructionMemory | Shader IMEM | 56×32-bit | 1 |
| uniformMem | Uniform buffer (2 pages) | 64×16-bit | 1 |
| coordLutX/Y | Pixel → FP16 | 64×16-bit | 2 |
| rcpLutA/B | Reciprocal LUT | 17×10-bit | 2 |
| rgbzMem | Tile buffer | 16×64-bit | 1 |
| **Total** | | | **10 / 30** |

### Development Platform Strategy

| Phase | Platform | Reason |
| --- | --- | --- |
| Phase 2 (Steps 21–27) | **pico-ice** (iCE40 UP5K) | TT-compatible pinout, forces area discipline |
| Phase 3 (Steps 28–31) | **pico-ice** (GPU=off) or **ULX3S** (ECP5-85K) | CPU-only fits at 75%; ECP5 for full design |
| Phase 4–5 (Steps 32–41) | **ULX3S** or **Nitefury II** (Artix-7) | FP32 GPU, Vulkan conformance, DDR3/PCIe |
| Tapeout | **Tiny Tapeout** (IHP SG13G2, 32 tiles) | Full SoC fits in ~14 tiles |

| Resource | pico-ice | ULX3S (ECP5-85K) | Nitefury II (XC7A200T) | TT (32 tiles, IHP) |
| --- | --- | --- | --- | --- |
| Logic | 5,280 LCs | 84,480 LUT4s | 134,600 LUT6s | ~96K std cells |
| BRAM | 120 Kbit | 3,744 Kbit | 13,140 Kbit | SRAM macros |
| DSP | 8 | 56 | 740 | None (synth) |
| RAM | 8 MB QSPI | 32 MB SDRAM | 1 GB DDR3 | External QSPI |
| Toolchain | Yosys+nextpnr | Yosys+nextpnr | Yosys+nextpnr (F4PGA) | OpenLane |
| TT pin-compat | ✅ | Adapter needed | ❌ | N/A (is TT) |
| Phase 2 fits? | ✅ (99%) | ✅ (6%) | ✅ (2%) | ✅ (~8 tiles) |
| Full Vulkan? | ❌ | ✅ | ✅ | ✅ (~14 tiles) |

### ULX3S / ECP5 Integration

The ULX3S (Lattice ECP5-85K) is the natural stepping stone between pico-ice
and Nitefury. It uses the **same open-source toolchain** (Yosys + nextpnr)
and has 16× the logic, with 32 MB SDRAM on board.

| pico-ice (iCE40) | ULX3S (ECP5) | Function |
| --- | --- | --- |
| `SB_IO` (pin_type=0x29) | `BB` / `TRELLIS_IO` | Bidirectional QSPI data |
| `SB_HFOSC` (48 MHz / div) | `EHXPLLL` (25 MHz → 24 MHz) | Clock generation |
| Direct pin assignment | Direct pin assignment | QSPI control (CS, SCK) |
| PCF constraints | LPF constraints | Pin mapping |

| Scenario | Platform |
| --- | --- |
| Phase 2 (Steps 21–27), area-constrained dev | pico-ice |
| Phase 3 (Steps 30–34), CPU grows past iCE40 | **ULX3S** |
| Phase 3 with GPU enabled (doesn't fit iCE40) | **ULX3S** |
| Phase 4–5, PCIe host access, DDR3 framebuffer | Nitefury II |
| ASIC validation, nightly CI | OpenLane |
| Tapeout | Tiny Tapeout (32 tiles) |

### Nitefury II / LiteX Integration

The Nitefury II (Artix-7 XC7A200T) has 40× the logic of the pico-ice, 1 GB
DDR3, and PCIe Gen2 x4. LiteX provides the outer shell (clocks, DDR3
controller, PCIe bridge) while the Borg SoC runs inside unchanged.

| TT Pin | Nitefury Mapping |
| --- | --- |
| `ui_in[7]` (UART RX) | PCIe UART bridge RX |
| `uo_out[0]` (UART TX) | PCIe UART bridge TX |
| `uio[0]` (Flash CS) | QSPI flash on PMOD or emulated |
| `uio[1:2,4:5]` (SD0–SD3) | QSPI PSRAM emulator data |
| `uio[3]` (SCK) | QSPI PSRAM emulator clock |
| `uio[6]` (RAM A CS) | QSPI PSRAM emulator select A |
| `uio[7]` (RAM B CS) | QSPI PSRAM emulator select B |
| `clk` | LiteX system clock (24 MHz, matching pico-ice) |
| `rst_n` | LiteX reset controller |

- `SoCLogic` trait (Project.scala:71) — all SoC wiring is platform-independent
- `tt_um_gonsolo_borg` (Project.scala:338) — standardized 8+8+8 pin interface
- `HuttTop` (`fpga/picoice/soc/src/PicoIce.scala`) — shows how to wrap `SoCLogic` for a

### Step 33: Fragment Interpolation (Hardware-Assisted)

Optimize shader interpolation path to utilize the hardware edge-equation signals for perspective-correct barycentric weights.

### Step 33.5: 2×2 Quad Execution (dFdx / dFdy support)

Real mobile GPUs (Mali, Adreno, PowerVR) run the fragment shader on **2×2 pixel
quads** simultaneously, enabling screen-space derivative instructions (`dFdx`,
`dFdy`). The Khronos `vkcube` demo uses these to compute flat face normals from
position derivatives without storing per-vertex normals.

Borg currently processes one pixel at a time. Supporting quads requires:

- **Pixel iterator**: emit 4 pixels per step (2×2 Morton-aligned block)
- **Fragment shader**: run 4 instances per quad, share outputs between neighbours
- **Derivative instruction**: new `FDFDX`/`DFDY` ISA opcode = subtract adjacent
  GPR output across the quad boundary
- **Helper invocations**: pixels outside the triangle still run (for derivative
  correctness at edges), with their tile-buffer write suppressed

Area impact: approximately 4× the register file width, or a 4-lane SIMD
fragment path. Feasible on ECP5-85K (`BorgSize.Large`); out of budget on iCE40.

Gate: `dFdx(frag_pos)` produces the same result as the CPU-precomputed face
normal in the vkcube lighting test.

## Phase 3: HPG 2026 Demo

Target: **June – July 14, 2026** — High Performance Graphics 2026 runs July 17;
the flight departs July 14, so all demo work must land by **July 14, 2026**.

**Deliverable:** the Borg GPU rendering an interactive `vkcube` on a monitor over
HDMI from the ULX3S, rotation driven live by mouse, no host PC in the render
loop. ✅ *Working* — mouse-controlled rotating textured cube, committed
2026-06-02 (`scripts/mouse_rotation.py` → UART → ULX3S).

**Goal:** take the demo from a 2.8 fps proof-of-life to a smooth, presentable
real-time demo, and bank the CPU memory-subsystem groundwork the roadmap was
missing (it doubles as Linux-arc prerequisite work for Phase 4).

### Measured baseline (2026-06-02, ULX3S @ 25 MHz, 128×128 fb)

Per-frame phase breakdown via UART markers (`scripts/measure_fps.py`):

| Phase | Time | Cause |
| ----- | ---- | ----- |
| matrix (2 rotate + 3× `mat4_mul`) | ~140 ms | scalar FP16 via per-op GPU round-trip |
| clear + texture + lighting | ~74 ms | same |
| `draw_cube` (12 tris, CPU transform) | ~155 ms | vertex shader + perspective divide per vertex |
| present (GPU autonomous render) | ~66 ms | the only well-optimized stage |
| **frame total** | **~355 ms → 2.8 fps** | **CPU FP16 is ~82% of the frame** |

Root cause: every scalar FP16 op (`borg_fp16_mul/add/fmadd`) is a full GPU launch
(IMEM rewrite + pipeline reset + double busy-poll + ~6 MMIO round-trips). The
RV32I Hutt CPU has no hardware multiply and no cache, so it is *also* instruction-
starved by the free-running scanout fill on the shared SDRAM port. Phase 2 moved
the *per-pixel* loop into hardware; the bottleneck has since moved to the **CPU
geometry/transform** stage, which no earlier roadmap step covered.

### Step H1 — Firmware op-count reduction (firmware-only, low risk, ~1–2 days)

Specialize the constant scale (`s`) and translate (`tz`) matrix multiplies
(sparse: ~12 and ~4 ops vs 64 each); precompute `tz·s` once outside the loop.
Transform the 8 unique cube vertices once per frame instead of 36 (12 tris × 3).
Expected: matrix ~140→~50 ms, draw ~155→~60 ms → ~5 fps. No bitstream rebuild.

### Step H2 — FPU call optimization (touches shared `borg_fpu.c`, ~1 day)

Preload FADD/FMUL/FMADD/FRCP once into the free IMEM region (offset 40+), one PC
per op; the per-op path becomes write-operands → START → poll → read, dropping
the per-op `RESET_PIPELINE`. Validate against the sim/golden image.
Expected: ~1.3–1.6× on every remaining FP16 op (helps all CPU phases).

### Step H3 — CPU L1 instruction cache (bitstream, ~3–5 days) ⭐

Small direct-mapped I-cache (1–4 KB) in the Hutt fetch path / `MemoryController`.
Removes the CPU as a per-cycle SDRAM requester → fixes instruction starvation and
decouples it from the scanout fill. Fills a roadmap gap (no CPU cache was
previously planned) and is prerequisite-grade work for the Linux arc (Phase 4).

### Step H4 — Scanout QoS / rate-limiter (bitstream, ~2–3 days)

Replace the full-frame BRAM fill's continuous `gpuMem.req` with a credit/budget so
it consumes *bounded* SDRAM bandwidth and yields to CPU + GPU; pair with a
priority reorder (instruction fetch above the soft display traffic). The
principled redo of the 2026-06-02 hold-off hack that broke the scanout handshake.

### Step H5 — GPU vertex-transform offload (architectural, stretch)

Move the MVP × vertex transform from the CPU into the sequencer's vertex shader so
the CPU only uploads the matrix uniform + raw vertices (the mobile-GPU approach).
Biggest single win — eliminates most of matrix + `draw_cube` — but the largest
change. Do only if H1–H4 land with time to spare.

### Stretch / nice-to-have for the talk

- [ ] Larger or higher-res framebuffer once bandwidth allows
- [ ] Bilinear texture filtering ([Step 34](#step-34-bilinear-texture-filtering)) if time permits

**Fallback:** the demo already works at 2.8 fps. H1 + H2 are firmware-only (no
bitstream risk) and alone should reach a presentable frame rate, so the demo is
safe to show even if the hardware steps (H3–H5) slip.

### Status update (2026-06-05) — burst-SDRAM done, H4 abandoned, reprioritized for Vulkan

Actual progress since the H1–H5 plan was written:

- **H5 (GPU vertex-transform offload): DONE.** The MVP×vertex transform now runs in
  the sequencer; the CPU only uploads the matrix + raw vertices.
- **H4 (Scanout QoS): ABANDONED.** Gating the scanout fill deadlocks the gpuMem
  handshake (→ Borg `mmio.req.ready` sticks → CPU freezes) **and** has ~zero upside:
  the priority arbiter already grants the scanout SDRAM only when the GPU isn't
  requesting, so it never causes the stall. The "stall" is the GPU waiting on its
  **own** flush + dma SDRAM traffic, not scanout contention.
- **Burst-SDRAM (new, the real lever): DONE & HW-verified.** The flusher now streams a
  whole tile as one burst write instead of 48 single-word writes that each paid a full
  `req→ready` round-trip. Result on the board: **flush 62→30 ms, stall 103→44 ms,
  present 207→150 ms, 3.93→5.06 fps (+29%)**, zero pin-timing risk.

#### Realistic ULX3S fps ceiling: ~10–15 fps @ 128×128

Stacking the full optimization set: command-buffer record-once + I-cache + M (CPU
47→~14 ms) → burst-reads + back-to-back flush → ~2 frag cores (frag 81→~50 ms) →
clock 25→~33 MHz → H5/double-buffer overlap ≈ **~13 fps**. Three hard walls cap it:

1. **Area** — each frag core is an FP16 FMA + HardFloat; the single-core GDS was ~107k
   instances vs the ECP5-85K's ~84k LUTs, so **~2 frag cores is the practical max**.
   That caps the biggest lever (frag is 54 % of present).
2. **Clock** — FPU Fmax ≈ 34 MHz post the FMA-pipeline fix; ~30–33 MHz is nearly free,
   but going higher needs deeper FPU pipelining.
3. **Per-pixel work is fixed** — only parallelism (cores) and clock move it.

**800×480 is display-only**, not an fps target: ~23× the pixels of 128×128 → frag ~23× →
fractions of an fps for shaded content. The smooth rotating-cube demo lives at 128×128.
Past ~15 fps needs a bigger FPGA / pipelined FPU / host CPU — a different board.

#### Vulkan-aligned next steps (each an fps win **and** a roadmap step)

The target is **Vulkan** Mesa + Linux, which reorders the levers:

1. **GPU command-buffer / queue-submit model** ⭐ — Vulkan *is* command buffers; the
   sequencer's descriptor list (`seq_desc_base`) is already proto-this / proto-DRM-submit.
   Make the GPU consume a full command buffer from SDRAM (CPU writes a pointer + kicks).
   Biggest CPU fps win too: the firmware re-records `draw_cube` (29 ms) **every** frame,
   but geometry is static and the GPU does the transform — so **record once, update only
   the MVP uniform per frame** → `draw_cube` ~29→~5 ms. Same change = fps win + the literal
   Vulkan/DRM submit shape.
2. **I-cache (H3)** — required by both the Mesa Vulkan driver and Linux; supports the above.
3. **M ext → D-cache → pipeline → CSRs → Sv32 MMU** — the Linux-boot ladder (Phase 4), each
   also a CPU fps gain.
4. **`nir_to_borg` backend + DRM kernel driver** — Vulkan ships SPIR-V; Mesa's `spirv_to_nir`
   feeds a driver NIR→Borg-ISA backend (the "SPIR-B" format is the seed). The long pole for
   "Vulkan runs" (Phases 4/5).
5. **~2 frag cores** — the fps ceiling lever; area-limited, large effort, SDRAM contention.

**Skip:** back-to-back-flush controller change (only ~+7 % now that flush isn't the
bottleneck — a roadmap dead-end) and any hand-tuning of the firmware vkcube (thrown away
once Mesa renders the real one).

**Reality check:** Mesa-Vulkan on a soft RV32 @ ~30 MHz is slow regardless — the Linux/Mesa
milestone is "runs standard Vulkan," not an fps number. But Vulkan's explicit command-buffer
model is far lighter per-frame than OpenGL, so it's more viable on a soft CPU. The fps demo
stays bare-metal; both goals share the same command-buffer + CPU investments.

## Phase 4: Linux-Capable CPU

Target: **~Sept 2026** — expand the Hutt CPU to RV32IMA. Sequential after the HPG demo (Phase 3).

*Velocity note: Steps 1–20 completed in 27 days (Mar 19 – Apr 14). Phase 2
adds ~7 steps of medium-hard complexity (FPGA at 99%). Phase 4's bottleneck
is the Sv32 MMU (3–4 weeks alone). Dates assume current solo-dev pace.*

### Step 40: M Extension (Integer Multiply/Divide)

Add dedicated integer multiplier for MUL/MULH/DIV/REM.
Estimate: 1 week.

### Step 41: A Extension (Atomics)

LR.W / SC.W for Linux `futex` and spinlocks. Reservation register (32-bit
address + valid bit). ~100 LUTs. Reference KianV implementation.
Estimate: 3–5 days.

### Step 42: Boot no-MMU Linux

Intermediate milestone before full MMU. Estimate: 1 week.

### Step 43: MMU (Sv32)

Two-level page table walker, 4–8 entry TLB, `satp`/`mstatus` CSRs.
Intermediate milestone: boot no-MMU Linux first (~1 week).
~800–1200 LUTs — the most expensive single addition.
Estimate: 3–4 weeks.

### Step 44: Boot Full Linux

Kernel, device tree, rootfs on QSPI PSRAM (8 MB). Estimate: 1–2 weeks.

## Phase 5: Mesa Vulkan Driver

Target: **~Nov–Dec 2026** (~8–10 weeks). Write a Mesa Vulkan ICD for the
Borg GPU. This is a domain shift — Mesa/NIR/SPIR-V are a new codebase.
Expect 2–3 weeks ramp-up on top of implementation time.

### Implementation notes (2026-06-05, from a Mesa source study)

Mesa checked out at `/home/gonsolo/src/mesa/src`. Three findings shape the work:

1. **You write ~10 % of a Vulkan driver.** `vulkan/runtime` (~52k LOC) is free: all the
   `vk_*` base objects (instance/device/queue/command_buffer/pipeline/image/render_pass),
   entrypoint dispatch, sync (`vk_sync`/fence/semaphore/drm_syncobj), WSI swapchain/present,
   and **`vk_meta`** (generic clears/copies/blits via shaders). You subclass ~15 `vk_`
   objects + fill hardware hooks. Driver-specific surface ≈ **2–4k LOC** C + the shader
   backend.

2. **Model on `v3dv` (Broadcom/RPi V3D), not `panvk` or `nvk`.** v3dv is small (~17k LOC TBR
   core), single-generation, and tile-based: it serializes a Binning Control List + Render
   Control List at driver time and submits with one ioctl, then the GPU runs per-tile and
   stores the tile buffer. **Borg already does exactly this** (sequencer descriptors →
   autonomous TBR → tile flush), so the GPU command-buffer model is the literal `borgvk`
   submit path. Key files to mirror: `broadcom/vulkan/v3dvx_cmd_buffer.c`
   (`job_emit_binning_prolog` + per-tile RCL), `v3dv_cmd_buffer.h`, `v3dv_pass.h`,
   `v3dv_cl.h`, `v3dv_queue.c`. Avoid panvk (multi-gen genxml + CSF GPU-firmware command
   streams — antithetical to Borg's CPU-canned model) and nvk (NVIDIA immediate-mode).

3. **`nir_to_borg` (Step 46) is the long pole, ~5–7k LOC.** `spirv_to_nir` is free
   (`compiler/spirv` via `vulkan/runtime/vk_nir.c`). Reuse ~90 common `nir_lower_*` passes
   (`nir_lower_to_scalar` is key for the scalar FP16 ISA) + 2–4 custom; then isel (NIR-walk)
   + linear-scan RA (32 regs, trivial) + a simple scheduler + emit. Model on
   `src/broadcom/compiler` (scalar; simpler than asahi). `software/borg/compiler/borg_backend.py`
   is a head start. The target is `nir_to_borg`, not the hand-written SPIR-B.

**Strategic reorder — no Linux-on-Borg needed for first Vulkan.** Don't gate Vulkan on
booting Linux on the soft Hutt CPU. Run Mesa + `borgvk` on the **host PC** and submit command
buffers to the FPGA over **USB** (ULX3S is USB-connected, like the pico-ice RP2040 host) —
Borg as an external Vulkan accelerator. Mesa's in-tree **`drm-shim`** runs a driver with no
kernel; point it at a USB transport (or the Verilator/Arcilator sim first). Milestones:
(1) `nir_to_borg` compiles vkcube's SPIR-V; (2) `borgvk` + drm-shim → simulator renders
vkcube; (3) drm-shim → FPGA over USB → **Vulkan vkcube on the board with Mesa on the host**
— the real demo, needing none of the Phase 4 Linux CPU work; (4, later) a real DRM/KMS
kernel driver + Linux on the upgraded Hutt for on-device vkcube. So Steps 45–49 below are
better done host-side first, with the kernel/on-device path as a separate later arc.

### Step 45: Minimal `vk_device` + `wsi_headless`

Headless rendering, no window system needed. Estimate: 1–2 weeks.

### Step 46: Shader Compiler (NIR → SPIR-B)

NIR backend generating Borg instructions. Estimate: 2–3 weeks.

### Step 47: Draw Path (`vkCmdDraw`)

Vertex + fragment shader dispatch to hardware. Estimate: 1–2 weeks.

### Step 48: Texture Sampling (Software)

CPU-side sampling, spec-compliant but slow. Estimate: 1 week.

### Step 49: Vulkan CTS Subset

Run conformance tests, fix failures. Estimate: 1–2 weeks.

## Phase 6: Mobile GPU Fidelity (Steps 34–39)

Target: **~Jan–Feb 2027**. Transition from "Autonomous Renderer" to a feature-complete
"Mobile-Class GPU" (Bilinear, Z-Buffer, Blending).

### Step 34: Bilinear Texture Filtering

Upgrade `BorgTextureUnit` and `TextureCache` to fetch 4 neighboring texels in a single
burst and perform hardware-weighted average. Eliminates pixelated "aliasing"
on magnified textures.

### Step 35: Hardware Z-Buffer & Atomic Depth Test

Expand `BorgTileBuffer` from 64-bit to 80-bit per pixel (RGBA + Z). Implement
hardware "Depth Pass" logic: fragments are only written to the tile buffer
if `fragment_z < buffer_z`. Eliminates the need for CPU-side triangle sorting.

### Step 36: Framebuffer Alpha Blending

Implement Read-Modify-Write (RMW) logic in `BorgTileBuffer`. Fragments can be
linearly blended with existing background pixels based on Alpha. Enables
smoke, glass, and transparency effects.

### Step 37: Integer ALU & Bitwise Ops

Add `IADD`, `ISUB`, `AND`, `OR`, `XOR`, `SLL`, `SRL` to the `BorgCore` pipeline.
Necessary for Vulkan integer address math and bit-packed data structures.

### Step 38: Multi-Lane SIMD (2–4 FMA Units)

Duplicate the FMA pipeline within `BorgCore` (or add multiple cores) to process
multiple pixels/vertices concurrently. Essential for hitting 60 FPS at
higher resolutions on ULX3S/Nitefury.

### Step 39: Second Tapeout Submission

4×4 or 4×5 tile, Linux + Vulkan capable, with full hardware fidelity suite.
Estimate: 1 week.

## Phase 7: Vulkan 1.0 Conformance

Target: **~Mar–Apr 2027**. Full CTS pass (~3–4 weeks); Mesa handles most
complexity. Khronos conformance submission (~2 weeks): documentation + test
results.

## Tile Budget Estimate

| Configuration | Tiles | Cost | Use Case |
| ------------- | ----- | ---- | -------- |
| Phase 1 (RV32I + Borg FP16 ALU) | 4×2 (8) | 515€ | Current tapeout |
| Phase 2 only (RV32I + autonomous GPU) | 4×3 (12) | 715€ | GPU autonomy, no Linux |
| Phase 2 + 4 (RV32IMA + autonomous GPU) | 4×5 (20) | 1115€ | Linux + GPU, target |
| Comfortable (room for Phase 6+) | 4×6 (24) | 1315€ | Full Vulkan + extensions |
| **Full Vulkan (FP32 + multicore)** | **4×8 (32)** | **1715€** | **Vulkan conformance target** |

## Hardware Resources

- **QSPI PSRAM**: 64 Mbit (8 MB) — sufficient for Linux + Mesa runtime
- **QSPI Flash**: 128 Mbit (16 MB) — kernel + rootfs + Mesa libraries
- **Display**: pico-ice → RP2040 reads framebuffer from PSRAM; ULX3S → direct HDMI/GPDI scanout from SDRAM (no host in the render loop)

## Build Configurations

The design will use build-time Chisel parameters so that a **$40
pico-ice** can still build and play with a working GPU, even after the full
roadmap is implemented. Features will be opt-in — the iCE40 "lite" config
will strip everything that doesn't fit, while larger FPGAs enable the full stack.

### Configuration Profiles (Planned)

A future `BorgConfig` case class will parameterize feature inclusion at
build time. The envisioned API:

| | 🔰 Lite (pico-ice) | 🔧 Developer (ULX3S) | 🚀 Full Vulkan (TT/Nitefury) |
| --- | --- | --- | --- |
| **FPGA** | iCE40 UP5K | ECP5-85K | XC7A200T / ASIC |
| **Cost** | ~$40 | ~$60 | ~$200 / 1715€ |
| GPU core | ✅ FP16 | ✅ FP16 | ✅ FP32 |
| Rasterizer | ✅ | ✅ | ✅ |
| Fragment shader | ✅ | ✅ | ✅ |
| Tile buffer | ✅ | ✅ | ✅ |
| Texture fetch | ❌ | ✅ | ✅ |
| DMA engine | ❌ | ✅ | ✅ |
| Auto sequencer | ❌ | ✅ | ✅ |
| GPU PSRAM write | ❌ | ✅ | ✅ |
| Blending | ❌ | ❌ | ✅ |
| CPU ISA | RV32I | RV32IMA | RV32IMA |
| C extension | ❌ | ✅ | ✅ |
| MMU | ❌ | ✅ | ✅ |
| Linux | ❌ | ✅ | ✅ |
| Vulkan conformant | ❌ | ❌ | ✅ |
| **Est. LCs** | **~4,800** | **~8,000** | **~36,000** |
| **Fits?** | ✅ (91%) | ✅ (9%) | ✅ |

### Lite Profile — What You Can Do

With just a pico-ice ($40) and the lite config, a user gets:

- **Hardware-accelerated triangle rendering** — the CPU submits per-tile
- **Vertex-colored 3D cube** — the `vkcube` demo works with CPU-driven
- **FP16 shader programming** — write fragment shaders in SPIR-B assembly,
- **Full source code** — Chisel RTL, C firmware, Python tools, all open-source

### How to Select a Configuration

```bash
# Lite config (pico-ice, iCE40)
make fpga CONFIG=lite

# Developer config (ULX3S, ECP5)
make fpga-ulx3s CONFIG=developer

# Full config (Nitefury, simulation, or TT ASIC)
make asic CONFIG=full
```

## Design Principles

1. **One thing at a time.** Each step produces bit-exact golden output.
2. **Area-first, configurable.** Reuse the FMA; don't duplicate ALUs. iCE40
   is the minimum target — features that don't fit are build-time optional.
3. **Firmware fallback.** Hardware fast path for common case; CPU for edge cases.
4. **Free software only.** All tools are open-source: Yosys, nextpnr, OpenLane,
   Chisel, Mill. No vendor-locked toolchains.
5. **Accessible.** Anyone with a $40 pico-ice can build and run the GPU.
