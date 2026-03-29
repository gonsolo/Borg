# The Software Driver

The firmware running on TinyQV provides a Vulkan-like API for rendering triangles.
It consists of a driver library (`borg_driver.c`, `borg_fpu.c`, `borg_raster.c`) and an application (`borg_triangle.c`).

## Memory-Mapped Hardware

The Borg shader processor is accessed through memory-mapped I/O registers.
The CPU reads and writes these addresses to load shader programs, set register
values, and control execution:

{{snippet:software/borg/borg_driver.c:mmio-map}}

The Borg peripheral occupies 16 words starting at `0x080000C0`: 8 FP16 registers
(r0–r7), 6 instruction memory words, and a control/status register. PSRAM
provides shared memory between the CPU and the RP2040 host.

## FPU Helper Functions

The driver provides convenience functions that program the instruction memory
and invoke the FPU for single operations:

{{snippet:software/borg/borg_fpu.c:fpu-helpers}}

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

## Texturing

The driver supports firmware-only texture mapping, with no additional
hardware. Textures are stored as FP16 RGB data in PSRAM and sampled
per-pixel using interpolated UV coordinates.

### UV Interpolation

Each vertex carries `uv[2]` texture coordinates (FP16, range 0–1).
The existing `borg_frag_channel` function interpolates U and V per pixel
using the same barycentric coordinates as color and depth — two additional
shader invocations per textured pixel.

### Texel Lookup

The interpolated UV values are multiplied by the texture dimensions
using the Borg FPU (`borg_fp16_mul`), converted to integer indices
via `fp16_to_uint`, and used to read RGB texel data from PSRAM:

```c
int tx = fp16_to_uint(borg_fp16_mul(u, tex_width_fp16));
int ty = fp16_to_uint(borg_fp16_mul(v, tex_height_fp16));
int texel = tex_offset + (ty * tex_width + tx) * 3;
r = PSRAM_IN(texel);
g = PSRAM_IN(texel + 1);
b = PSRAM_IN(texel + 2);
```

### PSRAM Layout

Texture data must not overlap with the framebuffer output region.
`PSRAM_OUT(n)` maps to `PSRAM_IN(n + 32)`, so the framebuffer and
z-buffer occupy `PSRAM_IN` words 32 through ~4128. Texture data is
placed above this range (e.g. offset 4200).

### Performance

Texturing adds per visible pixel:

| Operation                | Count |
|--------------------------|-------|
| UV interpolation         | 2 shader runs (frag) |
| UV × dimension multiply  | 2 FPU calls |
| Texel read               | 3 PSRAM reads |
| **Total per textured pixel** | **11 shader runs + 2 FPU + 3 PSRAM reads** |

## The Triangle Application

The application renders two overlapping triangles to demonstrate
texturing and z-buffering. The front triangle uses per-vertex color
interpolation, while the back triangle is textured with a 32×32
RGBW test pattern:

{{snippet:software/borg/borg_triangle.c:triangle-app}}

The front triangle's color-interpolated RGB appears in the center,
while the back triangle's RGBW texture is visible around the edges.
The z-buffer ensures correct occlusion regardless of draw order.
