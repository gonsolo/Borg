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
    >
    > 1. **Winding Order Inversion:** In our Y-down screen space, calculating edge vectors as `pos[next] - pos[i]` was generating *negative* edge bounds for strictly interior pixels. This incorrectly culled the entire triangle because hardware `fstep.s` expects strictly positive values for `inside_flag`. We reversed the subtraction to `pos[i] - pos[next]`.
    >
    > 2. **Barycentric Interpolation Collapse:** The `dy` component of the edge vector had a deeply buried sign error. Because of this, the edge distances no longer summed up to the triangle's explicit +area, causing barycentric multiplication by `inv_area` to explode pixel colors into blackness. Fixing `edges[i].y` to exactly `pos[next].y - pos[i].y` restored mathematical harmony.
    >
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
    Renamed `TinyQVMemCtrl` → `MemoryController` and moved to `soc` package.
    `TinyQV` is now a pure CPU (no QSPI knowledge); `MemoryController` owns all
    SPI/QSPI pins and arbitrates CPU instr-fetch, CPU data, and a stubbed GPU
    read port (`gpu_addr`, `gpu_read_req`, `gpu_data`, `gpu_read_ready`).
    `SoCLogic` wires all three peer components. All tests pass (Verilator + Arcilator).

- ✅ **Step 19.2: Wire GPU port to BorgRasterizer** *(2026-04-14)*
    Added `sTexFetch` FSM state to `BorgRasterizer`: 2-word packed PSRAM read
    (R+G in Word 0, B in Word 1), latching into `frag_r/g/b` before `sTileWrite`.
    Connected Morton index → `gpu_addr`; `tex_en` gates the fetch path.
    GPU read port wired end-to-end: `BorgRasterizer` → `Borg` → `Peripherals` →
    `Project` → `MemoryController` arbiter (real + sim). `MemoryControllerSim`
    implements fast-path bypass for Verilator/Arcilator. `make triangle` on FPGA
    passes. Chisel `tex_fetch_path` test verifies full FSM handshake.
    FPGA actual: 5256 / 5280 LCs (99%), 10 / 30 BRAMs.

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
    Changed `tile_rg` and `tile_bz` fields in `borg.rdl` from `hw=rw` to
    `hw=r`. PeakRDL no longer generates 4 `_in`/`_out` port pairs or the
    feedback mux into those 64 DFFs. Removed 4 dead `tile_*_in := 0.U` ties
    from `Borg.scala`. **Zero risk.**
  - ✅ **O5: Command FIFO 2→1 entries** (~20 LCs saved) *(2026-04-18)*
    Implemented via `BorgConfig.FPGA(fifoDepth=1)`. The centralized `BorgConfig`
    allows per-target depth: 1 for FPGA (area-constrained), larger for Sim.
  - **O6: Fp16Rcp NaN/Inf removal** (~8 LCs saved) — *deferred*
    GPU shaders never produce NaN/Inf inputs to FRCP. However, keeping `isNaN`/
    `isInf` detection preserves future CPU/Linux compatibility (software FP16
    paths may produce denormals). Defer until Phase 3.
  - ✅ **O7: Remove dead `peekZ` tile buffer port** (~15 LCs saved) *(2026-04-15)*
    `tile.io.peekZ` output was never read in `Borg.scala` — dead logic. Removed
    `peekZIdx`, `peekZ`, `peekZheld`, `notMainRead`, and the `effectiveReadIdx`
    mux from `BorgTileBuffer.scala`. Updated tile buffer tests to use `readData.z`.
  - ✅ **O8: Remove duplicate `read_addr_del`** (~6 LCs saved) *(2026-04-15)*
    Replaced `RegInit(0.U(10.W))` + assignment in `Borg.scala` with `RegNext(io.address)` —
    equivalent, but lets synthesis share/eliminate the register with the `readAddr`
    already computed inside `BorgGpuRegs`.
  - Target: **−89 LCs** → running total ~5191

- ✅ **Step 21.0.1: Parallel Test Runner** *(2026-04-15)*
    Replaced the serial `make test-all` chain with a Python runner
    (`scripts/test_runner.py`) that parallelises independent suites and
    shows a live animated display. Total wall-clock time: **~3 minutes**
    (was sequential; dominated by `chisel › borg` at 2m 40s).

    Execution order: `generate_verilog → lint` (sequential setup, avoids
    mill lock contention), then `chisel:borg · chisel:tinyqv · software ·
    cocotb:soc-core` all in parallel, then `cocotb:soc-borg` after
    soc-core (shared `test/soc/` directory).

    Result: **7/7 suites passed** in 2m 58s with green ✓ per suite.

- ✅ **Step 21.1: sTexFetch FSM Integration** *(completed during Step 19.2)*
    Morton index wiring, sTexFetch FSM, 2-word PSRAM read, Chisel tests — all
    done. `texConfig.en` is hardcoded to `false.B`; `texConfig.baseAddr` is
    hardcoded to `0x51A0`. Both will be made MMIO-controllable below.

