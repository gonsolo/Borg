# borgvk design notes

Explanations that used to sit as comments in the C driver in `mesa/src/borg/vulkan/`, moved here verbatim. Each note names the function it was in and the code line it stood above; line numbers refer to mesa commit `e1872f516f7`. Some notes describe an earlier state of the driver and are out of date.

The driver is Rust now (see the software driver chapter). Where each C file went:

| C file | Rust modules in `rust/` |
|---|---|
| `borgvk_cmd_buffer.c` | `cmd_buffer` |
| `borgvk_compiler.c`, `borgvk_compiler_cache.c` | `compiler`, `compile`, `nir_info`, `nir_passes` |
| `borgvk_descriptor_set.c` | `descriptor_set` |
| `borgvk_device.c` | `device`, `physical_device` |
| `borgvk_hw.c` | `hw` |
| `borgvk_pipeline.c` | `pipeline` |
| `borgvk_query.c` | `query` |
| `borgvk_shader_cache.c` | `shader_cache` |
| `borgvk_sim.c` | `sim`, `format` |
| `borgvk_sync.c` | `sync` |
| `borgvk_wsi.c` | `wsi` |
| `borgvk_serial.c` | `serial`, `transport` |
| `borgvk_memory.c` | `memory`, `image`, `sampler`, `copy`, `clear`, `texel`, `render` |
| `borgvk_queue.c` | `queue`, `draw`, `dispatch`, `cube`, `state_upload`, `texture_upload`, `corpus`, `sim_process` |
| `borgvk_private.h` | the structs in their modules, `types` |
| `borgvk_serial_test.c` | the tests in `serial` |
| `borgvk_burst_gen.c` | still C, a client of `borgvk_rust.h` |

## borgvk_cmd_buffer.c

**line 1, in `(file level)`, above ``**

* Command buffers for borgvk. We do NOT replay Vulkan commands to render — the
cube's per-frame MVP is read straight from the bound uniform buffer at submit
time (Phase 3). So recording uses the runtime's generic command-queue
emulation (vk_cmd_enqueue_*): every vkCmd* is recorded into the command
buffer's vk_cmd_queue and then simply discarded on reset. Begin/End just
drive the common command-buffer lifecycle.

**line 20, in `(file level)`, above `static void`**

