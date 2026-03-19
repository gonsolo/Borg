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

### Step 0: Nibble-Serial FMA

Replace the combinational 11×11 HardFloat `MulAddRecFN` with a multi-cycle
nibble-serial implementation. Trades latency (~16 cycles instead of 4) for
~215 fewer LUTs — headroom needed for all subsequent steps. MMIO interface
unchanged. Follows TinyQV's existing nibble-serial pattern. The nibble-serial
multiplier is reused in Step 10 for integer mul/div.

### Step 1: Hardware Edge Function Unit

Batch all 3 edge functions (`e = dx·dpy − (−dy)·dpx`) into a single trigger,
reusing the FMA sequentially. Eliminates 3 `borg_run()` MMIO round-trips per
pixel.

### Step 2: Hardware Fragment Interpolation

Batch up to 6 fragment channel computations
(`channel = (e0·c0 + e1·c1 + e2·c2) · inv_area` for R, G, B, Z, U, V) through
the FMA with a single trigger. Eliminates 3–6 more round-trips per pixel.

### Step 3: Pixel Iterator (Hardware Rasterizer)

Counter-based x/y walker that evaluates edge functions (Step 1) and triggers
fragment interpolation (Step 2) for each inside pixel. CPU submits one triangle
instead of driving every pixel. **This is the key transition from
"ALU co-processor" to "rasterizer."**

### Step 4: On-Chip Tile Buffer (BRAM)

4×4 pixel tile buffer in Block RAM (RGB + Z). Rasterizer writes on-chip; a
burst flush writes the completed tile to PSRAM. Eliminates per-pixel PSRAM
round-trips. Tile-based approach matches mobile GPU architecture (Mali,
PowerVR, Adreno).

### Step 5: Hardware Z-Buffer Unit

FP16 comparator at the tile buffer write port — depth test in hardware instead
of firmware. ~20 LUTs.

### Step 6: Command FIFO

2–4 entry FIFO between CPU and pixel iterator. CPU submits the next triangle
while GPU rasterizes the current one. Embryonic command buffer.

### Step 7: Texture Fetch Unit

UV-to-texel conversion, Morton addressing, and PSRAM texel read inside the
pixel iterator. By this step the CPU is out of the inner loop, so there is no
bus contention — the failure mode of the earlier texture cache experiment.

### Step 8: Vertex Shader Auto-Sequencer

FSM that sequences 3 vertex shader runs (loading attributes, running SPIR-B
shader, storing outputs, applying screen-space transform) without CPU
involvement.

### Step 9: Full Autonomous Triangle Pipeline

Integration of Steps 0–8. CPU submits a triangle descriptor; GPU does
vertex shade → triangle setup → rasterize → fragment shade → Z-test →
tile buffer → PSRAM flush. CPU only writes triangle data and waits for DONE.

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

Target: ~Q3 2026 — expand TinyQV to RV32IMA. Can be developed in parallel
with GPU Steps 1–9. The only shared resource is the nibble-serial multiplier
(Step 0), which must be arbitrated between CPU mul and GPU FMA.

### Step 10: M Extension (Integer Multiply/Divide)

Reuse the nibble-serial multiplier from Step 0 for MUL/MULH/DIV/REM.
~8 cycles for 32-bit multiply via 4-bit partials. ~50 LUTs for decode.

### Step 11: A Extension (Atomics)

LR.W / SC.W for Linux `futex` and spinlocks. Reservation register (32-bit
address + valid bit). ~100 LUTs.

### Step 12: MMU (Sv32)

Two-level page table walker, 4–8 entry TLB, `satp`/`mstatus` CSRs.
Intermediate milestone: boot no-MMU Linux first. ~800–1200 LUTs — the most
expensive single addition.

| Task | Estimate | Notes |
| ---- | -------- | ----- |
| M extension (mul/div) | 1–2 weeks | Reuse nibble-serial multiplier from Step 0 |
| A extension (atomics) | 1 week | LR/SC, reference KianV |
| MMU (Sv32) | 1–2 months | TLB + page table walker — hardest piece |
| Boot no-MMU Linux | 2 weeks | Intermediate milestone before MMU |
| Boot full Linux | 2–4 weeks | Kernel, device tree, rootfs on QSPI PSRAM (8 MB) |

## Phase 4: Mesa Vulkan Driver (~2-3 months)

Target: ~Q4 2026

Write a Mesa Vulkan ICD for the Borg GPU.

| Task | Estimate | Notes |
| ---- | -------- | ----- |
| Minimal `vk_device` + `wsi_headless` | 2-3 weeks | Headless rendering, no window system needed |
| Shader compiler (NIR → SPIR-B) | 2-4 weeks | NIR backend generating Borg instructions |
| Draw path (`vkCmdDraw`) | 2-3 weeks | Vertex + fragment shader dispatch to hardware |
| Texture sampling (software) | 1-2 weeks | CPU-side sampling, spec-compliant but slow |
| Vulkan CTS subset | 2-4 weeks | Run conformance tests, fix failures |

## Phase 5: GPU Hardware Extensions (~2-3 months)

Target: ~Q1 2027

Extend the shader processor to support more Vulkan features. These items only
make sense on a larger tile or ASIC.

| Task | Estimate | Notes |
| ---- | -------- | ----- |
| Larger register file + IMEM | 1 week | 16 regs, 16 IMEM slots for complex shaders |
| Integer ALU ops in shader | 2 weeks | Comparison, bitwise, integer math |
| Memory load/store from shader | 2–3 weeks | Enables shader-side texture addressing |
| Framebuffer blending | 1–2 weeks | Alpha blending support |
| Multi-lane SIMD (2–4 FMA) | 2–3 weeks | Process multiple pixels per cycle |
| Second tapeout submission | 2 weeks | 4×4 or 4×5 tile, Linux + Vulkan capable |

## Phase 6: Vulkan 1.0 Conformance

Target: ~Q2 2027

| Task | Estimate | Notes |
| ---- | -------- | ----- |
| Full CTS pass | 1–2 months | Mesa handles most complexity |
| Khronos conformance submission | 1 month | Documentation + test results |

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
