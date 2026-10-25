# The Software Driver

Applications reach Borg through **borgvk**, a native Vulkan driver in Mesa
(`mesa/src/borg/vulkan/`), modelled on v3dv. It builds on Mesa's Vulkan runtime
for objects, synchronization and WSI, compiles shaders with borgc
([The Shader Compiler](02_compiler.md)), and turns command buffers into a stream
of packets: geometry, textures, shaders, state and draws. The same stream has
three destinations:

- **The ULX3S over serial.** On a host PC, borgvk sends the packets to the
  firmware kernel on the board (below), which feeds the hardware.
- **The direct simulator.** For conformance, borgvk hands the stream to
  `direct_sim`, a compiled model of the Borg RTL
  ([Simulation](06_simulation.md)); the Vulkan CTS runs on it.
- **Linux on the board.** A DRM render-node driver on the RV64 Hutt takes the
  same packets, so the unmodified `cube.c` runs on the board without a host
  ([Running on an FPGA](04_fpga.md)).

The rest of this chapter describes the firmware side on Hutt, then the
conformance testing.

## Memory-Mapped Hardware

The Borg shader processor is accessed through memory-mapped I/O registers.
The CPU reads and writes these addresses to load shader programs, set register
values, and control execution:

{{snippet:software/borg/borg_driver.c:mmio-map}}

The Borg peripheral is accessed at `0x08000C00` (BORG_BASE). It exposes 32 general-purpose float registers (r0–r31, FP32), 72 instruction memory words, a control/status register, and a full RDL-generated register block covering tile buffer, texture, sequencer, DMA, and flush control. DRAM provides shared memory between the Hutt CPU and the GPU.

## How a Draw Runs

The CPU no longer drives shaders per triangle. It writes a draw's state and
triggers it with `SEQ_TRIGGER`; the hardware does the rest: primitive assembly
(lists, strips, fans, indices, restart, instancing), one vertex shader run per
triangle, varyings stored with `SOUT` and read back with `FATTR`, homogeneous
setup without clipping, binning into tiles, and one fragment shader run per
covered 2x2 quad. [Geometry Front End](B1_geometry_front_end.md) has the details
and a step-by-step recipe.

## Tile Buffer, Depth and Stencil

Each tile is rendered in an on-chip **tile buffer** holding colour, FP32 depth
and stencil. Depth, stencil, scissor and blending are tested and applied in
hardware; the finished tile is flushed to memory in the attachment's format.
Formats beyond the native ones are raw attachments that the fragment shader packs
and blends through `TLD` ([Colour Attachment Formats](B3_colour_formats.md)).

```c
// Accessing the tile buffer via MMIO
BorgGpuRegs.tile_ctrl.read_idx = i;
uint32_t rg = BorgGpuRegs.tile_rg;
uint32_t bz = BorgGpuRegs.tile_bz;
```

## Texturing

Sampling is done by the texture unit: `TEX` and `TEXA` read texture and sampler
descriptors from memory and support 51 formats, mipmaps with implicit LOD, all
address modes and border colours, trilinear filtering, depth compare, gather,
`texelFetch` and offsets. The firmware's earlier role, fetching texels by Morton
address, is gone. See [Texture Unit](B2_texture_unit.md).

## The borgvk Kernel

Earlier firmware revisions baked a standalone two-triangle demo
(`borg_triangle.c`, since removed) directly into the image: a
per-vertex-color-interpolated front triangle over a textured back
triangle, exercising z-buffering and hardware texturing with no host
involved.

