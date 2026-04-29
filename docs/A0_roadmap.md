# Borg GPU — Development Roadmap

## Phase 1: CPU + FP16 Shader Co-Processor (Complete)

Target: March 2026 — TTIHP26a shuttle

- [x] Borg FP16 shader processor (ADD, MUL, FMA, FNEG, FSTEP)
- [x] TinyQV RV32I CPU (Chisel rewrite)
- [x] Triangle rendering pipeline (vertex/rasterize/fragment)
- [x] SPIR-B runtime shader loading
- [x] Per-vertex color interpolation
- [x] Dynamic framebuffer resolution
- [x] Z-buffer (firmware, per-pixel depth testing)
- [x] Texture mapping (firmware, UV interpolation + PSRAM sampling)
- [x] FPGA validation on pico-ice
- [x] GDS submission (4×2 tiles, IHP SG13G2)
- [x] 32-bit RISC-V instructions & 32-entry register file

## Phase 2: GPU Autonomy (Steps 21–29)

Target: **~June 2026** — move the rendering inner loop from firmware into
hardware, step by step. Each step produces a measurable speed-up, can be
tested against the existing `triangle.py` golden image, and fits on iCE40.

### Current Architecture

TinyQV drives every pixel: ~7–9 `borg_run()` MMIO round-trips per pixel
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
our shared QSPI PSRAM, central bus arbiter = our 2:1 mux. Also matches
PowerVR SGX's Data Masters + tile-based deferred rendering pattern.

- ✅ **Step 19.1: Extract MemCtrl to SoC level** *(2026-04-13)*

- ✅ **Step 19.2: Wire GPU port to BorgRasterizer** *(2026-04-14)*

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

- ✅ **Step 21.0.1: Parallel Test Runner** *(2026-04-15)*

- ✅ **Step 21.1: sTexFetch FSM Integration** *(completed during Step 19.2)*

- ✅ **Step 21.2: Tex Config MMIO + Firmware Integration** *(2026-04-15)* (+10 LCs)

### Dev Infrastructure ✅ (2026-04-18)

Continuous housekeeping work done alongside Steps 21–22. Not a numbered GPU
feature step, but recorded here for traceability.

- **BorgConfig centralized parameterization**: New `BorgConfig` case class
- **`.verilog_stamp` incremental build**: Root `Makefile` skips `generate_verilog`
- **Hardware architecture diagram generator** (`scripts/gen_hw_diagram.py`):
- **nextpnr `--seed 0`**: Pinned the placement RNG seed in `fpga/Makefile` for
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
    - Updated `fpga/host/render.py` and `scripts/postprocess.py` to decode TBDR tiled layout (2 words/pixel, 4×4 tile addressing, `lo={B,Z}` / `hi={R,G}`).
    - Fixed arcilator `marker_offset_word` to use tiled layout (2 words/pixel vs old 4).
    - All 12/12 test suites pass including `render › fpga (hw)`. ✓

### Step 26: DMA Firmware Integration + LUT Recovery

Complete the firmware side of Step 22 and reclaim LC headroom to unblock the
hardware tile flusher. The DMA hardware (`BorgDMA.scala`) is already built
(Step 22.1); only firmware and FPGA config changes remain.

- ✅ **Step 26.1: Remove `entry_lo`/`entry_hi` latch registers** (~64 LCs saved, 2026-04-29)\
  `BorgTileFlusher`: `BorgTileBuffer.readDataHeld` already holds SRAM output stable; removed
  7→6-state FSM and 64 FFs. All 195/195 tests pass.

- ✅ **Step 26.2: Replace `tileBase_reg + (word_idx << 2)` adder with running `addrReg`** (~18 LCs saved, 2026-04-29)\
  `BorgTileFlusher`: removed `tileBase_reg` (20 FFs) and combinational adder; `addrReg`
  initializes to `io.tileBase` and increments +4 per write. All 195/195 tests pass.

- **Step 26.3: Don't latch full `descReg`** (~30 LCs)\
  `BorgDMA`: `length`, `dest`, and `offset` fields are stable for the entire transfer.
  Drive them as wires from `io.desc` directly; only `addrReg` needs a register.

- **Step 26.4: Firmware DMA wrapper** — implement `dma_load_shader()` and
  `dma_load_uniforms()` in `borg_fpu.c` using the `DMA_PSRAM` / `DMA_CONFIG`
  MMIO registers. Poll `STATUS.dma_busy` for completion.

- **Step 26.5: Enable DMA on FPGA** — set `hasDMA=true` in `BorgConfig.FPGA`.
  Replace `borg_load_spirb_shader_at()` MMIO word-by-word writes with
  `dma_load_shader()`. Verify pixel-perfect rendering.

- **Step 26.6: Remove IMEM MMIO write path** (`hasImemMmio=false`) (~15 LCs) — DMA replaces it

