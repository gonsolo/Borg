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
| 0–9 | R/W every pixel (unchanged) | None | Low |
| **10 (tile buffer)** | **Between tiles only** | **Write** (tile flush) | **Low** |
| 11–12 | Between tiles/triangles | Write (flush) | Low |
| **13 (texture fetch)** | Between triangles | **Read + Write** | **None** — CPU out of loop |
| 14–15 | Submit + wait only | Full owner | None |
| 16–20 (CPU ext.) | Submit + wait | Full owner | None |

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

### Step 10: Pixel Iterator (Hardware Rasterizer)

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
- **Step 10.5: Hardware Coord Expansion (int-to-fp16)**
- **Step 10.6: CPU-Drawn Pixel Dispatch**

### Step 11: On-Chip Tile Buffer (BRAM)

4×4 pixel tile buffer in Block RAM (RGB + Z). Rasterizer writes on-chip; a
burst flush writes the completed tile to PSRAM. Eliminates per-pixel PSRAM
round-trips. Tile-based approach matches mobile GPU architecture (Mali,
PowerVR, Adreno). Estimate: 1–2 weeks.

### Step 12: Hardware Z-Buffer Unit

FP16 comparator at the tile buffer write port — depth test in hardware instead
of firmware. ~20 LUTs. Estimate: 2–3 days.

### Step 13: Command FIFO

2–4 entry FIFO between CPU and pixel iterator. CPU submits the next triangle
while GPU rasterizes the current one. Embryonic command buffer.
Estimate: 3–5 days.

### Step 14: Texture Fetch Unit

UV-to-texel conversion, Morton addressing, and PSRAM texel read inside the
pixel iterator. By this step the CPU is out of the inner loop, so there is no
bus contention — the failure mode of the earlier texture cache experiment.
Estimate: 1–2 weeks.

### Step 15: Vertex Shader Auto-Sequencer

FSM that sequences 3 vertex shader runs (loading attributes, running SPIR-B
shader, storing outputs, applying screen-space transform) without CPU
involvement. Estimate: 1 week.

### Step 16: Full Autonomous Triangle Pipeline

Integration of Steps 0–15. CPU submits a triangle descriptor; GPU does
vertex shade → triangle setup → rasterize → fragment shade → Z-test →
tile buffer → PSRAM flush. CPU only writes triangle data and waits for DONE.
Estimate: 1–2 weeks.

### Step Dependencies

```text
Step 1 (edge HW) → Step 9 (frag HW) → Step 10 (pixel iterator)
                                               ├→ Step 11 (tile buffer) → Step 12 (Z-test)
                                               ├→ Step 13 (command FIFO)
                                               └→ Step 14 (texture fetch)
     Step 15 (vertex auto-seq) ────────────────┘ (independent, plugs in at front)
     Step 16 = integration test of all above
```

## Phase 3: Linux-Capable CPU

Target: ~Aug 2026 — expand TinyQV to RV32IMA. Sequential after Phase 2.

### Step 17: M Extension (Integer Multiply/Divide)

Add dedicated integer multiplier for MUL/MULH/DIV/REM.
Estimate: 1 week.

### Step 18: A Extension (Atomics)

LR.W / SC.W for Linux `futex` and spinlocks. Reservation register (32-bit
address + valid bit). ~100 LUTs. Reference KianV implementation.
Estimate: 3–5 days.

### Step 19: MMU (Sv32)

Two-level page table walker, 4–8 entry TLB, `satp`/`mstatus` CSRs.
Intermediate milestone: boot no-MMU Linux first (~1 week).
~800–1200 LUTs — the most expensive single addition.
Estimate: 3–4 weeks.

### Step 20: Boot no-MMU Linux

Intermediate milestone before full MMU. Estimate: 1 week.

### Step 21: Boot Full Linux

Kernel, device tree, rootfs on QSPI PSRAM (8 MB). Estimate: 1–2 weeks.

## Phase 4: Mesa Vulkan Driver

Target: ~Oct 2026 (~6–8 weeks total). Write a Mesa Vulkan ICD for the Borg GPU.

### Step 22: Minimal `vk_device` + `wsi_headless`

Headless rendering, no window system needed. Estimate: 1–2 weeks.

### Step 23: Shader Compiler (NIR → SPIR-B)

NIR backend generating Borg instructions. Estimate: 2–3 weeks.

### Step 24: Draw Path (`vkCmdDraw`)

Vertex + fragment shader dispatch to hardware. Estimate: 1–2 weeks.

### Step 25: Texture Sampling (Software)

CPU-side sampling, spec-compliant but slow. Estimate: 1 week.

### Step 26: Vulkan CTS Subset

Run conformance tests, fix failures. Estimate: 1–2 weeks.

## Phase 5: GPU Hardware Extensions

Target: ~Jan 2027 (~6–8 weeks total). Extend the shader processor to support
more Vulkan features. These items only make sense on a larger tile or ASIC.

### Step 27: Integer ALU Ops in Shader

Comparison, bitwise, integer math. Estimate: 1 week.

### Step 28: Memory Load/Store from Shader

Enables shader-side texture addressing. Estimate: 1–2 weeks.

### Step 29: Framebuffer Blending

Alpha blending support. Estimate: 3–5 days.

### Step 30: Multi-Lane SIMD (2–4 FMA)

Process multiple pixels per cycle. Estimate: 1–2 weeks.

### Step 31: Second Tapeout Submission

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
