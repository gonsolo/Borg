# Borg GPU — Development Roadmap

## Phase 1: ASIC Tapeout (Current)

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

## Phase 2: Linux-Capable CPU (~3-4 months)

Target: ~Q3 2026

Expand TinyQV to RV32IMA on a larger tile (4×4 or 4×8).

| Task | Estimate | Notes |
| ------ | ---------- | ------- |
| M extension (mul/div) | 1–2 weeks | Reuse FMA multiplier, KianV as reference |
| A extension (atomics) | 1 week | LR/SC for Linux, reference KianV |
| MMU (Sv32) | 1–2 months | TLB + page table walker — hardest piece |
| Boot no-MMU Linux | 2 weeks | Intermediate milestone before MMU |
| Boot full Linux | 2–4 weeks | Kernel, device tree, rootfs on QSPI PSRAM (8 MB) |

## Phase 3: Mesa Vulkan Driver (~2-3 months)

Target: ~Q4 2026

Write a Mesa Vulkan ICD for the Borg GPU.

| Task | Estimate | Notes |
| ------ | ---------- | ------- |
| Minimal `vk_device` + `wsi_headless` | 2-3 weeks | Headless rendering, no window system needed |
| Shader compiler (NIR → SPIR-B) | 2-4 weeks | NIR backend generating Borg instructions |
| Draw path (`vkCmdDraw`) | 2-3 weeks | Vertex + fragment shader dispatch to hardware |
| Texture sampling (software) | 1-2 weeks | CPU-side sampling, spec-compliant but slow |
| Vulkan CTS subset | 2-4 weeks | Run conformance tests, fix failures |

## Phase 4: Borg GPU Hardware Extensions (~2-3 months)

Target: ~Q1 2027

Extend the shader processor to support more Vulkan features.

| Task | Estimate | Notes |
| ------ | ---------- | ------- |
| Larger register file + IMEM | 1 week | Needed for complex shaders |
| Integer ALU ops in shader | 2 weeks | Comparison, bitwise, integer math |
| Memory load/store from shader | 2–3 weeks | Enables texture fetch in hardware |
| Framebuffer blending | 1–2 weeks | Alpha blending support |
| Second tapeout submission | 2 weeks | 4×4 tile, Linux + Vulkan capable |

## Phase 5: Vulkan 1.0 Conformance

Target: ~Q2 2027

| Task | Estimate | Notes |
| ------ | ---------- | ------- |
| Full CTS pass | 1–2 months | Mesa handles most complexity |
| Khronos conformance submission | 1 month | Documentation + test results |

## Tile Budget Estimate

| Configuration | Tiles | Use Case |
| --------------- | ------- | ---------- |
| Current (RV32I + Borg FP16) | 4×2 (8) | Shader coprocessor, no Linux |
| Linux-capable (RV32IMA + MMU + Borg) | 4×4 (16) | Minimal Linux + Vulkan |
| Comfortable (room for extensions) | 4×8 (32) | Full Vulkan + future features |

## Hardware Resources

- **QSPI PSRAM**: 64 Mbit (8 MB) — sufficient for Linux + Mesa runtime
- **QSPI Flash**: 128 Mbit (16 MB) — kernel + rootfs + Mesa libraries
- **Display**: RP2040 reads framebuffer from PSRAM, no KMS/DRM needed