- **Step 26.7: Remove MMIO uniform write path** (~15 LCs) — DMA replaces it

- **Step 26.8: Simplify RDL address decode** (~10 LCs)

- **Step 26.9: Remove MMIO GPR read path** (optional, ~20–30 LCs)

### Step 27: Enable HW Flusher on FPGA + Remove CPU Flush Path

Prerequisite: Step 26 (LUT recovery frees ~95–120 LCs, making room for
`BorgTileFlusher`'s net cost: ~218 LCs after 26.1, further reduced by 26.2).

- Hardware: set `hasFlusher=true` in `BorgConfig.FPGA`.
- Hardware: remove `tile_bz`/`tile_rg` MMIO readback arms from `wireMmioRead()` (~30–45 LCs saved).
- Hardware: remove `ctrlWriting` read trigger arm from `wireTileBuffer()` (~5–10 LCs saved).
- Firmware: delete the CPU tile-flush `else` branch in `borgBinRender()` (lines 536–547).
- Verify pixel-perfect rendering on FPGA with hardware flusher active.

### Step 28: Fully Autonomous Hardware Iteration

With the hardware flusher running on FPGA, the CPU no longer touches the
tile buffer or PSRAM write path during rendering. Full autonomy milestone.

### Step 29: Integrated Vertex + Triangle Setup Sequencer

Unified FSM that replaces what the CPU currently does in `shade_tiles()`,
`run_vertex_shader()`, `triangle_setup()`, and `compute_edge_vectors()`.
Combines the DMA engine (Step 26) and vertex sequencer into a single FSM
to share registers, address counters, and control logic.

- **Step 29.1: Vertex shader sequencing**

- **Step 29.2: Triangle setup shader**

- **Step 29.3: Automatic uniform reload**

### Step 30: Full Autonomous Triangle Pipeline

Integration of Steps 21–29. CPU submits a triangle descriptor; GPU does:

### Step 31: Multi-Triangle Autonomous Rendering

Extend Step 30 to process a list of triangle descriptors from PSRAM without
CPU involvement. The GPU reads the next descriptor, runs the full pipeline,
and signals DONE after the last triangle. The CPU submits a draw call
(base pointer + count) and waits.

### Step 32: Real-Time VGA Output (TT VGA PMOD)

Drive the Tiny Tapeout VGA PMOD directly from the pico-ice FPGA for
real-time display — the hardware equivalent of `make vkcube_gui`.
No host PC needed; the GPU renders to a monitor in real time.

| `uo_out` pin | VGA Function |
| --- | --- |
| `uo_out[0]` | R1 |
| `uo_out[1]` | G1 |
| `uo_out[2]` | B1 |
| `uo_out[3]` | VSync |
| `uo_out[4]` | R0 |
| `uo_out[5]` | G0 |
| `uo_out[6]` | B0 |
| `uo_out[7]` | HSync |

| Resolution | SPRAM usage | VGA upscale | Pixels/frame |
| --- | --- | --- | --- |
| 32×32 | 2 KB (6%) | 20×15 pixel blocks | 1,024 |
| 64×64 | 8 KB (25%) | 10×7 pixel blocks | 4,096 |
| 128×96 | 24 KB (75%) | 5×5 pixel blocks | 12,288 |

- **Step 32.1: VGA timing generator** (+30 LCs)

- **Step 32.2: SPRAM framebuffer** (+20 LCs)

- **Step 32.3: FP16→RGB222 scanout** (+15 LCs)

- **Step 32.4: `make fpga_vga` target** (+0 LCs)

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
| 27 (HW flusher enable) | hasFlusher=true − readback | +240 | 5408 | ⚠ |
| 29 (vert seq + tri setup) | Unified FSM | +45 | 5257 | ✅ |
| 30 (pipeline integration) | Wiring + control | +15 | 5272 | ✅ |
| 31 (multi-triangle) | Descriptor reader | +10 | **5282** | ⚠ |
| **Margin** | | | **−2 LCs** | |

- O4: Direct tile buffer write (−40 LCs, medium risk)
- O2: Remove `tex_uv` registers after Step 25 (−20 LCs)

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
| Phase 2 (Steps 21–26) | **pico-ice** (iCE40 UP5K) | TT-compatible pinout, forces area discipline |
| Phase 3 (Steps 27–30) | **pico-ice** (GPU=off) or **ULX3S** (ECP5-85K) | CPU-only fits at 75%; ECP5 for full design |
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
| Phase 2 (Steps 21–29), area-constrained dev | pico-ice |
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
- `tinyQV_top` (PicoIce.scala:17) — shows how to wrap `SoCLogic` for a

## Phase 3: Linux-Capable CPU (Steps 30–34)

Target: **~Sept 2026** — expand TinyQV to RV32IMA. Sequential after Phase 2.

*Velocity note: Steps 1–20 completed in 27 days (Mar 19 – Apr 14). Phase 2
adds ~7 steps of medium-hard complexity (FPGA at 99%). Phase 3's bottleneck
is the Sv32 MMU (3–4 weeks alone). Dates assume current solo-dev pace.*

### Step 30: M Extension (Integer Multiply/Divide)

Add dedicated integer multiplier for MUL/MULH/DIV/REM.
Estimate: 1 week.

### Step 31: A Extension (Atomics)

LR.W / SC.W for Linux `futex` and spinlocks. Reservation register (32-bit
address + valid bit). ~100 LUTs. Reference KianV implementation.
Estimate: 3–5 days.

### Step 32: Boot no-MMU Linux

Intermediate milestone before full MMU. Estimate: 1 week.

### Step 33: MMU (Sv32)

Two-level page table walker, 4–8 entry TLB, `satp`/`mstatus` CSRs.
Intermediate milestone: boot no-MMU Linux first (~1 week).
~800–1200 LUTs — the most expensive single addition.
Estimate: 3–4 weeks.

### Step 34: Boot Full Linux

Kernel, device tree, rootfs on QSPI PSRAM (8 MB). Estimate: 1–2 weeks.

## Phase 4: Mesa Vulkan Driver (Steps 35–39)

Target: **~Nov–Dec 2026** (~8–10 weeks). Write a Mesa Vulkan ICD for the
Borg GPU. This is a domain shift — Mesa/NIR/SPIR-V are a new codebase.
Expect 2–3 weeks ramp-up on top of implementation time.

### Step 35: Minimal `vk_device` + `wsi_headless`

Headless rendering, no window system needed. Estimate: 1–2 weeks.

### Step 36: Shader Compiler (NIR → SPIR-B)

NIR backend generating Borg instructions. Estimate: 2–3 weeks.

### Step 37: Draw Path (`vkCmdDraw`)

Vertex + fragment shader dispatch to hardware. Estimate: 1–2 weeks.

### Step 38: Texture Sampling (Software)

CPU-side sampling, spec-compliant but slow. Estimate: 1 week.

### Step 39: Vulkan CTS Subset

Run conformance tests, fix failures. Estimate: 1–2 weeks.

## Phase 5: GPU Hardware Extensions (Steps 40–44)

Target: **~Jan–Feb 2027** (~6–8 weeks). Extend the shader processor to
support more Vulkan features. These items only make sense on a larger FPGA
(ULX3S or Nitefury) or ASIC (Tiny Tapeout).

### Step 40: Integer ALU Ops in Shader

Comparison, bitwise, integer math. Estimate: 1 week.

### Step 41: Memory Load/Store from Shader

Enables shader-side texture addressing. Estimate: 1–2 weeks.

### Step 42: Framebuffer Blending

Alpha blending support. Estimate: 3–5 days.

### Step 43: Multi-Lane SIMD (2–4 FMA)

Process multiple pixels per cycle. Estimate: 1–2 weeks.

### Step 43.5: Multi-Core Shading Simulation (Optional)

Refactor `BorgRaster` with parameterizable execution width to dispatch multiple pixels concurrently across a parallel array of `BorgCore` FPUs exclusively for simulation speedup. (Moved from Phase 2; deferred until CPU bottlenecks are resolved).

### Step 44: Second Tapeout Submission

4×4 or 4×5 tile, Linux + Vulkan capable. Estimate: 1 week.

## Phase 6: Vulkan 1.0 Conformance

Target: **~Mar–Apr 2027**. Full CTS pass (~3–4 weeks); Mesa handles most
complexity. Khronos conformance submission (~2 weeks): documentation + test
results.

## Tile Budget Estimate

| Configuration | Tiles | Cost | Use Case |
| ------------- | ----- | ---- | -------- |
| Phase 1 (RV32I + Borg FP16 ALU) | 4×2 (8) | 515€ | Current tapeout |
| Phase 2 only (RV32I + autonomous GPU) | 4×3 (12) | 715€ | GPU autonomy, no Linux |
| Phase 2 + 3 (RV32IMA + autonomous GPU) | 4×5 (20) | 1115€ | Linux + GPU, target |
| Comfortable (room for Phase 5) | 4×6 (24) | 1315€ | Full Vulkan + extensions |
| **Full Vulkan (FP32 + multicore)** | **4×8 (32)** | **1715€** | **Vulkan conformance target** |

## Hardware Resources

- **QSPI PSRAM**: 64 Mbit (8 MB) — sufficient for Linux + Mesa runtime
- **QSPI Flash**: 128 Mbit (16 MB) — kernel + rootfs + Mesa libraries
- **Display**: RP2040 reads framebuffer from PSRAM, no KMS/DRM needed

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
