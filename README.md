![](../../workflows/gds/badge.svg) ![](../../workflows/book/badge.svg) ![](../../workflows/test/badge.svg) ![](../../workflows/fpga/badge.svg)

# Borg - European Graphics Processing Unit

## Foundational workflow for an open-source GPU

The Borg (**B**ring yer **O**wn **GR**aphics) project aims to establish
the complete foundational workflow for an open-source GPU using entirely free and open
Electronic Design Automation (EDA) tools.
Recognizing that full GPU development is highly complex, the initiative capitalizes on recent
advances in low-cost chip manufacturing to make individual tape-outs feasible for small teams.

📖 Read the [Borg GPU Book](https://gonsolo.github.io/Borg/) for detailed documentation.

## Architecture

The design is a **TinyQV RISC-V SoC** with the **Borg FP16 shader processor** as a memory-mapped peripheral, targeting both iCE40 FPGAs (pico-ice) and ASIC (IHP SG13G2 via Tiny Tapeout).

![Triangle rendered by the Borg GPU](docs/triangle.png)

### Borg Shader Processor

A minimal programmable shading unit with:

- **FP16 Fused Multiply-Add (FMA)** — IEEE-754 compliant HardFloat unit supporting ADD, MUL, FMA, FNEG, FSTEP, and FRCP operations
- **32 general-purpose FP16 registers** (r0–r31, expanding to 64), MMIO-accessible from the CPU
- **32-word instruction memory** for shader programs
- **Hardware FP16 reciprocal (RCP)** — LUT + linear interpolation for perspective division
- **4-cycle pipeline** with automatic halt-on-zero-instruction

### Rendering Pipeline

The firmware implements a full triangle rendering pipeline:

1. **Vertex Shader** — 4×4 MVP matrix multiply with hardware perspective division, executed as a single shader pass on the Borg FPU
2. **Screen-Space Translation** — NDC to pixel coordinates with configurable framebuffer resolution (up to 64×64)
3. **Rasterization** — Hardware-iterator driven edge evaluation with native FP16 coordinate expansion and FSM auto-chaining
4. **Fragment Shader** — Unified pass (compiled via linear scan allocator) performing barycentric interpolation for RGB, Z, and UV simultaneously
5. **Z-Buffer** — Per-pixel depth testing with texture mapping from PSRAM
6. **Framebuffer Output** — Results written to PSRAM, read by host (RP2040) for display

### SPIR-B Shader Format

Shaders are compiled from GLSL-like source to a compact binary format (SPIR-B) and loaded at runtime from PSRAM — no firmware reflash needed to change shaders.

### SystemRDL & Hardware Command FIFO

The MMIO architecture is generated automatically via the Accellera **SystemRDL** standard using `PeakRDL-chisel`, emitting both the Chisel `BorgGpuRegs` layout and the C-headers directly.

It features an asynchronous 2-entry **Command FIFO** so the CPU can pack and queue asynchronous drawing packets while the GPU handles geometry and rasterization in the background.

### TinyQV CPU

Based on Michael Bell's [TinyQV](https://github.com/MichaelBell/tinyQV), an RV32I RISC-V core with nibble-serial processing designed for Tiny Tapeout. The original Verilog was **rewritten in Chisel** and heavily modified — including expanded register file support (RV32E → RV32I), integrated Borg peripheral bus, and adapted pipeline for QSPI flash/PSRAM and UART.

## Prerequisites

- [Nix](https://nixos.org)
- [Git](https://git-scm.com)
- [Make](https://www.gnu.org/software/make)

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

### Cycle-Accurate C++ Simulation & Interactive Pygame UI

Fast C++ simulators for RTL validation, capable of rendering frames locally without an FPGA, featuring a real-time cycle-accurate interactive view.

```bash
python simulation/verilator/viewer.py # Bind the Pygame UI to cycle-accurate rendering
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
| 32-bit RISC-V instructions & 32-entry register file | ✅ Done |
| Hardware perspective projection (4×4 MVP shader) | ✅ Done |
| Hardware FP16 reciprocal (FRCP) | ✅ Done |
| Back-face culling & depth-correct vkcube | ✅ Done |
| Hardware fragment interpolation | ✅ Done |
| SystemRDL Automated Memory Mapping | ✅ Done |
| Hardware Command FIFO (2-entry asynchronous submission) | ✅ Done |
| Cycle-accurate C++ simulation (Arcilator & Verilator) | ✅ Done |
| Interactive UI Viewer (zero-copy Pygame) | ✅ Done |
| Test manufactured chip | ⏳ Pending |
| Vulkan driver | 📋 Planned |

## Software Bill of Materials

| Component | Description | License |
|-----------|-------------|---------|
| [Chisel](https://github.com/chipsalliance/chisel) | Hardware construction language (Scala → Verilog) | Apache-2.0 |
| [TinyQV](https://github.com/MichaelBell/tinyQV) | RV32I RISC-V CPU core (rewritten in Chisel) | Apache-2.0 |
| [Berkeley HardFloat](https://github.com/ucb-bar/berkeley-hardfloat) | IEEE-754 floating-point units (FMA) | BSD-3-Clause |
| [LibreLane](https://github.com/efabless/librelane) | RTL-to-GDS ASIC flow orchestrator | Apache-2.0 |
| [Yosys](https://github.com/YosysHQ/yosys) | RTL synthesis | ISC |
| [OpenROAD](https://github.com/The-OpenROAD-Project/OpenROAD) | Place and route | BSD-3-Clause |
| [Magic](https://github.com/RTimothyEdwards/magic) | Layout tool, DRC, GDS export | MIT |
| [KLayout](https://github.com/KLayout/klayout) | GDS viewer and DRC | GPL-2.0 |
| [IHP SG13G2 PDK](https://github.com/IHP-GmbH/IHP-Open-PDK) | IHP 130nm process design kit | Apache-2.0 |
| [cocotb](https://github.com/cocotb/cocotb) | Python-based RTL simulation and testing | BSD-3-Clause |
| [Icarus Verilog](https://github.com/steveicarus/iverilog) | Verilog simulation (cocotb backend) | GPL-2.0 |
| [Verilator](https://github.com/verilator/verilator) | Verilog linting and simulation | LGPL-3.0 |
| [nextpnr](https://github.com/YosysHQ/nextpnr) | FPGA place and route (iCE40) | ISC |
| [IceStorm](https://github.com/YosysHQ/icestorm) | iCE40 FPGA bitstream tools | ISC |
| [Netgen](https://github.com/RTimothyEdwards/netgen) | LVS (Layout vs. Schematic) | MIT |
| [GCC](https://gcc.gnu.org/) | RISC-V cross-compiler (`riscv32-embedded`) | GPL-3.0 |
| [Mill](https://github.com/com-lihaoyi/mill) | Scala build tool | MIT |
| [Tiny Tapeout Tools](https://github.com/TinyTapeout/tt-support-tools) | Build and submission orchestrator | Apache-2.0 |
| [Nix](https://github.com/NixOS/nix) | Reproducible development environment | LGPL-2.1 |
| [CIRCT/firtool](https://github.com/llvm/circt) | Chisel → Verilog compiler (FIRRTL) | Apache-2.0 (LLVM) |
| [Arcilator](https://github.com/llvm/circt) | Cycle-accurate FIRRTL C++ simulator | Apache-2.0 (LLVM) |
| [OpenJDK](https://openjdk.org/) | Java runtime for Chisel/Mill | GPL-2.0 + CE |