- ✅ **Step 21.2: Tex Config MMIO + Firmware Integration** *(2026-04-15)* (+10 LCs)
    Added `tex_config_reg_t` to `borg.rdl` (`base_addr[16]` + `en[1]` @ 0x208).
    Regenerated `BorgGpuRegs`. Replaced hardcoded `texConfig.baseAddr`/`en` in
    `Borg.scala` with `rdlRegs.io.hw.tex_config_*`. Morton encoder now muxes
    between CPU-written TEX_UV (tex disabled) and rasterizer-snooped fragment
    U/V (tex enabled) via new `fragU`/`fragV` outputs on `BorgRasterizerIO`.
    `borg_set_texture()` now writes `TEX_CONFIG` hardware register to enable
    `sTexFetch`; `borg_clear_texture()` clears it. Tile-flush firmware detects
    hardware fetch (TEX_CONFIG.en=1) and reads tile buffer as RGB directly,
    bypassing the CPU-side texel re-fetch.
    Verified: 7/7 test suites pass; Verilator textured triangle in 13.1M cycles.

### Dev Infrastructure ✅ (2026-04-18)

Continuous housekeeping work done alongside Steps 21–22. Not a numbered GPU
feature step, but recorded here for traceability.

- **BorgConfig centralized parameterization**: New `BorgConfig` case class
  consolidates `coordWidth`, `fifoDepth`, and `FloatConfig` into a single
  per-target parameter object. `BorgConfig.FPGA` and `BorgConfig.Sim` replace
  all ad-hoc compile-time flags throughout the hierarchy.
- **`.verilog_stamp` incremental build**: Root `Makefile` skips `generate_verilog`
  when Chisel/RDL sources haven't changed, saving ~30s per `make -C fpga` iteration.
- **Hardware architecture diagram generator** (`scripts/gen_hw_diagram.py`):
  Parses Scala sources to produce an SVG component graph. Used in the HPG 2026
  poster. Also useful for spotting dead/orphaned modules — nodes with no
  instantiation edges stand out immediately. Fixes: orphan detection,
  deduplication of instantiation edges, layout improvements, Software Stack overlay.
- **nextpnr `--seed 0`**: Pinned the placement RNG seed in `fpga/Makefile` for
  deterministic LC counts across local and CI environments.
- **CI tool-version diagnostics**: Added "Print tool versions" step to
  `.github/workflows/fpga.yaml` that logs the nixpkgs rev and nextpnr/yosys
  versions, enabling direct local-vs-CI comparison.
- **Memory package modularization**: `TinyQVMemCtrl` extracted into a standalone
  `memory` package; arcilator simulation fixed.
- **Miscellaneous**: Dead code removal (`LatchReg*`), Chisel test fixes
  (`BorgCoreTests` updated for `BorgConfig.Sim`), `test_raster.c` native mock
  for `borg_fb_width/height`.

### Step 22: GPU DMA Engine + LUT Recovery (2026-04-18)

Generalize the GPU read port for bulk transfers. The DMA engine drives the
**same** `gpu_read` port built in Step 19 — `SoCMemCtrl` is unchanged, only
the driver changes. Estimate: 1 week.

- **Step 22.0: LUT Recovery** (prerequisite micro-steps, −44 LCs)
  - **22.0a: Remove IMEM MMIO write path** (~15 LUTs saved) — DMA replaces it
  - **22.0b: Remove MMIO uniform write path** (~15 LUTs saved) — DMA replaces it
  - **22.0c: Simplify RDL address decode** (~10 LUTs saved)
  - **22.0d: S3 — Remove MMIO GPR read path** (optional, ~20–30 LUTs)
    The `regFileC` shared read port (`wirePortC()` `mmio_en` mux) is used only
    for CPU debugging of shader register state. With DMA in place this path
    is unused. Remove the `mmio_en` conditional from `wirePortC()` in
    `BorgCore.scala`. Requires refactoring all Chisel/cocotb test GPR reads
    to use pipeline write-back snooping.

  **Chicken-and-egg:** Steps 22.0a/b remove MMIO paths before DMA exists.
  Chisel tests and Verilator can load IMEM/uniforms via the C++ test harness
  (direct memory poke). Remove uniform MMIO first (less time-critical); keep
  IMEM MMIO until DMA is tested, then remove.

- **Step 22.1: DMA controller FSM** (`BorgDMA.scala`, +25 LCs) *(2026-04-18)*
    Accepts `(base_ptr, length, destination)` descriptor via MMIO. Issues
    sequential `gpu_read_req` for each word. Routes returned data to the correct
    on-chip buffer (uniform/IMEM/GPR). Multiplexes with `sTexFetch` requests.