`borg_kernel.c` replaces that with a thin runtime driven entirely by
the borgvk Mesa driver over serial: it drains borgvk's packets
(0xAD MVP, 0xAE geometry, 0xAF texture rows, 0xB0 borgc-compiled
shaders, and the later state and draw packets) and feeds the hardware, so real, unmodified Vulkan
applications (e.g. Khronos's `cube.c`) render on the hardware instead
of a fixed demo scene. The z-buffer, texturing, and per-vertex
interpolation this exercised are unchanged — they're now driven by
whatever geometry and textures the host application uploads.

## The borgvk source

borgvk lives in `mesa/src/borg/vulkan/` and is written in Rust; only the generated entry
point tables, the `borgvk_burst_gen` tool and the static link probe are C. The crate in
`rust/` follows NVK's Rust code: bindgen reads the Mesa runtime headers named in
`rust/bindings.h`, cbindgen writes `borgvk_rust.h` for the C callers, and meson builds a
static library that `libvulkan_borg.so` links whole.

| Modules | Contents |
|---|---|
| `entrypoint`, `object`, `types`, `util` | the `entrypoint!` macro and handle casts, objects allocated in place, limits and plain data |
| `device`, `physical_device`, `wsi` | instance and devices, properties and format queries, presentation |
| `memory`, `image`, `sampler`, `descriptor_set`, `pipeline`, `query`, `sync`, `event` | the Vulkan objects |
| `cmd_buffer`, `render`, `clear`, `copy`, `texel`, `draw`, `dispatch` | command buffers and what they record |
| `queue`, `cube`, `state_upload`, `texture_upload`, `corpus` | submission, the cube path, the driver corpus recorder |
| `compiler`, `compile`, `nir_info`, `nir_passes`, `shader_cache` | shaders through borgc, or from a cache file on the board |
| `serial`, `transport`, `format`, `drm`, `hw`, `sim`, `sim_process` | wire packets and the back ends |

Image layout, tile addressing, the texel formats (generated from `TexFormat.scala`) and the
texture and sampler descriptors are a crate of their own, `borg_layout` in
`mesa/src/borg/layout/`.

Four things are easy to get wrong:

* The generated dispatch tables reference the entry points weakly, so a library member that
  holds only entry points is never linked and the runtime's fallback runs silently. The
  library is linked whole, and `meson test --suite borg` checks that every Rust entry point
  is in the `.so`.
* Recorded commands replay through `Device::cmd_dispatch`, which holds borgvk's own entry
  points and no-ops for the rest: an entry point that only forwards to `vk_common_*` must stay.
* Objects live in zeroed memory from the runtime's allocators and never move
  (`object::allocate`). No C struct points at a Rust one: bindgen cannot see Rust types.
* bindgen binds some libc calls to glibc's `*64` names, which musl lacks: the board build
  (`make -C software/vkcube`) fails to link where the host build passes.

Every change runs `meson test --suite borg` in a build with `-Dbuild-tests=true`, the driver
corpus (`scripts/borgvk_corpus/check.sh`, compared with a run of main) and the board build.

## Vulkan Conformance (CTS)

borgvk is tested with the Khronos Conformance Test Suite,
[VK-GL-CTS](https://github.com/KhronosGroup/VK-GL-CTS) (`dEQP-VK`), release
1.4.6.2, against the Vulkan 1.0 must-pass list. The cases run on the direct
simulator, many in parallel, through `scripts/cts_par.sh`:

```bash
MUSTPASS=1.0.2.6 MUSTPASS_GROUPS=1 JOBS=12 DIRECT=1 scripts/cts_par.sh 'dEQP-VK.api.*'
```

The `api` group passes without failures (tag `conformance/vk1.0-api-direct-sim`);
the per-group results and known limits are in
[Vulkan 1.0 Conformance Status](conformance/STATUS.md). These are results on a
simulation of the RTL, not a conformance submission.

`make vulkan-cts` still runs the small slice of `api` cases described below,
which was the first contact with the CTS.

### Bugs the CTS caught first

In the early serial-only days, when borgvk could render only the cube and just
the query and setup cases could pass, pointing the CTS at the driver
immediately surfaced a genuine bug: `vkGetPhysicalDeviceSparseImageFormatProperties2` was an unimplemented
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
