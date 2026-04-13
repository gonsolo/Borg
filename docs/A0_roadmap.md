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

## Phase 2: GPU Autonomy

Move the rendering inner loop from firmware into hardware, step by step.
Each step produces a measurable speed-up, can be tested against the existing
`triangle.py` golden image, and fits on iCE40.

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
| 20–24 | Submit + wait only | Full owner | None |

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

Verified: Chisel tests (195/195 + batched edge test), cocotb SoC tests (2/2),
and FPGA triangle rendering on pico-ice.

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
  Added `start_pc` jump control via `BORG_CONTROL` to keep `rast`, `frag`, and `add` shaders concurrently active in the 32-entry IMEM.
- **Step 10.2: Bounding Box Early-Out** ✅ (2026-03-27)
  Structured the rasterization pipeline: `xy16_t`/`xy16x3_t`/`rgb16x3_t`/`uv16x3_t` types, `triangle_t` and `frag_result_t` structs, `compute_bbox` with correct negative-coord clamp, and helper extraction (`shade_pixel`→`shade_tile`→`shade_tiles`, `build_clip_vertices`, `clip_and_rasterize`). Fixed left-edge clipping bug caused by `fp16_to_uint` ignoring the sign bit.
- **Step 10.3: Hardware Counter Iterator** ✅ (2026-03-27)
  Added 6-bit x/y hardware counters, 4-coordinate bounding box registers, and single-instruction iteration advancement via `BORG_ITER` MMIO interface. Eliminated the nested software-based loop inside the `shade_tiles` firmware loop, moving spatial boundary checks strictly into hardware.
- **Step 10.4: Hardware Edge Bounding Box Evaluation**
  - **10.4.1: Edge Sign Evaluation & Inside Flag** ✅ (2026-03-30): Snoop FPU writes to `r0/1/2` to latch edge function signs and expose a unified `inside_flag` via the `BORG_ITER` MMR.
    > **Hardware-in-the-Loop Debugging Note:** During the transition to the native FPGA iterator, the GPU produced a solid black screen (despite passing RTL). The core bugs we isolated were entirely mathematical edge cases:
    > 1. **Winding Order Inversion:** In our Y-down screen space, calculating edge vectors as `pos[next] - pos[i]` was generating *negative* edge bounds for strictly interior pixels. This incorrectly culled the entire triangle because hardware `fstep.s` expects strictly positive values for `inside_flag`. We reversed the subtraction to `pos[i] - pos[next]`.
    > 2. **Barycentric Interpolation Collapse:** The `dy` component of the edge vector had a deeply buried sign error. Because of this, the edge distances no longer summed up to the triangle's explicit +area, causing barycentric multiplication by `inv_area` to explode pixel colors into blackness. Fixing `edges[i].y` to exactly `pos[next].y - pos[i].y` restored mathematical harmony.
    > 3. To prevent this from ever quietly breaking again, a new `test_raster.c` native invariant tracker was written that mathematically mocks the CCW boundary rules to guarantee +area bounds are strictly maintained by the C firmware mathematically.
  - **10.4.2: Rasterizer Auto-Execution** ✅ (2026-03-31): Auto-trigger the shader at `PC=0` on iterator advance, stalling the CPU until completion.
- **Step 10.5: Hardware Coordinate Expansion (int-to-fp16)** ✅ (2026-04-01)
  - **10.5.1: Hardware `coordLut` and MMIO Verification** ✅ (2026-03-31): Convert 6-bit int iterator coords into FP16 pixel centers mapped to `r30` and `r31`. Verify via MMIO reads against software computations without altering the running edge shader.
  - **10.5.2: FPU Coordinate Expansion Pipeline** ✅ (2026-04-01): Pass negative vertex coordinates (`-v.x`, `-v.y`) as uniforms into `rasterize.s`. Rewrite the shader to compute `dpx = px - vx` natively using `fadd.s` with `r30/r31`, keeping pixel accuracy.
  - **10.5.3: Software Delta Decommissioning** ✅ (2026-04-01): Remove legacy firmware `compute_pixel_deltas`. Validate `make triangle` produces the pixel-perfect rendering using strictly hardware coordinate expansion.