- **Step 22.2: Bulk IMEM load from PSRAM** (replaces MMIO IMEM writes)
- **Step 22.3: Bulk uniform load from PSRAM**
- **Step 22.4: Firmware integration** (`dma_load_shader()`, `dma_load_uniforms()`)

  **IMEM strategy:** IMEM BRAM stays (1-cycle fetch is critical for pipeline
  throughput). DMA loads it from PSRAM, replacing the ~56 `borg_write_imem()`
  MMIO calls per shader change. Streaming fetch (eliminate BRAM entirely) is a
  future optimization — trades 1 BRAM for ~30 LUTs + 30× latency on real QSPI.

  **Memory evolution:** The `gpu_read` port created in Step 19 IS the DMA port.
  Step 19 drives it from `BorgRasterizer.sTexFetch`; Step 22 drives it from
  `BorgDMA`.

### Step 23: Cross-Target Parity (Arcilator / Verilator / FPGA + Software) ✅ (2026-04-20)

Establish a systematic quality gate that ensures Arcilator, Verilator, and FPGA
always produce identical results to the software reference — so that bugs like
the RP2040 texture heap exhaustion (which was invisible in simulation) are caught
automatically before they can reach hardware. The root cause of such bugs is a
discrepancy between what the software stack does and what each target exercises.
This step closes that gap structurally. Estimate: 3–5 days.

- **Step 23.1: Unified `make` run targets** ✅
    Standardize `make triangle` and `make vkcube` across all three targets.
    A single top-level invocation exercises Arcilator, Verilator, and FPGA
    without manual per-target coordination, removing the opportunity for
    target-specific workarounds to silently accumulate.

- **Step 23.2: Pixel-exact golden comparison on all targets** ✅
    Extend `make test-all` to pixel-compare the output of each target against
    the software golden image (`golden.ppm`). Any divergence between Arcilator,
    Verilator, or FPGA output fails the suite immediately. This catches
    rendering bugs that are invisible when only one target is tested.

- **Step 23.3: Shared software path for texture upload** ✅
    Unify the texture upload code path so that the same logic (chunked
    Morton-ordered streaming, sentinel check) runs on all three targets.
    Simulation targets use a C++ harness shim; FPGA uses the RP2040
    MicroPython path — but both exercise the same protocol, ensuring
    firmware-level regressions are caught in simulation before FPGA runs.

- **Step 23.4: `make test-all` target parity enforcement** ✅
    Add a CI check that fails if any target is missing from the test matrix.
    No target may be silently skipped. FPGA test results are uploaded as
    artefacts so regressions are traceable.

### Step 24: Memory Controller Rearchitecture

> **Prerequisite for GPU write port.** The Step 24/25 swap was forced by an
> 8-hour debugging session (2026-04-22) that revealed the dual-controller
> architecture (`MemoryController` + `MemoryControllerSim` both instantiated,
> muxed by `fast_sim_en`) is fundamentally broken: SPI address-space offsets
> leak into the fast model, `sim_psram_ext` and the C++ `psram->mem` diverge,
> and GPU writes land in different memories depending on which controller's
> `ready` signal is selected. The GPU write port (now Step 25) cannot be
> reliably tested until the memory architecture is clean.

**Goal:** The SoC sees exactly **one** memory controller at a time — never
both. Which controller is instantiated is a **build-time** (Chisel elaboration)
decision, not a runtime mux:

- **FPGA:** always the real QSPI `MemoryController`.
- **Verilator / Arcilator:** selectable per build — either the fast
  `MemoryControllerSim` (default, for rapid GPU iteration) or the real
  `MemoryController` (for QSPI-level regression testing).

**Transparency principle:** The swap must be invisible to everything above
the memory controller layer. Firmware, TinyQV (CPU), Borg (GPU), Peripherals,
and the SoC top-level must be completely agnostic — the same unmodified
firmware binary must produce identical results on either controller. This
means both controllers expose an identical `MemoryInterface`, and the
C++ simulator adapts its init/readback to match whichever controller is
instantiated. No `#ifdef`, no firmware-side address fixups.

No dual-instantiation, no `fast_sim_en` mux, no SPI offset confusion.

- **Step 24.1: Extract `MemoryInterface` trait**
    Define a common Chisel IO trait (`MemoryInterface`) with `instrFetch`,
    `cpuData`, and `gpuRead` ports. Both `MemoryController` and
    `MemoryControllerSim` implement this trait. `Project.scala` instantiates
    exactly one, selected at **elaboration time** via `isSimulation`, not at
    runtime via `ui_in[7]`.

- **Step 24.2: Single-controller `Project.scala`**
    Remove the dual-instantiation mux. When `isSimulation = true`:
    only `MemoryControllerSim`. When `isSimulation = false`: only
    `MemoryController`. No `fast_sim_en` signal. No runtime switching.

