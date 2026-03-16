![](../../workflows/gds/badge.svg) ![](../../workflows/docs/badge.svg) ![](../../workflows/test/badge.svg) ![](../../workflows/fpga/badge.svg)

# Borg - European Graphics Processing Unit

## Foundational workflow for an open-source GPU

The Borg (**B**ring yer **O**wn **GR**aphics) project aims to establish
the complete foundational workflow for an open-source GPU using entirely free and open
Electronic Design Automation (EDA) tools.
Recognizing that full GPU development is highly complex, the initiative capitalizes on recent
advances in low-cost chip manufacturing to make individual tape-outs feasible for small teams.

The prototype based on Firesim can be found in the
[pre-NLnet](https://github.com/gonsolo/OldBorg/tree/PreNLnet) repository.

## Architecture

The design is a **TinyQV RISC-V SoC** with the **Borg FP16 shader processor** as a memory-mapped peripheral, targeting both iCE40 FPGAs (pico-ice) and ASIC (IHP SG13G2 via Tiny Tapeout).

### Borg Shader Processor

A minimal programmable shading unit with:

- **FP16 Fused Multiply-Add (FMA)** — IEEE-754 compliant HardFloat unit supporting ADD, MUL, FMA, FNEG, and FSTEP operations
- **8 general-purpose FP16 registers** (r0–r7), MMIO-accessible from the CPU
- **6-word instruction memory** for shader programs
- **4-cycle pipeline** with automatic halt-on-zero-instruction

### Rendering Pipeline

The firmware implements a full triangle rendering pipeline:

1. **Vertex Shader** — Transforms vertices (e.g., 2D rotation) using programmable SPIR-B shaders executed on the Borg FPU
2. **Screen-Space Translation** — NDC to pixel coordinates with configurable framebuffer resolution (up to 64×64)
3. **Rasterization** — Edge-function based triangle testing with hardware-accelerated FP16 cross products
4. **Fragment Shader** — Barycentric interpolation for per-vertex RGB color blending
5. **Framebuffer Output** — Results written to PSRAM, read by host (RP2040) for display

### SPIR-B Shader Format

Shaders are compiled from GLSL-like source to a compact binary format (SPIR-B) and loaded at runtime from PSRAM — no firmware reflash needed to change shaders.

### TinyQV CPU

Based on Michael Bell's [TinyQV](https://github.com/MichaelBell/tinyQV), an RV32I RISC-V core with nibble-serial processing designed for Tiny Tapeout. The original Verilog was **rewritten in Chisel** and heavily modified — including expanded register file support (RV32E → RV32I), integrated Borg peripheral bus, and adapted pipeline for QSPI flash/PSRAM and UART.

## Prerequisites

* [Nix](https://nixos.org)
* [Git](https://git-scm.com)
* [Make](https://www.gnu.org/software/make)

## Building and Testing

### Run all tests (Chisel + RTL cocotb)

```
make test-all
```

### Individual test targets

```
make test-chisel-borg          # Borg FPU unit tests (Chisel)
make test-chisel-core          # TinyQV CPU tests (Chisel)
make test-cocotb-soc-core-rtl  # CPU SoC integration tests (cocotb)
make test-cocotb-soc-borg-rtl  # Borg peripheral tests (cocotb)
```

### FPGA (pico-ice)

Prerequisites: pico-ice FPGA + Raspberry Pi debug probe.

```
cd fpga
make burn           # Build bitstream and upload to FPGA
make triangle       # Run triangle rendering (vertex shader on FPGA, display on RP2040)
```

### ASIC (Tiny Tapeout)

```
make gds            # Full RTL-to-GDS flow via LibreLane/OpenROAD
```

## Milestones

| Task | Status |
|------|--------|
| FPU on software simulator (Chisel + cocotb) | ✅ Done |
| FPU integrated into TinyQV SoC | ✅ Done |
| Vertex shader on FPGA | ✅ Done |
| Triangle rasterization + fragment shading | ✅ Done |
| SPIR-B runtime shader loading | ✅ Done |
| Per-vertex color interpolation | ✅ Done |
| Dynamic framebuffer resolution | ✅ Done |
| Tiny Tapeout TTIHP26a [submission](https://app.tinytapeout.com/projects/3645) | ✅ Submitted |
| Test manufactured chip | ⏳ Pending |
| Vulkan driver | 📋 Planned |
