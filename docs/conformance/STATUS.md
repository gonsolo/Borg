# Vulkan 1.0 conformance status (2026-10-07)

What borgvk and Borg pass in the Khronos CTS today, what fails, and what the numbers do not say.
This is **not** a Khronos conformance submission.

## Scope

- CTS `vulkan-cts-1.4.6.2`, mustpass list 1.0.2.6. 96,109 of the 233,795 listed names exist in 1.4.6.2.
- Tier 1 (below). Device under test: borgvk (Mesa) on the **direct simulator**, which runs the Borg RTL through arcilator.
  No CPU, firmware or serial path; the Linux/DRM path on the board is not run through the CTS.
- Minimal Vulkan 1.0: optional extensions are not offered, so cases that need one report `NotSupported`.
- Results are reported as the CTS gives them. `NotSupported` is not a pass: it means the feature is absent.

## Test tiers

Results are labelled with the tier they were measured on; a number from one tier says nothing about another.

| Tier | Setup | Proves | Does not prove | Status |
|---|---|---|---|---|
| 1 | Host: `deqp-vk`, borgvk and the DRM shim on x86, Borg RTL in `direct_sim` (arcilator) | Vulkan driver and Borg hardware design, full CTS | The kernel driver, Hutt, the board's memory | **All results in this document** |
| 2 | QEMU rv64: Linux with the Rust DRM driver, borgvk built for rv64, QEMU's CPU instead of Hutt (`info` only so far) | The shipped kernel driver (GEM, whitelist, mmap) | Hutt timing, FPGA specifics | Driver tests in QEMU exist for the whitelist; the GEM allocator and shim parity are designed (`../B5_gpu_memory.md`), not built. Running the CTS through the real driver needs a QEMU-to-`direct_sim` bridge, not planned |
| 3 | ULX3S: Hutt, Linux, Rust DRM driver, borgvk, Borg | The whole stack on real hardware | Most CTS groups: `deqp-vk` is about 100 MB, the board has 13.7 MB free | `vkcube` renders (`../evidence/`); a short list of small test programs is planned, not a CTS run |

Order of work: tier 1 for all groups, the GEM allocator with its QEMU tests and shim parity alongside the
`memory.*` items, then tier 3 as a smoke subset.

## Results per tier, all groups

Tier 1 only (host `deqp-vk` + borgvk + DRM shim + `direct_sim`), CTS 1.4.6.2, as of 2026-10-10. Every row is a run of that
group alone with its own command; no mustpass filter unless stated. The earlier all-groups table (6.6% Pass) was
measured in a broken environment and is withdrawn. Groups not listed have **not been rerun**; that is not a result.

| Group | Cases | T1 Pass | T1 NotSupp | T1 Fail | Note |
|---|---:|---:|---:|---:|---|
| api (mustpass 1.0.2.6) | 278,342 | 64,866 | 213,472 | 0 | 4 QualityWarning |
| info | 21 | 18 | 3 | 0 | |
| memory.allocation | 202 | 202 | 0 | 0 | |
| memory.mapping | 4,466 | 811 | 3,655 | 0 | |
| pipeline.monolithic.sampler.view_type (1d, 2d) | 21,286 | 3,170 | 18,116 | 0 | after the stencil/alpha fix, whole subgroup |
| pipeline.monolithic.sampler (unnormalized 1d/2d, separate_stencil_usage, exact_sampling, max_sampler_lod_bias) | 6,465 | 1,155 | 5,310 | 0 | the 268 earlier failures rerun: all Pass |
| pipeline.monolithic.sampler.border_swizzle | 105,600 | 14,370 | 91,230 | 0 | all 88 formats, run in batches between fixes, not in one run |
| pipeline.monolithic.sampler (array, 3D, cube sample) | 240 | 215 | 25 | 0 | sample, not the whole group |
| pipeline.monolithic.sampler, regression set (rerun 2026-10-10) | 27,033 | 4,003 | 23,030 | 0 | view_type 1d, 2d (normalized and unnormalized) and separate_stencil_usage in one run, after all later changes |
| pipeline.monolithic.depth | 8,521 | 2,699 | 5,822 | 0 | whole subgroup, one run; lines are drawn as quads |
| pipeline.monolithic, tree order excluding sampler and depth (first 121,750 of 274,810) | 121,750 | 10,305 | 111,171 | 270 deferred | overnight 2026-10-11; open: RGBA16F blend (100), image cube npot (83+), descriptor_limits (30), dynamic_offset.combined_descriptors (18), framebuffer_attachment (34), empty_fs (5); sampler, depth and all 10,305 passes rerun at the end: no regression |