- **Step 24.3: Unified PSRAM address space**
    Remove the `PSRAM_SPI_BASE` (0x1000) offset from the simulation path.
    The fast model's `sim_psram_ext` is indexed by `addr(22,0)` directly —
    no SPI command header. The C++ simulator must use the same zero-based
    addressing for `out_base_word`, `marker_offset_word`, and texture base.

    Sub-tasks:
    - **24.3a:** Update `BorgSimulator.h` constructor: `psram_spi_word_offset = 0`,
      `out_base_word = PSRAM_OUT_OFFSET / 4` (no SPI offset).
    - **24.3b:** Update `load_texture()` to write texture at byte offset 128
      (not `0x1080 = PSRAM_SPI_BASE + 128`).
    - **24.3c:** Update `sync_to_chisel()` to copy `psram->mem` →
      `sim_psram_ext` and `sim_psram_gpu` with correct zero-based addressing.
    - **24.3d:** Verify `PSRAM_OUT_WORD_BASE` in firmware matches the
      hardware's `addr(22,0)` for GPU flush targets.

- **Step 24.4: C++ simulator cleanup**
    - **24.4a:** `BorgSimulatorBase::step()` skips QSPI ticks when
      `fast_mode = true`. Only clocks the design, decodes UART, and
      calls `fast_sim_snoop()`.
    - **24.4b:** `fast_sim_snoop()` syncs the done-marker word from
      `sim_psram_ext` → `psram->mem` each cycle.
    - **24.4c:** `sync_framebuffer()` bulk-copies the FB+ZB region from
      `sim_psram_ext` → `psram->mem` once before `save_ppm()`.
    - **24.4d:** `sync_to_chisel()` populates `sim_psram_ext` and
      `sim_psram_gpu` from `psram->mem` at init time.

- **Step 24.5: Regression — triangle golden image**
    `make triangle` must produce the correct golden triangle image using
    **only** the fast controller. No QSPI involvement. This is the gate
    for proceeding to Step 25.

### Step 25: GPU PSRAM Write Port (+15 LCs)

> **Depends on Step 24.** The GPU write port adds a write path from
> `BorgRasterizer` to PSRAM, enabling autonomous tile buffer flushing.
> Steps 25.1a–25.2b were completed on the old branch (2026-04-21) but
> must be re-applied on top of the clean memory architecture from Step 24.

Add a GPU write path so `BorgRasterizer` can autonomously flush the 4×4 tile
buffer (16 entries × 4 words = 64 writes) to PSRAM without CPU involvement.
Currently the CPU reads each tile entry via MMIO (`TILE_RG`/`TILE_BZ`) and
writes to PSRAM itself — this is the last CPU-in-the-loop bottleneck before
full GPU autonomy (Step 27).

- **Step 25.1a: Rename `GpuReadIO` → `GpuMemIO`, add `wr`/`wdata`**
    (Completed 2026-04-21, carry forward)

- **Step 25.1b: Update all import/usage sites**
    (Completed 2026-04-21, carry forward)

- **Step 25.1c: Tie `wr := false.B`, `wdata := 0.U` everywhere**
    (Completed 2026-04-21, carry forward)

- **Step 25.2a: GPU write bypass in `MemoryControllerSim`**
    (Completed 2026-04-21, carry forward)

- **Step 25.2b: GPU write path in `MemoryController` (real QSPI)**
    (Completed 2026-04-21, carry forward)

- **Step 25.2c: `sTileFlush` FSM in `BorgRasterizer`** (+10 LCs)
    New FSM state after `sTileWrite` on last pixel: reads 16 tile buffer entries
    sequentially and issues 4 GPU write requests per entry (R, G, B, Z = 64
    writes per tile). CPU pre-computes PSRAM base address and passes it via
    command FIFO (avoids `y × width` multiply in hardware).

- **Step 25.2d: Wire `sTileFlush` in `Borg.scala` + add MMIO/RDL regs**
    Wire `flushConfig` (fbBase, zbBase, en) from MMIO registers regenerated by
    PeakRDL. Mux tile buffer read port between MMIO and `sTileFlush`.

    Remove the 16-iteration CPU flush loop from `shade_tiles()` in
    `borg_driver.c`. Write `flush_base` MMIO register once per frame.

- **Step 25.5: Firmware Integration**
  - **Step 25.5.1: Add `borg_set_flush_base()` helper**
  - **Step 25.5.2: Enable `flush_config.en` in `borg_set_texture()`**
  - **Step 25.5.3: Set flush base per-tile in `shade_tiles()`**
  - **Step 25.5.4: Implement polling handoff & guard legacy loop**

### Step 26: Integrated Vertex + Triangle Setup Sequencer (+45 LCs)

Unified FSM that replaces what the CPU currently does in `shade_tiles()`,
`run_vertex_shader()`, `triangle_setup()`, and `compute_edge_vectors()`.
Combines the planned DMA engine (Step 22) and vertex sequencer into a
single FSM to share registers, address counters, and control logic.

