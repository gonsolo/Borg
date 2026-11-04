# Device memory, command lists and descriptors

Status: plan, 2026-11-04. Steps are built in order; the driver works after every commit. Built:
1.1 to 1.6 (heap, tiled images, attachments, sampled images and compute buffers in place); of step 3,
graphics uniform and storage buffers in place through the draw's binding table (root table word 3: base
and offset limit per binding, as compute's table); 1.7's vertex pulling for triangle pipelines (binding
table slot 128 + vertex binding); of step 2,
the list builder in the driver (`list.rs`) for the simulator and the device (`dev.rs`, the DRM
render node or a pipe to `direct_sim`), compute jobs on the same raw device; `borg_core.c` and
`direct_sim`'s other modes are gone.

## Why

The other Mesa Vulkan drivers (v3dv, panvk, nvk, radv) bind `VkDeviceMemory` to memory the GPU
addresses, record command lists into that memory, and keep descriptor sets there. borgvk does
none of the three:

- **Memory:** buffers and images live in host memory. Every pass copies its attachments into the
  simulator's fixed framebuffer, depth and stencil planes and back (`sim.rs`, `copy_in`,
  `copy_out`); draws copy vertex data into the draw heap; compute copies every binding into a
  2 MB window, decoding images to RGBA32.
- **Submission:** a command buffer records borgvk's own byte stream, replayed at submit in a
  simulator process or over serial; every submit waits for the GPU.
- **Descriptors:** bindings are flattened into fixed slots and windows at submit.

The copies set most of the size limits the CTS hits: a depth pass is capped at 1M pixels (the
plane holds 1024 x 1024), a 4096 x 4096 colour target overlaps the heap, a compute storage image
is cut at its window (the cube `image.atomic_operations` intermediates are 9.8 MB as RGBA32),
and a set holds at most 32 windows. They also cost time on every pass.

## The model

One address space, as on the board, where CPU and Borg share SDRAM:

- **Device heap.** All `VkDeviceMemory` comes from one heap at a fixed GPU base, sized as the
  advertised heap. In the simulator the heap is a memfd the driver maps and every simulator
  process maps at the same GPU base; on the board it is the DRM driver's carve-out
  ([B5](B5_gpu_memory.md)). An allocation knows its GPU address.
- **Images in place.** Optimal-tiling images use the hardware's 4x4-tiled layout
  ([B2](B2_texture_unit.md), "Layouts"): the flusher writes it, the loader and the texture unit
  read it. Colour, depth and stencil attachments render at the image's address
  (`FLUSH_ZB_BASE`, `FLUSH_SB_BASE`, the colour base); depth and stencil of a combined format are
  two planes of the image. Linear images stay row-major for the host.
- **Buffers in place.** Vertex, index, uniform and storage buffers are read at their GPU address.

## Steps

### 1. Device memory

1. direct_sim maps a second memory, the device heap, at `BORG_DEVICE_BASE`; the GPU address space
   grows to 512 MB (private 128 MB at 0, heap from 256 MB).
2. borgvk allocates `VkDeviceMemory` from the heap (first fit, 4 KiB pages, freed pages read as
   zeros) in the simulator backends; the advertised heap is its size. The copies still run.
3. Images tiled: optimal images take the tiled layout, depth and stencil as two planes (D16 as
   16 bits, D24 and D32 as FP32, stencil a byte); copies, clears, blits, resolves, texture uploads
   and the pass copies go through one texel accessor (`texels.rs`). Images with storage usage
   stay linear until 7: compute and fragment shaders address them by rows. The driver corpus
   hashes images in the linear layout, so its hashes do not depend on the layout.
4. Attachments in place: a pass renders at the images' addresses; `copy_in`, `copy_out` and the
   depth area limit go. Strips render their rows into the same image. A clear of a render area
   that is not tile aligned becomes a load and a host clear of the rectangle.
5. Sampled images in place: texture descriptors point at the image.
6. Compute in place: the compute job maps the heap; storage buffers and storage images are read
   at their address. borgc bakes each binding's window address into the code
   (`compute.rs`, `SLOT_BASE + slot * SLOT_BYTES`), so the shader first takes binding bases and
   sizes from a per-dispatch table: the first part of step 3.