Sampler NotSupported cases are mostly `VK_EXT_border_color_swizzle`, `customBorderColors`, compute-queue variants,
`VK_KHR_maintenance5` and formats the texture unit lacks (a8, a1b5g5r5, scaled, 10-bit SNORM/SINT, ASTC, ETC2). Combined
depth/stencil formats have 25 applicable `border_swizzle` cases each (185 for plain depth): a swizzle to one or alpha
on them needs `depthStencilSwizzleOneSupport` from `VK_KHR_maintenance5`. Not yet run in the sampler group: 3d, 2d_array, 1d_array, cube, cube_array (only the sample above). Not yet rerun: pipeline (other than the rows above), glsl,
image, texture, synchronization, ssbo, ubo, spirv_assembly, query_pool, rasterization, the rest.

### Samples of the groups that are mostly optional features

Not full runs: up to 300 random cases per group (two batches of 150, fixed seed), 2026-10-10, same setup as above.
A sample with no Pass does not prove the whole group is NotSupported. NotPresent: the case is in the CTS case
list but the test binary does not create it on this device. Cases = the size of the group in that list.

| Group | Cases | Sampled | Pass | NotSupp | NotPresent | Fail |
|---|---:|---:|---:|---:|---:|---:|
| pipeline.shader_object_unlinked_spirv | 381,002 | 300 | 0 | 299 | 1 | 0 |
| shader_object | 242,412 | 300 | 0 | 300 | 0 | 0 |
| transform_feedback | 133,704 | 300 | 0 | 300 | 0 | 0 |
| pipeline.fast_linked_library | 131,667 | 300 | 0 | 294 | 6 | 0 |
| pipeline.pipeline_library | 119,750 | 300 | 0 | 300 | 0 | 0 |
| fragment_shading_rate | 110,443 | 300 | 0 | 300 | 0 | 0 |
| robustness | 96,963 | 300 | 2 | 298 | 0 | 0 |
| synchronization2 | 81,618 | 300 | 0 | 300 | 0 | 0 |
| ycbcr | 65,477 | 300 | 0 | 300 | 0 | 0 |
| cooperative_vector | 53,562 | 300 | 0 | 300 | 0 | 0 |
| subgroups | 48,705 | 300 | 0 | 300 | 0 | 0 |
| ray_query | 38,894 | 300 | 0 | 300 | 0 | 0 |
| wsi | 35,917 | 300 | 1 | 299 | 0 | 0 |
| mesh_shader | 28,043 | 300 | 0 | 298 | 2 | 0 |
| pipeline.shader_object_unlinked_binary | 24,354 | 300 | 0 | 300 | 0 | 0 |
| pipeline.shader_object_linked_spirv | 24,354 | 300 | 0 | 300 | 0 | 0 |
| pipeline.shader_object_linked_binary | 24,354 | 300 | 0 | 300 | 0 | 0 |
| fragment_shading_barycentric | 20,991 | 300 | 0 | 300 | 0 | 0 |
| ray_tracing_pipeline | 19,207 | 300 | 0 | 294 | 6 | 0 |
| sparse_resources | 19,150 | 300 | 0 | 295 | 5 | 0 |
| memory_model | 17,300 | 300 | 1 | 299 | 0 | 0 |
| data_graph | 10,176 | 300 | 0 | 300 | 0 | 0 |
| video | 9,309 | 300 | 0 | 298 | 2 | 0 |
| reconvergence | 6,253 | 300 | 0 | 300 | 0 | 0 |
| protected_memory | 6,000 | 300 | 0 | 300 | 0 | 0 |
| dgc | 4,587 | 300 | 0 | 294 | 6 | 0 |
| drm_format_modifiers | 1,572 | 300 | 0 | 300 | 0 | 0 |
| tessellation | 1,103 | 300 | 0 | 300 | 0 | 0 |
| conditional_rendering | 1,030 | 300 | 0 | 300 | 0 | 0 |
| tensor | 844 | 300 | 0 | 300 | 0 | 0 |
| multiview | 694 | 300 | 0 | 300 | 0 | 0 |
| image_processing | 611 | 300 | 0 | 300 | 0 | 0 |
| fragment_shader_interlock | 576 | 300 | 0 | 300 | 0 | 0 |
| geometry | 199 | 199 | 0 | 199 | 0 | 0 |
| descriptor_indexing | 114 | 114 | 0 | 110 | 4 | 0 |
| device_group | 18 | 18 | 0 | 18 | 0 | 0 |
| imageless_framebuffer | 6 | 6 | 0 | 6 | 0 | 0 |
| **Total** | **1,760,959** | **10,237** | **4** | **10,201** | **32** | **0** |

