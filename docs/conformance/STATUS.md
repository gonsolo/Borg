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

**Not reliable, see 2a below; rerun pending.** Tier 1 command: `MUSTPASS=1.0.2.6 JOBS=20 DIRECT=1 scripts/cts_par.sh 'dEQP-VK.*'` (206 s).
Raw results (not committed): one `<status> <case>` line per case.

| Group | Cases | T1 Pass | T1 NotSupp | T1 Fail | T2 | T3 |
|---|---:|---:|---:|---:|---|---|
| pipeline | 52,228 | 88 | 28,998 | 23,142 | NotRun | NotRun |
| glsl | 11,891 | 210 | 977 | 10,704 | NotRun | NotRun |
| synchronization | 8,618 | 891 | 6,924 | 803 | NotRun | NotRun |
| image | 7,082 | 531 | 5,912 | 639 | NotRun | NotRun |
| api | 4,939 | 3,326 | 1,596 | 13 | see "2a on tier 2" | NotRun |
| spirv_assembly | 3,135 | 7 | 2,597 | 531 | NotRun | NotRun |
| memory | 2,584 | 1,053 | 1,495 | 36 | NotRun | NotRun |
| ssbo | 1,681 | 0 | 242 | 1,439 | NotRun | NotRun |
| texture | 1,138 | 216 | 60 | 862 | NotRun | NotRun |
| ubo | 533 | 0 | 150 | 383 | NotRun | NotRun |
| query_pool | 118 | 7 | 33 | 78 | NotRun | NotRun |
| rasterization | 116 | 21 | 71 | 24 | NotRun | NotRun |
| others* | 2,046 | 30 | 2,014 | 2 | NotRun | NotRun |
| **Total** | **96,109** | **6,380** | **51,069** | **38,656** | NotRun | NotRun |
| info (2b)** | 21 | 18 | 3 | 0 | 18 Pass, 3 NotSupp, 0 Fail (see below) | NotRun |

T1 = tier 1 (host + arcilator), T2 = tier 2 (QEMU + DRM driver), T3 = tier 3 (ULX3S); see above.
`NotRun` means the tier has not run the CTS for that group; it is not a result. Pass is 6.6% of the total.

\* sparse_resources, ycbcr, tessellation, geometry, wsi, clipping, fragment_operations, info (4 cases).
\*\* `info` as a separate run of the whole group, no mustpass filter (see below); not part of the total.

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

## memory.allocation and memory.mapping (tier 1)

`memory.allocation` (202) and `memory.mapping` (810 Pass, 1,420 NotSupported) have no failures. On the host the memory comes from
the DRM shim (`libborg_drm_shim.so`, GEM backed by anonymous memory), not from the board's driver:
the Rust render node has no GEM yet. The design for the allocator is `docs/B5_gpu_memory.md`; nothing of it is
implemented. Until it is, these results show the Vulkan side, not the driver that ships.

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
  The full tier-2 group was not rerun after the fix.
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