- **Step 10.6: CPU-Drawn Pixel Dispatch**
    Auto-chain the rasterizer and fragment shaders so a single `BORG_ITER` advance evaluates edges, tests inside, and (for inside pixels) runs the fragment shader — all while the CPU stalls. The CPU only reads back shaded results and writes to PSRAM.
  - **10.6.1: Fragment Shader Register Alignment** ✅ (2026-04-01): Recompile `frag.s` so it reads edge values directly from r0/r1/r2 (rasterizer output slots) instead of separate attribute registers. Remove the firmware register copy. No hardware changes.
  - **10.6.2: Chained Shader Trigger (Hardware)** ✅ (2026-04-01): Add `frag_start_pc` register and phase FSM (`IDLE→RAST→FRAG`) to BorgRasterizer/BorgCore. Fix edge-sign snooping convention (positive=inside, negative=outside). Add attribute copy from rasterizer output regs to fragment attribute regs.
  - **10.6.3: Linear Scan Register Allocation** ✅ (2026-04-01)
    Implemented Poletto & Sarkar (1999) linear scan register allocation in `borg_backend.py`. Added a pass manager (`identify_vregs` → `parse_annotations` → `compute_live_intervals` → `linear_scan_alloc` → `emit_instructions`). Reused temporaries across non-overlapping lifetimes:
    - `rasterize.s`: 18 → 17 registers (dpx/dpy reused across edges).
    - `vert.s`: 29 → 24 registers.
    - `shader.frag`: 29/30 registers (28 I/O vregs must be live simultaneously; uniforms persist).
    - Verified pixel-perfect against `golden.ppm`.

    *Bugs fixed during register allocator development:*
    1. **`rs3` field width misconception:** The initial implementation restricted `fmadd` accumulators to r0-r3, assuming a 2-bit `rs3` field. `Instructions.scala` defines a full 5-bit field. Removed the unnecessary restriction.
    2. **Uniform sorting broke R↔B vertex colors:** The SPIR-V compiler sorted uniforms by `member_idx`, reordering them. This broke the implicit mapping where edge weights match their opposite vertex colors. Reverted to SPIR-V instruction order.
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
    Replaced brittle, manually hardcoded C-packing macros (`BORG_ITER_PACK_BBOX`) with a dynamic Chisel-to-C struct generator inside `MmioGenerator.scala`. By recursively walking `Bundle.elements`, the exact hardware logical layouts of structures like `Coord` and `Bbox` are dynamically reflected into the C firmware headers as `borg_bbox_t` structs. This decouples the hardware layout from arbitrary software shifts and permanently solves C-bitfield layout mismatch regressions.

  **Uniform data path progression:**
  - *Step 10 (now)*: CPU → MMIO writes → on-chip buffer (32 entries, scaffolding)
  - *Step 21 (GPU DMA)*: GPU fetches uniforms, IMEM, and registers from PSRAM autonomously

### Step 11: On-Chip Tile Buffer (BRAM)

4×4 pixel tile buffer in Block RAM (RGB + Z). Rasterizer writes on-chip; a
burst flush writes the completed tile to PSRAM. Eliminates per-pixel PSRAM
round-trips. Tile-based approach matches mobile GPU architecture (Mali,
PowerVR, Adreno). Estimate: 1–2 weeks.

- **Step 11.1: Standalone `BorgTileBuffer` Module** ✅ (2026-04-06)
    Standalone Chisel module: 16×64-bit unified RGBZ BRAM (single iCE40 EBR).
    Write/read/clear ports. Auto-clear on reset (16-cycle BRAM sequential write).
    Unit tested without FSM wiring.
