# Device memory, command lists and descriptors

Status: plan, 2026-11-04. Steps are built in order; the driver works after every commit.

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
2. borgvk allocates `VkDeviceMemory` from the heap (first fit, 4 KiB pages) in the simulator
   backends; the advertised heap is its size. Behaviour unchanged: the copies still run.
3. Draw buffers in place: vertex attributes, index buffers and uniform windows point at the
   buffer's GPU address; the draw heap holds only what the driver builds (state, converted
   attributes).
4. Images tiled: optimal images take the tiled layout; copies, clears, blits, resolves and
   texture uploads address it through `borg_layout`.
5. Attachments in place: a pass renders at the images' addresses; `copy_in`, `copy_out` and the
   depth area limit go. Strips render their rows into the same image.
6. Sampled images in place: texture descriptors point at the image.
7. Compute in place: the compute job maps the heap; storage buffers and R32/RGBA8 storage images
   are read at their address through the window base table (step 3 makes it general).

Check after each: driver corpus identical or every difference explained, borgc corpus, cube
capture, `meson test --suite borg`; render_to_image huge and `image.atomic_operations` after 5
and 7.

### 2. Command lists

The driver writes Borg's render lists (`BORG_LIST_SPI` entries, state and parameter blocks,
[B1](B1_geometry_front_end.md)) into device memory while recording, as v3dv writes control lists.
A submit hands the list addresses to the backend, which starts the sequencer and signals the
fence when it is done; the captured stream, its replay table and `sim_run_stream` go. On the
board this is a DRM submit ioctl with a fence.

### 3. Descriptors in memory

Descriptor sets become device memory holding hardware descriptors: texture and sampler
descriptors as the texture unit reads them, buffers as address and range. A shader finds a set
through a table of set addresses; the slot flattening, `BORGVK_MAX_BINDINGS` and the 32-window
limit go. This is stage 1 to 4 of the ISA v2 plan ([B8](B8_isa_v2.md)); its hardware part
(buffer access through a descriptor, a descriptor cache) is measured for area on the ULX3S.

## Left out

- A GPU MMU: one process at a time is what Vulkan 1.0 needs.
- The host-over-serial backend (cube demo): it keeps its copies until the board has GEM with GPU
  addresses ([B5](B5_gpu_memory.md)); then the board path is the self-hosted driver.
