# The Software Driver

The firmware running on Hutt provides a Vulkan-like API for rendering triangles.
It consists of a driver library (`borg_driver.c`, `borg_fpu.c`, `borg_raster.c`) and an application (`borg_triangle.c`).

## Memory-Mapped Hardware

The Borg shader processor is accessed through memory-mapped I/O registers.
The CPU reads and writes these addresses to load shader programs, set register
values, and control execution:

{{snippet:software/borg/borg_driver.c:mmio-map}}

The Borg peripheral is accessed at `0x08000C00` (BORG_BASE). It exposes 32 FP16 general-purpose registers (r0–r31), 31 usable instruction memory words, a control/status register, and a full RDL-generated register block covering tile buffer, texture, sequencer, DMA, and flush control. DRAM provides shared memory between the Hutt CPU and the GPU.

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

To reduce DRAM bandwidth, Borg includes a 16-pixel **Hardware Tile Buffer**.
Each pixel in the buffer stores both RGB color and a 16-bit Z-depth value.

During rasterization, the fragment shader computes the Z-depth for the current
pixel. The hardware then performs an automatic **depth test** against the value
stored in the tile buffer. If the new pixel is closer, the color and depth are
updated in the buffer.

Once a tile (typically 4×4 or 16×1 pixels) is complete, the CPU or DMA engine
flushes the buffer to DRAM in a single burst write. This avoids the "read-modify-write"
penalty of performing depth testing directly in DRAM.

```c
// Accessing the tile buffer via MMIO
BorgGpuRegs.tile_ctrl.read_idx = i;
uint32_t rg = BorgGpuRegs.tile_rg;
uint32_t bz = BorgGpuRegs.tile_bz;
```

## Texturing

Borg supports **hardware-assisted texture mapping**. While the final texel
fetch still happens in firmware (to avoid complex DRAM controller logic),
the hardware handles the most expensive part of the coordinate calculation.

### Morton Encoding

Textures are stored in DRAM using a **Morton order** (Z-order curve) layout.
This significantly improves cache locality compared to a linear scanline layout.
The hardware `MortonEncode` unit automatically converts interpolated `U` and `V`
coordinates into a linear memory offset:

{{snippet:hardware/borg/src/Borg.scala:mmio}}

### Texel Lookup

The firmware simply reads the pre-calculated `tex_addr_morton` from the MMIO
status registers and uses it as an offset into the texture data in DRAM:

```c
uint16_t offset = BORG_GPU->tex_addr & 0xFFFF; /* bits [15:0] = Morton index */
r = DRAM_IN(tex_base + offset * 3 + 0);
g = DRAM_IN(tex_base + offset * 3 + 1);
b = DRAM_IN(tex_base + offset * 3 + 2);
```

### Performance

Hardware acceleration reduces the per-pixel cost significantly:

| Operation                | Implementation | Cost |
|--------------------------|----------------|------|
| UV Interpolation         | Fragment Shader| 0 extra cycles |
| Morton Encoding          | **Hardware**   | 0 extra cycles |
| Depth Test               | **Hardware**   | 0 extra cycles |
| Texel Read               | Firmware       | 3 DRAM reads |

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
# ==> borgvk: passed 6887 of 1647405 mandatory Vulkan CTS tests = 0.418%
```

The default target runs five `dEQP-VK.api.*` classes (~9,276 cases combined):

| Test class | Pass | Total | 0 failures? | What it validates |
|---|---|---|---|---|
| `api.info.*` | 5,936 | 8,182 | ✅ | Device/format/limit/feature reporting |
| `api.format_feature_flags2.*` | 184 | 184 | ✅ | `VkFormatProperties3` / 64-bit feature flags |
| `api.device_init.*` | 224 | 236 | ✅ | `VkDevice` creation and property queries |
| `api.null_handle.*` | 23 | 24 | ✅ | `destroy(VK_NULL_HANDLE)` no-op behaviour |
| `api.granularity.*` | 520 | 650 | ✅ | Image/buffer granularity queries |
| **Total** | **6,887** | **9,276** | ✅ | |

borgvk is intentionally narrow — it renders a single hand-compiled cube over the
serial link, not arbitrary geometry — so the cases that pass are the
**query/setup** ones that read back the driver's reported capabilities or create
Vulkan objects, never rendering. Rendering classes (`dEQP-VK.draw.*`, `.pipeline.*`,
`api.smoke.triangle`, …) drive the cube-only serial path and therefore fail;
this is an honest "the Vulkan API surface works" data point, **not** a
conformance claim.

Run a specific slice with `make vulkan-cts VK_CTS_CASE='dEQP-VK.api.granularity.*'`;
`VK_CTS_CASE` accepts comma-separated globs (deqp-vk `--deqp-case` syntax).

### Bugs the CTS caught

Pointing a real conformance harness at the driver immediately surfaced a genuine
bug: `vkGetPhysicalDeviceSparseImageFormatProperties2` was an unimplemented
(`NULL`) dispatch entry, so *any* call segfaulted inside the Mesa runtime's common
shim. Implementing it (Borg has no sparse residency, so it reports zero
properties) turned the `api.info.sparse_image_format_properties2.*` group from a
crash into ~1,500 passing cases.

A second pass fixed `VkFormatProperties3` pNext propagation (~1,849 cases),
YCbCr format feature restrictions, `VK_FORMAT_UNDEFINED` handling, 3D image
`maxArrayLayers=1`, `textureCompressionBC`, `R32_SINT`/`R32_UINT` storage atomics,
MSAA `sampleCounts` consistency for 2D-optimal/cube-compatible/YCbCr images, and
the full set of required Vulkan 1.0–1.3 limits. Result: **5,936** passing the
`api.info.*` slice.

A third pass added `VK_FORMAT_FEATURE_2_SAMPLED_IMAGE_DEPTH_COMPARISON_BIT` for
depth formats in the `VkFormatProperties3` path, and implemented the missing
`CreateBufferView`/`DestroyBufferView`, `CreateEvent`/`DestroyEvent`/`GetEventStatus`/
`SetEvent`/`ResetEvent`, and `GetDescriptorSetLayoutSupport` dispatch entries — all
were weak-NULL symbols that crashed on any call. These fixes unlocked the
`format_feature_flags2`, `null_handle`, `device_init`, and `granularity` classes,
bringing the total to **6,887 passing, 0 failing**.

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
borgvk ICD, preloads the DRM shim, runs the slice, and prints the
`passed N of <mandatory total>` summary. Run a different slice with
`make vulkan-cts VK_CTS_CASE='dEQP-VK.api.device_init.*'` (any case glob), or point
at a checkout elsewhere with `make vulkan-cts VK_GL_CTS=/path/to/VK-GL-CTS`.
