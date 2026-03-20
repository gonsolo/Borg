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
| 0–3 | R/W every pixel (unchanged) | None | Low |
| **4 (tile buffer)** | **Between tiles only** | **Write** (tile flush) | **Low** |
| 5–6 | Between tiles/triangles | Write (flush) | Low |
| **7 (texture fetch)** | Between triangles | **Read + Write** | **None** — CPU out of loop |
| 8–9 | Submit + wait only | Full owner | None |
| 10–12 (CPU ext.) | Submit + wait | Full owner | None |

### Step 0: Nibble-Serial FMA ✅ (2026-03-19)

Replace the combinational 11×11 HardFloat `MulAddRecFN` with a multi-cycle
nibble-serial implementation. Trades latency (~16 cycles instead of 4) for
~215 fewer LUTs — headroom needed for all subsequent steps. MMIO interface
unchanged. Follows TinyQV's existing nibble-serial pattern. The nibble-serial
multiplier is reused in Step 10 for integer mul/div.

Verified: Chisel tests (unit + Borg integration + pipeline) and FPGA triangle
rendering on pico-ice.

### Step 1: Hardware Edge Function Unit ✅ (2026-03-19)

Batch all 3 edge functions (`e = dx·dpy − (−dy)·dpx`) into a single trigger,
reusing the FMA sequentially. Eliminates 2 of 3 `borg_run()` MMIO round-trips
per pixel. Widened register file (8→16), IMEM (6→8), and address bus (6→7 bits).

Verified: Chisel tests (195/195 + batched edge test), cocotb SoC tests (2/2),
and FPGA triangle rendering on pico-ice.

### Step 1a: Multiple Triangles (Firmware) ✅ (2026-03-20)

Loop over a multi-triangle mesh in firmware (e.g. 12-triangle cube via
repeated `borg_cmd_draw()` calls). Already works for 2 triangles in
`borg_triangle.c`; extending to N is trivial.

### Step 1b: Perspective Projection (Firmware)

4×4 MVP matrix multiply per vertex (~16 FMA per vertex). Transforms from
model space to clip space and applies perspective divide. Uses the existing
Borg FMA via MMIO. Estimate: 2–3 days.

### Step 1c: Triangle Clipping (Firmware)

Near/far plane clipping before rasterization. Clip triangles that cross the
near plane; cull those entirely behind it. Sutherland–Hodgman or simplified
guard-band approach. Estimate: 2–3 days.

### Step 2: Hardware Fragment Interpolation

Batch up to 6 fragment channel computations
(`channel = (e0·c0 + e1·c1 + e2·c2) · inv_area` for R, G, B, Z, U, V) through
the FMA with a single trigger. Eliminates 3–6 more round-trips per pixel.
Estimate: 1 week.

### Step 3: Pixel Iterator (Hardware Rasterizer)

Counter-based x/y walker that evaluates edge functions (Step 1) and triggers
fragment interpolation (Step 2) for each inside pixel. CPU submits one triangle
instead of driving every pixel. **This is the key transition from
"ALU co-processor" to "rasterizer."** Estimate: 1–2 weeks.

### Step 4: On-Chip Tile Buffer (BRAM)

4×4 pixel tile buffer in Block RAM (RGB + Z). Rasterizer writes on-chip; a
burst flush writes the completed tile to PSRAM. Eliminates per-pixel PSRAM
round-trips. Tile-based approach matches mobile GPU architecture (Mali,
PowerVR, Adreno). Estimate: 1–2 weeks.

### Step 5: Hardware Z-Buffer Unit

FP16 comparator at the tile buffer write port — depth test in hardware instead
of firmware. ~20 LUTs. Estimate: 2–3 days.

### Step 6: Command FIFO

2–4 entry FIFO between CPU and pixel iterator. CPU submits the next triangle
while GPU rasterizes the current one. Embryonic command buffer.
Estimate: 3–5 days.

### Step 7: Texture Fetch Unit

UV-to-texel conversion, Morton addressing, and PSRAM texel read inside the
pixel iterator. By this step the CPU is out of the inner loop, so there is no
bus contention — the failure mode of the earlier texture cache experiment.
Estimate: 1–2 weeks.

