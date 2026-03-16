<!---

This file is used to generate your project datasheet. Please fill in the information below and delete any unused
sections.

You can also include images in this folder and reference them in the markdown. Each image must be less than
512 kb in size, and the combined size of all images must be less than 1 MB.
-->

## How it works

Borg is a tiny GPU built around a RISC-V SoC (TinyQV). It renders a 10-frame animation of a rotating RGB triangle, fully self-contained in firmware — no host-side rendering needed.

### Architecture

- **CPU**: TinyQV (RV32EC), runs the application and GPU driver firmware
- **GPU core (Borg)**: FP16 FMA unit with 16 registers, 8-instruction IMEM, MMIO-accessible
- **Shaders**: Compiled from GLSL → SPIR-V → SPIR-B (custom binary format), embedded in firmware

### Rendering Pipeline

1. **Vertex shader** — rotates triangle vertices using sin/cos (computed via LUT)
2. **Triangle setup** — computes edge vectors and 1/area (Newton-Raphson reciprocal on the FPU)
3. **Rasterization** — evaluates edge functions per pixel to determine coverage
4. **Fragment shader** — interpolates per-vertex RGB colors using barycentric coordinates (runs 3× per pixel, once per channel)
5. **Framebuffer** — 16×16 RGB (FP16), written to PSRAM for host readback

### Software Stack

The firmware is structured like a Vulkan application:

```c
const borg_vertex_t vertices[3] = {
    { .pos = { ... }, .color = { FP16_ONE,  FP16_ZERO, FP16_ZERO } },  // red
    { .pos = { ... }, .color = { FP16_ZERO, FP16_ONE,  FP16_ZERO } },  // green
    { .pos = { ... }, .color = { FP16_ZERO, FP16_ZERO, FP16_ONE  } },  // blue
};

int main() {
    borg_init(vert_borg, ..., frag_borg, ...);
    for (int frame = 0; frame < 10; frame++) {
        borg_set_angle(&draw, angle);
        borg_cmd_draw(&draw, vertices, frame);
        borg_present(frame);
        angle = fp16_add(angle, FP16_36DEG);
    }
}
```

## How to test

In `./fpga`:

```
make triangle
```

This programs the firmware, boots the FPGA, waits for all 10 frames to render, then reads back 10 PPM images (`triangle_00.ppm` through `triangle_09.ppm`).

## External hardware

- QSPI PMOD from the Tiny Tapeout store
- For FPGA development: pico-ice board and Raspberry Pi Debug Probe