- **Step 11.2: MMIO Wiring** ✅ (2026-04-06)
    Wired tile buffer into `Borg.scala` with two-step MMIO write protocol
    (BZ shadow registers → RG trigger). Added CTRL register for read index
    and clear. 32-bit packed readback (RG and BZ). Holding registers for BRAM
    read persistence. Shared BRAM read port for peekZ (2-cycle latency).
    Improved `print_resources` Makefile target with carry chain and LC estimation.
    FPGA: 3859 LUTs (73%), 1549 DFFs (29%), 10 BRAMs — comfortably within budget.
    Verified: 31/31 Chisel tests, Verilator triangle+vkcube, FPGA triangle+vkcube.
- **Step 11.2.5: Hardware Types & Decoupled Bus Reflection** ✅ (2026-04-06)
    General structural clean up across the codebase prior to Auto-Write integration:
  - **`ColorZ` Bundle**: Replaced 8 discrete RGBZ ports inside `BorgTileBuffer` with a cleanly casted 64-bit `.asUInt()` unified structure.
  - **Instruction Bundling**: Stripped primitive tuple decodes inside `BorgCore` in favor of `FpuOpFlags` and `RegIndices` bundles cleanly routing down into the pipelined FSM execution blocks.
  - **`BorgBusIO` Layer**: Substituted ad-hoc MMIO wires (`address`, `data_in`, `is_writing`, `is_reading`) with a unified internal `BorgBusIO`, cleanly bridging dependencies into cleanly typed abstractions over standard sub-modules.
- **Step 11.3: Auto-Write from Fragment Shader** ✅ (2026-04-06)
    After FRAG completes for inside pixel, auto-write RGB+Z from fragment output
    registers to tile buffer. CPU no longer reads per-pixel results. New FSM phase
    `sTileWrite`. Hardware ABIs pinned for R/G/B/Z output. Chisel test + Verilator.

- **Step 11.4: Firmware Tile-Loop Restructuring** ✅ (2026-04-06)
    Restructure `shade_tiles()` to loop in 4x4 tile chunks. After each tile,
    compute 4x4 bbox for hardware. CPU spins until `BORG_ITER_VALID` goes low,
    then CPU reads from `BorgTileBuffer` and blasts 16 pixels to PSRAM in a burst.
    Implemented in C firmware. Verified functional in Arcilator and Verilator.

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
    Implement a decoupled Chisel `Queue` wrapping a new `BorgCommand` bundle (Bbox, uniform_page, frag_pc).
    Create Chisel unit tests to verify enqueue/dequeue handshaking, ensuring it holds commands correctly.
- **Step 13.2: Dual-Page Uniform Buffer** ✅ (2026-04-08)
    Expand the hardware uniform buffer from 32 to 64 entries (2 pages of 32 entries).
    The CPU writes to the "front" page while the GPU reads from the "back" page (specified by `uniform_page` from the FIFO).
    Add MMIO addressing for the second page and verify through Chisel tests.
- **Step 13.3: Hardware FIFO Integration** ✅ (2026-04-08)
    Wire `BorgCommandFIFO` into `Borg.scala`. Map the push interface to a new MMIO write endpoint
    (`BORG_COMMAND_ENQUEUE`). Connect the pop interface to `BorgRasterizer`. Expose a `FIFO_FULL`
    bit in `BORG_STATUS` so the firmware can poll. Ensure the rasterizer uses the popped `uniform_page`.
    FPGA utilization: 5192 / 5280 LCs (98%) after reducing FIFO depth from 4 to 2.
    Verified: Chisel tests, Verilator+Arcilator triangle/vkcube, FPGA triangle/vkcube.
