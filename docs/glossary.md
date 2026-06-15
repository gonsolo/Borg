# Glossary

Canonical terms used throughout the Borg documentation. When two names refer to
the same thing, the **bold** term is preferred in technical writing.

---

## Hardware components

| Term | Meaning |
|------|---------|
| **Borg** | The project; the whole GPU system (hardware + firmware + driver). |
| **Borg GPU** | The GPU hardware implemented in Chisel — the FPGA/ASIC portion. Use when distinguishing it from the host-side software stack. |
| **BorgCore** | The shader execution unit: FP16 FMA pipeline, 32-entry register file, 32-slot instruction memory. The Scala class name; use in technical/RTL contexts. *Synonym (prose only):* "shader processor". |
| **BorgSequencer** | The tile-based rendering orchestrator. Drives BorgCore and BorgRasterizer across two passes (bin + render). |
| **BorgRasterizer** | Hardware edge-function unit. Evaluates coverage and invokes BorgCore for each covered pixel. |
| **tile buffer** | The 16-pixel on-chip SRAM (BorgTileBuffer) that holds RGBZ during rasterization. *Not* "on-chip SRAM buffer". |
| **BorgBinner** | Pass-1 hardware: counts triangles per tile and stores them in the binner's on-chip SRAM. |
| **Hutt** | The RV32I CPU core (replaced TinyQV in 2026-03). Runs firmware; communicates with BorgCore via MMIO. |
| **MemoryController** | Arbitrates the CPU instruction port, CPU data port, and GPU memory port across QspiBackend (flash/PSRAM) and SdramBackend (SDRAM). |

## Shader formats

| Term | Meaning |
|------|---------|
| **SPIR-B** | Borg's binary shader format (analogous to SPIR-V). Produced by `spirv_compiler.py` or borgc. Loaded at runtime by `borg_spirb.c`. *Not* "SPIR-V" — Borg uses its own encoding. |
| **borgc** | The Rust NIR→Borg compiler in-tree under `mesa/src/borg/compiler/`. Compiles Vulkan SPIR-V shaders to SPIR-B for the Mesa driver. |
| **SPIR-V** | Khronos standard IR. Mesa's `vk_spirv_to_nir` converts SPIR-V to NIR; borgc then lowers NIR to SPIR-B. |

## Rendering concepts

| Term | Meaning |
|------|---------|
| **TBR** | Tile-Based Rendering: split the frame into tiles, bin triangles per tile (Pass 1), then render tile-by-tile in on-chip SRAM (Pass 2), flushing to PSRAM/SDRAM between tiles. |
| **tile** | A 4×4 pixel region. The tile buffer holds one tile worth of RGBZ. |
| **PSRAM** | Pseudo-SRAM: the 8 MB external memory used for the framebuffer and firmware data (original pico-ice target). The ULX3S uses SDRAM instead. |
| **SDRAM** | 32 MB external SDRAM on the ULX3S board; used for framebuffer, firmware, and texture storage. |
| **flat MemBackendIO** | The word-wide memory bus that the verilator/arcilator simulator drives directly, bypassing QSPI. The MemoryController exposes this as its backend interface. |

## Build / toolchain

| Term | Meaning |
|------|---------|
| **Chisel** | The hardware construction language (Scala DSL) used for all RTL in `hardware/`. |
| **firtool** | LLVM/CIRCT tool that converts Chisel's FIRRTL output to Verilog (or MLIR for Arcilator). |
| **Arcilator** | CIRCT-based fast simulator: compiles MLIR to LLVM IR, then to a native binary. Faster than Verilator for this design. |
| **Verilator** | Cycle-accurate C++ simulator compiled from the emitted Verilog. |
| **SystemRDL / PeakRDL** | Register-description language + toolchain used to define the MMIO layout (`hardware/rdl/*.rdl`). Generates both `BorgGpuRegs.scala` and `borg_regs.h`. |
| **borgvk** | The Mesa Vulkan ICD (in `mesa/src/borg/vulkan/`) that runs unmodified `vkcube` on Linux and ships frames to the ULX3S over serial. |
