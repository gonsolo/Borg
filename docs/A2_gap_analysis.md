# Gap Analysis and Vulkan Strategy

## vkcube Gap Analysis

What's needed to run `vkcube` — the standard Vulkan spinning textured cube demo
(12 triangles, one texture, perspective projection, no lighting).

Required (all firmware-only):

| Feature | Effort | Notes |
| --------- | -------- | ------- |
| Perspective projection | Medium | 4×4 MVP matrix multiply (~16 FMA per vertex) |
| Triangle clipping | Medium | Near/far plane clip before rasterization |
| Multiple triangles | Easy | Loop over 12-triangle cube mesh |
| Mesa Vulkan ICD | Large | Minimal `vk_device`, shader compiler, draw path (Phase 3) |

Already have:

| Feature | Status |
| --------- | -------- |
| Texture mapping | ✅ UV interpolation + PSRAM sampling |
| Z-buffer | ✅ Per-pixel depth testing |
| Vertex transformation | ✅ Matrix via FPU |

Not needed for vkcube: lighting, fog, alpha blending, skinned animation.

vkcube is the natural first Vulkan milestone — achievable after Phase 3 (Mesa driver).

## SuperTuxKart Gap Analysis

What's needed beyond the current pipeline to render a Vulkan game like SuperTuxKart.

Firmware-only (no hardware changes):

| Feature | Effort | Notes |
| --------- | -------- | ------- |
| Perspective projection | Medium | Full MVP matrix multiply (~16 FMA per vertex) |
| Camera/view matrix | Easy | Combined with perspective |
| Triangle clipping | Medium | Near/far plane clip before rasterization |
| Directional lighting | Medium | dot(N, L) per vertex or per pixel |
| Multiple textures | Easy | Already works — different `borg_set_texture` per triangle |
| Fog | Easy | Blend toward fog color based on depth |
| Bounding-box culling | Easy | Skip pixels outside triangle AABB |

Architectural gaps (need hardware or major rework):

| Feature | Challenge |
| --------- | ----------- |
| Performance | Showstopper — 32×32 × 2 tri takes ~60s; SuperTuxKart needs 1080p × 100k+ tri at 60fps. Gap: ~10⁹× |
| Bilinear filtering | 4 texel reads + 3 lerps per pixel |
| Alpha blending | Read-modify-write framebuffer + sort order |
| Skinned animation | Bone matrix palette per vertex |
| Real-time display | Need VGA/SPI LCD output instead of PSRAM dump |

## "No Graphics API" Gap Analysis

How Borg compares to the idealized bindless GPU hardware described by
Sebastian Aaltonen in [No Graphics API](https://www.sebastianaaltonen.com/blog/no-graphics-api).

Where Borg already aligns:

| Principle | Borg Status |
| --------- | ----------- |
| No descriptor sets or binding tables | ✅ Direct MMIO registers — no binding model at all |
| Shader = simple compute kernel | ✅ SPIR-B is a flat sequence of FMA/ADD/MUL/FNEG/FSTEP |
| Minimal API surface | ✅ Handful of firmware calls (`borg_set_shader`, `borg_run`) |
| Raw memory access from shader | ✅ Register file + IMEM are raw MMIO-mapped |
| No PSO permutation explosion | ✅ No pipeline state objects exist |
| Strip fixed-function hardware | ✅ Rasterization and z-buffer live in firmware |

Gaps relative to the idealized hardware:

| Feature | Gap |
| ------- | --- |
| 64-bit GPU pointers | Borg has no pointer model — shaders read only from registers loaded by firmware |
| Bindless texture heap | No texture hardware — sampling is firmware-driven from PSRAM |
| Compute shader dispatch | No general compute — shader core is rasterization-only |
| Barriers / fences | Not needed yet — single-threaded firmware serializes everything |
| Command buffers | No concept — firmware issues MMIO writes directly |
| Mesh shaders / GPU-driven rendering | No programmable geometry stage |
| Wide SIMD execution | Single-lane FP16 ALU, no wavefront/warp parallelism |

Borg validates Aaltonen's thesis from the opposite direction: by starting from
bare metal and building upward, it shows that textured, z-buffered triangle
rendering is achievable without any of the API complexity that modern stacks
carry. The gaps above define the path from minimal GPU to the kind of hardware
his proposed API targets.

## Vulkan Implementation Strategy (Aligned with Aaltonen)

When building the Mesa Vulkan ICD (Phase 3), prioritize the subset of Vulkan
that maps naturally to Borg's minimal hardware and matches Aaltonen's vision.
Defer or omit the complexity that exists only to abstract away hardware
differences Borg doesn't have.

**Implement first** — these are cheap because Borg's simplicity makes them trivial:

| Vulkan Feature | Why |
| -------------- | --- |
| `vkCmdDraw` / `vkCmdDrawIndexed` | Core draw path — maps to `borg_run()` loop |
| NIR → SPIR-B shader compiler | Aaltonen's "shader = kernel" — just emit FMA/ADD/MUL ops |
| `VK_EXT_headless_surface` / `wsi_headless` | No display hardware — render to PSRAM buffer |
| Push constants | Maps directly to Borg register loads — zero binding overhead |
| Single `VkQueue`, single `VkCommandBuffer` | Borg is single-threaded — no synchronization needed |
| `vkCmdPipelineBarrier` (no-op) | Firmware serializes everything — barriers are free |
| Vertex input (pull model) | CPU-side vertex fetch, load into registers per-vertex |

**Defer** — real work, but not needed for first milestone:

| Vulkan Feature | Why Defer |
| -------------- | --------- |
| Descriptor sets / pools | Aaltonen says skip these — use push constants or direct MMIO |
| Multiple render passes | Single render target to PSRAM is sufficient initially |
| Multisampling (MSAA) | No hardware support — would need firmware supersampling |
| Dynamic state (`VK_DYNAMIC_STATE_*`) | No PSO permutations — Borg has no baked state to vary |
| `VkFence` / `VkSemaphore` (real sync) | Single-threaded — `vkQueueWaitIdle` is sufficient |

**Omit entirely** — complexity that Aaltonen explicitly argues against:

| Vulkan Feature | Rationale |
| -------------- | --------- |
| Descriptor indexing / bindless descriptors | Borg has no descriptor model — registers are already "bindless" |
| Pipeline cache / `VkPipelineCache` | No PSO — shader compilation is a trivial SPIR-B translation |
| Geometry / tessellation shaders | Aaltonen calls these "failed experiments" |
| Sparse resources / sparse binding | No virtual memory, no page tables |
| `VK_KHR_ray_tracing_pipeline` | No ray-tracing hardware |
| Subpass dependencies | Single render target, single pass — not applicable |
| Multiple `VkPhysicalDevice` | One GPU, one device |

The result: a Vulkan ICD that is closer to Aaltonen's 150-line prototype API
than to a full Vulkan 1.0 implementation. Mesa's `vk_device` and `vk_meta`
helpers handle the boilerplate; the Borg-specific driver stays small.