- **Step 13.4: Firmware Integration & Synchronization** ✅ (2026-04-08)
    Updated `borg_driver.c` to ping-pong between uniform pages. `shade_tiles()` toggles
    `current_uniform_page`, sets `uniformWritePage` via `BORG_CONTROL[5]`, loads uniforms to
    the inactive page, polls `BORG_STS_FIFO_FULL`, then enqueues with `uniformPage` in bit 30.
    Verified pixel-perfect against `golden.ppm` in Verilator (11.3M cycles).

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
  describing all Borg GPU registers: GPR file (32×16-bit), IMEM (56×32-bit),
  pixel iterator (bbox + position/valid/inside), control/status, fragment PC,
  uniform buffer (32-entry MMIO window), tile buffer (CTRL/RG/BZ), and command
  FIFO enqueue. Validated with `systemrdl-compiler` — 512-byte address map,
  all offsets match `MmioMap.scala`.
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
  to dual `SyncReadMem(17, UInt(10.W))` instances. Used unique 17×10 dimensions
  to prevent CIRCT module deduplication with `coordLut`. Added `rcp_lut.hex` and
  a `coordWriteIsRcp` test-time init port. New `BorgCoreTests.frcp_fp16` test
  verifies 8 reciprocal cases. FPGA: 5268/5280 LCs, 10/30 BRAMs.

- **Step 16.2: Peripheral Bus Widening (11→12 bit)** ✅ (2026-04-11): Widened
  peripheral `addr_in` from 11 to 12 bits, giving each peripheral 1024 bytes of
  MMIO space instead of 512. Updated `SoCDecode.userRegion` matchFn, `PeriphDecode`
  sel/sub-addr positions, `BorgIO`/`BorgBus` address widths, and `soc.rdl`/
  `borg_sys.h` base addresses (BORG now at `0x08000C00`). Zero net LC overhead.

- **Step 16.3: FP16→uint6 + Morton Encoding Hardware** ✅ (2026-04-11): Added
  `tex_uv_reg_t` (@ 0x200) and `tex_addr_reg_t` (@ 0x204) to `borg.rdl` and
  regenerated `BorgGpuRegs.scala`. New `TextureAddr.scala` implements `Fp16ToUint6`
  (combinational floor+clamp, ~15 LUTs) and `MortonEncode` (pure bit interleaving,
  0 LUTs). CPU writes packed FP16 {V, U} to `TEX_UV`; hardware computes the 12-bit
  Morton texel index and exposes it at `TEX_ADDR`. `BorgTests.tex_fetch_tests`
  verifies 10 cases including clamping and negatives. Zero net LC overhead.

> **Note:** Steps 16.4–16.5 (texel fetch FSM and firmware integration) require
> the GPU to act as a PSRAM bus master, which depends on the Shared Memory
> Controller (Step 19). They continue as Step 20.

### Step 17: LUT Recovery ✅ (2026-04-13)

Before adding any new infrastructure, recover LC headroom from three
low-risk structural changes identified in [A7_lc_savings.md](A7_lc_savings.md).
Target: free ~165–250 LUTs, bringing running total from 5420 to ~5170–5255.

- **Step 17.1: S4 — Remove RDL shadow registers** ✅ (2026-04-12) (~15–20 LUTs)
    PeakRDL generates shadow flip-flops for fields that are hardware-writable
    but also MMIO-readable. Fields like `iter_x`, `iter_y`, `iter_valid` are
    hardware-read-only — redundant FFs. Add `hwReadOnly` flags to `borg.rdl`
    and regenerate `BorgGpuRegs.scala`.

- **Step 17.2: A4 — Nibble-serial barrel shifter** ❌ abandoned (2026-04-12) (actual: −3 LCs)
    Replace `TinyQVShifter` barrel shifter (149 cells) with a 4-bit-per-cycle
    iterative version, matching the nibble-serial pattern already used in
    `TinyQVCounter` and `TinyQVTime`. Cost: 8 extra cycles per shift
    instruction. Shifts are rare in GPU shader firmware.

- **Step 17.3: Remove C Extension** ✅ (2026-04-12)
    Removed the RISC-V C (compressed) extension entirely from TinyQV.
    Deleted the 14-case compressed decoder (~210 lines in `Decode.scala`),
    all compressed immediate computations, the `is_ret` early-return
    optimization, and the `.option rvc` directive from `start.s`. Switched
    firmware from `rv32ec_zicsr` to `rv32e_zicsr` in both Makefiles.
    Firmware code size increases ~30-40% but runs from 16 MB QSPI flash
    (not area-constrained). Hardwired `instr_len` to 2 (32-bit only).
    The C extension can be re-added in Phase 3 on a larger tile.
    **Results:** Yosys 3881 LUT4s (was ~4100), nextpnr 5184/5280 LCs (98%).
    Verified: Chisel tests, Verilator, Arcilator, FPGA synthesis.