### Samples of the Vulkan 1.0 groups not yet run

Not full runs: 150 random cases per group (300 for the small `pipeline.monolithic` subgroups together), fixed
seed, 2026-10-10, 60 s per case. These groups have real failures; the sample says where, not how many.

| Group | Cases | Sampled | Pass | NotSupp | NotPresent | Fail | Timeout |
|---|---:|---:|---:|---:|---:|---:|---:|
| binding_model | 146,955 | 150 | 1 | 132 | 0 | 17 | 0 |
| image | 142,447 | 150 | 4 | 143 | 0 | 3 | 0 |
| pipeline.monolithic.image | 122,912 | 150 | 6 | 140 | 0 | 4 | 0 |
| renderpasses | 79,817 | 150 | 0 | 149 | 1 | 0 | 0 |
| spirv_assembly | 68,603 | 150 | 0 | 149 | 0 | 1 | 0 |
| synchronization | 64,871 | 150 | 0 | 150 | 0 | 0 | 0 |
| compute | 60,811 | 150 | 0 | 148 | 0 | 2 | 0 |
| pipeline.monolithic.stencil | 58,496 | 150 | 109 | 41 | 0 | 0 | 0 |
| pipeline.monolithic.sampler (3d, arrays, cube) | 48,684 | 150 | 21 | 129 | 0 | 0 | 0 |
| pipeline.monolithic, the other subgroups | 29,596 | 300 | 0 | 285 | 8 | 7 | 0 |
| draw | 29,392 | 150 | 1 | 149 | 0 | 0 | 0 |
| pipeline.monolithic.image_view | 27,708 | 150 | 32 | 118 | 0 | 0 | 0 |
| glsl | 26,801 | 150 | 5 | 80 | 0 | 57 | 8 |
| texture | 25,669 | 150 | 0 | 113 | 0 | 36 | 1 |
| query_pool | 18,270 | 150 | 1 | 143 | 2 | 4 | 0 |
| rasterization | 15,079 | 150 | 1 | 149 | 0 | 0 | 0 |
| pipeline.monolithic.vertex_input | 13,296 | 150 | 0 | 30 | 87 | 33 | 0 |
| ubo | 13,240 | 150 | 2 | 123 | 0 | 25 | 0 |
| pipeline.monolithic.blend | 12,226 | 150 | 6 | 127 | 0 | 17 | 0 |
| ssbo | 12,225 | 150 | 1 | 114 | 0 | 35 | 0 |
| pipeline.monolithic.multisample | 10,576 | 150 | 1 | 149 | 0 | 0 | 0 |
| graphicsfuzz | 757 | 150 | 38 | 12 | 1 | 90 | 9 |
| dynamic_state | 671 | 150 | 13 | 135 | 0 | 2 | 0 |
| clipping | 308 | 150 | 10 | 140 | 0 | 0 | 0 |
| fragment_operations | 151 | 150 | 3 | 105 | 0 | 42 | 0 |
| depth | 8 | 8 | 0 | 8 | 0 | 0 | 0 |
| **Total** | **1,029,569** | **3,908** | **255** | **3,161** | **99** | **375** | **18** |

T1 = tier 1, T2 = tier 2 (QEMU + DRM driver), T3 = tier 3 (ULX3S); see above.

Tier 2 has run the driver's own tests in QEMU (whitelist), and tier 3 renders `vkcube`; neither is a CTS case.

Four `api` cases report `QualityWarning` (`object_management`).

What the failures are:
- **pipeline (23,142) and glsl (10,704):** "Image mismatch". Shader-compiler and fixed-function gaps. These
  are the later Task 2 items (pipeline, shader rendering).
