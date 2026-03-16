# The Borg Shader Processor

The Borg is a minimal programmable shader unit designed for FP16 graphics operations.
It operates as a memory-mapped peripheral within the TinyQV RISC-V SoC.

## Storage

At the heart of the processor are a small register file, an instruction memory,
and a program counter. The entire state fits in a handful of flip-flops:

{{snippet:borg/src/Borg.scala:storage}}

The register file holds 8 FP16 values that the CPU can read and write via MMIO.
The instruction memory stores up to 6 shader instructions — enough for simple
vertex transformations and fragment shading operations.
