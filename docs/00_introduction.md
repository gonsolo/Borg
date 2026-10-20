# Introduction

The Borg project is an attempt to build an open-source GPU from scratch using
entirely free and open tools — from the hardware description language down to the
manufacturing process.

## Why?

Modern GPUs are among the most complex chips ever built, and their designs are
closely guarded trade secrets. The Borg project asks: what would it take to build
one in the open?  Not a full GPU — that would take hundreds of engineers — but a
*minimal* one that can run real graphics workloads: vertex shading, rasterization,
fragment shading, and eventually a Vulkan driver.

## The Approach

Borg is a **programmable shader processor**, an FP32 core that runs a 2x2 pixel
quad on four lanes, surrounded by the fixed function Vulkan needs: a draw front
end that walks whole draws, a tile-based rasterizer, a descriptor-based texture
unit, depth, stencil, blending and MSAA. On the ULX3S board a small RISC-V CPU
(Hutt) runs the firmware or Linux next to it; the ASIC carries Borg alone behind a
memory link.

Applications reach it through Vulkan: borgvk, a Mesa driver with its own shader
compiler (borgc), runs the unmodified Khronos `cube.c` on the board, on Linux on
the board itself, and on a simulator of the RTL that runs the Vulkan conformance
tests.

The design is written in [Chisel](https://www.chisel-lang.org/), a hardware
construction language embedded in Scala. Chisel generates synthesizable Verilog
that targets both FPGAs (for development) and ASICs (for manufacturing).

The diagram below shows how all hardware components interact. It is
auto-generated from the Chisel source by `scripts/gen_hw_diagram.py` and
reflects the current state of `hardware/`.

[![Borg hardware component interaction diagram](hw_diagram.svg)](hw_diagram.svg)

## What This Book Covers

1. **The Borg Shader Processor** — the FP32 quad core, its registers and instruction set
2. **The Shader Compiler** — borgc, from NIR to Borg machine code
3. **The Software Driver** — borgvk, the Vulkan driver, and where it runs
4. **Running on an FPGA** — the ULX3S FPGA target
5. **Generating the ASIC** — the RTL-to-GDS flow for silicon manufacturing
6. **Simulation** — from the whole SoC down to the direct simulator for conformance
7. **Tile-Based Rendering** — how a frame moves through the tiles

The appendices hold the specifications of the newer units (geometry front end,
texture unit, colour formats), the design notes of the compiler and driver, and
the conformance status.

All code snippets in this book are extracted directly from the source code.
If the source changes, the book updates automatically via `make book`.