7. Draw buffers in place: vertex attributes, index buffers and uniform windows at the buffer's
   GPU address. Uniform and storage buffers go through the draw's binding table (done). The
   vertex fetch is a typed fetch from a tight 4096-wide linear image the driver converts at draw
   time; it becomes vertex pulling in the shader (decided 2026-11-04, in place of a strided fetch
   in the texture unit): a NIR pass at pipeline creation turns each attribute into loads at
   `base + index * stride + offset`, with the binding's base and limit from the draw's table,
   and converts the format in the shader. No RTL change, every vertex format the same way. Then
   index buffers in place, and line and point expansion off the CPU (it reads the indices).

Check after each: driver corpus identical or every difference explained, borgc corpus, cube
capture, `meson test --suite borg`; render_to_image huge and `image.atomic_operations` after 4
and 6.

### 2. Command lists

The driver writes Borg's render lists (`BORG_LIST_SPI` entries, state and parameter blocks,
[B1](B1_geometry_front_end.md)) into device memory while recording, as v3dv writes control lists.
A submit hands the list addresses to the backend, which starts the sequencer and signals the
fence when it is done; the captured stream, its replay table and `sim_run_stream` go. On the
board this is a DRM submit ioctl with a fence.

What moves from `borg_core.c` into borgvk, built at record time into device memory the command
buffer owns:

- per pipeline stage: the code with its HALT and the constant window (`list_stage`);
- per draw: the state block when the state changed and the parameter block (`list_block`,
  `borg_core_list_draw`), the vertex attribute and texture descriptor tables, the root tables;
- per render: the entries, and the render's own registers (`list_trigger`): attachments,
  pitch, clear values, `TILE_LOAD`, bins and setup store, windows and strips.

A backend then runs a render as register writes and `SEQ_TRIGGER`: direct_sim through a
"write these registers, run" request, the board through a DRM submit. The packet decoder, the
list builder and the 0xB0-0xC8 packets go from the host path. Work done at submit today moves to
record time or into shaders: vertex format conversion, line and point expansion, clears of
render areas that are not tile aligned.

Building the lists while recording needs draws that read memory by its GPU address at run time:
today they copy what they read when the runtime replays the commands at submit (uniform data as
heap writes, vertex data, descriptors), which a recorded list would freeze. So step 3 and 1.7
come before the record-time builder.

The cube path is gone (2026-11-04): the unmodified cube.c renders through the general path on
the simulator (`scripts/vkcube_host.sh`, golden `simulation/golden/vkcube_00.ppm`), and
`cube.rs`, the 0xAD/0xAE/0xAF packets, the cube ioctls, the serial transport and the firmware
were deleted. The board comes back through the DRM driver with command lists.

### 3. Descriptors in memory

Descriptor sets become device memory holding hardware descriptors: texture and sampler
descriptors as the texture unit reads them, buffers as address and range. A shader finds a set
through a table of set addresses; the slot flattening, `BORGVK_MAX_BINDINGS` and the 32-window
limit go. This is stage 1 to 4 of the ISA v2 plan ([B8](B8_isa_v2.md)); its hardware part
(buffer access through a descriptor, a descriptor cache) is measured for area on the ULX3S.

First piece, storage images and texel buffers in place (decided 2026-11-04): today they are
windows (a size header, then texels the driver decodes in and encodes back). Instead the
binding table entry points at the view's 64-byte texture descriptor (B2); a load is a
`texelFetch` through the texture unit (every format), a store or atomic an address from the
descriptor's base and row pitch with the format packed in the shader (Vulkan 1.0 storage image
stores name their format). borgc gets a global access (`LW`/`SW`/atomic at a computed
address); image robustness is not a Vulkan 1.0 requirement.

## Left out

- A GPU MMU: one process at a time is what Vulkan 1.0 needs.
- The host-over-serial backend: it ends with the cube path; the board path is the self-hosted
  driver ([B5](B5_gpu_memory.md)).