- **Step 17.4: Verify all targets** ✅ (2026-04-13)
    All Chisel tests pass (28 TinyQV + 24 Borg), Verilator triangle OK,
    Arcilator triangle OK, FPGA synthesis 5184 LCs (98%), 10/30 BRAMs.

### Step 18: SoC Project Restructure ✅ (2026-04-13)

Reorganize the Mill build so `soc` is the parent module of both `borg` (GPU)
and `tinyqv` (CPU). This must happen before Step 19 adds new SoC-level files.

- **Step 18.1: Create `hardware/soc/` Mill module** ✅ (2026-04-13)
    Move `Project.scala`, `Peripherals.scala` from `borg` package → `soc` package.
    Move `TinyQVMemCtrl.scala`, `TinyQVMemCtrlSim.scala` from `tinyqv.cpu` → `soc`.
    Create `hardware/soc/package.mill` with
    `moduleDeps = Seq(build.hardware.tinyqv, build.hardware.borg)`.
    Update `hardware/borg/package.mill` and `hardware/tinyqv/package.mill` to have
    no deps (leaf modules).

- **Step 18.2: Verify all targets** ✅ (2026-04-13)
    All Chisel tests pass, Verilator/Arcilator triangle+vkcube, FPGA synthesis.

```text
Mill dependency graph:
  tinyqv (CPU, leaf)  ──┐
                        ├──→  soc (parent)  ──→  fpga
  borg   (GPU, leaf)  ──┘
```

### Step 19: Shared Memory Controller

Extract `TinyQVMemCtrl` to SoC level and add a GPU read port, making Borg an
autonomous bus master for PSRAM reads. This follows the universal GPU memory
architecture pattern: every GPU that shares memory with a CPU is a bus master
issuing its own read/write transactions through a shared interconnect with an
arbiter. The closest existing architecture is Broadcom VideoCore IV (RPi):
VideoCore's TMU = our `sTexFetch`, VPM = our `BorgTileBuffer`, shared SDRAM =
our shared QSPI PSRAM, central bus arbiter = our 2:1 mux. Also matches
PowerVR SGX's Data Masters + tile-based deferred rendering pattern.

- ✅ **Step 19.1: Extract MemCtrl to SoC level** *(2026-04-13)*
    Renamed `TinyQVMemCtrl` → `MemoryController` and moved to `soc` package.
    `TinyQV` is now a pure CPU (no QSPI knowledge); `MemoryController` owns all
    SPI/QSPI pins and arbitrates CPU instr-fetch, CPU data, and a stubbed GPU
    read port (`gpu_addr`, `gpu_read_req`, `gpu_data`, `gpu_read_ready`).
    `SoCLogic` wires all three peer components. All tests pass (Verilator + Arcilator).

- **Step 19.2: Wire GPU port to BorgRasterizer**
    Add `sTexFetch` FSM state to `BorgRasterizer`. Connect Morton index →
    `psramAddr`. New Chisel tests for texel fetch path.
    FPGA estimate: +8–12 LUTs, running total ~5100–5120.

### Step 20: Hardware PSRAM Texel Fetch (continues Step 16)

The texel fetch FSM and firmware integration, now that the Shared Memory
Controller (Step 19) provides the GPU read port.

- **Step 20.1: sTexFetch FSM Integration**
    Wire Morton index from `TextureAddr` → `psramAddr` on the GPU read port.
    `sTexFetch` state between `sFrag` and `sTileWrite`: assert `gpu_read_req`,
    wait for `gpu_data_ready`, latch RGB. Chisel tests + Verilator verification.

- **Step 20.2: Firmware Integration**
    Update `frag.s` to write UV outputs to `TEX_UV` register. Update
    `borg_triangle.c` to load test texture into PSRAM and enable texturing.
    Textured triangle rendering verified against golden output.

