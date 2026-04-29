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

Rather than designing a traditional fixed-function GPU, Borg is a **programmable
shader processor** attached to a small RISC-V CPU. The CPU handles control flow
(draw calls, rasterization loops) while the shader processor handles the
floating-point math (vertex transformations, color interpolation).

The design is written in [Chisel](https://www.chisel-lang.org/), a hardware
construction language embedded in Scala. Chisel generates synthesizable Verilog
that targets both FPGAs (for development) and ASICs (for manufacturing).

The diagram below shows how all hardware components interact. It is
auto-generated from the Chisel source by `scripts/gen_hw_diagram.py` and
reflects the current state of `hardware/`.

[![Borg hardware component interaction diagram](hw_diagram.svg)](hw_diagram.svg)

## What This Book Covers

1. **The Borg Shader Processor** — the FP16 FMA unit, register file, and instruction set
2. **The Shader Compiler** — translating GLSL shaders to Borg machine code
3. **The Software Driver** — firmware that ties everything together into a rendering pipeline
4. **Running on an FPGA** — prototyping on the pico-ice board
5. **Generating the ASIC** — the RTL-to-GDS flow for silicon manufacturing

All code snippets in this book are extracted directly from the source code.
If the source changes, the book updates automatically via `make book`.
