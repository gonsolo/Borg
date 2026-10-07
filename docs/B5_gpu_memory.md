# GPU Memory: Allocating VkDeviceMemory Through the DRM Driver

Status: design note, written 2026-10-07. Nothing here is implemented. It is a reading of
`mesa/src/borg/vulkan/borgvk_memory.c`, `mesa/src/borg/drm/borg_shim.c`,
`software/linux/overlay/drivers/gpu/drm/borg/borg.rs`, `software/borg/borg_layout.h` and
`software/linux/borg.dts`. Claims marked *(to verify)* have not been checked.

## Why

A Vulkan driver gets device memory from its kernel driver. Borg's does not on the board:

- On the host, `vkAllocateMemory` calls `GEM_CREATE` and `GEM_MMAP` on a **shim**
  (`libborg_drm_shim.so`, preloaded by `scripts/cts_one.sh`), which backs each buffer with
  anonymous host memory. It has no limit, so it can never run out.
- On the board, the Rust render node has five ioctls (info, register write and read, GPU-memory
  write and read) and **no GEM**. `borgvk` finds no device with GEM, falls back to `malloc`, and
  the sink copies data into the 5 MB GPU region at submit time.

So `dEQP-VK.memory.allocation` and `memory.mapping` pass today against a shim, not against the
driver that ships. The conformance claim should rest on the real driver.

## What exists

- **GPU region:** 5 MB (`0x500000`) at CPU `0x01000000`, `no-map` in the device tree. Hutt has no
  data cache, so CPU and GPU see the same bytes without flushes *(to verify for the Borg's write
  path)*.
- **Fixed anchors** (`borg_layout.h`, Tiny layout, offsets inside the region): descriptors
  `0x4A80`, textures `0x5680` to `0x85680`, framebuffer at `0x85680` (128 x 128 RGB565 = 32 KiB).
  The Z buffer anchor (`0x4400000`) is outside the region: Tiny has no depth flush. The fixed
  part therefore ends below `0x90000`; about **4.4 MiB are free**.
- **Two uAPIs:** `mesa/include/drm-uapi/borg_drm.h` (older, cube-specific: `GEM_CREATE`,
  `GEM_MMAP`, `SETUP`, `SUBMIT` ...) and `software/linux/overlay/include/uapi/drm/borg_drm.h`
  (the five ioctls). They disagree; this note adds GEM to the second.

## Design

### Kernel (Rust driver)

New ioctls in the render-node uAPI:

| ioctl | in | out |
|---|---|---|
| `DRM_BORG_GEM_CREATE` | `size`, `flags` (0) | `handle`, `offset` (inside the region) |
| `DRM_BORG_GEM_MMAP` | `handle` | `mmap_offset` |
| `DRM_IOCTL_GEM_CLOSE` | `handle` | (standard) |

`drm_borg_info` gains `mem_free_base`, the first offset the allocator may hand out, so userspace
can compute the real heap size (`mem_size - mem_free_base`) instead of assuming one.

- **Allocator:** 4 KiB pages over `[mem_free_base, mem_size)`, first fit over a sorted free list
  that coalesces on free, under one mutex. About 1,100 pages, so a bitmap would also do; the list
  keeps large allocations cheap. `size == 0` is `EINVAL`; no space is `ENOMEM`.
- **Lifetime:** the buffer is owned by its GEM handle; closing the handle or the file frees it.
  The kernel never reads the contents.
- **mmap:** map the buffer's pages of the reserved region into the process
  (`remap_pfn_range`, as `/dev/mem` did for `replay_linux`), page aligned, shared. Whether the
  Rust DRM abstractions in 7.2 support a carve-out-backed GEM object *(to verify)*; if not, a
  small hand-written object table with its own `mmap` handler replaces them. The driver stays
  thin: an allocator and a mapping, no GPU knowledge.
- **Fixed anchors stay.** `borg_core` still reads and writes its fixed layout through
  `MEM_WRITE`/`MEM_READ`; GEM buffers never overlap it because they start at `mem_free_base`.
  Making `borg_core` use allocated addresses is a separate, larger change and not needed here.

### Mesa (borgvk)

- `borgvk_AllocateMemory` already has the `drm_fd >= 0` branch (`GEM_CREATE`, `GEM_MMAP`, `mmap`);
  it works unchanged once the Rust driver offers the ioctls. `ENOMEM` already maps to
  `VK_ERROR_OUT_OF_DEVICE_MEMORY`.
- The advertised heap comes from `drm_borg_info` (about 4.4 MiB on the board), not the fixed
  256 MiB, and `vkAllocateMemory` keeps a count of live bytes for a clean out-of-memory result.
- Memory types stay one: device local, host visible, host coherent (no CPU cache).
- The shim gets the same allocator behaviour (same granularity, same limit) so host CTS runs see
  the board's out-of-memory behaviour.

## Test plan, cheapest first

1. **QEMU, seconds.** Extend `software/linux/tests/borg_drm_test.c` (fake Borg in reserved RAM):
   create and close, double close, size 0, exhaustion then recovery, fragmentation and
   coalescing, mmap read and write through the mapping, and the same through `MEM_READ`.
2. **Allocation stress on the render node.** A static rv64 program that repeats what
   `allocation.basic` and `allocation.random` do (block sizes 64 B to 1 MiB, 1 to 1,000 blocks,
   forward, reverse and mixed free orders, seeded random allocate and free) directly on the ioctls.
   Runs on QEMU and on the board.
3. **Shim parity.** The host CTS with the shim limited to the board's heap size: the `memory.*`
   groups then see real out-of-memory results.
4. **CTS against the real driver (stretch, not planned).** `deqp-vk` is about 100 MB and needs C++,
   so it cannot run on Hutt (13.7 MB free). It could run in `qemu-system-riscv64` with a large
   RAM and a riscv64 build of the CTS and borgvk on the Rust-driver kernel. Expensive; only if a
   sponsor needs a CTS number from the real driver stack.

## Open questions

- How `vkMapMemory` should behave on a buffer the GPU also writes (framebuffer reads): the memory
  is coherent only if no CPU cache sits between *(to verify)*.
- Whether any CTS `memory.*` case in the minimal-Vulkan scope asks for more than 4.4 MiB in one
  allocation and expects success; the random tests treat out-of-memory as acceptable *(to verify
  in `vktMemoryAllocationTests.cpp`)*.
- Whether to keep the older cube-specific uAPI in `mesa/include/drm-uapi` at all once the shim
  and the Rust driver share one.