struct borgvk_command_buffer is defined in borgvk_private.h -- it also
tracks render-pass/dynamic-rendering state (see the struct's comment),
needed by borgvk_CmdBeginRendering/CmdClearAttachments in
borgvk_memory.c.

**line 130, in `borgvk_CmdPushConstants`, above `VK_FROM_HANDLE(vk_command_buffer, cmd, commandBuffer);`**

Step 50 item 13.  The shader half of this already worked: borgc lowers
load_push_constant to `LOAD rd, rs1` with rs1 pinned to the field's word
index, and the hardware forms LS_BASE + (rs1 << 2).  What was missing was
anything putting real data where those loads look -- this, plus the
firmware's 0xB2 handler and borg_set_push_constants().

`layout` and `stageFlags` are deliberately unused: Borg has one shader
core with one LS_BASE, so there is no per-stage or per-layout push-constant
window to select between.  A range pushed for the vertex stage is visible
to the fragment stage too.  That is more permissive than Vulkan requires,
not less, so it cannot make a conformant application read the wrong value
-- it would only fail to catch an application reading a stage it never
pushed to, which is already undefined behaviour.

Emitted at record time rather than queued to submit: the firmware applies
a 0xB2 packet when it arrives, and recording necessarily precedes the
submit that ships the draw's MVP, so ordering holds for the
record-then-submit pattern borgvk targets.  KNOWN LIMITATION: a command
buffer recorded once and submitted repeatedly pushes only on the
recording, so a later submit reuses whatever LS_BASE last pointed at.
That matches how borgvk_serial_send_shader() already behaves and is
invisible to the current re-record-every-frame callers; fixing it means
capturing the range into struct borgvk_command_buffer and replaying it
from the submit path, which is the natural follow-on once anything
actually reuses command buffers.

## borgvk_compiler.c

**line 1, in `(file level)`, above ``**

* C side of the borgvk shader compiler. The NIR→Borg-ISA lowering lives in the
Rust crate `borgc` (src/borg/compiler/); this file turns the app's SPIR-V into
NIR (via the Mesa runtime) and hands each stage to Rust.

**line 27, in `(file level)`, above `static uint32_t`**

Implemented in Rust: src/borg/compiler/lib.rs. borgc_compile_nir compiles the
shader to a .borg blob: it writes up to buf_cap bytes into out_buf and sets
*out_len to the true blob size (so *out_len > buf_cap signals an undersized
buffer); the return value is the instruction count.

**line 65, in `ubo_mask_used`, above `static int`**

The binding of the texture the shader samples (-1 for none); a draw sends one texture.

**line 104, in `reads_point_coord`, above `uint32_t`**

The draw extension's varying count of a compiled blob (software/borg/borg_spirb.c layout).

**line 114, in `borgvk_blob_num_varyings`, above `void`**

The sets of a layout are numbered flat: set s starts where the sets before it end, so the
buffer windows borgc names by binding alone cannot collide.

**line 129, in `borgvk_layout_set_base`, above `static void`**

borgc names buffers by (set, binding): point each constant array index at its flat slot (the
slot the descriptor update wrote) and fold the set number in.

**line 166, in `remap_array_bindings`, above `static void`**

A line is drawn as a quad (borgvk_queue.c gathers six rows per line). This shader runs once
per corner: a copy of it computes the other endpoint's position from the attributes at
location + BORGVK_LINE_OTHER, and the corner moves half a pixel along the line's minor axis.
The line attribute (location BORGVK_LINE_SLOT) holds that step in clip units for x and y,
then the target's width and height.

**line 306, in `borgvk_compile_stage`, above `borg_lower_nir_for_borgc_blend(nir, blend);`**

Shared with the offline borgc CLI -- see borg_nir_passes.h for why this
must not be a second copy.

**line 315, in `borgvk_compile_stage`, above `int slot = -1;`**

Map the Vulkan stage to a firmware shader slot. Only the vertex and
fragment stages are app-derived; anything else (e.g. compute) has no Borg
slot, so compile for diagnostics but don't capture a blob.

## borgvk_compiler_cache.c

**line 1, in `(file level)`, above ``**

* The shader compiler of a borgvk without borgc (-Dborg-compiler=cache): stages come from
the shader cache ($BORGVK_SHADER_CACHE, borgvk_shader_cache.c) or are not compiled.

## borgvk_descriptor_set.c

**line 1, in `(file level)`, above ``**

* Descriptor set layouts, pools and sets for borgvk. We don't drive shaders
with descriptors on the host; a set simply records which buffer, image and
sampler is bound at each binding so the submit path can locate the cube's
uniform buffer (binding 0, the per-frame MVP) and its texture and sampler
(binding 1).

**line 72, in `borgvk_GetDescriptorSetLayoutSupport`, above `pSupport->supported = pCreateInfo->bindingCount <= BORGVK_MAX_BINDINGS;`**

Mandatory core Vulkan 1.1 entry point, entirely missing -- same failure
mode as the other gaps this pass has been finding: a null
dispatch-table slot, SEGV the moment CTS's maintenance3_check.* calls
through it. borgvk has no descriptor-indexing/mutable-descriptor-type
support to account for (not advertised anywhere in this driver), so the
only real constraint is the fixed per-set binding array used by
borgvk_descriptor_set (BORGVK_MAX_BINDINGS, see borgvk_UpdateDescriptorSets).

**line 204, in `borgvk_FreeDescriptorSets`, above `static bool`**

The flat slot of element `elem` of `binding`, counting on into the next bindings when the
element is past the end of its own (a write may span several). False past the last binding.

**line 278, in `borgvk_UpdateDescriptorSets`, above `if (w->pImageInfo &&`**

The sampler (combined image sampler or a plain sampler); the submit path ships its
packed descriptor with the texture.

**line 288, in `borgvk_UpdateDescriptorSets`, above `if (w->pImageInfo &&`**

Image bindings (the cube's texture is a combined image sampler at binding 1); the
submit path reads its mapped RGBA8 to upload it.

## borgvk_device.c

**line 1, in `(file level)`, above ``**

* Instance + physical-device bring-up for borgvk.  When libborg_drm_shim.so is
preloaded, enumerate_devices finds the "borg" DRM device and opens it; all
GEM and submit ioctls go through that fd.  Without the shim (drm_fd == -1),
the driver falls back to malloc + direct serial (legacy path).

**line 27, in `(file level)`, above `#define BORGVK_API_VERSION VK_API_VERSION_1_0`**

The Vulkan version borgvk claims. It implements the Vulkan 1.0 feature set (the target of
the conformance work), and says so: reporting a higher version would promise 1.1+ behaviour.

**line 38, in `borgvk_EnumerateInstanceVersion`, above `static const struct vk_instance_extension_table instance_extensions = {`**

Instance extensions advertised by borgvk. The WSI surface extensions let an
app create a swapchain so its render loop runs; the actual pixels go to the
FPGA over serial, the host window is just a formality (software WSI path).

**line 155, in `is_ycbcr_format`, above `switch (format) {`**

Multi-planar and 4:2:x packed formats require samplerYcbcrConversion
(VK_KHR_sampler_ycbcr_conversion) which borgvk does not advertise.
Report zero features for all of them.

**line 206, in `is_compressed_format`, above `return (format >= VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK &&`**

BC and ASTC — zero features (not advertised in device features).
The 3D ASTC formats (VK_EXT_texture_compression_astc_3d,
VK_FORMAT_ASTC_3x3x3_UNORM_BLOCK_EXT .. VK_FORMAT_ASTC_6x6x6_SFLOAT_BLOCK_EXT,
enum range 1000288000-1000288029) live in the extension's own enum
range, well outside VK_FORMAT_ASTC_12x12_SRGB_BLOCK -- this range check
alone let them fall through to borgvk_optimal_features' generic "any
other format" branch and get full feature support as if fully
supported. That's what let CTS create and actually copy/read back a
real 3D-block-compressed image, hitting a genuine heap corruption in
that path (borgvk's addressing has never been correctly worked out for
block-compressed depth).

A block-depth-based generic check (util_format_get_blockdepth) was
tried first instead of hardcoding this range, but doesn't work:
vk_format_to_pipe_format() has NO mapping at all for these EXT enum
values (confirmed by direct probe: vkGetPhysicalDeviceFormatProperties
still returned nonzero optimalTilingFeatures after that fix), so it
silently returns PIPE_FORMAT_NONE, and util_format_get_blockdepth's
!desc fallback returns 1 -- indistinguishable from an ordinary 2D
format. Hardcoding the range is the only thing that actually reaches
this format at all, confirmed by re-running the same direct probe
after this change (optimalTilingFeatures == 0).

**line 236, in `is_compressed_format`, above `static bool`**

BC1-BC7: the one compression family Vulkan 1.0 asks for.  The texels stay compressed
in image memory; the driver decodes them (Mesa's util/format decoders) when it blits
from them or hands them to the texture unit.

**line 246, in `is_bc_format`, above `#define BORGVK_SC_MSAA      (VK_SAMPLE_COUNT_1_BIT | VK_SAMPLE_COUNT_4_BIT)`**

Sample counts the device supports per use; VkPhysicalDeviceLimits and the per-format
sampleCounts both derive from these, so the two cannot disagree.

**line 260, in `borgvk_optimal_features`, above `if (vk_format_is_scaled(format))`**

Scaled-integer formats (*_USCALED, *_SSCALED) are vertex-buffer formats in Vulkan 1.0;
the spec requires no image feature for them.  The texture unit cannot sample them and
the blit/attachment paths do not convert to them, so advertise none.

**line 266, in `borgvk_optimal_features`, above `if (format == VK_FORMAT_A2R10G10B10_SNORM_PACK32 || format == VK_FORMAT_A2B10G10R10_SNORM_`**

10-bit signed packed formats: the texture unit has none (only UNORM and UINT), and
Vulkan 1.0 does not require them.

**line 272, in `borgvk_optimal_features`, above `if (format >= VK_FORMAT_R64_UINT && format <= VK_FORMAT_R64G64B64A64_SFLOAT)`**

64-bit-per-channel formats (R64*): the texture unit and the tile buffer top out at
32 bits per channel, and Vulkan 1.0 does not require them.

**line 277, in `borgvk_optimal_features`, above `if (is_bc_format(format)) {`**

BC1-7: sampled, blit source, transfer (the Vulkan 1.0 requirement for a
compression family, vkGetPhysicalDeviceFormatProperties tables).  No blit
destination, colour attachment or storage: the block formats cannot be rendered to.

**line 296, in `borgvk_optimal_features`, above `return common | VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT;`**

Depth/stencil formats: sampled + depth/stencil attachment only.
No color attachment (CTS unsupported_image_usage.optimal checks this).

**line 301, in `borgvk_optimal_features`, above `VkFormatFeatureFlags f =`**

Color formats: full feature set, no depth/stencil.
Storage image atomics mandated by spec for R32_UINT and R32_SINT.

**line 307, in `borgvk_optimal_features`, above `switch (format) {`**

Blending: the formats Vulkan 1.0 requires it for, no others (the fixed-function blend does
the UNORM8 ones; docs/B3_colour_formats.md has the plan for the rest).

**line 329, in `borgvk_optimal_features`, above `if (!vk_format_is_int(format) && !vk_format_is_scaled(format))`**

Integer and scaled formats (the sampler has no scaled-integer formats) can't be linearly filtered -- interpolating between
integer texel values is meaningless, and no real hardware advertises
this bit for them. Without this exclusion CTS legitimately runs
VK_FILTER_LINEAR blits/samples against e.g. R32_UINT (since we claimed
to support it) and gets "incorrect" results compared to its NEAREST-only
reference for integer formats.

**line 342, in `borgvk_optimal_features`, above `static VkFormatFeatureFlags`**

Texel-buffer features Vulkan 1.0 requires (the mandatory format tables; verified by
dEQP-VK.api.info.format_properties.*).  Nothing else is advertised: the compute path only
implements these.  Every format listed for storage is also listed for uniform.

**line 404, in `borgvk_GetPhysicalDeviceFormatProperties2`, above `VkFormatFeatureFlags lin =`**

Linear tiling: transfer + sampling.  borgvk samples textures by reading the
host-visible image memory directly over serial, so a LINEAR host-visible
image is sampleable.  Advertising SAMPLED here lets cube.c upload its texture
straight into a linear host-visible image (the path the shim reads) instead
of falling back to a staging buffer + OPTIMAL image + vkCmdCopyBufferToImage,
which borgvk does not execute (that fallback left the texture all-black).
Only for color formats that the optimal path already samples.

**line 420, in `borgvk_GetPhysicalDeviceFormatProperties2`, above `VkFormatFeatureFlags buf = 0;`**

Buffer features: for non-compressed non-depth color formats only.
BC formats are sampled-only (no buffer/vertex support).

**line 437, in `borgvk_GetPhysicalDeviceFormatProperties2`, above `VkFormatProperties3 *fp3 = (VkFormatProperties3 *)ext;`**

VkFormatProperties3 mirrors VkFormatProperties with 64-bit flags.
CTS checks these are consistent with the core struct.

**line 444, in `borgvk_GetPhysicalDeviceFormatProperties2`, above `if (vk_format_has_depth(format) &&`**

VkFormatFeatureFlagBits2 has bits with NO 32-bit counterpart, so a
straight copy of the core flags under-reports them.
SAMPLED_IMAGE_DEPTH_COMPARISON (bit 33) is one: the spec's mandatory
format table requires it for every depth format that supports
SAMPLED_IMAGE, and dEQP-VK.api.format_feature_flags2.* compares
against exactly that table (vktApiFormatPropertiesExtendedKHRtests
checkFlags → "missing flags 0x0000000200000000" for all six depth
formats).  Only optimal tiling: borgvk deliberately withholds
SAMPLED_IMAGE from depth formats in the linear path above, so there
is nothing to qualify there.

**line 500, in `borgvk_GetPhysicalDeviceImageFormatProperties2`, above `pImageFormatProperties->imageFormatProperties =`**

Always zero the output so callers see clean data even on NOT_SUPPORTED.
The v1 bridge (vk_common_GetPhysicalDeviceImageFormatProperties) zero-inits
its VkImageFormatProperties2 on the stack; v1 and v2 must agree exactly,
including on error paths.

**line 510, in `borgvk_GetPhysicalDeviceImageFormatProperties2`, above `if (pImageFormatInfo->type == VK_IMAGE_TYPE_3D &&`**

3D images using a 3D-block-compressed format (any ASTC_*x*x*_BLOCK_EXT,
e.g. ASTC_3x3x3) require real block-depth-aware addressing throughout
the copy/clear paths. That math was added (borgvk_mip_level_size's `bd`
parameter) but a heap corruption still surfaced running
dEQP-VK.api.copy_and_blit...3d_to_3d.astc_3x3x3_* even after fixing the
addressing CmdCopyImage itself does -- meaning some other path (the
initial data upload via CmdCopyBufferToImage, or CTS's own readback)
still isn't right for this narrow combination. Rather than ship a
partially-debugged data-corruption risk for a combination real content
essentially never uses (3D block compression has minimal real-world
adoption), decline it honestly here so CTS reports NotSupported instead
of crashing or corrupting memory.

**line 526, in `borgvk_GetPhysicalDeviceImageFormatProperties2`, above `if ((usage & VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT) &&`**

INPUT_ATTACHMENT requires color or depth-stencil attachment capability.
Compressed formats (ETC2/EAC) are sampled-only — reject them here.

**line 533, in `borgvk_GetPhysicalDeviceImageFormatProperties2`, above `const bool cube_compatible =`**

Sample counts follow the device limits for the way the image will be used (the
CTS requires exactly that intersection): storage, sampled (depth / stencil / integer
/ other colour) and attachment usage each narrow the set. Only 2D optimal images
that are not cube-compatible and have an attachment feature can be multisampled.

**line 590, in `borgvk_GetPhysicalDeviceSparseImageFormatProperties2`, above `}`**

Borg has no sparse-residency support, so no format supports sparse images.
Report zero properties.  Without this entrypoint the Mesa runtime's common
v1 shim (vk_common_GetPhysicalDeviceSparseImageFormatProperties) calls a
NULL dispatch slot and segfaults.

**line 603, in `borgvk_GetPhysicalDeviceExternalBufferProperties`, above `pExternalBufferProperties->externalMemoryProperties = (VkExternalMemoryProperties){`**

Unlike External{Fence,Semaphore}Properties, Mesa's vk runtime has no
vk_common_ fallback for this one -- every driver must implement it
itself, and borgvk didn't, so this was a NULL dispatch-table call
(confirmed via coredumpctl/gdb: SEGV in
vk::InstanceDriver::getPhysicalDeviceExternalBufferProperties).
Borg has no import/export path for any external memory handle type
(malloc+serial only), so every handle type reports unsupported --
same "no support" answer lvp_GetPhysicalDeviceExternalBufferProperties
gives for any handle type it doesn't implement.

**line 629, in `borgvk_get_properties`, above `.maxImageDimension1D = 4096,`**

Vulkan 1.0 limits — modest, sane values so vulkaninfo and apps see a
coherent device. Tightened/grown as real features come online.

**line 772, in `create_physical_device`, above `.robustBufferAccess = true,`**

Buffer accesses stay inside the bound range: see compute.rs (window mask) and
fetch_vertex_attr (bounded vertex fetch).

**line 805, in `create_physical_device`, above `#ifdef BORGVK_USE_WSI_PLATFORM`**

WSI init must follow supported_sync_types: wsi_device_init queries external
semaphore/fence properties, which walk that list.

**line 821, in `create_physical_device`, above `static int`**

Try to open a /dev/dri/renderD* node whose DRM driver name is "borg".
Returns an open fd, or -1 if no such device is found.

**line 952, in `borgvk_CreateDevice`, above `vk_foreach_struct_const(f_stype, f, pCreateInfo->pNext) {`**

The runtime's feature check ignores Vulkan 1.1 feature structs on a device that reports
1.0, so enabling a 1.1 feature we do not have would succeed. Refuse them here.

**line 981, in `borgvk_CreateDevice`, above `struct vk_device_dispatch_table dispatch_table;`**

Main device dispatch: our entrypoints, then fill every vkCmd* we don't
implement with the runtime's record-into-queue emulation.

**line 992, in `borgvk_CreateDevice`, above `#define BORGVK_RECORD_CMD(name) dispatch_table.name = vk_cmd_enqueue_##name`**

Every vkCmd* the application calls only RECORDS (the runtime's vk_cmd_enqueue_*). The real
borgvk_Cmd* implementations are in cmd_dispatch below and run when the command buffer is
submitted (borgvk_queue_submit -> vk_cmd_queue_execute), in submission order, so a copy
waits for its semaphores and a command buffer can be submitted again. The commands borgvk
implements itself are listed here: borgvk_device_entrypoints put them in this table first
and the line above only fills what was empty.

**line 1000, in `borgvk_CreateDevice`, above `BORGVK_RECORD_CMD(CmdBeginRendering);   BORGVK_RECORD_CMD(CmdEndRendering);`**

The legacy vkCmd{Begin,Next,End}RenderPass* stay borgvk's translators (they call
vk_common_*, which turns them into vkCmdBeginRendering / vkCmdEndRendering through this
table); the dynamic-rendering commands they produce are what gets recorded and replayed.

**line 1021, in `borgvk_CreateDevice`, above `vk_device_dispatch_table_from_entrypoints(&dispatch_table,`**

Finally, fill anything STILL null from the runtime's generic
implementations.  vk_cmd_enqueue_device_entrypoints above only covers
vkCmd* functions, so before this every non-Cmd core entrypoint that
borgvk didn't implement itself resolved to NULL and
vkGetDeviceProcAddr returned nullptr for it -- caught by
dEQP-VK.api.version_check.entry_points listing eight of them
(GetDeviceMemoryCommitment, GetImageSparseMemoryRequirements2,
UpdateDescriptorSetWithTemplate, ResetQueryPool,
Get{Buffer,DeviceMemory}OpaqueCaptureAddress,
GetDeviceImage{,Sparse}MemoryRequirements), all of which Mesa already
implements generically.

Ordering matters and is deliberate: this comes AFTER the cmd-enqueue
table, so every vkCmd* keeps its existing record-into-queue behaviour
and only genuinely-empty slots change.  overwrite=false throughout means
borgvk's own entrypoints always win.

**line 1043, in `borgvk_CreateDevice`, above `if (!device->cmd_dispatch.CmdExecuteCommands)`**

Not vk_common_device_entrypoints wholesale: its vkCmd* wrappers (vk_common_CmdSetEvent,
vk_common_CmdPipelineBarrier, ...) call through the MAIN table, which now records, so a
replay would append to the very queue it is walking. ExecuteCommands is the one wanted:
it replays the secondaries through command_dispatch_table, i.e. this table.

**line 1049, in `borgvk_CreateDevice`, above `{`**

vk_cmd_queue_execute calls every recorded command through this table without a NULL check:
give the commands borgvk does not act on (state setting, draws on the way to the generic
path, ...) an empty body. A command's arguments sit in registers and the callee ignores
them, so one function serves every signature.

**line 1067, in `borgvk_CreateDevice`, above `vk_device_enable_threaded_submit(&device->vk);`**

Queue work runs on a thread when it has to (a submit waiting on work that has not been
submitted yet), as in lavapipe and v3dv; the sync type has real state and a move().

**line 1087, in `borgvk_CreateDevice`, above `device->queue.driver_submit = borgvk_queue_submit;`**

On every vkQueueSubmit, read the MVP from the bound uniform buffer and
ship it to the FPGA over serial. This is the driver's whole render path.

**line 1116, in `borgvk_GetInstanceProcAddr`, above `PUBLIC VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL`**

With version 1+ of the loader interface the ICD should expose
vk_icdGetInstanceProcAddr to work around certain LD_PRELOAD issues.

## borgvk_hw.c

**line 1, in `(file level)`, above ``**

* borgvk_hw.c — the Borg behind the DRM render node (or `direct_sim --raw` on a host).
The wire stream a frame would send over the serial link is run through borg_core
(software/borg) in this process; its register and memory hooks (borg_hw.c) are ioctls
on /dev/dri/renderD128. The colour attachment comes back by reading GPU memory.

**line 27, in `borgvk_hw_enabled`, above `size_t`**

Render `n` bytes of wire stream on a dim x dim target; the pixels (RGB888, or the raw
32-bit words of a RAW32 target) go to `out`. Returns the number of bytes, 0 on failure.

**line 40, in `borgvk_hw_render`, above `bool target_seen = false;`**

a 0xB6 packet carries the app's clear colour; otherwise the default

**line 87, in `borgvk_hw_render`, above `if (fmt) {`**

tile = 16 pixels x 4 B: R,G,B,A (fmt 1) or B,G,R,A (fmt 2)

## borgvk_pipeline.c

**line 1, in `(file level)`, above ``**

* Pipelines for borgvk. The cube's vertex/fragment shaders are already
hand-compiled to SPIR-B in the firmware (vert_borg/frag_borg), so there is no
host-side shader compilation: a VkPipeline is an opaque placeholder that lets
pipeline creation and binding succeed. The actual draw is realized at submit
time by shipping the MVP to the FPGA.

**line 67, in `borgvk_CreateGraphicsPipelines`, above `vfetch |= 0xC0000000u;`**

fetching, and each point is a quad of two triangles

**line 72, in `borgvk_CreateGraphicsPipelines`, above `vfetch |= 0x90000000u;`**

fetching, and each line is a quad: borgvk_lower_lines

**line 74, in `borgvk_CreateGraphicsPipelines`, above `for (int st = 0; st < BORGVK_SHADER_STAGE_COUNT; st++)`**

Compile each shader stage's SPIR-V → NIR → Borg ISA (borgc). The blobs land in the
device (the cube's path uploads those) and are kept in the pipeline for generic draws.

**line 79, in `borgvk_CreateGraphicsPipelines`, above `uint32_t color_count = ri ? ri->colorAttachmentCount : 0, mrt_opt = 0;`**

Several colour attachments: every one an R8 format, whose class the fragment stage packs
(1 UNORM, 2 SNORM, 3 UINT or SINT).

**line 98, in `borgvk_CreateGraphicsPipelines`, above `struct borg_blend blend = { 0 };`**

A RAW attachment has no fixed-function blend or write mask: the shader does both.

**line 137, in `borgvk_CreateGraphicsPipelines`, above `const VkPipelineShaderStageCreateInfo *vs = NULL, *fs = NULL;`**

gl_PointCoord: the fragment stage reads two varyings placed after the vertex stage's own.

**line 194, in `borgvk_CreateGraphicsPipelines`, above `VkGraphicsPipelineCreateInfo ci = pCreateInfos[i];`**

With rasterizer discard the spec ignores the viewport, multisample, depth/stencil
and colour-blend pointers (they may be invalid), so never read them.

## borgvk_query.c

**line 1, in `(file level)`, above ``**

* Minimal query-pool stubs. borgvk does no host-side timestamping or occlusion
queries, but Mesa's common WSI references vkCreateQueryPool/vkDestroyQueryPool
for swapchain present-timing bookkeeping (and calls DestroyQueryPool even when
no pool was created). There's no vk_common implementation for these, so the
driver must provide non-NULL entrypoints. They allocate an opaque object and
report zeroed results.

## borgvk_shader_cache.c

**line 1, in `(file level)`, above ``**

* borgvk_shader_cache.c — compiled shaders keyed by their SPIR-V.
A board running borgvk compiles slowly. $BORGVK_SHADER_CACHE names a file of compiled
blobs; a stage whose SPIR-V is in it skips SPIR-V → NIR → borgc altogether.
$BORGVK_SHADER_CACHE_RECORD=<file> appends every compiled stage to such a file.
Entry: u64 key, u8 flags (bit 0: reads gl_PointCoord), u16 len, blob.

## borgvk_sim.c

**line 1, in `(file level)`, above ``**

* borgvk_sim.c — the persistent simulator behind the driver.
One `direct_sim --serve` process per driver process stands in for the GPU: it keeps
its state between passes, takes wire packets on a pipe and shares its memory with the
driver, so attachments go in and come back by copying memory instead of through a
process per draw. The application's images stay the truth: a pass copies its
attachments into GPU memory, every draw loads them (and leaves alone the tiles it does
not reach), and the pixels the pass changed are copied back.

**line 31, in `(file level)`, above `#define SIM_MEM_BYTES 0x8000000u`**

simulation/direct/direct_sim.h DirectSim::MEM_BYTES

**line 104, in `sim_stop`, above `static void *`**

Each server consumes its stream at simulation speed, so they are fed side by side.

**line 128, in `sim_start`, above `S->memfd = mfd;`**

stays open, and inheritable, for the server started on it

**line 176, in `sim_set_size`, above `bool`**

Colour flush format of an attachment (software/borg FlushFormat as borg_core takes it):
1 R8G8B8A8, 2 B8G8R8A8, 3 RAW32; 0 = R5G6B5 for everything else.

**line 202, in `borgvk_sim_half2`, above `static bool`**

Plain 8-, 16- or 32-bit channel formats of 1 to 4 channels that the fragment stage packs into RAW words
(compiler option bits, see borgc "Packed colour" and the half-float block); false for any other format.

**line 277, in `borgvk_sim_flush_format`, above `bool`**

Whether the persistent simulator can render to this colour attachment: the direct
simulator, and a power-of-two-wide target of up to 1024 x 1024 pixels with depth, 4096 x 4096 without.

**line 286, in `borgvk_sim_serves`, above `const uint32_t max_dim = has_depth_stencil ? 1024 : 4096;`**

The depth plane (BORG_ZB_SPI) holds 1024 x 1024 D32 pixels; colour alone goes up to 4096 x 4096.

**line 320, in `px_put`, above `static inline uint32_t`**

Byte address inside an attachment of pixel (x, y): 4x4 tiles of `tile_bytes`, row-major
tiles, row-major pixels of `px_bytes` in a tile.

**line 390, in `pack_s`, above `bool`**

Depth as the GPU stores it for this attachment: D16_UNORM for a 16-bit host depth,
D32_SFLOAT for everything else.

**line 398, in `borgvk_sim_depth_is_d32`, above `VkResult`**

Run one pass: `stream` holds its packets (pass, state, draws). The attachments' content
goes to the GPU first and what the draws changed comes back.

**line 413, in `borgvk_sim_run_pass`, above `const uint32_t hp = (h + 3) & ~3u;`**

the GPU renders whole tiles: a short, narrow or odd target is padded

**line 442, in `borgvk_sim_run_pass`, above `uint8_t *before = malloc(npx * (16 + 4 + 1 + 3));`**

what went in: colour, depth, stencil, attachments 1-3

**line 599, in `borgvk_sim_run_pass`, above `for (uint32_t i = 0; i < nwb; i++)`**

Storage buffers: the bytes a draw's shaders stored, back into the application's buffer.
Every strip started from the same bytes, so a byte that differs was stored.

## borgvk_sync.c

**line 1, in `(file level)`, above ``**

* Synchronization for borgvk. vkQueueSubmit ships the frame to the FPGA over
serial and returns once it's been sent, so from the host's perspective all
GPU work is already complete by the time anyone waits. That makes every sync
operation trivial: init/signal/reset/wait all succeed immediately. This one
type advertises every feature fences and (binary) semaphores need, so no
timeline/binary wrappers are required.

**line 23, in `(file level)`, above `struct borgvk_sync {`**

---- Binary sync (fences, binary semaphores) -------------------------------------------- *
A signalled flag per object under one process-wide lock and condition variable: waiters are
few and short-lived, and a single condvar lets wait_many sleep on several syncs at once.
Real state matters now that submission can be threaded (vk_device_enable_threaded_submit):
a fence or semaphore must stay unsignalled until the queue has actually run the work, and
a wait must block until then. "GPU" waits and signals are done by borgvk_queue_submit itself
(borgvk's GPU is driven from the submit thread, in order): it waits on submit->waits before
running the work and signals submit->signals after. A wait on a sync whose signal has not
been submitted yet just blocks until it is, which is what WAIT_PENDING asks for.

**line 88, in `borgvk_sync_reset`, above `static VkResult`**

Moves the signalled state from src to dst and resets src (required of binary types
once the device can submit on a thread).

**line 159, in `borgvk_sync_wait_many`, above `struct borgvk_event {`**

---- Events ---------------------------------------------------------- *
VkEvent is a separate mandatory core-1.0 object (not a vk_sync type), and
was entirely missing -- same story as vkCreateBufferView: a null dispatch
slot that SEGVs the moment CTS's command_buffers.* tests call through it.
Host-only storage, modeled on lavapipe's lvp_event: Borg has no device-side
event wait/signal, and every command-buffer op here is already complete by
the time an app can observe it (see borgvk_sync_wait_many above).

**line 238, in `borgvk_ResetEvent`, above `VKAPI_ATTR void VKAPI_CALL`**

---- Event commands, run when the command buffer is submitted ---------------------------- *
(cmd_dispatch in borgvk_device.c; the application-facing table only records them.) The v1
and v2 forms are both implemented because the runtime records whichever the app called.

## borgvk_wsi.c

**line 1, in `(file level)`, above ``**

* WSI (window-system integration) for borgvk.  borgvk's real output is the
FPGA's HDMI monitor, reached over serial at submit time.  WSI only needs to
function (surfaces, swapchains, acquire/present all succeed) so an unmodified
app (e.g. Vulkan-Tools/cube.c) runs its render loop and submits each frame.
No host window is created.  borgvk overrides every platform surface-creation
entrypoint to silently return a headless surface (VK_ICD_WSI_PLATFORM_HEADLESS)
regardless of what WSI the app requests.  Mesa's headless WSI backend (always
initialized by wsi_device_init) then handles swapchain/acquire/present without
opening any display connection.  The app's window-system call succeeds, its
extension check passes, and nothing appears on the workstation screen.
wsi_device_init + the wsi_*_entrypoints still provide GetSurfaceCapabilities,
GetSurfaceFormats, etc.; only surface *creation* is overridden here.

**line 63, in `borgvk_wsi_finish`, above `static VkResult`**

── Surface-creation overrides ─────────────────────────────────────────────
These are picked up automatically by borgvk_instance_entrypoints (which is
loaded with priority=true before wsi_instance_entrypoints in CreateInstance).
Each function ignores its platform-specific parameters and returns a minimal
headless surface so no window-system connection is ever opened.

## borgvk_serial.c

**line 1, in `(file level)`, above ``**

* Serial transport for borgvk. The real GPU is the ULX3S FPGA, reached over the
board's FT231X USB-serial bridge (/dev/ttyUSB0 @115200). Each frame the submit
path hands us the 4×4 model-view-projection matrix; we frame it and write it to
the port. The firmware (software/borg/borg_kernel.c) decodes the packet and
renders the frame through the autonomous TBR sequencer.
Packet format ("0xAD" full-MVP):
  byte 0      : 0xAD marker
  bytes 1..64 : 16 little-endian float32, row-major mvp[4][4] (cube.c order)
  byte 65     : XOR checksum of bytes 1..64
Total 66 bytes. Unlike the legacy 0xAC rotation matrix, MVP entries are not
bounded to [-1,1] (projection scales them), so the firmware validates with the
checksum instead of a magnitude range.
Sim transport: when $BORGVK_SIM_SOCKET is set, packets go to a Unix domain
socket instead of the serial port — the interactive verilator/arcilator
viewer (simulation/verilator/viewer.py) listens there and injects the same
bytes into the simulated hardware UART RXD line.  Same wire protocol, same
borg_kernel.c firmware image; only the transport differs.

**line 44, in `(file level)`, above `#define BORGVK_GEOM_PKT_LEN \`**

The geometry packet is a fixed length with padded, fixed-offset regions so the
UART drain reads a constant byte count (must match the firmware RX_GEOM_*):
marker, nverts, ntris, MAX_VERTS*3 float32 positions, MAX_TRIS*3 u8 indices,
MAX_TRIS*3*2 float32 UVs, XOR checksum -- 520 B.
BORGVK_GEOM_MAX_VERTS/TRIS come from borgvk_private.h.

**line 53, in `(file level)`, above `static int borgvk_serial_fd = -1;`**

Opened lazily on first submit and kept open for the process lifetime. -1 = not
yet attempted, -2 = open failed (don't retry every frame).

**line 95, in `borgvk_serial_open`, above `static int`**

Connect to the interactive sim viewer's Unix domain socket (see
simulation/verilator/viewer.py).  Lazily opened on first submit, kept open
for the process lifetime — same lifecycle as borgvk_serial_open().

**line 128, in `borgvk_sim_socket_open`, above `static void`**

A float32 as its IEEE bits, little-endian: positions and UVs are shader
datapath values, and the datapath is FP32 -- no conversion.

**line 140, in `put_f32_le`, above `static void`**

Write the full packet, then idle so the receiver sees an inter-packet gap
to sync on (the firmware's gap-sync waits for the line to go idle before
trusting the next marker byte — see borg_kernel.c).  `is_socket` selects
tcdrain() (serial, flushes the kernel TTY write buffer) vs a plain sleep
(socket writes are already synchronous once write() returns).

**line 165, in `borgvk_transport_write_paced`, above `static bool     borgvk_capture_active = false;`**

---- Transport sink: serial port / sim socket (default) or capture buffer ---
Selection: capture (during a frame wrapped in capture_begin/end) takes
priority; otherwise $BORGVK_SIM_SOCKET routes to the interactive sim
viewer; otherwise the real serial port.

**line 174, in `borgvk_transport_write_paced`, above `static void`**

Single emit chokepoint for every framed packet.  In capture mode the bytes
are appended to the growable buffer (no serial port, no pacing — the sim
injects them all at once); otherwise they are paced out to the socket or
serial fd.

**line 235, in `borgvk_transport_capture_end`, above `void`**

Ship the app's real mesh: nverts unique model-space positions, ntris triangles
each indexing 3 of them, with per-triangle-vertex UVs.  Fixed-offset padded
regions keep the packet a constant length (see firmware RX_GEOM_*).

**line 272, in `borgvk_serial_send_geom`, above `#define BORGVK_TEX_PKT_LEN (1 + 1 + 16 + BORGVK_TEX_DIM * 4 + 1)`**

0xAF: marker, y, sampler descriptor (4 words LE), BORGVK_TEX_DIM RGBA8
texels, checksum. Every row carries the sampler, so any row that arrives
intact delivers it.

**line 296, in `borgvk_serial_send_tex_row`, above `#define BORGVK_SHADER_SMALL   512   /* the firmware's BC_SHADER_MAX; a larger blob goes in`**

0xB0 shader upload: marker, stage(1B), len(2B LE), blob padded to
BORGVK_SHADER_BLOB_MAX, checksum. Fixed length so the firmware drain reads a
constant byte count (like 0xAE/0xAF); `len` says how many blob bytes are valid.
517 B total < the firmware's 0xAF drain buffer, so no RX buffer growth needed.

**line 300, in `borgvk_serial_send_tex_row`, above `#define BORGVK_SHADER_SMALL   512`**

the firmware's BC_SHADER_MAX; a larger blob goes in a 0xC1 packet

**line 333, in `borgvk_serial_send_shader`, above `#define BORGVK_PUSH_MAX_WORDS 32`**

0xB2 push constants: marker, off_words(1B), n_words(1B), data padded to
BORGVK_PUSH_MAX_WORDS words, checksum.  0xB1 is the firmware's serial-reload
trigger, hence 0xB2.  Fixed length for the same reason as 0xAE/0xAF/0xB0:
the firmware drain reads a constant byte count per marker, and `n_words`
says how much of the payload is valid.

32 words = 128 B = Vulkan 1.0's minimum maxPushConstantsSize, which is what
this driver advertises.  Raising that limit means growing this AND the
firmware's BORG_PUSH_CONST_MAX_WORDS together -- the firmware clamps, so a
mismatch truncates silently rather than corrupting, but it would still be a
wrong answer.

**line 351, in `borgvk_serial_send_push_constants`, above `if (!values || size == 0 || (offset & 3) || (size & 3)) {`**

Vulkan requires both to be multiples of 4 (VUID-vkCmdPushConstants-offset
-00368 / -size-00369); check rather than assume, because the whole
word-index mapping below is built on it.

**line 374, in `borgvk_serial_send_push_constants`, above `memcpy(&pkt[3], values, size);`**

memcpy, not a u32 store loop: `values` is void* from the application and
carries no alignment guarantee, and the wire format is little-endian
bytes either way.

**line 451, in `borgvk_serial_send_mvp`, above `borgvk_transport_emit(pkt, sizeof(pkt));`**

Paced write: the firmware aligns to packets via the inter-packet IDLE GAP,
so a blocking app's back-to-back submits would otherwise stream gaplessly
and freeze the cube (it locks onto the first packet and never re-syncs).

**line 460, in `borgvk_serial_send_mvp`, above `const char *ms_env = getenv("BORGVK_FRAME_MS");`**

Steady frame pacing (BORGVK_FRAME_MS): hold each host frame to a fixed
wall-clock period.  cube.c spins by a CONSTANT angle per frame, so emitting
MVPs at a steady rate at/below the FPGA's sustained render rate makes the
displayed motion advance by exactly one angle step per shown frame —
removing the judder from subsampling ~60 host fps down to ~15 with a
variable stride.  Because re-rendering the same MVP yields an identical
image, the FPGA's per-frame time variance no longer affects the motion.
Drift-free: the deadline advances by a fixed period, not from "now".
Unset = no throttle (legacy free-run behaviour).

**line 518, in `borgvk_serial_send_state`, above `void`**

0xC2 viewport and scissor: marker, x y w h minDepth maxDepth (float32 LE), scissor x0 y0 x1 y1
(u32 LE), csum. Only the simulator's host driver acts on it.

**line 538, in `borgvk_serial_send_viewport`, above `void`**

0xB6 render target: marker, flush format (0 R5G6B5, 1 R8G8B8A8, 2 B8G8R8A8), the clear colour
(4 x float32 LE), csum. Only the simulator's host driver acts on it.

**line 571, in `emit_checked`, above `void`**

Host-simulator only. 0xB8: write n (<= 256, a multiple of 4 is enough; the rest is padded)
bytes at heap offset `off` (BORG_HEAP_SPI + off in the device's memory).

**line 585, in `borgvk_serial_send_mem`, above `void`**

Host-simulator only. 0xB9: vertex attribute `slot` is a typed fetch (texture format code
`fmt`, component swizzle bits `swz` as in the texture descriptor) of `count` rows `stride`
bytes apart from heap offset `base` (two's complement, may precede the uploaded data).

**line 603, in `borgvk_serial_send_vattr`, above `void`**

Host-simulator only. 0xBA: run a draw. topology 0 list / 1 strip / 2 fan, index_type 0 none /
1 16-bit / 2 32-bit, index_base = heap offset of the first index.

**line 623, in `borgvk_serial_send_draw`, above `void`**

Host-simulator only: how many colour attachments the pass has, and the flush formats of 1-3.

**line 631, in `borgvk_serial_send_att`, above `void`**

Host-simulator only: the pass's attachments. flags bit 0 depth, 1 stencil, 2 D32_SFLOAT depth.

**line 674, in `borgvk_state_pack`, above `reg[4] = ((uint32_t)rs->cullMode & 3) |`**

VkCullModeFlags bit 0 = front, bit 1 = back, as in CULL_CFG. cube.c's
CCW winding is the hardware's unmodified facing; CW inverts it.

**line 682, in `borgvk_state_pack`, above `void`**

0xB5: marker, off (LE u32), n (LE u16), desc words 1..3, sampler words,
BORGVK_TEXG_CHUNK data bytes (zero padded), csum.

**line 712, in `borgvk_serial_send_texture_chunk`, above `void`**

0xC0: marker, the byte offsets of levels 1..12 (LE u32 each), checksum. Precedes the 0xB5
chunks of a texture with several levels.

## borgvk_memory.c

**line 1, in `(file level)`, above ``**

* Memory, buffers, images, image views and samplers for borgvk. Everything is
backed by plain host RAM (the real GPU is the FPGA over serial); these
entrypoints exist so an app can allocate/bind/map resources during init. The
submit path (Phase 3) reads the bound uniform buffer's mapped bytes.

**line 172, in `borgvk_DestroyBuffer`, above `VKAPI_ATTR VkResult VKAPI_CALL`**

---- Buffer views -------------------------------------------------------
Was entirely missing: vkCreateBufferView/vkDestroyBufferView are
mandatory core Vulkan 1.0 entry points (not extension-gated like sparse
binding), so their dispatch-table slot was null and calling through it
crashed with a SEGV at instruction address 0 -- confirmed via
coredumpctl/gdb backtrace through vk::createBufferView() ->
BufferViewTestInstance -- rather than any real driver logic being wrong.

**line 217, in `borgvk_GetDeviceBufferMemoryRequirements`, above `uint64_t size = pInfo->pCreateInfo->size;`**

align64(size, 256) overflows (wraps toward 0) for a size within 255 of
UINT64_MAX. borgvk_CreateBuffer never rejects an oversized VkBuffer (it
defers all real allocation to vkAllocateMemory/vkBindBufferMemory), so
reporting a wrapped, too-small size here made VK-GL-CTS's
api.buffer.basic.size_max_uint64 fail: it requires
memoryRequirements.size >= the buffer's own requested size whenever
creation succeeds. Saturate instead of wrapping.

**line 257, in `borgvk_BindBufferMemory2`, above `VKAPI_ATTR void VKAPI_CALL`**

Was entirely missing (discarded into the generic vk_cmd_queue). Needed by
ImageClearingTestInstance::preClearImage, which cmdFillBuffer(0)s a staging
buffer before uploading it into an image via CmdCopyBufferToImage -- a
no-op fill meant that upload copied whatever garbage the staging buffer's
host allocation already contained instead of zero.

**line 278, in `borgvk_CmdFillBuffer`, above `VKAPI_ATTR void VKAPI_CALL`**

vkCmdUpdateBuffer: inline data embedded in the command buffer, copied into
the destination at submit time.  Was entirely missing -- same discard-into-
vk_cmd_queue story as CmdFillBuffer above, so every
dEQP-VK.api.fill_and_update_buffer.*.update_buffer_* case read back whatever
the destination already held.  (The fill_buffer_* cases in the same group
passed, which is exactly why this looked implemented from the group name.)

Spec constraints that make this a plain memcpy: dataSize is at most 65536,
and both dstOffset and dataSize must be 4-byte multiples, so there is no
partial-word or oversized case to handle.

**line 301, in `borgvk_CmdUpdateBuffer`, above `VKAPI_ATTR void VKAPI_CALL`**

Plain buffer-to-buffer copy: also entirely missing, same no-op-via-
vk_cmd_queue-discard story as every other unimplemented vkCmd* in this
file. No format/block-size concerns at all here -- just raw bytes.

**line 339, in `borgvk_CmdCopyBuffer2`, above `static uint64_t`**

One consistent linear layout, shared by every place that reads or writes
image bytes (borgvk_image_size, CmdClearColorImage, CmdCopyImage,
CmdCopyImageToBuffer): layer-major, mip-minor -- layer 0's full mip chain
(level 0, level 1, ...) is contiguous, then layer 1's, etc. This is
internal to borgvk only (VK_IMAGE_TILING_LINEAR's real layout is
implementation-defined, and nothing outside this driver ever reads this
memory), but every reader/writer MUST agree, or an offset computed one way
and validated against a total computed another way silently walks off the
end of the allocation -- the same class of bug as the earlier CmdCopyImage
block-size overflow. Returns the level's size in bytes and, via
*width_blocks_out, its row stride (in format blocks) for callers doing
row-by-row copies.

**line 361, in `borgvk_mip_level_size`, above `uint32_t d_blocks = DIV_ROUND_UP(d, bd);`**

bd (block depth) is >1 only for 3D block-compressed formats (e.g. any
ASTC_*x*x*_BLOCK_EXT), where a single bs-byte block already encodes a
bd-texel-deep volume -- not accounting for it here overstated the level
size (and every offset derived from it) by a factor of bd, which
combined with the 3D copy paths' per-Z-slice addressing walked off the
end of the allocation. Confirmed via coredumpctl/gdb: "corrupted
double-linked list" surfacing in a later borgvk_FreeMemory, found
running dEQP-VK.api.copy_and_blit...3d_to_3d.astc_3x3x3_unorm_block_ext.
bd=1 for every other format, so this is a no-op there.

**line 377, in `borgvk_mip_level_size`, above `static uint64_t`**

Byte size of ONE sample plane of ONE array layer (the full mip chain, for
a single MSAA sample index). Multisampled images always have exactly 1
mip level (Vulkan requires mipLevels=1 whenever samples>1), so for those
this is just that single level's size.

**line 394, in `borgvk_image_sample_plane_size`, above `static uint64_t`**

Byte size of ONE array layer, including all its MSAA sample planes (a
layer of a samples>1 image packs `image->vk.samples` consecutive sample
planes back to back -- see borgvk_image_size and the CmdResolveImage/
clear-to-all-samples code, which add a sample's plane offset on top of
what borgvk_image_level_offset (below) returns for this layer/level).
For samples==1 (every non-MSAA image) this is a no-op multiply by 1, so
every existing copy/blit/clear caller of this function is unaffected.

**line 409, in `borgvk_image_layer_size`, above `static uint64_t`**

Byte offset of (level, layer)'s data within the image's backing memory,
plus that level's row stride (in format blocks).

**line 431, in `borgvk_image_level_offset`, above `static inline bool`**

PIPE_FORMAT_Z16_UNORM_S8_UINT (VK_FORMAT_D16_UNORM_S8_UINT) has NO
util_format_{pack,unpack}_{z_float,s_8uint} implementation anywhere in
Mesa -- every one of those six functions is a bare UNREACHABLE() stub
(src/util/format/u_format_zs.c), confirmed by hitting the assertion
running dEQP-VK.api.image_clearing...d16_unorm_s8_uint. This is an
upstream Mesa gap, not something to route around silently: hand-roll the
packing here instead, using the format's own documented layout
(u_format.yaml: channels [UN16, UP8], no padding -- 2-byte little-endian
UNORM16 depth followed directly by a 1-byte stencil, 3 bytes/texel).

**line 461, in `borgvk_z16_s8_unpack_z`, above `static inline bool`**

VK_FORMAT_R4G4_UNORM_PACK8 has no entry anywhere in
vk_format_to_pipe_format()'s mapping table -- unlike Z16_UNORM_S8_UINT
(which has a valid pipe_format with stub pack/unpack functions),
this format has no pipe_format counterpart at all, so
vk_format_to_pipe_format() silently returns PIPE_FORMAT_NONE for it.
Every util_format_*() call downstream (pack_rgba, unpack_rgba, ...) then
operates on a dummy "no format" description and produces all-zero output
with no crash and no error -- confirmed via
dEQP-VK.api.image_clearing...r4g4_unorm_pack8 reading back (0,0,0,1)
instead of the requested clear colour, and the same format appearing as
a blit destination in dEQP-VK.api.copy_and_blit...blit_image failures.
vk_format_get_blocksize() still happens to return the numerically
correct answer (1 byte) purely by coincidence: its own !desc fallback
defaults to 1, which is simply correct here. Hand-rolled: bits [7:4] = R,
bits [3:0] = G (Vulkan's PACK8 component order is MSB-first), each a
plain 4-bit UNORM channel -- this format is never pure-integer, so only
the float unpack/pack path needs the special case.

**line 511, in `borgvk_pack_rgba_float`, above `static inline bool`**

Which VkClearColorValue union member a format's clear color uses. Matches
CTS's own classification (VK-GL-CTS's getTextureChannelClass): UINT and
USCALED both read .uint32, SINT and SSCALED both read .int32, everything
else (UNORM/SNORM/UFLOAT/SFLOAT/SRGB) reads .float32. Gallium's
util_format_is_pure_uint/sint alone isn't enough here -- it deliberately
excludes SCALED formats (pure_integer is false for "U10"/"S10"-style
unnormalized-but-not-pure-integer channels).

**line 540, in `borgvk_clear_color_is_sint`, above `static inline void`**

Packs a VkClearColorValue into `pixel` for the given format, picking the
right union member per borgvk_clear_color_is_uint/is_sint above. For a
TRUE pure-integer format (UINT/SINT) this hands the union member's raw
array straight to util_format_pack_rgba, matching its own internal
is_pure_uint/is_pure_sint dispatch exactly. For a SCALED format, though,
util_format_pack_rgba's dispatch (which only checks pure_integer, not
is_scaled) would take its non-pure "else" branch and reinterpret our
uint32_t/int32_t array's raw BITS as a float* -- type-punning e.g. the
integer 51 into the float bit-pattern of a tiny denormal near zero,
packing to all-zero bytes. Confirmed via a debug print on
dEQP-VK...clear_color_image...a2b10g10r10_sscaled_pack32: is_sint=1,
int32={51,255,153,0} (exactly the expected value -- classification was
already correct), yet packed pixel={0,0,0,0}. Converting the integer
components to float first fixes it: that's exactly the representation a
SCALED format's pack_rgba_float expects (the raw integer value presented
as float, no normalization), which is what CTS's own reference does too
(VK-GL-CTS's makeClearColorValue routes SSCALED/USCALED through
.int32/.uint32 while the shader-facing numeric value is still the plain
integer, just typed as float in the pipeline -- SCALED's whole point).

**line 563, in `borgvk_pack_clear_color`, above `int first_ch = util_format_get_first_non_void_channel(pfmt);`**

R64_UINT/R64_SINT/R64G64_UINT/R64G64_SINT (64-bit-per-channel integer
formats): util_format_pack_rgba's generic dispatch always casts src to
a 32-bit-per-component pointer, matching every other integer format's
clear-value convention, regardless of the format's actual channel
width, so it cannot correctly pack one of these; confirmed via a debug
print on dEQP-VK...clear_color_image...r64_uint: the real 64-bit clear
value's low 32 bits packed correctly but the high 32 bits were
silently discarded, reading back as 2147483647 (0x7FFFFFFF, exactly
color->uint32[0] alone) instead of the actual huge reference value.
These formats are a raw native 64-bit integer per channel with no
bit-packing math at all, and CTS builds their VkClearColorValue via a
straight memcpy of each 64-bit value into a consecutive uint32 pair
(vktApiImageClearingTests.cpp, makeClearColorValue64), which is
therefore already byte-identical to this format's storage layout; skip
the pack machinery entirely and copy the union's raw bytes instead.

**line 602, in `borgvk_pack_clear_color`, above `static bool`**

For a combined depth-stencil image, a copy naming only one aspect
(VkBufferImageCopy::imageSubresource.aspectMask) writes/reads a buffer
packed with just that aspect's own element size (e.g. 1 tightly-packed
byte per texel for VK_IMAGE_ASPECT_STENCIL_BIT on D24_UNORM_S8_UINT), not
the combined format's element size (4 bytes there). Blindly using
vk_format_get_blocksize(image->vk.format) for that side overflowed the
buffer by up to 8x -- a real "double free or corruption" heap-overflow
abort confirmed via coredumpctl/gdb in a later borgvk_FreeMemory (found
running dEQP-VK.api.command_buffers.record_many_draws_primary_1, which
exercises a depth-stencil attachment readback). For VK_IMAGE_ASPECT_COLOR_BIT
this returns the image's own format unchanged, so callers that always pass
COLOR_BIT see no behavior change. A region can also legally name BOTH
VK_IMAGE_ASPECT_DEPTH_BIT and _STENCIL_BIT together (image-to-image copies
between two images of the same combined format): that means "copy the
whole combined texel" and must NOT be forwarded to
vk_format_get_aspect_format(), which asserts on a single-bit aspect mask
(confirmed via a clean assertion failure, not a crash, running
dEQP-VK.api.copy_and_blit...depth_stencil_aspects tests) -- so it's handled
as the identity case here, same as passing the format through unchanged.

**line 621, in `borgvk_pack_clear_color`, above `static bool`**

True when `aspect` names only ONE aspect of a combined depth+stencil format.

This is exactly the case where a raw whole-texel memcpy is wrong even though
the two element sizes happen to match. For VK_FORMAT_D24_UNORM_S8_UINT the
depth aspect's transfer format is X8_D24_UNORM_PACK32 -- also 4 bytes -- so
a `bs == img_bs` fast path would memcpy the full 32-bit texel and blow away
the stencil byte that shares it. The aspect-selective pack/unpack path
read-modify-writes only the named aspect's bits and must be used instead.
Confirmed by dEQP-VK.api.copy_and_blit.*.buffer_to_depthstencil.
buffer_offset_d24_unorm_s8_uint_{SD,S_D} reporting "Stencil comparison
failed" while depth compared clean.

**line 664, in `borgvk_image_size`, above `uint64_t size = layer_size * MAX2(info->arrayLayers, 1) * MAX2(info->samples, 1);`**

Multisampled images pack `samples` consecutive sample planes per array
layer (matches borgvk_image_layer_size's convention) -- Vulkan requires
mipLevels==1 whenever samples>1, so layer_size above is already a
single sample plane's size for those. No-op for samples==1.

**line 728, in `borgvk_GetImageMemoryRequirements2`, above `VKAPI_ATTR void VKAPI_CALL`**

Linear subresource layout. cube.c queries this to upload its staging texture
row by row; report a tightly-packed linear layout matching how an app maps and
writes the malloc-backed image memory (mip 0 / layer 0 at offset 0).

This computed row = width(texels) * blocksize(bytes/BLOCK) directly, with no
division by block width/height/depth at all -- for any block-compressed
format that overstated the real row pitch by a factor of the block extent
(e.g. 16x for a 4x4-block format, up to 27x for a 3x3x3 3D-block ASTC
format). Real apps and CTS alike use exactly this query to know how far
apart rows are before writing into a mapped LINEAR image directly, so a
wrong answer here doesn't fail loudly at the call site -- it silently walks
the write past the real allocation, corrupting unrelated heap state that
only aborts later. This was a live, previously-unaudited bug independent
of every other block-size fix earlier in this file (nothing else calls
this function's math). Addressed via the same shared block-aware layout
(borgvk_image_level_offset) as everything else, generalized to any
mip level/array layer instead of always assuming (0, 0).

**line 825, in `borgvk_DestroyImageView`, above `VKAPI_ATTR void VKAPI_CALL`**

Every other vkCmd* is recorded into the generic vk_cmd_queue and discarded
on reset (see the file comment in borgvk_cmd_buffer.c) -- fine for
rendering, since the cube's real state is read from bound
buffers/descriptors at submit time, not replayed commands. But
CmdClearColorImage's whole observable effect IS the write it makes to image
memory, and CTS's image_clearing tests read that memory back afterward. Left
unimplemented, the clear byte pattern was whatever the host malloc arena
happened to already contain -- which is why the *_clamp_input tests were
flipping Pass/Fail between otherwise-identical runs (confirmed by diffing
two full CTS runs where the only driver change was unrelated: whatever
garbage ended up in a given allocation depends on the process's prior
allocation history). Fixed the same way as CmdCopyImage: execute for real
at record time instead of queuing, addressed via the shared
borgvk_image_level_offset layout so every mip level/array layer in range is
covered, not just level 0. util_format_pack_rgba() clamps to the
destination format's representable range, which is exactly the
"_clamp_input" semantics CTS is checking.

**line 858, in `borgvk_CmdClearColorImage`, above `if (bd > 1)`**

3D-block-compressed formats (any ASTC_*x*x*_BLOCK_EXT) remain a defended
gap: the format-query rejection in borgvk_GetPhysicalDeviceImageFormatProperties2
doesn't reliably stop every CTS code path from creating one anyway, and
getting this addressing fully right cost more debugging than this format
class's near-zero real-world usage justifies. No-op rather than risk
writing outside the allocation.

**line 885, in `borgvk_CmdClearColorImage`, above `uint64_t plane_size = borgvk_image_sample_plane_size(image, bs, bw, bh, bd);`**

Multisampled images clear identically to every sample plane --
a clear has no per-sample coverage, it's a uniform fill. See
borgvk_image_layer_size's comment for the sample-plane packing
this offset math relies on. No-op loop (1 iteration, +0 offset)
for samples==1.

**line 903, in `borgvk_CmdClearColorImage`, above `VKAPI_ATTR void VKAPI_CALL`**

Same reasoning and layout as CmdClearColorImage, but depth/stencil clears
can legally touch only ONE aspect of a combined format (e.g. depth-only on
D24_UNORM_S8_UINT), which must leave the other aspect's bits in each texel
untouched -- unlike a colour clear, this can't be a blind per-texel
memcpy/overwrite. Rather than hand-deriving each combined format's bit
layout, this uses gallium's own util_format_pack_z_float/pack_s_8uint,
which read-modify-write exactly the requested aspect's bits and leave the
rest alone (the same packing tables CTS's own reference implementation is
built on, so this matches what CTS expects by construction).

**line 928, in `borgvk_CmdClearDepthStencilImage`, above `if (bd > 1)`**

See the matching comment in CmdClearColorImage: 3D-block-compressed
formats remain a defended gap, not a supported combination.

**line 951, in `borgvk_CmdClearDepthStencilImage`, above `uint64_t plane_size = borgvk_image_sample_plane_size(image, bs, bw, bh, bd);`**

See the matching comment in CmdClearColorImage: a clear fills
every sample plane identically. No-op loop for samples==1.

**line 978, in `borgvk_CmdClearDepthStencilImage`, above `static void`**

Clears a rectangular sub-region of one image view's base mip level, across
`layerCount` array layers starting at the view's base layer + baseLayer.
Shared by borgvk_CmdBeginRendering (VK_ATTACHMENT_LOAD_OP_CLEAR) and
borgvk_CmdClearAttachments -- both are "clear this rectangle of this
attachment" in the end, just triggered differently. Unlike
CmdClearColorImage/CmdClearDepthStencilImage (which always clear a whole
subresource), this is bounded to an arbitrary rect within the level.

**line 1002, in `borgvk_clear_attachment_rect`, above `if (bd > 1)`**

See the matching comment in CmdClearColorImage: 3D-block-compressed
formats remain a defended gap, not a supported combination -- and
attachments are never 3D images with real depth slices in practice.

**line 1024, in `borgvk_clear_attachment_rect`, above `uint64_t plane_size = borgvk_image_sample_plane_size(image, bs, bw, bh, bd);`**

See the matching comment in CmdClearColorImage: a clear fills every
sample plane identically. No-op loop for samples==1.

**line 1057, in `borgvk_clear_attachment_rect`, above `static void borgvk_resolve_region(struct borgvk_image *src, struct borgvk_image *dst,`**

Defined below (near CmdResolveImage); forward-declared here so
CmdEndRendering2EXT can call it for implicit render-pass resolve.

**line 1062, in `borgvk_clear_attachment_rect`, above `VKAPI_ATTR void VKAPI_CALL`**

borgvk never overrides RenderPass/Framebuffer creation, nor the legacy
CmdBeginRenderPass(2)/CmdEndRenderPass(2) entrypoints -- Mesa's own
vk_common_* runtime code (src/vulkan/runtime/vk_render_pass.c) emulates
all of that generically for any driver, always resolving down to a
VkRenderingInfo and calling the driver's CmdBeginRendering, whether the
app used the legacy API or dynamic rendering directly. This was the only
entrypoint actually missing: without it, every attachment's
VK_ATTACHMENT_LOAD_OP_CLEAR silently did nothing (same discard-into-
vk_cmd_queue story as every other unimplemented vkCmd* in this file), and
CmdClearAttachments had no way to resolve its attachment indices back to
real images -- so this also captures the current attachment set on the
command buffer for CmdClearAttachments to use.

**line 1099, in `borgvk_CmdBeginRendering`, above `cmd->color_resolve_views[i] = resolve_view;`**

resolveMode is always NONE or AVERAGE for a color attachment per
spec, and resolveImageView is only set when a resolve was actually
requested, so a non-NULL view is a sufficient "should resolve"
signal without also checking resolveMode.

**line 1136, in `borgvk_CmdBeginRendering`, above `VKAPI_ATTR void VKAPI_CALL`**

A direct vkCmdEndRendering call resolves to vk_common_CmdEndRendering,
which forwards to CmdEndRendering2EXT below -- but the LEGACY render-pass
path (vkCmdEndRenderPass/2, which is how CTS's clear_color_attachment MSAA
tests actually end their render pass) does NOT go through
vk_common_CmdEndRendering at all: vk_common_CmdEndRenderPass2 calls
`disp->CmdEndRendering` on the main dispatch table directly
(src/vulkan/runtime/vk_render_pass.c). Since borgvk never gave that a real
borgvk_ symbol, it fell into the same generic record-and-discard fallback
that bit CmdBeginRenderPass earlier (see that function's comment) -- a
debug print confirmed CmdBeginRendering fires (so the resolve views below
get captured fine) but CmdEndRendering2EXT never did. Same fix: an
explicit thin wrapper instead of relying on it being reached indirectly.

**line 1154, in `borgvk_CmdEndRendering`, above `VKAPI_ATTR void VKAPI_CALL`**

This is where a multisample resolve actually has to happen: CTS's
clear_color_attachment MSAA tests' resolve is driven entirely by
VkSubpassDescription::pResolveAttachments -- there is no explicit
vkCmdResolveImage call anywhere in that path (confirmed by reading
ClearAttachmentTestInstance::iterate() in CTS). Mesa's
vk_common_CmdEndRenderPass2 emulation turns that subpass resolve
attachment into VkRenderingAttachmentInfo::resolveImageView/resolveMode
when it calls our CmdBeginRendering, which is why the resolve targets are
captured there and performed here at end-of-rendering, matching real
Vulkan's resolve-at-subpass-end timing.

**line 1172, in `borgvk_CmdEndRendering2EXT`, above `borgvk_flush_draws(cmd);`**

the pass's draws land in the attachment before it is resolved

**line 1210, in `borgvk_CmdEndRendering2EXT`, above `VKAPI_ATTR void VKAPI_CALL`**

Was entirely missing -- discarded into the generic vk_cmd_queue like every
other unimplemented vkCmd*. Resolves each VkClearAttachment against the
attachment set borgvk_CmdBeginRendering captured on this command buffer,
then does a real rect clear (borgvk_clear_attachment_rect) per VkClearRect.

**line 1255, in `borgvk_CmdClearAttachments`, above `VKAPI_ATTR void VKAPI_CALL`**

Legacy render-pass entrypoints (CmdBeginRenderPass[2], CmdNextSubpass[2],
CmdEndRenderPass[2]) are NOT auto-serviced by vk_common_* the way they are
in drivers without their own generic-record-and-discard fallback: borgvk's
main dispatch table fills any vkCmd* without a borgvk_ symbol from
vk_cmd_enqueue_device_entrypoints (record into the command buffer's
vk_cmd_queue, discarded on reset -- see the file comment in
borgvk_cmd_buffer.c), and that fallback applies to these entrypoints too
since we never gave them real borgvk_ symbols. That silently ate every
legacy-render-pass CmdBeginRenderPass call, so vk_common_CmdBeginRenderPass2's
translation to our CmdBeginRendering (see the comment above it) was never
reached. Fix: explicit thin wrappers that call the vk_common_* emulation
directly instead of relying on it being picked up as a fallback.

**line 1310, in `borgvk_CmdEndRenderPass2`, above `VKAPI_ATTR void VKAPI_CALL`**

Image copy for readback: both images are linear host-RAM; just memcpy the
region, addressed via the shared borgvk_image_level_offset layout so every
mip level/array layer a region names is handled, not just level 0/layer 0.

**line 1326, in `borgvk_CmdCopyImage`, above `enum pipe_format src_pfmt = vk_format_to_pipe_format(src->vk.format);`**

Strides and extents must be counted in compressed blocks, not texels:
vk_format_get_blocksize() returns bytes per BLOCK (e.g. 16 B covering a
4x4 texel block for ETC2/EAC), not bytes per texel. Multiplying texel
width/height directly by bs overflowed the destination allocation for
any block-compressed format -- surfaced as a heap "double free or
corruption" abort in a later borgvk_FreeMemory, not here. VkImageCopy
offsets/extents are always block-aligned per the spec, so the divisions
below are exact. For uncompressed formats bw = bh = 1 and this is
unchanged from the original per-texel math.

**line 1345, in `borgvk_CmdCopyImage`, above `if (src_bd > 1 || dst_bd > 1)`**

See the matching comment in CmdClearColorImage: 3D-block-compressed
formats remain a defended gap, not a supported combination.

**line 1353, in `borgvk_CmdCopyImage`, above `uint32_t src_elem_bs, src_elem_bw, src_elem_bh;`**

Element size actually moved per texel: for a combined depth-stencil
image with an aspect-selective region this differs from the
image's own (combined) block size -- see borgvk_aspect_block_size.
Addressing within each image still uses its own combined format
(that's the real backing layout); src_elem_bs/dst_elem_bs is only
how many bytes of each texel are actually copied.

**line 1370, in `borgvk_CmdCopyImage`, above `bool src_3d = src->vk.image_type == VK_IMAGE_TYPE_3D;`**

A copy between a 2D (array) image and a 3D image is a spec-defined
special case (VUID-vkCmdCopyImage-srcImage-07743 and friends): the
2D side's array layers correspond to Z slices within the 3D image's
SINGLE array layer, not to separate array layers of their own (a 3D
image always has arrayLayers=1). Treating each slice as a real array
layer on the 3D side -- which borgvk_image_level_offset's "layer"
parameter always multiplies by the image's full per-layer size --
walked off the end of the 3D image's allocation for slice > 0. Real
bug, not a corner case: confirmed via coredumpctl/gdb, a heap
corruption surfacing later as a SEGV inside CTS's own posix_memalign
while logging the (unrelated, already-corrupted) test result, found
running dEQP-VK.api.copy_and_blit.core.image_to_image.3d_images.*.

**line 1384, in `borgvk_CmdCopyImage`, above `uint32_t slice_bd = src_3d ? src_bd : (dst_3d ? dst_bd : 1);`**

For a 3D image whose format is 3D-block-compressed (e.g. any
ASTC_*x*x*_BLOCK_EXT), Z must be walked in blocks, not texels --
VkImageCopy offsets/extents for such a format are block-aligned per
spec, and a single bs-byte block already covers `bd` texels of
depth (see the borgvk_mip_level_size comment). Getting this wrong
doesn't just miscount slices, it multiplies the per-slice offset by
the wrong stride: confirmed via coredumpctl/gdb, a
"corrupted double-linked list" surfacing later in borgvk_FreeMemory,
running dEQP-VK.api.copy_and_blit...3d_to_3d.astc_3x3x3_*. A 2D
image can never use a 3D-block-compressed format, so bd is always 1
whenever only one side is 3D -- this reduces to the prior
texel-granular stepping for every other case.

**line 1431, in `borgvk_CmdCopyImage`, above `uint64_t src_plane = borgvk_image_sample_plane_size(src, src_bs, src_bw, src_bh, src_bd);`**

Common case (whole-texel copy, e.g. plain colour formats):
one memcpy per row. Row advance MUST use the destination
image's own real row stride (dst_stride_blocks, its full mip
level width), not the copy region's width (w_blocks) -- those
only coincide for a full-image-width copy, which is why every
earlier single-region "whole image" test passed while a
partial-width region (e.g. any multi-region test placing
several sub-rectangles) silently corrupted adjacent rows: a
region narrower than the image walked into the next row's
bytes early, drifting further with every row. Found via a
pixel-level diff against CTS's reference image, running
dEQP-VK.api.copy_and_blit.core.image_to_image.all_formats.
color.2d_to_2d (100% of that group was failing this way).

**line 1444, in `borgvk_CmdCopyImage`, above `uint64_t src_plane = borgvk_image_sample_plane_size(src, src_bs, src_bw, src_bh, src_bd);`**

A multisampled image packs one plane per sample; both sides have the
same sample count (VUID-vkCmdCopyImage-srcImage-00136).

**line 1455, in `borgvk_CmdCopyImage`, above `VkImageAspectFlags aspect = reg->srcSubresource.aspectMask;`**

Aspect-selective copy on a combined format (e.g. STENCIL_BIT
only, image-to-image, between two combined depth-stencil
images): both sides are real images with their own full
combined-format texel storage, not a tightly-packed buffer, so
the row/column stride here must use dst_bs/src_bs (the real
per-texel spacing), not dst_elem_bs/elem_bs. And a naive
"copy the first N bytes of each texel" is wrong whenever the
aspect isn't literally stored first (true for stencil in
several Mesa depth-stencil pipe formats) -- use the same real
per-aspect pack/unpack as the depth/stencil copy-to/from-buffer
paths, including the PIPE_FORMAT_Z16_UNORM_S8_UINT special
case (see borgvk_is_z16_s8's comment: every one of its six
pack/unpack functions is an UNREACHABLE() stub in Mesa itself).

**line 1503, in `borgvk_CmdCopyImage`, above `static void *`**

Like CmdClearColorImage, this was entirely missing -- discarded into the
generic vk_cmd_queue and never executed, so a blit's destination read back
whatever the image's fresh host allocation already contained. This was the
single largest concentration of failures anywhere in the api/info surface
(~44k of ~79k total). Unlike CmdCopyImage, a blit can rescale (src/dst
region sizes differ) and convert between compatible formats, so texels
are addressed and copied individually rather than row-at-once, going
through util_format_{un,}pack_rgba (same normalized intermediate as
CmdClearColorImage) for the format conversion.

Nearest is exact. Linear does real bilinear (2D) / trilinear (3D)
filtering by blending the up-to-8 texels around the sampled position --
always through float, matching Vulkan's own requirement that only
non-integer formats support VK_FILTER_LINEAR (borgvk's own
borgvk_optimal_features only grants SAMPLED_IMAGE_FILTER_LINEAR to
non-integer color formats, so this assumption holds for any image this
driver would let an app filter in the first place).

Blit source/destination formats are never block-compressed (invalid usage
per spec: VUID-vkCmdBlitImage-srcImage-06421 and friends), so block
width/height/depth are always 1 here and addressing reduces to plain texel
indexing -- no need for the block-aware machinery CmdCopyImage needs.

**line 1525, in `borgvk_CmdCopyImage`, above `static void *`**

Decode a block-compressed (BC1-7) image into R32G32B32A32_SFLOAT, level by level,
layer by layer, so the generic blit path can read it as an ordinary image.  The
compressed texels in image memory are left alone (they must read back unchanged).
Returns the decoded buffer (caller frees) and describes it in *out/*mem.

**line 1590, in `borgvk_CmdBlitImage`, above `if (vk_format_get_blockwidth(dst->vk.format) > 1 ||`**

A block-compressed destination cannot be blitted to (BLIT_DST is not advertised). A
BC source is decoded to float first; ETC2/ASTC are not advertised at all.

**line 1608, in `borgvk_CmdBlitImage`, above `bool is_uint = util_format_is_pure_uint(src_pfmt);`**

Per spec, blit source/dest formats must be from the same numeric-format
class (both pure-uint, both pure-sint, or neither), so one class check
per region -- taken from src -- applies to both sides.

**line 1619, in `borgvk_CmdBlitImage`, above `bool is_depth_aspect = (reg->srcSubresource.aspectMask & VK_IMAGE_ASPECT_DEPTH_BIT) != 0;`**

A depth or stencil aspect has no "RGBA" concept at all -- Mesa's
format table doesn't populate pack_rgba/unpack_rgba for pure
depth/stencil pipe formats, so calling them crashed through a null
function pointer. Confirmed via coredumpctl/gdb: SIGSEGV running
dEQP-VK...blit_image...depth_stencil.1d.d16_unorm_d16_unorm. Use the
same z_float/s_8uint helpers CmdClearDepthStencilImage and the
depth/stencil copy paths already rely on instead. Stencil is never a
valid VK_FILTER_LINEAR target per spec (integer data, no filtering
feature ever granted for it), so it always samples nearest
regardless of `filter`.

**line 1639, in `borgvk_CmdBlitImage`, above `float dst_ex = (float)(reg->dstOffsets[1].x - reg->dstOffsets[0].x);`**

Signed extents of each box, corner 0 to corner 1 -- negative when the
region is flipped on that axis. The parametric formulas below give
the correct (possibly mirrored) source position regardless of sign.

**line 1688, in `borgvk_CmdBlitImage`, above `int32_t six = CLAMP((int32_t)floorf(sx), 0, (int32_t)src_level_w - 1);`**

A combined DEPTH+STENCIL region has both aspect bits set
at once (CTS's defaultDSSourceLayer does exactly this),
so stencil and depth/color must be handled as
independent writes to the same texel rather than a
mutually-exclusive dispatch -- an earlier else-if chain
here meant a combined-aspect blit only ever wrote
stencil (nearest-filter case) or silently dropped depth
entirely (linear-filter case, since is_stencil_aspect
alone routed the whole texel through the nearest/stencil
path). Stencil is never filtered (no LINEAR feature ever
granted for it), so it always samples nearest regardless
of `filter`.

**line 1707, in `borgvk_CmdBlitImage`, above `uint8_t s = borgvk_is_z16_s8(src_pfmt) ? stexel[2] : 0;`**

PIPE_FORMAT_Z16_UNORM_S8_UINT has no
pack_s_8uint/unpack_s_8uint either -- see
borgvk_is_z16_s8's comment. Byte 2 of its 3-byte
texel is the stencil value directly.

**line 1732, in `borgvk_CmdBlitImage`, above `float fx = sx - 0.5f, fy = sy - 0.5f, fz = sz - 0.5f;`**

Bilinear (2D) / trilinear (3D), texel-center
sampling with edge clamping -- degenerates to a
single sample per axis with only one texel.

**line 1791, in `borgvk_CmdBlitImage`, above `float fx = sx - 0.5f, fy = sy - 0.5f, fz = sz - 0.5f;`**

Bilinear (2D) / trilinear (3D), texel-center
sampling with edge clamping -- degenerates to a
single sample per axis with only one texel.

**line 1837, in `borgvk_CmdBlitImage`, above `VKAPI_ATTR void VKAPI_CALL`**

Like CmdClearColorImage, this was entirely missing -- discarded into the
generic vk_cmd_queue like every other unimplemented vkCmd*. That's the
actual reason CmdClearColorImage's fix alone didn't make image_clearing
tests pass: CTS's own readback path (ImageClearingTestInstance::readImage)
doesn't read the image directly, it always goes through
vkCmdCopyImageToBuffer into a host-visible staging buffer first. With this
missing, that staging buffer was never actually written, so verification
saw whatever the buffer's fresh host allocation already contained,
regardless of what CmdClearColorImage or anything else wrote to the image.
Addressed via the shared borgvk_image_level_offset layout so every mip
level CTS reads back (not just level 0) sees real data.

**line 1864, in `borgvk_CmdCopyImageToBuffer`, above `if (img_bd > 1)`**

See the matching comment in CmdClearColorImage: 3D-block-compressed
formats remain a defended gap, not a supported combination.

**line 1872, in `borgvk_CmdCopyImageToBuffer`, above `uint32_t bs, bw, bh;`**

The buffer side is packed with the SELECTED ASPECT's element size,
which for an aspect-selective region on a combined depth-stencil
image (e.g. VK_IMAGE_ASPECT_STENCIL_BIT on D24_UNORM_S8_UINT) is
smaller than the image's own combined element size -- see
borgvk_aspect_block_size. Using the combined size for the buffer
side overflowed it by up to 8x, a real heap-overflow "double free
or corruption" abort confirmed via coredumpctl/gdb.

**line 1893, in `borgvk_CmdCopyImageToBuffer`, above `bool img_3d = src->vk.image_type == VK_IMAGE_TYPE_3D;`**

A 3D image has exactly one array layer (imageSubresource.layerCount
is always 1); its depth slices are what iterate here instead, via
imageExtent.depth. Without this, only the first Z slice (z=0) was
ever copied -- imageOffset.z was silently ignored -- so a 3D
image's remaining slices in the destination buffer stayed whatever
that buffer's fresh host allocation already contained. Found via
dEQP-VK.api.image_clearing...clear_depth_stencil_image.3d.*
reading back Depth:0 for every slice past the first.

**line 1931, in `borgvk_CmdCopyImageToBuffer`, above `enum pipe_format img_pfmt = vk_format_to_pipe_format(src->vk.format);`**

A raw byte-copy of the first `bs` bytes of each combined texel
would be wrong here -- stencil isn't necessarily stored in the
texel's first byte(s) (Mesa's combined depth-stencil pipe
formats put depth first, stencil last). Use gallium's own
stencil pack/unpack so this matches CTS's own reference
layout regardless of the combined format's actual bit
packing.

**line 1953, in `borgvk_CmdCopyImageToBuffer`, above `enum pipe_format img_pfmt = vk_format_to_pipe_format(src->vk.format);`**

Depth-only extraction where the aspect-only format isn't the
same size as the combined format (e.g. D32_SFLOAT_S8_UINT's
depth-only view is 4 B vs its own 8 B combined texel).
util_format_unpack_z_float/pack_z_float convert through a
normalized float intermediate, so this is correct regardless
of either format's exact bit layout.

**line 1985, in `borgvk_CmdCopyImageToBuffer`, above `VKAPI_ATTR void VKAPI_CALL`**

Upload counterpart of CmdCopyImageToBuffer, same reasoning: was entirely
missing (discarded into the generic vk_cmd_queue), which broke every test
that pre-fills an image from a host buffer before checking a partial
operation against it. Concretely: ImageClearingTestInstance::preClearImage
cmdFillBuffer(0)s a staging buffer then vkCmdCopyBufferToImage()s it across
every mip level and array layer *before* the real (possibly partial-range)
clear -- so pixels/layers the real clear doesn't touch are supposed to read
back as zero. Without this, they read back whatever the image's fresh host
allocation already contained, which is why clear_color_image's
multiple_layers/remaining_array_layers* groups kept failing even after
CmdClearColorImage and CmdCopyImageToBuffer were both fixed.

**line 2012, in `borgvk_CmdCopyBufferToImage`, above `if (img_bd > 1)`**

See the matching comment in CmdClearColorImage: 3D-block-compressed
formats remain a defended gap, not a supported combination.

**line 2020, in `borgvk_CmdCopyBufferToImage`, above `uint32_t bs, bw, bh;`**

See the matching comment in CmdCopyImageToBuffer: the buffer side is
packed with the selected aspect's element size, not the image's
combined element size.

**line 2036, in `borgvk_CmdCopyBufferToImage`, above `bool img_3d = dst->vk.image_type == VK_IMAGE_TYPE_3D;`**

See the matching comment in CmdCopyImageToBuffer: a 3D image's depth
slices iterate via imageExtent.depth, not imageSubresource.layerCount
(always 1 for a 3D image).

**line 2069, in `borgvk_CmdCopyBufferToImage`, above `enum pipe_format img_pfmt = vk_format_to_pipe_format(dst->vk.format);`**

See the matching comment in CmdCopyImageToBuffer: a raw byte
write into the first `bs` bytes of the combined texel would
land in the wrong bit position (and clobber depth bits) --
use gallium's stencil pack, which writes only the stencil
bits and preserves the rest.

**line 2088, in `borgvk_CmdCopyBufferToImage`, above `enum pipe_format img_pfmt = vk_format_to_pipe_format(dst->vk.format);`**

Depth-only upload where the aspect-only format isn't the same
size as the combined format -- see the matching comment in
CmdCopyImageToBuffer.

**line 2113, in `borgvk_CmdCopyBufferToImage`, above `VKAPI_ATTR void VKAPI_CALL`**

VK_KHR_copy_commands2 / core-1.3 struct-based variants of the four
commands above. Their region types (VkImageCopy2, VkBufferImageCopy2,
VkImageBlit2) are the exact same fields as the legacy structs with an
sType/pNext header prepended -- not implementing these at all meant
vkCmdCopyImage2/vkCmdBlitImage2/etc. fell through to the generic
discard-on-reset vk_cmd_queue path like any other unimplemented vkCmd*,
so the copy silently did nothing. This was invisible relative to the
legacy-struct entrypoints being fixed earlier in this file: CTS's
copy_commands2.* test group mirrors the exact same combinatorial test
matrix as core and dedicated_allocation, just calling the *2 entrypoints
instead, so it needed this separately. Confirmed by the pattern of the
failures themselves: the destination image read back its untouched
original fill pattern everywhere, as if the copy were never issued at
all -- found running
dEQP-VK.api.copy_and_blit.copy_commands2.image_to_image.all_formats.*.
Each wrapper strips the header and forwards to the already-verified
legacy-struct implementation via a small on-stack VLA (region counts here
are always small, test-parameter-bounded).

**line 2205, in `borgvk_CmdCopyBufferToImage2`, above `static void`**

Resolves one region of a multisampled COLOR image down to a single-sample
destination by averaging each pixel's src->vk.samples sample planes (see
borgvk_image_layer_size's comment for how those planes are packed in
memory). Multisampled images are always 2D, non-block-compressed, single
mip level (Vulkan requires this whenever samples>1), so this needs none of
CmdBlitImage's 1D/3D/block-compressed handling. Shared by CmdResolveImage
(explicit resolve) and CmdEndRendering2EXT's implicit render-pass-resolve
handling below -- see that function's comment for why both are needed.

**line 2249, in `borgvk_resolve_region`, above `if (util_format_is_pure_integer(src_pfmt) && src_bs == dst_bs) {`**

Integer formats do not average: the resolve takes sample 0 bit-exact
(the float path would round 32-bit values).

**line 2270, in `borgvk_resolve_region`, above `VKAPI_ATTR void VKAPI_CALL`**

Depth/stencil resolve is a separate mechanism
(VK_KHR_depth_stencil_resolve, via a render pass, not this entrypoint) --
core vkCmdResolveImage is colour-only per spec.

**line 2309, in `borgvk_CmdResolveImage2`, above `static void`**

The texture unit's sampler descriptor (docs/B2_texture_unit.md), word 0:
[0] mag linear, [1] min linear, [2] mip linear, [5:3] [8:6] [11:9] address
mode U V W (VkSamplerAddressMode), [14:12] border colour (VkBorderColor),
[15] compare, [18:16] compare op (VkCompareOp), [19] unnormalized
coordinates; words 1-3: mip LOD bias, min LOD, max LOD as FP32. The Vulkan
enums go in as they are -- the hardware uses the same encodings.

## borgvk_queue.c

**line 1, in `(file level)`, above ``**

* Queue submit for borgvk — the single interception point of the driver. cube.c
records a frame into a command buffer (captured into cmd_buffer->cmd_queue by
the runtime's vk_cmd_enqueue emulation) and submits it. We never replay those
commands; instead we locate the bound descriptor set and forward the app's
real data to the FPGA over serial:
  binding 0 (uniform buffer) = `struct vktexcube_vs_uniform`:
      float mvp[4][4];          // bytes   0..63   (updated every frame → 0xAD)
      float position[12*3][4];  // bytes  64..639  (static mesh → 0xAE)
      float attr[12*3][4];      // bytes 640..1215 (static UVs)
  binding 1 (combined image sampler) = the texture (RGBA8 → 0xAF rows)
Startup: geometry + texture are burst-uploaded once on the first submit, then
every subsequent submit ships only the MVP.  A sentinel file in /tmp records
that the FPGA already holds the mesh+texture so subsequent runs in the same
power cycle skip the upload entirely (~0 s startup instead of ~2 s).
Set BORGVK_FORCE_UPLOAD=1 to ignore the sentinel and re-upload unconditionally.

**line 83, in `find_color_attachment`, above `struct borgvk_command_buffer *bcb =`**

Mesa emulates the legacy render-pass entrypoints on top of
CmdBeginRendering, which does not enqueue; the command buffer
remembers the colour view it last rendered to instead.

**line 95, in `find_color_attachment`, above `static void`**

Dedup the 36 expanded positions to unique corners; build the indexed triangle
list + per-tri-vertex UVs, then ship the mesh.

**line 131, in `send_geometry`, above `static const uint32_t default_sampler[4] = { (2u << 3) | (2u << 6) | (2u << 9), 0, 0, 0 };`**

Box-downsample the linear RGBA8 texture to one BORGVK_TEX_DIM-wide RGBA8
row and ship it with the sampler's packed descriptor. With no sampler
recorded (an immutable one, which borgvk does not track yet) it sends the
firmware's own default: nearest, CLAMP_TO_EDGE, LOD clamped to 0.

**line 177, in `send_texture_row`, above `static void`**

Sentinel path: /tmp/borgvk_<devbasename>_setup — presence means the FPGA
already holds the current session's geometry+texture in PSRAM.

**line 210, in `mark_setup_done`, above `static void`**

Ship the pipeline's colour-blend state (BLEND_CFG/BLEND_CONST). blend_cfg is
never 0 once a graphics pipeline has set it (the write mask resets to 0xF),
so 0 means "no pipeline yet": leave the hardware at its reset state.

**line 252, in `send_blend_state`, above `static void`**

Ship the borgc-compiled shader blobs (captured at pipeline creation) to the
firmware once, as part of setup. DRM path → inline ioctl (the shim/kernel owns
the serial port); serial fallback → direct send. The firmware stages each blob
into PSRAM in place of its baked borgc_vert/frag_shader[] array.

**line 297, in `submit_is_cube`, above `static VkResult`**

RGB888 sdim×sdim (or the raw words of a RAW32 target) → the colour attachment. Takes ownership of `rgb`.

**line 313, in `sim_store_rgb`, above `bool fits = color_img->mem && color_img->mem->map &&`**

RGB888 sdim×sdim → R8G8B8A8_UNORM attachment (width×height),
nearest-neighbour upscale, opaque alpha.

**line 315, in `sim_store_rgb`, above `bool fits = color_img->mem && color_img->mem->map &&`**

The write below assumes a 4-byte texel; an attachment of another size (or unmapped
memory) must not be written past its backing.

**line 324, in `sim_store_rgb`, above `uint64_t plane_size = (uint64_t)width * height * 4;`**

The sim returns the resolved pixel. A multisampled image packs one plane per
sample (see borgvk_image_layer_size): put the resolved value in every plane,
so a later vkCmdResolveImage averages it back to itself.

**line 345, in `sim_store_rgb`, above `static VkResult`**

Feed a captured wire stream to `arcilator_sim --cts-uart` and write the
rendered RGB888 pixels into the colour attachment (nearest-neighbour scaled,
opaque alpha). `sim_dim` = 0 uses the vkcube default (128, BORGVK_SIM_DIM);
otherwise the simulator renders sim_dim x sim_dim. Takes ownership of `bytes`.

**line 362, in `sim_run_stream`, above `uint32_t sdim = sim_dim ? sim_dim : 128;`**

The firmware renders at its fixed native size (128² fallback for the cube),
which need not match the swapchain extent.  Render the sim at that size and
nearest-upscale into the attachment.  Override with BORGVK_SIM_DIM.

**line 366, in `sim_run_stream`, above `const bool raw32 = borgvk_sim_raw32(color_img->vk.format) && width == height;`**

A RAW32 attachment (R32_UINT) comes back as its raw words and is never resampled.

**line 370, in `sim_run_stream`, above `const char *direct = getenv("BORGVK_SIM_DIRECT");`**

BORGVK_SIM_DIRECT=<direct_sim>: Borg alone driven by a host-side driver
(simulation/direct) -- no firmware, so the target size is free (power of
two, 4..256, square) and a draw takes well under a second.

**line 387, in `sim_run_stream`, above `const char *keep = getenv("BORGVK_SIM_KEEP");`**

BORGVK_SIM_KEEP=<path>: also keep the stream, for replaying it by hand
with scripts/cts_uart_render.py.

**line 430, in `sim_run_stream`, above `const uint32_t WIN = 128;`**

A target over 128 pixels is rendered as 128-pixel windows (the hardware draws a large
framebuffer as several windows, docs/B1_geometry_front_end.md); the windows are
independent, so with the direct simulator they are dealt out to one process per core
and the pieces merged. Window w (row-major) belongs to process w % nparts.

**line 521, in `sim_run_stream`, above `static VkResult`**

Sim path for cube.c: capture the EXACT serial byte stream borgvk would put on
the wire (0xB0 borgc shaders + 0xAE geom + 0xAF texture + 0xAD MVP), feed it to
`arcilator_sim --cts-uart`, and write the rendered pixels into the colour
attachment.  Unlike the live serial path there is no once-only sentinel: every
frame ships the full stream because the sim boots fresh per invocation.  This
exercises the same borgc shaders + protocol as the FPGA, with no serial port.

**line 568, in `borgvk_submit_sim_cube`, above `const char *dump = getenv("BORGVK_SIM_DUMP");`**

Dump the captured wire stream and skip the sim fork -- how the simulation
goldens' borgvk_capture.bin is produced. Needs no mappable colour
attachment, so it works against a real WSI swapchain.
  BORGVK_SIM_DUMP=/path  → write one frame's stream there, then return.

**line 597, in `borgvk_submit_sim_cube`, above `struct tex_conv {`**

A format's source texel to the hardware's: copied, widened from three channels or R/B
swapped (cs bytes per channel), repacked 16-bit, or a depth part cut from depth/stencil.

**line 637, in `tex_convert`, above `static bool`**

Convert a sampled image to texels the texture unit reads (linear for one level,
tiled for several) and ship it as 0xB5 chunks. Returns false for formats not handled
yet, in which case nothing is sent.

**line 665, in `send_generic_texture`, above `uint32_t bpp, fmt_code, cs = 0, sch = 0, alpha = 0, pack16 = 0, zstride = 0, sten_off = 0;`**

source texel layout -> a TexFormat code (borg_tex_format.h) and its texels:
copied (RAWFMT), widened from three channels or R/B swapped (CHFMT, cs bytes
per channel), or 16-bit repacked (pack16).

**line 740, in `send_generic_texture`, above `uint32_t lw[13], lh[13], ld[13];`**

the source image keeps a layer's levels one after the other, rows packed, a 3D level
slice after slice; layers follow each other

**line 759, in `send_generic_texture`, above `const bool tiled = levels > 1 || layers > 1 || depth > 1 || type == 3;`**

One level of one layer is linear. Anything more is tiled (4x4 texels, a 16-byte texel in
two halves): a layer's levels one after the other, the layers after each other, the level
offsets within a layer sent ahead in a 0xC0 packet.

**line 810, in `send_generic_texture`, above `uint32_t sw[4] = { view->swizzle.r, view->swizzle.g, view->swizzle.b, view->swizzle.a };`**

descriptor words 1..3: layout, levels, depth or layers, view swizzle (the
VkComponentSwizzle values are the hardware's own encoding).

**line 837, in `send_generic_texture`, above `static bool`**

A texel buffer as a 1D texture of the same texels (linear layout, one level), so a
texelFetch reads it through the texture unit. Only 32-bit single-channel formats so far.

**line 867, in `send_buffer_texture`, above `static bool`**

VkFormat of a vertex attribute -> the texture unit's format code (borg_tex_format.h
BORG_TEX_FORMAT_*), the component swizzle bits of its descriptor, and how many bytes the
typed fetch reads. Three-component formats are fetched as four with alpha forced to one (the
Vulkan default), which reads the next vertex's first bytes: the upload keeps slack for it.

**line 940, in `generic_reject`, above `#define BORGVK_HEAP_BYTES 0x2000000u`**

The simulator's heap (software/borg/borg_layout.h BORG_HEAP_*): a bump allocator per stream.

**line 942, in `generic_reject`, above `#define BORGVK_POINT_SLOT 16`**

the point corner attribute's vertex slot (BORG_MAX_VATTRS - 1)

**line 948, in `generic_reject`, above `static VkResult sim_run_stream(uint8_t *bytes, size_t nbytes, struct borgvk_image *color_i`**

Run the accumulated draws of the open render pass (one simulator process for all of them) and
read the render target back. A no-op when nothing is pending.

**line 1030, in `stream_append`, above `static uint32_t`**

Upload n bytes (the tail up to a multiple of 4 is zero) and return their heap offset, or
UINT32_MAX when the heap is full.

**line 1060, in `heap_upload`, above `static bool`**

Sim path for a draw (CTS and applications): the pipeline's vertex shader fetches its inputs
through the texture unit (borgc, load_input -> TEX fetch from slot 128 + location), so the
driver uploads the bound vertex (and index) buffers into the GPU heap, describes each
attribute with a texture descriptor, and sends the draw parameters. No vertex data is read
on the host except to find the index range. Returns true when it handled the draw.

**line 1086, in `borgvk_sim_generic_draw`, above `const bool points = pipeline->topology == VK_PRIMITIVE_TOPOLOGY_POINT_LIST;`**

A point is a quad of two triangles: six vertices, whose clip offsets come from slot 15.

**line 1104, in `borgvk_sim_generic_draw`, above `struct borgvk_image *extra[3] = { NULL, NULL, NULL };`**

Colour attachments 1-3, when the pipeline's render pass has several (all R8, one pass each).

**line 1191, in `borgvk_sim_generic_draw`, above `{`**

The heap this draw needs: flush the pass so far when it would not fit next to it.

**line 1255, in `borgvk_sim_generic_draw`, above `uint32_t load = first_of_batch ? 0u : 1u;`**

Persistent simulator: the attachments hold what the application's images hold, so every
draw loads them (colour, depth, stencil) and keeps the tiles it does not reach.

**line 1270, in `borgvk_sim_generic_draw`, above `uint8_t fmt = color_img->vk.format == VK_FORMAT_R8G8B8A8_UNORM ? 1 :`**

Render target: the attachment's format and the clear colour its render pass asked for.

**line 1295, in `borgvk_sim_generic_draw`, above `const uint32_t asz = vk_format_get_blocksize(at->format);`**

Row 6 * line + corner: the corner's own endpoint, and in a second slot the other one.

**line 1324, in `borgvk_sim_generic_draw`, above `const uint32_t nrows = (uint32_t)(e1 - e0 + 1);`**

The attribute, gathered into a tight array of `rbytes` elements: element r of the buffer
is element r of the array, so the base is shifted back by row0 elements (a 32-bit
two's-complement heap offset). The unit reads it as a 4096-wide linear image.

**line 1452, in `borgvk_sim_generic_draw`, above `uint32_t ubo_base = 0;`**

The first bound uniform buffer is the shader's LOAD window (ls_base). Buffers of all bound
sets are numbered flat (set_base + slot), as the compiler numbers them.

**line 1467, in `borgvk_sim_generic_draw`, above `const uint32_t umask = pipeline->ubo_mask & ~1u;`**

uniform buffers past binding 0 live in the region too

**line 1469, in `borgvk_sim_generic_draw`, above `const uint32_t slots = 1 + util_last_bit(smask | umask);`**

With storage buffers the window is a region: the uniform buffer in slot 0, buffer number
f in slot 1 + f. A draw with the same set as the one before shares its region, so it
sees that draw's stores.

**line 1531, in `borgvk_sim_generic_draw`, above `int want = -1;`**

the slot of the binding the fragment shader samples

**line 1586, in `borgvk_sim_generic_draw`, above `VKAPI_ATTR void VKAPI_CALL`**

---- Draw state and the draw itself, run in submission order -------------------------------- *
The application-facing table only records these (see borgvk_CreateDevice); cmd_dispatch runs
them when the command buffer is submitted. Tracking the state here, instead of scanning the
queue afterwards, is what lets a draw happen at its place in the command stream: a
vkCmdCopyImageToBuffer recorded after it sees the rendered image.

**line 1604, in `borgvk_CmdBindPipeline`, above `#define BORGVK_CS_SLOT_BYTES (0x80000u * 4)`**

Compute (simulator only): the compute shader's buffers live in fixed windows of GPU memory,
one per binding, which the host fills before the dispatch and reads back afterwards (the
addresses are baked into the program by borgc's compute backend, see compute.rs). The job
goes to `direct_sim --compute`. Runs when the recorded commands replay at submit, so it
lands in command order.

**line 1629, in `borgvk_CmdDispatch`, above `struct { uint8_t *host; uint8_t *decoded; bool wb; uint32_t skip, size, addr;`**

wb: copy the window back to the app's buffer afterwards.  A texel buffer is decoded into a
private array (the canonical 4-word texel the compiler reads, see borg_nir_passes.c); the
texels a shader changed are encoded back.

**line 1670, in `borgvk_CmdDispatch`, above `enum pipe_format pf = vk_format_to_pipe_format(simg->vk.format);`**

A sampled image: word 0 = width, then level 0 / layer 0 decoded to 16-byte texels.

**line 1696, in `borgvk_CmdDispatch`, above `uint32_t w = simg->vk.extent.width, h = simg->vk.extent.height;`**

A storage image: word 0 = width, then level 0 / layer 0 texel by texel (4-byte
formats; the compiler addresses it as y * width + x + 1).

**line 1749, in `borgvk_CmdDispatch`, above `const uint32_t HDR = 12;`**

Job header: nprog gx gy gz lx ly lz nregs nbufs bx by bz (b* = the grid origin, which the
compiled program adds to the workgroup id).

**line 1768, in `borgvk_CmdDispatch`, above `uint32_t axis = gy > 1 && gy >= gx ? 1 : gx > 1 ? 0 : 2;`**

A big grid is cut along one axis into slices that run as separate simulator processes
(the simulator is cycle-accurate: a 512x512 grid of one-invocation workgroups takes
minutes in one process). Each slice starts from the same buffers and the changed bytes
are merged afterwards, so it is only done for shaders without atomics, whose result does
not depend on how the workgroups are ordered.

**line 1813, in `borgvk_CmdDispatch`, above `uint8_t *merged[BORGVK_MAX_BINDINGS + 1] = { 0 };`**

Merged result per buffer: the original bytes with every changed byte of every slice.

**line 2011, in `borgvk_CmdDrawIndexedIndirect`, above `VkResult`**

driver_submit: honour the submit's semaphore/fence waits and signals (the runtime leaves both
to the driver for a sync type with GPU_WAIT), around the actual work.

**line 2038, in `borgvk_queue_submit_work`, above `struct borgvk_device *replay_dev =`**

Run the recorded commands (copies, clears, blits, events, push constants, render-pass
state, secondaries) in submission order. The application-facing dispatch only records;
cmd_dispatch holds the real implementations (see borgvk_CreateDevice).

**line 2052, in `borgvk_queue_submit_work`, above `if (getenv("BORGVK_SIM") || borgvk_hw_enabled()) {`**

Sim path: if BORGVK_SIM is set, render via arcilator_sim and write pixels
into the colour attachment instead of shipping to the FPGA.
  - cube.c (UBO-driven): borgvk_submit_sim_cube captures the full serial
    stream incl. 0xB0 borgc shaders and replays it via --cts-uart, so the
    sim runs the exact protocol + real shaders as the FPGA.
  - generic draws already ran, at their place in the replay.

**line 2085, in `borgvk_queue_submit_work`, above `bool has_gem = ubuf->mem->gem_handle != 0 &&`**

DRM path: delegate geometry/texture upload and per-frame MVP to the
shim (or kernel driver) via ioctls.  The shim manages the sentinel
and serial transport; we just pass the GEM handles.

**line 2113, in `borgvk_queue_submit_work`, above `if (!g_setup_done) {`**

Serial fallback: no DRM device (shim not loaded).  Run the legacy
direct-serial path exactly as before.

## borgvk_private.h

**line 1, in `(file level)`, above ``**

* borgvk — a native Mesa Vulkan ICD for the Borg GPU (ULX3S FPGA), driven over
serial. Modeled on the v3dv (Broadcom) driver. See
~/.claude/plans/atomic-questing-stream.md for the overall design.

**line 32, in `(file level)`, above `#define BORGVK_USE_WSI_PLATFORM   /* Mesa's headless WSI backend needs no platform */`**

WSI (window-system integration) is available when at least one surface
platform is compiled in. borgvk uses the software (CPU) WSI path: the host
window is irrelevant — the real output is the FPGA's HDMI — so WSI only needs
to function so an app's render loop runs and submits each frame.

**line 45, in `(file level)`, above `VkPhysicalDeviceMemoryProperties memory;`**

Memory layout reported to the app. Borg renders on the FPGA; from the
host's point of view all "device" memory is plain host-visible RAM that we
stage and ship over serial, so we expose a single host-visible coherent
heap.

**line 51, in `(file level)`, above `const struct vk_sync_type *sync_types[2];`**

Submit is synchronous (ship MVP over serial, return), so a single
immediate-success sync type (borgvk_sync_type) backs fences/semaphores.

**line 55, in `(file level)`, above `int drm_fd;`**

DRM device fd opened during enumeration.  -1 when no borg DRM device is
found (drm-shim not loaded or no kernel driver).  In that case the driver
falls back to direct malloc + serial (legacy path).

**line 72, in `(file level)`, above `#define BORGVK_SHADER_BLOB_MAX 1024`**

Compiled Borg-ISA shader blobs (borgc output). Captured per stage at pipeline
creation and shipped to the firmware over serial (0xB0) at submit-setup, where
they replace the firmware's baked borgc_vert/frag_shader[] arrays. Only the
vertex and fragment stages come from the app; the rasterize stage stays baked
(it is a fixed-function Borg shader, not derived from the app).

**line 85, in `(file level)`, above `#define BORGVK_SRGB_SLOT 41`**

256 floats: sRGB to linear, for blending in the shader

**line 89, in `(file level)`, above `uint32_t pool[BORGVK_POOL_MAX];`**

Fragment constants that did not fit the window, fetched from vertex slot BORGVK_POOL_SLOT.

**line 104, in `(file level)`, above `struct vk_device_dispatch_table cmd_dispatch;`**

Dispatch table used when replaying recorded command queues (secondary
cmd buffers). We never replay for rendering, but the runtime wants it set.

**line 108, in `(file level)`, above `struct vk_queue queue;`**

Single graphics/compute/transfer queue. cube.c and vulkaninfo both use one
queue from family 0; multi-queue support can come later.

**line 115, in `(file level)`, above `const struct borg_blend *blend_lower;   /* the fragment stage being compiled blends in the`**

borgc-compiled shader blobs captured at pipeline creation, uploaded to the
firmware over serial (0xB0) on the first submit.

**line 117, in `(file level)`, above `const struct borg_blend *blend_lower;`**

the fragment stage being compiled blends in the shader

**line 119, in `(file level)`, above `VkPipelineLayout compile_layout;`**

the layout of the pipeline being created, for array bindings

**line 120, in `(file level)`, above `uint32_t stage_ubo[2];`**

mask of the uniform buffer bindings each stage (vertex, fragment) reads

**line 121, in `(file level)`, above `int frag_tex_binding;`**

binding of the last fragment stage's texture, -1 if unknown

**line 122, in `(file level)`, above `bool frag_reads_pntc;`**

the last fragment stage compiled reads gl_PointCoord

**line 123, in `(file level)`, above `uint32_t blend_cfg;`**

Colour-blend state of the last graphics pipeline created, already packed
in BLEND_CFG / BLEND_CONST register layout (see borgvk_blend_pack()).

**line 127, in `(file level)`, above `uint32_t state_reg[5];`**

Stencil / depth / cull state of the last graphics pipeline created, in
register layout (see borgvk_state_pack()); state_valid once set.

**line 137, in `(file level)`, above `#define BORGVK_MAX_COLOR_ATTACHMENTS 8`**

A render pass/framebuffer or dynamic-rendering scope currently open on this
command buffer, captured in borgvk_CmdBeginRendering(). Mesa's own
vk_common_CmdBeginRenderPass2 (borgvk never overrides RenderPass or
Framebuffer creation, nor the legacy Begin/EndRenderPass entrypoints --
Mesa's runtime emulates all of that generically) always resolves down to a
VkRenderingInfo and calls this driver's CmdBeginRendering, whether the app
used the legacy API or called it directly, so this is the one place that
needs to remember "what image backs each attachment right now" --
CmdClearAttachments has no other way to resolve its attachment indices
back to real images.

**line 153, in `(file level)`, above `#define BORGVK_SSBO_SLOT_BYTES 65536u`**

A draw's buffer region: the uniform buffer, then storage buffer b at slot 1 + b (lib.rs SSBO_SLOT_WORDS).

**line 166, in `(file level)`, above `struct vk_image_view *color_resolve_views[BORGVK_MAX_COLOR_ATTACHMENTS];`**

Multisample resolve targets captured from VkRenderingAttachmentInfo::
resolveImageView, NULL when that attachment isn't multisampled or
requested no resolve. Performed at CmdEndRendering2EXT -- see its
comment for why the legacy render-pass path (which is how CTS's
clear_color_attachment MSAA tests actually exercise this, via
VkSubpassDescription::pResolveAttachments) needs this at all.

**line 173, in `(file level)`, above `bool has_clear;`**

The first colour attachment's load-op clear colour, for the simulator's render target.

**line 188, in `(file level)`, above `uint8_t *state_sent;`**

Shader and blend packets last sent in this batch; a draw repeats them only when they change.

**line 194, in `(file level)`, above `bool batch_serve;`**

The batch runs on the persistent simulator, with these depth and stencil attachments.

**line 197, in `(file level)`, above `struct borgvk_writeback { uint32_t off, size; uint8_t *host; } wb[BORGVK_MAX_WRITEBACKS];`**

Storage buffers of the pass's draws: heap bytes to copy back into the application's buffer.

**line 200, in `(file level)`, above `bool batch_srgb;`**

the pass's sRGB decode table (vertex slot 41) is uploaded

**line 201, in `(file level)`, above `const void *batch_pool;`**

the pipeline whose constant pool the pass's slot holds

**line 244, in `(file level)`, above `void borgvk_serial_send_mvp(const float mvp[16]);`**

---- Serial transport (borgvk_serial.c) ------------------------------- *
The "GPU" is the ULX3S FPGA reached over the FT231X serial bridge
(/dev/ttyUSB0 @115200, overridable via $BORGVK_SERIAL). The submit path
ships the per-frame 4×4 MVP as a framed packet the firmware decodes.

**line 249, in `(file level)`, above `void borgvk_serial_send_blend(uint32_t blend_cfg, uint32_t blend_const);`**

0xB3: BLEND_CFG and BLEND_CONST register values, applied by the firmware
before the next draw.

**line 252, in `(file level)`, above `void borgvk_blend_pack(const VkPipelineColorBlendStateCreateInfo *cb,`**

Pack a pipeline's colour-blend state into the BLEND_CFG / BLEND_CONST layout
(hardware/rdl/borg.rdl); the Vulkan enum values pass through unchanged.

**line 262, in `(file level)`, above `void borgvk_serial_send_mem(uint32_t off, const uint8_t *data, uint32_t n);`**

0xB8 / 0xB9 / 0xBA (simulator): heap write, vertex-attribute fetch descriptor, draw.

**line 270, in `(file level)`, above `#define BORGVK_TEXG_CHUNK 256`**

0xB5: one chunk (<= 256 B at byte offset `off`) of a texture's texels plus
the texture descriptor words 1..3 and the four sampler words
(docs/B2_texture_unit.md); descriptor and sampler ride with every chunk.

**line 281, in `(file level)`, above `#define BORGVK_GEOM_MAX_VERTS 16`**

Max mesh the firmware's fixed-length 0xAE geometry packet carries (must match
the firmware RX_GEOM_*). cube.c is 8 unique verts / 12 triangles.

**line 286, in `(file level)`, above `void borgvk_serial_send_geom(const float *verts, int nverts,`**

Ship the app's real mesh once (Phase B): nverts unique model-space positions
(xyz floats), ntris triangles each indexing 3 of them (idx, 3 per tri), with
per-triangle-vertex UVs (uv, 2 floats per tri-vertex).

**line 292, in `(file level)`, above `#define BORGVK_TEX_DIM 64`**

The Borg texture dimension (must match firmware TEX_WIDTH). The app's texture
is downsampled to this on the host (lossless at the 128x128 render size).

**line 296, in `(file level)`, above `void borgvk_serial_send_tex_row(int y, const uint8_t *rgba, const uint32_t sampler[4]);`**

Ship one texture row (Phase B): `rgba` is BORGVK_TEX_DIM RGBA8 texels, framed
as a 0xAF packet together with the four-word sampler descriptor `sampler`
(struct borgvk_sampler::desc), which the firmware installs as sampler 0.

**line 301, in `(file level)`, above `void borgvk_serial_send_shader(uint8_t stage, const uint8_t *blob, uint32_t len);`**

Ship a borgc-compiled Borg-ISA shader blob to the firmware (0xB0 packet):
`stage` selects the firmware shader slot (0 = vertex, 1 = fragment), `blob` is
the .borg bytes (spirb_parse format). The firmware stages it to PSRAM in place
of its baked borgc_vert/frag_shader[] array before the next render.

**line 307, in `(file level)`, above `void borgvk_serial_send_push_constants(uint32_t offset, uint32_t size,`**

Ship a push-constant range to the firmware (0xB2 packet).  `offset` and
`size` are BYTES, as Vulkan gives them, and both are required by the spec to
be multiples of 4; the packet carries words because that is what the shader
side addresses (LS_BASE + rs1<<2, with borgc pinning rs1 to the field's word
index).  The firmware stages the bytes and points LS_BASE at them, so a
borgc-compiled LOAD lands on the right word.

**line 316, in `(file level)`, above `void borgvk_transport_capture_begin(void);`**

---- Transport capture (serial ↔ sim unification) --------------------- *
By default every send_* packet above is written to the serial port (paced).
Wrapping a sequence between capture_begin/capture_end redirects the identical
framed bytes into a growable in-memory buffer instead — exactly the byte
stream borgvk would have put on the wire.  The BORGVK_SIM path captures one
frame's packets this way and feeds them to `arcilator_sim --cts-uart`, so the
simulator exercises the same protocol (incl. 0xB0 borgc shaders) as the FPGA,
with no serial port involved.  Not re-entrant: one capture at a time.

**line 325, in `(file level)`, above `uint8_t *borgvk_transport_capture_end(size_t *out_len);`**

End capture: returns the malloc'd captured bytes (caller frees) and sets
*out_len; restores serial mode.  Returns NULL with *out_len=0 if empty.

**line 329, in `(file level)`, above `VkResult borgvk_queue_submit(struct vk_queue *queue,`**

Queue submit hook (borgvk_queue.c): reads the MVP from the submitted command
buffer's bound descriptor set and ships it over serial. Wired into
device->queue.driver_submit by CreateDevice.

**line 345, in `(file level)`, above `#define BORGVK_LINE_SLOT  16`**

Lines: vertex slot of the line attribute, and the offset to the other endpoint's attributes.

**line 359, in `VK_DEFINE_HANDLE_CASTS`, above `struct borgvk_device_memory {`**

Device memory: backed by a GEM BO (drm_fd >= 0) or plain malloc.
In both cases `map` is a CPU-writable pointer to the allocation.

**line 381, in `VK_DEFINE_HANDLE_CASTS`, above `struct borgvk_buffer_view {`**

Texel buffer view: format + range over a VkBuffer, used by
VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER / STORAGE_TEXEL_BUFFER. No
driver-specific state needed yet -- vk_buffer_view already tracks
buffer/format/offset/range; the shader-visible side is future work
(texel buffers aren't wired into the texture unit path yet). This
object existing and being destroyable correctly is what CTS's basic
lifetime/API tests need.

**line 392, in `VK_DEFINE_HANDLE_CASTS`, above `struct borgvk_sampler {`**

A sampler, packed at creation into the texture unit's four-word sampler
descriptor (docs/B2_texture_unit.md in the Borg repository). The firmware
stores the words in its sampler table as they are.

**line 412, in `VK_DEFINE_NONDISP_HANDLE_CASTS`, above `struct borgvk_desc_map {`**

Where each binding's array elements sit in a set's flat slot arrays: slot = binding plus the
extra elements of the lower bindings, so a layout without arrays maps each binding to itself.

**line 416, in `VK_DEFINE_NONDISP_HANDLE_CASTS`, above `uint8_t total;`**

slots the set takes: the next set's flat numbering starts here

**line 434, in `VK_DEFINE_NONDISP_HANDLE_CASTS`, above `struct borgvk_descriptor_set {`**

A descriptor set remembers which buffer/image/sampler is bound at each
binding, so the submit path can find the cube's uniform buffer (binding 0 →
MVP + geometry) and its texture and sampler (binding 1 → combined image
sampler).

**line 467, in `VK_DEFINE_NONDISP_HANDLE_CASTS`, above `VkPrimitiveTopology       topology;`**

VkPipelineVertexInputStateCreateInfo, so a generic draw can find
position and texture coordinates in the bound vertex buffers.

**line 476, in `VK_DEFINE_NONDISP_HANDLE_CASTS`, above `bool                      mrt;`**

several attachments, all R8, which the fragment stage packs

**line 477, in `VK_DEFINE_NONDISP_HANDLE_CASTS`, above `uint32_t                  ubo_mask;      /* the uniform buffer bindings the draw loads fro`**

borgc option word for this pipeline's vertex shader (see borgc_compile_nir): vertex inputs
are typed fetches whenever the pipeline declares any attribute.

**line 480, in `VK_DEFINE_NONDISP_HANDLE_CASTS`, above `uint8_t                   set_base[4];`**

flat number of each set's first slot, from the layout

**line 483, in `VK_DEFINE_NONDISP_HANDLE_CASTS`, above `struct borgvk_shader_blob blob[BORGVK_SHADER_STAGE_COUNT];`**

The pipeline's own shader blobs and fixed-function state, as compiled/packed at creation.

**line 490, in `VK_DEFINE_NONDISP_HANDLE_CASTS`, above `bool     cs_ok;`**

Compute: the borgc program, the registers the driver presets (register,
value pairs) and LocalSize. cs_ok is false when borgc refused the shader.

**line 497, in `VK_DEFINE_NONDISP_HANDLE_CASTS`, above `uint32_t cs_flags;`**

bit 0: the shader uses atomics (its grid cannot be split)

**line 515, in `VK_DEFINE_NONDISP_HANDLE_CASTS`, above `void borgvk_event_set_status(VkEvent event, bool set);`**

borgvk_sync.c: set or reset a VkEvent from the host (queue replay of vkCmdSetEvent / vkCmdResetEvent).

## borgvk_burst_gen.c

**line 1, in `(file level)`, above ``**

* Burst generator for the push-constant end-to-end test (Step 50 item 13,
phase 2).
Emits one frame's worth of borgvk wire packets to a file, which the
simulator replays through its UART (simulation/verilator --cts-uart). The
point of doing it here rather than building the bytes in Python is that
every packet comes from the REAL senders in borgvk_serial.c, through the
same capture transport borgvk uses for its golden captures -- so the burst
cannot drift from what the driver actually puts on the wire.
Usage:
  borgvk_burst_gen <vert.borg> <frag.borg> <out.bin> <r> <g> <b> <a>
The colour is what gets pushed as the push-constant range. Phase 3 renders
two bursts with different colours and requires the output to follow, which
is the only way to prove the shader READ the pushed value rather than
returning something baked in.

**line 30, in `(file level)`, above `static int`**

Packet order matches what the firmware's drain loop expects, and it is not
arbitrary: each handler sets skip_gap to say "more of this burst follows
immediately", and only the MVP does not -- it is the frame trigger, so it
goes last. Push constants precede it so the values are staged before the
draw they apply to.

**line 40, in `emit_burst`, above `static const float verts[3 * 3] = {`**

One triangle, submitted twice with opposite winding.

Which winding survives backface culling depends on the screen-space Y
direction (see the Phase 3 culling history: screen y-down flips the
winding test), and getting it wrong yields an empty frame that looks
exactly like a push-constant failure. Sending both removes that
ambiguity from the experiment: whichever orientation passes culling
draws, and if both do, the first wins the depth test at equal Z. Either
way every covered pixel ends up with the pushed colour.

Oversized on purpose (the standard full-screen triangle trick) so it
covers the whole framebuffer at identity MVP and the test does not
depend on where the viewport lands.

**line 59, in `emit_burst`, above `static const float uv[2 * 3 * 2] = { 0 };`**

UVs are required by the sender's signature but unused: pushconst.frag
samples no texture, and no 0xAF rows are sent.

**line 63, in `emit_burst`, above `static const float mvp[16] = {`**

Identity, in cube.c's row-major order -- the geometry is already in the
clip-space range we want, so the vertex stage must not move it.

**line 77, in `emit_burst`, above `borgvk_serial_send_push_constants(32, 16, color);`**

offset 32 == word index 8, matching testdata/pushconst.frag's
`layout(offset = 32)` and the window the other two push-constant tests
pin. 16 bytes = one vec4.

## borgvk_serial_test.c

**line 1, in `(file level)`, above ``**

* Unit tests for wire packets: push constants (0xB2) and geometry (0xAE).
This exists because the simulator-side test
(simulation/verilator/main.cpp --push-const-test) BUILDS its own packets
rather than calling this sender, so on its own it proves the firmware
parses a correctly-formed packet -- not that borgvk emits one. A byte-order
slip or an offset miscalculation here would sail straight past it.
So the expected bytes below are written out literally, in the same shape
the simulator test constructs, and compared against what the real sender
produces through the capture transport. The two tests together cover the
host->firmware path end to end: this one pins the bytes borgvk emits, and
the simulator one proves the firmware stages exactly those bytes correctly.

**line 53, in `test_well_formed_range`, above `const uint32_t words[4] = {`**

Word offset 8, four words -- deliberately the same range the simulator
test's second packet uses, so the two tests pin the same bytes.

**line 71, in `test_well_formed_range`, above `for (unsigned i = 0; i < 4; i++) {`**

Payload is little-endian, at the head of the buffer regardless of the
offset field -- the offset says where it lands, not where it sits.

**line 82, in `test_well_formed_range`, above `bool padded = true;`**

Everything past the supplied words must be zero: the firmware reads a
constant byte count, so the tail is padding it checksums but ignores.

**line 124, in `test_rejected_ranges`, above `len = 0;`**

Vulkan requires offset and size to be multiples of 4
(VUID-vkCmdPushConstants-offset-00368 / -size-00369). The word-index
mapping depends on it, so the sender checks rather than assuming.

**line 159, in `test_rejected_ranges`, above `#define GEOM_MAX_VERTS 16`**

0xAE geometry: positions and UVs are shader datapath values, so they must
go out as float32 bits (the FP16 packing they used to have reads as
denormals on the FP32 datapath). Layout mirrors borg_kernel.c RX_GEOM_*.

**line 216, in `test_geometry_packet`, above `static void`**

0xAF: the firmware (borg_kernel.c) reads y at [1], the sampler descriptor
as four little-endian words at [2..17], the RGBA8 texels from [18].