- **ssbo, ubo, texture, image, spirv_assembly, synchronization:** failure reasons not analysed yet.
- **memory (36):** all in `memory.pipeline_barrier`.
- **api:** 0 Fail in the full group, see "2a" below; the 13 in the table are from the faulty run.

## 2b: `dEQP-VK.info.*` (tier 1)

Tier 1. Command: `JOBS=4 DIRECT=1 scripts/cts_par.sh 'dEQP-VK.info.*'`, no mustpass filter, 21 cases, 1 s.

**18 Pass, 3 NotSupported, 0 Fail.** The three NotSupported cases need an extension that borgvk does not offer:

| Case | Needs |
|---|---|
| `info.device_memory_budget` | `VK_EXT_memory_budget` |
| `info.physical_device_groups` | `VK_KHR_device_group_creation` |
| `info.device_group_peer_memory_features` | `VK_KHR_device_group_creation` |

The scope is `dEQP-VK.info.*` only. The `api.info` group belongs to 2a.

## 2b on tier 2 (QEMU, Rust DRM driver)

`dEQP-VK.info.*`: **18 Pass, 3 NotSupported, 0 Fail**, the same cases as tier 1 (same three missing extensions).
Run with `software/linux/tests/qemu-cts.sh` (3 s). What ran: riscv64 `deqp-vk` and borgvk (glibc, cross-built)
in an initrd, on QEMU `virt` with Linux 7.2.9 and the Rust Borg DRM driver; borgvk logs "found borg DRM device".

- The Borg registers and memory are mapped at addresses with nothing behind them, so any access faults.
  `info` ran without a fault, so these 21 cases never touch the GPU. `borg_drm_test` on the same setup passes
  its info call and panics at its first register write (store access fault), as intended.
- The CPU is QEMU's, not Hutt. The kernel is the board's config plus `CONFIG_FPU=y`
  (`configs/borg_rv64_rust_fpu.frag`): the userspace is glibc hard-float, and the board kernel has no FPU.
- A sample of 200 `api` cases (random, fixed seed) with the GEM allocator: 150 NotSupported and 50 Pass, every case
  equal to its tier-1 result (`software/linux/tests/qemu-cts-run.sh`, 10 s on 4 guests). Without GEM, borgvk's
  allocation ioctls collided with the driver's register ioctls and 58 of the 200 failed with `OUT_OF_HOST_MEMORY`.
  The full `api` group has not been run on tier 2.
- Queries only. Cases that render need the real Borg behind the register block (a QEMU-to-`direct_sim` bridge, not built).
- The riscv64 Rust `std` and cross file for the Mesa build are in the scratchpad, not the repo yet.

## 2e and 2f: all of `dEQP-VK.pipeline.*`

The memorandum has two pipeline tasks, so every `pipeline` case belongs to one of them. The eight subgroups of
`dEQP-VK.pipeline` (CTS 1.4.6.2, extension variants included, 1,162,094 cases) are distributed whole, to get halves of
the same size:

| Task | Subgroups | Cases |
|---|---|---:|
| 2e, creation modes | `fast_linked_library`, `shader_object_linked_binary`, `shader_object_linked_spirv`, `shader_object_unlinked_binary`, `shader_object_unlinked_spirv`, `no-queues` | 581,367 |
| 2f, state coverage | `monolithic`, `pipeline_library` | 580,727 |

**2e on tier 1 (2026-10-13):** `fast_linked_library` in full (`CASELIST=<its 128,113 cases> JOBS=12 DIRECT=1 scripts/cts_par.sh`, 82 s):
**128,113 NotSupported, 0 Pass, 0 Fail.** Every case needs `VK_EXT_graphics_pipeline_library`, which borgvk does not offer
(`vkPipelineConstructionUtil.cpp:212`). The shader-object subgroups have not been run in full; a 3,000-case sample of
all 2e subgroups was all NotSupported (they need `VK_EXT_shader_object`).

**2f on tier 1:** not run in full. What ran, all in `pipeline.monolithic`:

