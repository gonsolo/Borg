# The Software Driver

The firmware running on Hutt provides a Vulkan-like API for rendering triangles.
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

### 2. Rasterize Shader (1 invocation per pixel)

For each pixel in the bounding box, the rasterizer shader evaluates all three edge functions simultaneously. Pixel integer coordinates are automatically expanded to FP16 by the hardware `coordLut` and injected into `r30`/`r31`. If all three edges are valid, the hardware `inside_flag` is set.

### 3. Fragment Shader (1 invocation per visible pixel)

For pixels inside the triangle, the hardware FSM auto-chains to the fragment shader. The fragment shader is a unified program (`shader.frag`) compiled with the Poletto & Sarkar linear scan register allocator. Taking up to 29 registers, it simultaneously performs barycentric interpolation for all outputs:

- Per-vertex RGB color blending
- Z-buffer depth interpolation
- UV texture coordinate interpolation

### Summary

| Stage              | Shader        | Invocations           |
|--------------------|---------------|-----------------------|
| Vertex shading     | `vert.s`      | 3 per triangle        |
| Rasterization      | `rasterize.s` | 1 per pixel           |
| Fragment shading   | `shader.frag` | 1 per visible pixel   |
| **Total per pixel**|               | **1 to 2 shader runs**|

## Tile Buffer and Z-Buffer

To reduce PSRAM bandwidth, Borg includes a 16-pixel **Hardware Tile Buffer**.
Each pixel in the buffer stores both RGB color and a 16-bit Z-depth value.

During rasterization, the fragment shader computes the Z-depth for the current
pixel. The hardware then performs an automatic **depth test** against the value
stored in the tile buffer. If the new pixel is closer, the color and depth are
updated in the buffer.

Once a tile (typically 4×4 or 16×1 pixels) is complete, the CPU or DMA engine
flushes the buffer to PSRAM in a single burst write. This avoids the "read-modify-write"
penalty of performing depth testing directly in PSRAM.

```c
// Accessing the tile buffer via MMIO
BorgGpuRegs.tile_ctrl.read_idx = i;
uint32_t rg = BorgGpuRegs.tile_rg;
uint32_t bz = BorgGpuRegs.tile_bz;
```

## Texturing

Borg supports **hardware-assisted texture mapping**. While the final texel
fetch still happens in firmware (to avoid complex PSRAM controller logic),
the hardware handles the most expensive part of the coordinate calculation.

### Morton Encoding

Textures are stored in PSRAM using a **Morton order** (Z-order curve) layout.
This significantly improves cache locality compared to a linear scanline layout.
The hardware `MortonEncode` unit automatically converts interpolated `U` and `V`
coordinates into a linear memory offset:

{{snippet:hardware/borg/src/Borg.scala:mmio}}

### Texel Lookup

The firmware simply reads the pre-calculated `tex_addr_morton` from the MMIO
status registers and uses it as an offset into the texture data in PSRAM:

```c
uint16_t offset = BorgGpuRegs.tex_addr_morton;
r = PSRAM_IN(tex_base + offset * 3 + 0);
g = PSRAM_IN(tex_base + offset * 3 + 1);
b = PSRAM_IN(tex_base + offset * 3 + 2);
```

### Performance

Hardware acceleration reduces the per-pixel cost significantly:

| Operation                | Implementation | Cost |
|--------------------------|----------------|------|
| UV Interpolation         | Fragment Shader| 0 extra cycles |
| Morton Encoding          | **Hardware**   | 0 extra cycles |
| Depth Test               | **Hardware**   | 0 extra cycles |
| Texel Read               | Firmware       | 3 PSRAM reads |

## The Triangle Application

The application renders two overlapping triangles to demonstrate
texturing and z-buffering. The front triangle uses per-vertex color
interpolation, while the back triangle is textured with a 32×32
RGBW test pattern:

{{snippet:software/borg/borg_triangle.c:triangle-app}}

The front triangle's color-interpolated RGB appears in the center,
while the back triangle's RGBW texture is visible around the edges.
The z-buffer ensures correct occlusion regardless of draw order.

## Vulkan Conformance (CTS)

The host-side Vulkan driver **borgvk** (a native Mesa ICD that runs the
unmodified Khronos `cube.c` and ships each frame to the FPGA over serial) is
exercised by the official Khronos Conformance Test Suite,
[VK-GL-CTS](https://github.com/KhronosGroup/VK-GL-CTS) (`dEQP-VK`):

```bash
make vulkan-cts
# ==> borgvk: passed 3 of 1647405 mandatory Vulkan CTS tests (ran 3)
```

borgvk is intentionally narrow — it renders a single hand-compiled cube over the
serial link, not arbitrary geometry — so the only CTS cases that survive are the
**setup-only** ones that create Vulkan objects but never render:

| Test | What it validates |
|------|-------------------|
| `dEQP-VK.api.device_init.create_device.basic` | Brings up a conformant `VkDevice` |
| `dEQP-VK.api.smoke.create_sampler`             | Instance + device + sampler creation |
| `dEQP-VK.api.smoke.create_shader`              | Shader-module creation |

These exercise borgvk's instance/device/object-creation paths through the Mesa
runtime *without* touching the serial→FPGA pipeline, so the Khronos harness
returns a genuine `Pass`. Rendering classes (`dEQP-VK.draw.*`, `.pipeline.*`,
`api.smoke.triangle`, …) drive the cube-only serial path and therefore fail;
this is an honest "the Vulkan API surface works" data point, **not** a
conformance claim.

### Building the test runner

`deqp-vk` is built once from a VK-GL-CTS checkout. Two things to note: force the
native compiler (the riscv64 cross toolchain is the default `CC`/`CXX` inside the
Nix shell), and select the headless Vulkan target (no GL/X11/EGL dependencies):

```bash
cd $VK_GL_CTS                              # default: ~/src/VK-GL-CTS
python3 external/fetch_sources.py
CC=gcc CXX=g++ cmake -S . -B build -GNinja -DCMAKE_BUILD_TYPE=Release \
     -DDEQP_TARGET=vulkan_headless -DSELECTED_BUILD_TARGETS=deqp-vk
cmake --build build --target deqp-vk
```

`make vulkan-cts` then sets up the Vulkan loader, points `VK_DRIVER_FILES` at the
borgvk ICD, preloads the DRM shim, runs the survivor case list, and prints the
`passed N of <mandatory total>` summary. Override the checkout location with
`make vulkan-cts VK_GL_CTS=/path/to/VK-GL-CTS`.