- **Step 26.1: Vertex shader sequencing** (unchanged)
    FSM sequences 3 vertex shader runs: DMA loads vertex attributes from
    PSRAM into GPR, runs SPIR-B shader, stores clip-space outputs. Reloads
    uniform buffer between vertex and fragment stages.

- **Step 26.2: Triangle setup shader**
    A 4th shader program that computes edge equations, signed area, `inv_area`
    (FRCP), and bounding box from the 3 vertex outputs. Currently done in
    firmware (`triangle_setup()` + `compute_edge_vectors()` in
    `borg_driver.c`). No new hardware — uses existing FMA+FRCP. ~80 shader
    cycles per triangle, negligible vs. per-pixel rasterization.

- **Step 26.3: Automatic uniform reload**
    After triangle setup, the sequencer DMA-loads the rasterizer + fragment
    shader uniforms (edge constants, vertex colors, inv_area, z_vals) from
    the setup shader outputs, then enqueues the first tile command.

### ~~Step 24 (old): Data Cache~~ → Deferred

Deferred to Phase 5 (larger FPGA or ASIC). Not needed for functional
correctness. The ~30 LUT cost doesn't fit the iCE40 budget, and on ASIC
(Tiny Tapeout) a BRAM-based full-coverage cache is possible at zero LUT cost.

### ~~Step 25 (old): SoC Bus Protocol~~ → Deferred

Deferred to Phase 5 (larger FPGA or ASIC). Code quality improvement, not
functional. The hand-rolled priority chain works correctly. A proper
`BorgBus` → TileLink adapter is worth doing on the Nitefury II or for ASIC,
but not on iCE40 at 99% utilization.

### Step 27: Full Autonomous Triangle Pipeline

Integration of Steps 21–26. CPU submits a triangle descriptor; GPU does:

1. **Vertex shade** (3× vertex shader runs via DMA + sequencer)
2. **Triangle setup** (setup shader: edge equations, inv_area, bbox)
3. **Rasterize** (hardware pixel iterator + edge evaluation)
4. **Fragment shade** (hardware FMA pipeline, snooped write-back)
5. **Texture fetch** (sTexFetch FSM, Morton-encoded PSRAM read)
6. **Z-test** (tile buffer BRAM comparison)
7. **Tile write** (sTileWrite to BRAM)
8. **Tile flush** (sTileFlush to PSRAM via GPU write port)

CPU only writes the triangle descriptor and waits for DONE interrupt.
Estimate: 1–2 weeks.

### Step 28: Multi-Triangle Autonomous Rendering

Extend Step 27 to process a list of triangle descriptors from PSRAM without
CPU involvement. The GPU reads the next descriptor, runs the full pipeline,
and signals DONE after the last triangle. The CPU submits a draw call
(base pointer + count) and waits.

### Step 29: Real-Time VGA Output (TT VGA PMOD)

Drive the Tiny Tapeout VGA PMOD directly from the pico-ice FPGA for
real-time display — the hardware equivalent of `make vkcube_gui`.
No host PC needed; the GPU renders to a monitor in real time.

**TT VGA PMOD pinout** (resistor-DAC, 2 bits per channel):

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

**Pin conflict:** The VGA PMOD uses all 8 `uo_out` pins, but the current
design uses `uo_out[0]` for UART TX and `uo_out[6]` for debug UART.
Solution: **build-time option** — `CONFIG=lite_vga` routes VGA to
`uo_out`, while `CONFIG=lite` keeps UART. The RP2040 can provide UART
independently via its own USB connection, so VGA mode doesn't lose debug.

**Architecture — SPRAM framebuffer + VGA scanout:**

```text
┌─ iCE40 UP5K ─────────────────────────────────────────────┐
│                                                           │
│  GPU renders → PSRAM (existing)                           │
│       │                                                   │
│  CPU copies frame → SPRAM (32 KB, one of 4 blocks)        │
│       │              ↑ single-port, time-shared            │
│       │              │                                     │
│  VGA Controller ─────┘                                     │
│       │  H/V counters + pixel address + FP16→RGB222        │
│       │  Reads SPRAM during active pixels                  │
│       │  CPU writes during vblank (16.7 ms budget)         │
│       ↓                                                    │
│  uo_out[7:0] → TT VGA PMOD → Monitor                      │
└───────────────────────────────────────────────────────────┘
```

**SPRAM timing:** The iCE40 SPRAM is single-port (16-bit × 16K = 256 Kbit).
Time-sharing: VGA reads during active display lines, CPU writes during
vertical blanking (~1.6 ms per frame = ~38K cycles at 24 MHz — enough
to copy a 32×32×3 = 3072-word framebuffer with margin).

**Supported framebuffer sizes:**

| Resolution | SPRAM usage | VGA upscale | Pixels/frame |
| --- | --- | --- | --- |
| 32×32 | 2 KB (6%) | 20×15 pixel blocks | 1,024 |
| 64×64 | 8 KB (25%) | 10×7 pixel blocks | 4,096 |
| 128×96 | 24 KB (75%) | 5×5 pixel blocks | 12,288 |