- 14 subgroups for creation, layouts and cache (8,037 cases, 170 s, 2026-10-13): **589 Pass, 7,276 NotSupported, 172 Fail.**

  | Subgroup | Pass | NotSupp | Fail |
  |---|---:|---:|---:|
  | `cache` | 261 | 513 | 0 |
  | `bind_point` | 48 | 1,248 | 0 |
  | `dynamic_offset` | 204 | 192 | 12 (`combined_descriptors`) |
  | `descriptor_limits` | 58 | 310 | 28 (`compute_shader` 16, `fragment_shader` 12) |
  | `push_constant` | 5 | 39 | 21 (`graphics_pipeline` 11, `lifetime` 9, `compute_pipeline` 1) |
  | `interface_matching` | 12 | 1,466 | 111 (`vector_length` 48, `decoration_mismatch` 35, `shader_layout_component_matching` 28) |
  | `spec_constant` | 1 | 1,412 | 0 |
  | `bind_buffers_2`, `creation_cache_control`, `executable_properties`, `no_position`, `pipeline_binary`, `push_descriptor`, `shader_module_identifier` | 0 | 2,096 | 0 |

  A rerun the same day gave `interface_matching` 106 and `push_constant` 22: a few cases depend on load.
- `framebuffer_attachment` (50 cases): 14 Pass, 2 NotSupported, 34 Fail (1d, 2d, 2d_array, cube, cube_array, with and
  without MSAA).
- The older rows in the tables above (sampler, depth, the first 121,750 cases in tree order) and the 150-case samples.

## memory.allocation (tier 1)

Rerun 2026-10-08 with the library present: `OUT=... JOBS=12 DIRECT=1 scripts/cts_par.sh 'dEQP-VK.memory.allocation.*'`,
no mustpass filter. **202 Pass, 0 NotSupported, 0 Fail** (1 s). Raw results:
`vk1.0-memory-allocation-direct-sim-results.txt.gz`.

On the host the memory comes from the DRM shim (`libborg_drm_shim.so`, GEM backed by anonymous memory) and has
no size limit, so these cases never see an out-of-memory result. The Rust render node now has GEM (system RAM,
1 GiB cap per buffer, `../B5_gpu_memory.md`), exercised on tier 2 by `borg_drm_test` and the `api` run, but
`memory.allocation` on tier 2 (2026-10-13, `qemu-cts-run.sh`, 4 guests, 22 s): **202 Pass**, same as tier 1. The board's heap limit is not implemented.

## memory.mapping (tier 1)

Rerun 2026-10-08 with the library present: `OUT=... JOBS=12 DIRECT=1 scripts/cts_par.sh 'dEQP-VK.memory.mapping.*'`,
no mustpass filter, whole group. **811 Pass, 3,655 NotSupported, 0 Fail** of 4,466 (3 s). Raw results:
`vk1.0-memory-mapping-direct-sim-results.txt.gz`.

| Subgroup | Pass | NotSupported |
|---|---:|---:|
| `suballocation` | 811 | 811 |
| `dedicated_alloc` | 0 | 2,844 |

`dedicated_alloc` needs `VK_KHR_dedicated_allocation`, which borgvk does not offer. The all-groups table counts 2,230
cases because it is filtered by the mustpass list. Memory comes from the host shim, as for `allocation`.

On tier 2 (2026-10-13, `qemu-cts-run.sh`, 4 guests, 42 s): **811 Pass, 3,655 NotSupported, 0 Fail**, equal to tier 1.
`VK_KHR_dedicated_allocation` is optional in Vulkan 1.0, so those cases stay NotSupported.

## 2a (tier 1): rerun 2026-10-07

