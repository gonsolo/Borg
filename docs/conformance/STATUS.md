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
| 2 | QEMU rv64: Linux with the Rust DRM driver, borgvk built for rv64, QEMU's CPU instead of Hutt | The shipped kernel driver (GEM, whitelist, mmap) | Hutt timing, FPGA specifics | Driver tests in QEMU exist for the whitelist; the GEM allocator and shim parity are designed (`../B5_gpu_memory.md`), not built. Running the CTS through the real driver needs a QEMU-to-`direct_sim` bridge, not planned |
| 3 | ULX3S: Hutt, Linux, Rust DRM driver, borgvk, Borg | The whole stack on real hardware | Most CTS groups: `deqp-vk` is about 100 MB, the board has 13.7 MB free | `vkcube` renders (`../evidence/`); a short list of small test programs is planned, not a CTS run |

Order of work: tier 1 for all groups, the GEM allocator with its QEMU tests and shim parity alongside the
`memory.*` items, then tier 3 as a smoke subset.

## Results per tier, all groups

Tier 1 command: `MUSTPASS=1.0.2.6 JOBS=20 DIRECT=1 scripts/cts_par.sh 'dEQP-VK.*'` (206 s).
Raw results (not committed): one `<status> <case>` line per case.

| Group | Cases | Tier 1: host + arcilator | Tier 2: QEMU + DRM driver | Tier 3: ULX3S |
|---|---:|---:|---|---|
| pipeline | 52,228 | 88 / 28,998 / 23,142 | NotRun | NotRun |
| glsl | 11,891 | 210 / 977 / 10,704 | NotRun | NotRun |
| synchronization | 8,618 | 891 / 6,924 / 803 | NotRun | NotRun |
| image | 7,082 | 531 / 5,912 / 639 | NotRun | NotRun |
| api | 4,939 | 3,326 / 1,596 / 13 | NotRun | NotRun |
| spirv_assembly | 3,135 | 7 / 2,597 / 531 | NotRun | NotRun |
| memory | 2,584 | 1,053 / 1,495 / 36 | NotRun | NotRun |
| ssbo | 1,681 | 0 / 242 / 1,439 | NotRun | NotRun |
| texture | 1,138 | 216 / 60 / 862 | NotRun | NotRun |
| ubo | 533 | 0 / 150 / 383 | NotRun | NotRun |
| query_pool | 118 | 7 / 33 / 78 | NotRun | NotRun |
| rasterization | 116 | 21 / 71 / 24 | NotRun | NotRun |
| others (sparse_resources, ycbcr, tessellation, geometry, wsi, clipping, fragment_operations, info) | 2,046 | 30 / 2,014 / 2 | NotRun | NotRun |
| **Total** | **96,109** | **6,380 (6.6%) / 51,069 / 38,656** | NotRun | NotRun |
| `info` (2b, whole group, no mustpass filter) | 21 | 18 / 3 / 0 | NotRun | NotRun |

Cells read Pass / NotSupported / Fail. `NotRun` means the tier has not run the CTS for that group; it is not a result.
The 2b row is a separate run (see below); its cases are not in the total, whose `info` cases are the 4 the mustpass lists.
Tier 2 has run the driver's own tests in QEMU (whitelist), and tier 3 renders `vkcube`; neither is a CTS case.

Four `api` cases report `QualityWarning` (`object_management`).

What the failures are:
- **pipeline (23,142) and glsl (10,704):** "Image mismatch". Shader-compiler and fixed-function gaps. These
  are the later Task 2 items (pipeline, shader rendering).
- **ssbo, ubo, texture, image, spirv_assembly, synchronization:** failure reasons not analysed yet.
- **memory (36):** all in `memory.pipeline_barrier`.
- **api (13):** see "Open discrepancy" below.

## 2b: `dEQP-VK.info.*` (tier 1)

Tier 1. Command: `JOBS=4 DIRECT=1 scripts/cts_par.sh 'dEQP-VK.info.*'`, no mustpass filter, 21 cases, 1 s.

**18 Pass, 3 NotSupported, 0 Fail.** The three NotSupported cases need an extension that borgvk does not offer:

| Case | Needs |
|---|---|
| `info.device_memory_budget` | `VK_EXT_memory_budget` |
| `info.physical_device_groups` | `VK_KHR_device_group_creation` |
| `info.device_group_peer_memory_features` | `VK_KHR_device_group_creation` |

The scope is `dEQP-VK.info.*` only. The `api.info` group belongs to 2a.

## memory.allocation and memory.mapping (tier 1)

`memory.allocation` (202) and `memory.mapping` (810 Pass, 1,420 NotSupported) have no failures. On the host the memory comes from
the DRM shim (`libborg_drm_shim.so`, GEM backed by anonymous memory), not from the board's driver:
the Rust render node has no GEM yet. The design for the allocator is `docs/B5_gpu_memory.md`; nothing of it is
implemented. Until it is, these results show the Vulkan side, not the driver that ships.

## Open discrepancy: 2a (tier 1)

The tag `conformance/vk1.0-api-direct-sim` lists `0` failures in 278,342 `api` cases. Today 13 of them fail:
`api.smoke.triangle`, `asm_triangle`, `asm_triangle_no_opname`, `unused_resolve_attachment`,
`command_buffers.order_bind_pipeline`, `record_simul_use_secondary_{one,two}_primary`, and six
`copy_and_blit.{core,dedicated_allocation}.resolve_image.*.4_bit` cases. The smoke cases draw a blank frame.
The same failures appear with the tag's mesa commit, a simulator built from the tag's source and the tag's exact
command, so this is not a regression since the tag. Why the tag lists them as Pass is not explained.
Do not repeat "0 failures" for `api` until the full group is rerun and the cases are fixed.

## Known limitations

- Direct simulator only; the Linux/DRM/borgvk stack on the ULX3S is shown with `vkcube`, not the CTS
  (`docs/evidence/`).
- Tiny Borg config on the board: 128 x 128, no blend, stencil or compute.
- Minimal Vulkan 1.0, no optional extensions.
- The CTS binary and the direct simulator are not shipped in the repo; the commands above reproduce a run.