### Step 8: Vertex Shader Auto-Sequencer

FSM that sequences 3 vertex shader runs (loading attributes, running SPIR-B
shader, storing outputs, applying screen-space transform) without CPU
involvement. Estimate: 1 week.

### Step 9: Full Autonomous Triangle Pipeline

Integration of Steps 0–8. CPU submits a triangle descriptor; GPU does
vertex shade → triangle setup → rasterize → fragment shade → Z-test →
tile buffer → PSRAM flush. CPU only writes triangle data and waits for DONE.
Estimate: 1–2 weeks.

### Step Dependencies

```text
Step 0 (nibble-serial FMA)
  └→ Step 1 (edge HW) → Step 2 (frag HW) → Step 3 (pixel iterator)
                                               ├→ Step 4 (tile buffer) → Step 5 (Z-test)
                                               ├→ Step 6 (command FIFO)
                                               └→ Step 7 (texture fetch)
     Step 8 (vertex auto-seq) ─────────────────┘ (independent, plugs in at front)
     Step 9 = integration test of all above
```

## Phase 3: Linux-Capable CPU

Target: ~Aug 2026 — expand TinyQV to RV32IMA. Sequential after Phase 2.
The only shared resource is the nibble-serial multiplier (Step 0), which must
be arbitrated between CPU mul and GPU FMA.

### Step 10: M Extension (Integer Multiply/Divide)

Reuse the nibble-serial multiplier from Step 0 for MUL/MULH/DIV/REM.
~8 cycles for 32-bit multiply via 4-bit partials. ~50 LUTs for decode.
Estimate: 1 week.

### Step 11: A Extension (Atomics)

LR.W / SC.W for Linux `futex` and spinlocks. Reservation register (32-bit
address + valid bit). ~100 LUTs. Reference KianV implementation.
Estimate: 3–5 days.

### Step 12: MMU (Sv32)

Two-level page table walker, 4–8 entry TLB, `satp`/`mstatus` CSRs.
Intermediate milestone: boot no-MMU Linux first (~1 week).
~800–1200 LUTs — the most expensive single addition.
Estimate: 3–4 weeks.

### Step 12a: Boot no-MMU Linux

Intermediate milestone before full MMU. Estimate: 1 week.

### Step 12b: Boot Full Linux

Kernel, device tree, rootfs on QSPI PSRAM (8 MB). Estimate: 1–2 weeks.

## Phase 4: Mesa Vulkan Driver

Target: ~Oct 2026 (~6–8 weeks total). Write a Mesa Vulkan ICD for the Borg GPU.

### Step 13: Minimal `vk_device` + `wsi_headless`

Headless rendering, no window system needed. Estimate: 1–2 weeks.

### Step 14: Shader Compiler (NIR → SPIR-B)

NIR backend generating Borg instructions. Estimate: 2–3 weeks.

### Step 15: Draw Path (`vkCmdDraw`)

Vertex + fragment shader dispatch to hardware. Estimate: 1–2 weeks.

### Step 16: Texture Sampling (Software)

CPU-side sampling, spec-compliant but slow. Estimate: 1 week.

### Step 17: Vulkan CTS Subset

Run conformance tests, fix failures. Estimate: 1–2 weeks.

## Phase 5: GPU Hardware Extensions

Target: ~Jan 2027 (~6–8 weeks total). Extend the shader processor to support
more Vulkan features. These items only make sense on a larger tile or ASIC.

### Step 18: Larger Register File + IMEM

16 regs, 16 IMEM slots for complex shaders. Estimate: 3–5 days.

### Step 19: Integer ALU Ops in Shader

Comparison, bitwise, integer math. Estimate: 1 week.

### Step 20: Memory Load/Store from Shader

Enables shader-side texture addressing. Estimate: 1–2 weeks.

### Step 21: Framebuffer Blending

Alpha blending support. Estimate: 3–5 days.

### Step 22: Multi-Lane SIMD (2–4 FMA)

Process multiple pixels per cycle. Estimate: 1–2 weeks.

### Step 23: Second Tapeout Submission

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