### Step 21: GPU DMA Engine

Generalize the GPU read port for bulk transfers. The DMA engine drives the
**same** `gpu_read` port built in Step 19 — `SoCMemCtrl` is unchanged, only
the driver changes. Estimate: 1 week.

- **Step 21.0: LUT Recovery** (prerequisite micro-steps)
  - **21.0a: Remove IMEM MMIO write path** (~15 LUTs saved) — DMA replaces it
  - **21.0b: Remove MMIO uniform write path** (~15 LUTs saved) — DMA replaces it
  - **21.0c: Simplify RDL address decode** (~10 LUTs saved)
  - **21.0d: S3 — Remove MMIO GPR read path** (optional, ~20–30 LUTs)
    The `regFileC` shared read port (`wirePortC()` `mmio_en` mux) is used only
    for CPU debugging of shader register state. With DMA in place this path
    is unused. Remove the `mmio_en` conditional from `wirePortC()` in
    `BorgCore.scala`. Requires refactoring all Chisel/cocotb test GPR reads
    to use pipeline write-back snooping.
  - Target: free ~40–70 LUTs to bring running total back under budget

- **Step 21.1: DMA controller FSM** (`BorgDMA.scala`)
    Accepts `(base_ptr, length, destination)` descriptor via MMIO. Issues
    sequential `gpu_read_req` for each word. Routes returned data to the correct
    on-chip buffer (uniform/IMEM/GPR). Multiplexes with `sTexFetch` requests.

- **Step 21.2: Bulk IMEM load from PSRAM** (replaces MMIO IMEM writes)
- **Step 21.3: Bulk uniform load from PSRAM**
- **Step 21.4: Firmware integration** (`dma_load_shader()`, `dma_load_uniforms()`)

  **IMEM strategy:** IMEM BRAM stays (1-cycle fetch is critical for pipeline
  throughput). DMA loads it from PSRAM, replacing the ~56 `borg_write_imem()`
  MMIO calls per shader change. Streaming fetch (eliminate BRAM entirely) is a
  future optimization — trades 1 BRAM for ~30 LUTs + 30× latency on real QSPI.

  **Memory evolution:** The `gpu_read` port created in Step 19 IS the DMA port.
  Step 19 drives it from `BorgRasterizer.sTexFetch`; Step 21 drives it from
  `BorgDMA`. Step 23 (bus protocol) wraps it as a standard `BorgBus` master —
  each step changes *who drives the port*, not the port itself.

### Step 22: Data Cache

Tiny texture cache to avoid redundant PSRAM reads for adjacent pixels.
On QSPI PSRAM, each texel fetch costs ~120 cycles (4 bytes × 30 cy/byte).
Adjacent pixels in a textured triangle almost always hit the same or
neighboring texels. A tiny cache eliminates redundant PSRAM reads.

- **Step 22.1: 4-line direct-mapped texture cache**
    4 × (12-bit Morton tag + 24-bit RGB data) = 144 bits = ~18 FFs +
    comparators. ~30 LUTs, 0 BRAMs. `sTexFetch` FSM checks cache first; on
    hit, skips PSRAM entirely. Est. hit rate: ~60%.

- **Step 22.2: Cache hit/miss perf counter** via status register

- **Step 22.3: Evaluate 8-line variant** (~50 LUTs, ~75% hit rate) — if LUT
    budget allows

### Step 23: SoC Bus Protocol

Replace the hand-rolled `elsewhen` priority chain with a named bus protocol.

- **Step 23.1: BorgBus protocol definition**
    `BorgBusIO` bundle: `addr(25.W)`, `data_w(32.W)`, `op(2.W)`, `valid(Bool)`
    → `data_r(32.W)`, `ready(Bool)`. Inspired by TileLink-UL semantics but
    without the Diplomacy overhead.