**VGA timing:** 640×480 @ 60 Hz requires a 25.175 MHz pixel clock.
The iCE40 PLL can generate 25.125 MHz from the 12 MHz oscillator
(within VGA spec tolerance). The VGA controller upscales the small
framebuffer by repeating each pixel N× horizontally and M× vertically.

**FP16 → RGB222 conversion:** The GPU framebuffer stores FP16 colors.
The VGA DAC needs 2 bits per channel. A minimal converter:

```verilog
R[1:0] = fp16_color[14:13]  (top 2 mantissa bits, exponent-gated)
```

~10 LUTs for all 3 channels. Visually crude but functional for a demo.

**Sub-steps:**

- **Step 29a: VGA timing generator** (+30 LCs)
    H/V counters, sync pulse generation, blanking flags.
    Chisel module `VgaController.scala`.

- **Step 29b: SPRAM framebuffer** (+20 LCs)
    SPRAM `SB_SPRAM256KA` instantiation, address mux (CPU write port
    during vblank, VGA read port during active). MMIO trigger for
    frame copy (`PSRAM → SPRAM` DMA, or CPU loop).

- **Step 29c: FP16→RGB222 scanout** (+15 LCs)
    Pixel fetch from SPRAM, upscale counter, format conversion, `uo_out`
    drive. Build-time `CONFIG=lite_vga` selects VGA vs. UART on `uo_out`.

- **Step 29d: `make fpga_vga` target** (+0 LCs)
    Makefile target that builds the VGA-enabled bitstream. PCF constraints
    for VGA PMOD pins on the pico-ice output header.

**LC cost:** ~65 LCs. Fits in lite profile (~4800 + 65 = ~4865 LCs, 92%).

**SPRAM budget:** Uses 1 of 4 available SPRAM blocks. 3 remain free for
future use (e.g., instruction cache, data cache, or larger framebuffer).

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
                                                  Step 19 (shared MemCtrl)
                                                       │
                                                  Step 20 (IO bundle refactor)
                                                       │
                                               Step 21 (area opts + tex enable)
                                                       │
                                               Step 22 (DMA + LUT recovery)
                                                       │
                                           Step 23 (unified runtime + tex)
                                                       │
                                               Step 24 (GPU write port)
                                                       │
                                               Step 25 (MemCtrl rearch)
                                                       │
                                               Step 26 (vert seq + tri setup)
                                                       │
                                               Step 27 (full autonomous pipeline)
                                                        │
                                               Step 28 (multi-triangle rendering)
```

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
| 22.1 (DMA FSM) | FSM + addr counter | +25 | 5182 | ✅ |
| 23 (unified runtime + tex) | Makefile + tex unification | +0 | 5182 | |
| 24 (GPU write port) | sTileFlush + arbiter | +15 | 5197 | ✅ |
| 25 (MemCtrl rearch) | Unified arbiter logic | +0 | 5197 | ✅ |
| 26 (vert seq + tri setup) | Unified FSM | +45 | 5242 | ✅ |
| 27 (pipeline integration) | Wiring + control | +15 | 5257 | ✅ |
| 28 (multi-triangle) | Descriptor reader | +10 | **5267** | ✅ |
| **Margin** | | | **13 LCs** | |

**Reserve optimizations** (if margin is too tight):

- O4: Direct tile buffer write (−40 LCs, medium risk)
- O2: Remove `tex_uv` registers after Step 24 (−20 LCs)

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

**Development flow:**

```text
RTL development ──→ Nitefury II / ULX3S (fast iteration, full design)
                │
Area lint ──────→ pico-ice iCE40 build (canary: if it fits here, it fits everywhere)
                │
ASIC validation ─→ OpenLane CI (nightly: Yosys → OpenROAD → STA at 50 MHz)
                │
