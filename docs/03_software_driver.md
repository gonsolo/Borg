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

## Rendering Pipeline and Shader Invocations

For each triangle, the driver runs the following pipeline stages on Borg:

### 1. Vertex Shader (3 invocations per triangle)

Run once per vertex. Transforms positions (e.g. rotation) using uniforms
set by the CPU.

### 2. Rasterize Shader (3 invocations per pixel)

For each pixel in the framebuffer, the rasterize shader evaluates one edge
function per call. Three calls produce the edge values `e0`, `e1`, `e2`.
If all three are ≤ 0, the pixel is inside the triangle.

### 3. Fragment Shader (3 invocations per visible pixel)

For pixels inside the triangle, the fragment shader performs barycentric
interpolation of per-vertex attributes. It runs once per color channel
(R, G, B), computing `result = (e0 * c0 + e1 * c1 + e2 * c2) * inv_area`.

### 4. Depth Interpolation (1 invocation per visible pixel)

The fragment shader runs a fourth time to interpolate the vertex z-values
using the same barycentric coordinates. This produces per-pixel depth for
the z-buffer test.

### Summary

| Stage              | Shader       | Invocations          |
|--------------------|--------------|----------------------|
| Vertex shading     | `vert.s`     | 3 per triangle       |
| Rasterization      | `rasterize.s`| 3 per pixel          |
| Fragment shading   | `frag.s`     | 3 per visible pixel  |
| Depth interpolation| `frag.s`     | 1 per visible pixel  |
| **Total per pixel**|              | **7 shader runs**    |

## Z-Buffer

The driver implements depth testing entirely in firmware, with no hardware
changes to the Borg processor.

Each frame's PSRAM layout includes a z-buffer region alongside the RGB
framebuffer:

```text
[R G B] × (width × height) + [Z] × (width × height) + [DONE marker]
```

Before rendering, `borg_clear_zbuffer()` initializes the RGB framebuffer to
black and the z-buffer to `FP16_MAX_DEPTH` (0x7BFF). During rasterization,
each visible pixel's interpolated depth is compared against the stored value.
Pixels that are farther away are rejected:

```c
uint16_t old_z = PSRAM_OUT(zb_idx);
if (z < old_z) {
  PSRAM_OUT(zb_idx) = z;
  // write RGB to framebuffer
}
```

This allows correct rendering of overlapping triangles regardless of draw
order, at the cost of one extra PSRAM read/write and one shader invocation
per visible pixel.

## The Triangle Application

The application renders two overlapping triangles to demonstrate the z-buffer.
Vertices include position (x, y, z) and per-vertex RGB color, all FP16:

{{snippet:fpga/firmware/triangle.c:triangle-app}}

The front triangle is a small color-interpolated triangle (red/green/blue
vertices) at z = 0.2. The back triangle is a larger solid-red triangle at
z = 0.8. Despite being drawn second, the red triangle only appears where it
extends beyond the front triangle — the z-buffer correctly rejects pixels
where the closer triangle has already been rendered.