- **Step 23.2: BorgBus arbiter** (replaces hand-rolled Mux chain in SoCMemCtrl)
- **Step 23.3: Borg → BorgBus master adapter**
- **Step 23.4: TileLink compatibility layer** (optional, for future Chipyard
    integration — ~50 line `BorgBusToTileLink` adapter)

  **Why not TileLink directly?** TileLink requires the RocketChip Diplomacy
  framework (~30K LOC). The `LazyModule` conversion would blow the iCE40 LC
  budget. BorgBus is semantically 1:1 with TL-UL Get/Put, so a future
  migration is a thin adapter.

### Step 24: Vertex Shader Auto-Sequencer

FSM that sequences 3 vertex shader runs (loading attributes, running SPIR-B
shader, storing outputs, applying screen-space transform) without CPU
involvement. Uses the DMA engine (Step 21) to load vertex attributes from
PSRAM. The sequencer reloads the uniform buffer between stages: the vertex
shader uses 16 uniform slots (4×4 MVP matrix), while the rasterizer and
fragment shaders use 31 slots (edge constants + vertex colors + inv_area).
Estimate: 1 week.

### Step 25: Full Autonomous Triangle Pipeline

Integration of Steps 1–24. CPU submits a triangle descriptor; GPU does
vertex shade → triangle setup → rasterize → fragment shade → Z-test →
tile buffer → PSRAM flush. CPU only writes triangle data and waits for DONE.
Estimate: 1–2 weeks.

### Step Dependencies

```text
Step 1 (edge HW) → Step 9 (frag HW) → Step 10 (pixel iterator)
                                               ├→ Step 11 (tile buffer) → Step 12 (Z-test)
                                               ├→ Step 13 (command FIFO)
                                               ├→ Step 14 (SystemRDL)
                                               ├→ Step 15 (interactive viewer)
                                               └→ Step 16 (texture addr HW)
                                                       │
                                                  Step 17 (LUT recovery)
                                                       │
                                                  Step 18 (SoC restructure)
                                                       │
                                                  Step 19 (shared MemCtrl) → Step 21 (DMA)
                                                       │                          │
                                                  Step 20 (texel fetch)     Step 22 (cache)
                                                       │                          │
                                                       │                     Step 23 (bus)
                                                       │                          │
                                                       └──→ Step 24 (vertex seq) ←┘
                                                                  │
                                                            Step 25 (full pipeline)
```

### FPGA LC Budget

| Step | Change | Est. LCs | Running total | Fits? |
| --- | --- | --- | --- | --- |
| Current (16.3) | — | — | 5268 | ⚠ |
| 17.1 (S4 RDL shadows) | Remove redundant FFs | **−15–20** | ~5250 | ⚠ |
| 17.2 (A4 nibble shifter) | ❌ abandoned — iterative replacement ~= barrel LUTs | **−3** | ~5265 | ⚠ |
| 17.3 (remove C ext) | Delete RVC decoder entirely | **−84** (actual) | **5184** | ✅ |
| 18 (SoC restructure) | Package move only | +0 | ~5184 | ✅ |
| 19.1 (MemCtrl extract) | GPU port mux | +5–8 | ~5192 | ✅ |
| 19.2 (sTexFetch FSM) | 1 FSM state + addr calc | +8–12 | ~5204 | ✅ |
| 21.0 (LUT recovery) | Remove MMIO IMEM+uniform+GPR write | **−40–70** | ~5140 | ✅ |
| 21.1 (DMA FSM) | FSM + addr counter + dest mux | +20–30 | ~5170 | ✅ |
| 22 (cache) | Tag compare + data FFs | +25–35 | ~5200 | ✅ |

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

## Phase 3: Linux-Capable CPU

Target: ~Aug 2026 — expand TinyQV to RV32IMA. Sequential after Phase 2.

### Step 26: M Extension (Integer Multiply/Divide)

Add dedicated integer multiplier for MUL/MULH/DIV/REM.
Estimate: 1 week.

### Step 27: A Extension (Atomics)

LR.W / SC.W for Linux `futex` and spinlocks. Reservation register (32-bit
address + valid bit). ~100 LUTs. Reference KianV implementation.
Estimate: 3–5 days.