Tapeout ────────→ Tiny Tapeout (IHP SG13G2, 32 tiles)
```

**Key portability rules:**

1. No vendor primitives — all memories are `SyncReadMem`, all multiplies via HardFloat
2. Keep the iCE40 build alive as a size canary (GPU as build-time option)
3. Design for 50 MHz max (iCE40 meets timing at 24 MHz → IHP meets at 50 MHz)
4. Run ASIC synthesis in CI to catch non-portable constructs early

**Platform comparison:**

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

**Architecture — Chisel top-level wrapper (same pattern as PicoIce.scala):**

```text
┌─ ULX3S FPGA (ECP5-85K) ────────────────────────────────────┐
│                                                              │
│  ┌─ ULX3S_top (Chisel RawModule + SoCLogic) ──────────────┐ │
│  │                                                         │ │
│  │  ECP5 PLL (25 MHz xtal → 24 MHz system clock)           │ │
│  │  BB / TRELLIS_IO for QSPI bidirectional pins            │ │
│  │                                                         │ │
│  │  ┌─ SoCLogic (identical to pico-ice and TT) ─────────┐ │ │
│  │  │  TinyQV CPU                                        │ │ │
│  │  │  MemoryController (QSPI)                           │ │ │
│  │  │  Peripherals (Borg GPU + UART)                     │ │ │
│  │  └────────────────────────────────────────────────────┘ │ │
│  │                                                         │ │
│  │  QSPI pins → PMOD header → PSRAM daughter board         │ │
│  │  UART TX/RX → FTDI USB or PMOD                          │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                              │
│  On-board SDRAM (32 MB) — unused by Borg, available for     │
│  future direct-memory experiments                            │
└──────────────────────────────────────────────────────────────┘
```

The ULX3S wrapper is a Chisel `RawModule with SoCLogic` — exactly like
`tinyQV_top` (PicoIce.scala), but using ECP5 I/O primitives instead of
iCE40 `SB_IO`:

| pico-ice (iCE40) | ULX3S (ECP5) | Function |
| --- | --- | --- |
| `SB_IO` (pin_type=0x29) | `BB` / `TRELLIS_IO` | Bidirectional QSPI data |
| `SB_HFOSC` (48 MHz / div) | `EHXPLLL` (25 MHz → 24 MHz) | Clock generation |
| Direct pin assignment | Direct pin assignment | QSPI control (CS, SCK) |
| PCF constraints | LPF constraints | Pin mapping |

**Why ULX3S is ideal for Phase 3:**

1. **Same toolchain** — Yosys + nextpnr-ecp5. Same Makefile
   structure as iCE40, just `--85k` instead of `--up5k`.
2. **Same `SoCLogic` trait** — zero RTL changes. Only the top-level wrapper
   and pin constraints differ.
3. **LUT4 architecture** — ECP5 uses LUT4 like iCE40 and ASIC synthesis.
   Area estimates transfer directly (unlike Artix-7 LUT6).
4. **QSPI on PMOD** — attach real PSRAM on a PMOD daughter board. Same
   protocol, same timing, same firmware. Or use on-board flash for code.
5. **85K LUTs** — enough for full CPU (RV32IMA + MMU) + GPU + Phase 5
   features, all at once.
6. **~$60** — fraction of the Nitefury II cost.

**QSPI PSRAM connection:** The ULX3S has PMOD headers. A small daughter
board with an APS6404L QSPI PSRAM chip (same as on pico-ice) connects
via 6 PMOD pins (4× data + SCK + CS). The Borg SoC sees identical PSRAM
hardware. Alternatively, the on-board 16 MB SPI flash can serve as
read-only PSRAM for code-only testing.

**Integration files (future):**

```text
fpga/ulx3s/
  src/ULX3S.scala        — Chisel top-level (RawModule + SoCLogic)
  ULX3S.lpf              — ECP5 pin constraints
  Makefile               — Yosys + nextpnr-ecp5 + ecppack
```

**When to use ULX3S vs. pico-ice vs. Nitefury:**

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

**Architecture — TT-compatible black box:**

```text
┌─ Nitefury II FPGA ──────────────────────────────────────────┐
│                                                              │
│  ┌─ LiteX Shell ──────────────────────────────────────────┐  │
│  │  PCIe ↔ Wishbone bridge                                │  │
│  │  DDR3 controller (LiteDRAM)                            │  │
│  │  UART-over-PCIe                                        │  │
│  │  QSPI PSRAM emulator (DDR3 → QSPI protocol bridge)    │  │
│  │                                                        │  │
│  │  ┌─ tt_um_gonsolo_borg ─────────────────────────────┐  │  │
│  │  │                                                   │  │  │
│  │  │  ui_in[7:0]  ← UART RX, interrupts (from LiteX)  │  │  │
│  │  │  uo_out[7:0] → UART TX, debug (to LiteX)         │  │  │
│  │  │  uio[7:0]    ↔ QSPI (to PSRAM emulator)          │  │  │
│  │  │                                                   │  │  │
│  │  │  Identical RTL to pico-ice and TT ASIC            │  │  │
│  │  └───────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

The `tt_um_gonsolo_borg` module is instantiated as a LiteX `Instance()`
black box. Its 8+8+8 pin interface is wired to LiteX-provided bridges:

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

**QSPI PSRAM emulator:** A small LiteX module that implements the QSPI PSRAM
protocol (APS6404L/ESP-PSRAM64H) on the `uio` pins but backs storage with a
DDR3 memory region. This gives the Borg SoC 8 MB of "PSRAM" at full QSPI
speed but backed by DDR3 reliability. The emulator reuses the existing QSPI
timing — no RTL changes inside `tt_um_gonsolo_borg`.

**Why this approach:**

1. **Byte-identical RTL** — the exact same `tt_um_gonsolo_borg` Verilog tapes
   out on TT and runs on Nitefury. No `ifdef`, no conditional compilation.