Rerun with the tag's command (`MUSTPASS=1.0.2.6 MUSTPASS_GROUPS=1 JOBS=12 DIRECT=1 scripts/cts_par.sh
'dEQP-VK.api.*'`, tier 1): **0 Fail**, 64,866 Pass, 213,472 NotSupported, 4 QualityWarning, the same numbers
as the tag `conformance/vk1.0-api-direct-sim`.

After the descriptor-pool fix below the same command still gives 0 Fail, 64,866 Pass. The 13 cases the strict
all-groups baseline listed as failing (4 `smoke`, 3 `command_buffers`, 6 `resolve_image`) all Pass when run alone
with the library present, so the baseline's "13" came from the broken environment.

An earlier rerun the same day showed 87 failures. That was an environment fault: `direct_sim` could not load
`libstdc++.so.6`, so every case that needs the simulator failed. The "13 failures" in the all-groups
table below come from the same kind of run and are not trusted either.

**The all-groups table is not reliable.** It was measured without that check; only the `api` row and the
`info` run were repeated with the library present. The all-groups rerun is pending.

## 2a on tier 2 (QEMU, Rust DRM driver, faulting Borg)

The same 278,342 `api` cases as the tier-1 rerun above, run with `software/linux/tests/qemu-cts-run.sh <list> <out> 16`
(16 guests of 2 GB, 15.7 minutes). Tier 1 and tier 2 side by side:

| Status | Tier 1 | Tier 2 |
|---|---:|---:|
| Pass | 64,866 | 64,778 |
| NotSupported | 213,472 | 213,472 |
| QualityWarning | 4 | 4 |
| Fail | 0 | 87 |
| Crash | 0 | 1 |

- **The 87 Fail** are the cases that need the Borg to execute: `buffer_view.access` texel buffers (51), `resolve_image`
  with 4 samples (29), `smoke` (4) and `command_buffers` (3). They fail because the Borg behind the register block is
  a stub that faults; they are not driver or Borg errors. No guest panicked, so none of them reached the register ioctls.
  They need the real Borg behind the node (a QEMU-to-`direct_sim` bridge, not built).
- **The 1 Crash** was `descriptor_pool.repeated_reset_long`: the guest's out-of-memory killer fired in `deqp-vk`.
  borgvk's `vkResetDescriptorPool` did nothing and pools did not track their sets, so sets leaked (the case makes
  8.4 million allocations; peak 4.4 GB on tier 1). Fixed: pools track and free their sets and refuse past `maxSets`
  (`VK_ERROR_OUT_OF_POOL_MEMORY`); peak is 34 MB and the case passes on tier 1 and, rerun alone, on tier 2.
  The tier-2 group was rerun on 2026-10-13 (below): 0 Crash.
**Rerun 2026-10-13** (current mesa, rv64 driver rebuilt with `software/linux/tests/rv64-rust.sh`, same list and
command): 60,879 Pass, 217,372 NotSupported, 87 Fail, 4 QualityWarning, 0 Crash, no guest panics.
`descriptor_pool.repeated_reset_long` passes. The 87 Fail are the same four groups as above (51 `buffer_view.access`,
29 `resolve_image`, 4 `smoke`, 3 `command_buffers`).
Pass is 3,899 lower than on 2026-10-08 (`copy_and_blit` -3,376, `image_clearing` -508) and NotSupported higher by as
many. It is not specific to tier 2: a sample of 300 of those NotSupported cases is NotSupported on tier 1 too, with
`... format not supported` or a missing extension as the reason. The format-feature changes of 2026-10-10 (no
features for 10-bit signed packed formats, blending only where Vulkan 1.0 requires it) are the likely cause; not
verified. The tier-1 `api` group has not been rerun since.

**The 87 on the real Borg (2026-10-13).** `BRIDGE=1 software/linux/tests/qemu-cts-run.sh` runs the guests on the
Borg RTL (`direct_sim --bridge` behind the `qemu-borg` device, `nix build .#qemu-borg`) with `BORGVK_HW=1`. All 87
cases pass (the three `suballocation` compute cases need about 30 minutes each, so `BOOT_TIMEOUT=3600`). The rest of
the `api` group has not been run this way.

- **How tier-2 Pass should be read.** The 64,778 Pass are mostly `copy_and_blit` (45,547) and `image_clearing`
  (15,224), which borgvk implements in C on the buffer and image memory (`borgvk_memory.c`), with a Borg that
  faults. They show the driver, GEM and the rv64 build work. They say nothing about the Borg RTL, which only
  tier 1 exercises, and only for the cases that reach it.
- Everything else matches tier 1 case for case. This needs the GEM allocator: before it, 58 of a 200-case sample failed
  with `OUT_OF_HOST_MEMORY`.

## Known limitations

- Direct simulator only; the Linux/DRM/borgvk stack on the ULX3S is shown with `vkcube`, not the CTS
  (`docs/evidence/`).
- Tiny Borg config on the board: 128 x 128, no blend, stencil or compute.
- Minimal Vulkan 1.0, no optional extensions.
- The CTS binary and the direct simulator are not shipped in the repo; the commands above reproduce a run.