### Step 28: Boot no-MMU Linux

Intermediate milestone before full MMU. Estimate: 1 week.

### Step 29: MMU (Sv32)

Two-level page table walker, 4–8 entry TLB, `satp`/`mstatus` CSRs.
Intermediate milestone: boot no-MMU Linux first (~1 week).
~800–1200 LUTs — the most expensive single addition.
Estimate: 3–4 weeks.

### Step 30: Boot Full Linux

Kernel, device tree, rootfs on QSPI PSRAM (8 MB). Estimate: 1–2 weeks.

## Phase 4: Mesa Vulkan Driver

Target: ~Oct 2026 (~6–8 weeks total). Write a Mesa Vulkan ICD for the Borg GPU.

### Step 31: Minimal `vk_device` + `wsi_headless`

Headless rendering, no window system needed. Estimate: 1–2 weeks.

### Step 32: Shader Compiler (NIR → SPIR-B)

NIR backend generating Borg instructions. Estimate: 2–3 weeks.

### Step 33: Draw Path (`vkCmdDraw`)

Vertex + fragment shader dispatch to hardware. Estimate: 1–2 weeks.

### Step 34: Texture Sampling (Software)

CPU-side sampling, spec-compliant but slow. Estimate: 1 week.

### Step 35: Vulkan CTS Subset

Run conformance tests, fix failures. Estimate: 1–2 weeks.

## Phase 5: GPU Hardware Extensions

Target: ~Jan 2027 (~6–8 weeks total). Extend the shader processor to support
more Vulkan features. These items only make sense on a larger tile or ASIC.

### Step 36: Integer ALU Ops in Shader

Comparison, bitwise, integer math. Estimate: 1 week.

### Step 37: Memory Load/Store from Shader

Enables shader-side texture addressing. Estimate: 1–2 weeks.

### Step 38: Framebuffer Blending

Alpha blending support. Estimate: 3–5 days.

### Step 39: Multi-Lane SIMD (2–4 FMA)

Process multiple pixels per cycle. Estimate: 1–2 weeks.

### Step 39.5: Multi-Core Shading Simulation (Optional)

Refactor `BorgRaster` with parameterizable execution width to dispatch multiple pixels concurrently across a parallel array of `BorgCore` FPUs exclusively for simulation speedup. (Moved from Phase 2; deferred until CPU bottlenecks are resolved).

### Step 40: Second Tapeout Submission

4×4 or 4×5 tile, Linux + Vulkan capable. Estimate: 1 week.

## Phase 6: Vulkan 1.0 Conformance

Target: ~Mar 2027. Full CTS pass (~3–4 weeks); Mesa handles most complexity.
Khronos conformance submission (~2 weeks): documentation + test results.

## Tile Budget Estimate

| Configuration | Tiles | Cost | Use Case |
| ------------- | ----- | ---- | -------- |
| Phase 1 (RV32I + Borg FP16 ALU) | 4×2 (8) | 515€ | Current tapeout |
| Phase 2 only (RV32I + autonomous GPU) | 4×3 (12) | 715€ | GPU autonomy, no Linux |
| Phase 2 + 3 (RV32IMA + autonomous GPU) | 4×5 (20) | 1115€ | Linux + GPU, target |
| Comfortable (room for Phase 5) | 4×6 (24) | 1315€ | Full Vulkan + extensions |

Costs: 50€/tile + 100€ PCB + 15€ shipping (Tiny Tapeout IHP).

## Hardware Resources

- **QSPI PSRAM**: 64 Mbit (8 MB) — sufficient for Linux + Mesa runtime
- **QSPI Flash**: 128 Mbit (16 MB) — kernel + rootfs + Mesa libraries
- **Display**: RP2040 reads framebuffer from PSRAM, no KMS/DRM needed

## Design Principles

1. **One thing at a time.** Each step produces bit-exact golden output.
2. **Area-first.** Reuse the FMA; don't duplicate ALUs. iCE40 is the constraint.
3. **Firmware fallback.** Hardware fast path for common case; CPU for edge cases.