2. **PCIe host access** — the host PC can read/write DDR3 directly (framebuffer
   inspection, texture upload, register debug) via LiteX's PCIe→Wishbone bridge,
   without touching the Borg SoC's QSPI interface.
3. **Higher clock headroom** — the Artix-7 can run `tt_um_gonsolo_borg` at
   50+ MHz (vs. 24 MHz on pico-ice), validating ASIC timing margins.
4. **Incremental migration** — when Phase 4–5 needs more bandwidth, the QSPI
   emulator can be replaced with a direct AXI↔Wishbone bridge into the SoC
   (requires modifying `SoCLogic`, but the `tt_um_gonsolo_borg` wrapper stays
   as the tapeout target).

**LiteX integration files (future):**

```text
fpga/nitefury/
  nitefury_borg.py       — LiteX SoC definition + PSRAM emulator
  qspi_psram_emu.py      — QSPI protocol → DDR3 bridge
  Makefile               — builds bitstream via Yosys + nextpnr-xilinx (F4PGA)
```

**Existing code that enables this:**

- `SoCLogic` trait (Project.scala:71) — all SoC wiring is platform-independent
- `tt_um_gonsolo_borg` (Project.scala:338) — standardized 8+8+8 pin interface
- `tinyQV_top` (PicoIce.scala:17) — shows how to wrap `SoCLogic` for a
  different platform (SB_IO for iCE40 vs. LiteX `Instance()` for Artix-7)

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

*CTS debugging is inherently unpredictable — a single spec-compliance edge
case can take days. The Mar 2027 date assumes no major architectural
rework is needed.*

## Tile Budget Estimate

| Configuration | Tiles | Cost | Use Case |
| ------------- | ----- | ---- | -------- |
| Phase 1 (RV32I + Borg FP16 ALU) | 4×2 (8) | 515€ | Current tapeout |
| Phase 2 only (RV32I + autonomous GPU) | 4×3 (12) | 715€ | GPU autonomy, no Linux |
| Phase 2 + 3 (RV32IMA + autonomous GPU) | 4×5 (20) | 1115€ | Linux + GPU, target |
| Comfortable (room for Phase 5) | 4×6 (24) | 1315€ | Full Vulkan + extensions |
| **Full Vulkan (FP32 + multicore)** | **4×8 (32)** | **1715€** | **Vulkan conformance target** |

Costs: 50€/tile + 100€ PCB + 15€ shipping (Tiny Tapeout IHP).

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

```scala
// Planned — does not exist yet
case class BorgConfig(
  enableGpu:           Boolean = true,   // Borg GPU (FP16 shader core)
  enableFp32:          Boolean = false,  // FP32 FMA (Phase 5)
  enableDMA:           Boolean = true,   // GPU DMA engine (Step 22)
  enableTexFetch:      Boolean = true,   // Hardware texel fetch (Step 21)
  enableAutoSequencer: Boolean = true,   // Vertex sequencer (Step 24)
  enableGpuWrite:      Boolean = true,   // GPU PSRAM write (Step 24)
  enableBlending:      Boolean = false,  // Alpha blending (Phase 5)
  enableCExtension:    Boolean = false,  // RV32C compressed ISA
  enableMExtension:    Boolean = false,  // RV32M multiply/divide
  enableMMU:           Boolean = false,  // Sv32 MMU (Phase 3)
)
```

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
  4×4 commands, the GPU rasterizes + fragment-shades autonomously
- **Vertex-colored 3D cube** — the `vkcube` demo works with CPU-driven
  vertex shading and tile flush
- **FP16 shader programming** — write fragment shaders in SPIR-B assembly,
  load via MMIO, see results on screen
- **Full source code** — Chisel RTL, C firmware, Python tools, all open-source

What's missing vs. the full config: texture mapping, autonomous vertex
shading / tile flush (CPU does these), Linux, Vulkan API. But the **core GPU
experience** — writing shaders, watching triangles render, understanding the
pipeline — is fully there.

### How to Select a Configuration

```bash
# Lite config (pico-ice, iCE40)
make fpga CONFIG=lite

# Developer config (ULX3S, ECP5)
make fpga-ulx3s CONFIG=developer

# Full config (Nitefury, simulation, or TT ASIC)
make asic CONFIG=full
```

The Makefile passes the config name to Chisel's `MILL_ARGS`, which selects
the appropriate `BorgConfig` case class. All configs share the same RTL
source — only the parameter values differ.

## Design Principles

1. **One thing at a time.** Each step produces bit-exact golden output.
2. **Area-first, configurable.** Reuse the FMA; don't duplicate ALUs. iCE40
   is the minimum target — features that don't fit are build-time optional.
3. **Firmware fallback.** Hardware fast path for common case; CPU for edge cases.
4. **Free software only.** All tools are open-source: Yosys, nextpnr, OpenLane,
   Chisel, Mill. No vendor-locked toolchains.
5. **Accessible.** Anyone with a $40 pico-ice can build and run the GPU.
