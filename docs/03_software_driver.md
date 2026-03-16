# The Software Driver

The firmware running on TinyQV provides a Vulkan-like API for rendering triangles.
It consists of a driver library (`driver.c`) and an application (`triangle.c`).

## Memory-Mapped Hardware

The Borg shader processor is accessed through memory-mapped I/O registers.
The CPU reads and writes these addresses to load shader programs, set register
values, and control execution:

{{snippet:fpga/firmware/driver.c:mmio-map}}

The Borg peripheral occupies 16 words starting at `0x080000C0`: 8 FP16 registers
(r0–r7), 6 instruction memory words, and a control/status register. PSRAM
provides shared memory between the CPU and the RP2040 host.

## FPU Helper Functions

The driver provides convenience functions that program the instruction memory
and invoke the FPU for single operations:

{{snippet:fpga/firmware/driver.c:fpu-helpers}}

Each helper loads a one-instruction shader, writes the operands to registers,
triggers execution, and reads back the result. The `borg_run()` function handles
the start/poll/wait protocol.

## The Triangle Application

The application is structured like a minimal Vulkan program — define vertices,
set up shaders, and issue a draw call:

{{snippet:fpga/firmware/triangle.c:triangle-app}}

Vertices are defined in normalized coordinates with per-vertex RGB colors, all
encoded as FP16. The driver handles the full pipeline: vertex shading → screen-space
translation → rasterization → fragment shading → framebuffer output.
