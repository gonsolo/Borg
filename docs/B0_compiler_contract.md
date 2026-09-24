# Compiler and Driver Contract: Pending Follow-ups

The hardware changes on branch `feat/vulkan-conformance-gaps` (September
2026) changed or extended what the shader compiler (`borgc`, in the Mesa fork
at `mesa/src/borg/compiler/`) and the driver/firmware (`borgvk`,
`software/borg/`) must do. The RTL side is done and tested; this page
collects everything the software side still has to pick up, so that work
can happen after tapeout without re-deriving it from the RTL.

**Merge warning.** Two of these break existing content the moment the
branch lands on `main`, before any follow-up is done:

- the new `FTEX` encoding (every compiled shader that samples a texture),
  see [FTEX encoding](#ftex-encoding);
- the texture base register moved (firmware texturing), see
  [Texture binding registers](#texture-binding-registers).

The source of truth for every encoding below is
`hardware/borg/src/Instructions.scala`; `software/borg/borg_isa.h` is
generated from it (`mill hardware.borg.runMain borg.EmitIsaHeader`).

## All shaders: program length and the instruction cache

A shader is no longer limited to IMEM (64 words on Wafer, 72 on ULX3S/sim).
With `BorgConfig.hasShaderICache` (on in Default and Wafer), IMEM is a
direct-mapped instruction cache over the program's image in DRAM: word `pc`
lives at `codeBase + 4*pc`, and a PC past what IMEM holds is fetched from
there on demand and kept for the next quad.

What the compiler must know:

- **Program length:** up to **1024 words**. That is the program-counter width,
  and also the range of the absolute 10-bit branch targets that BRZ/BRNZ
  already had. Nothing else changes: branches work across the IMEM boundary
  (a loop entirely past IMEM is tested). A BARRIER past IMEM is supported,
  since its resume PC and the compute sequencer's segment PCs were widened
  to 10 bits, but no test exercises it yet.
- **Performance, not correctness:** a program that fits in IMEM never misses
  and runs exactly as before. Code past IMEM costs one DRAM word read on first
  use, and again whenever the line has been reused for other code. Placing
  hot loops early in the program keeps them resident.

What the driver/firmware must do:

- **Keep the whole program contiguous in DRAM**, not only its tail. Any PC
  can miss, including one inside IMEM whose line far code evicted, and the
  miss is served from `codeBase + 4*pc`.
- **Sequencer-loaded shaders need nothing else.** Every shader DMA into IMEM
  (`seq_vert/setup/frag_addr` + `_len`) sets `codeBase` automatically, to the
  DMA source minus 4 × its IMEM offset, and flushes the cache. `_len` only
  controls how much is preloaded. It is a 6-bit field, and anything past IMEM
  is simply not preloaded, so the natural setting is `min(program length,
  IMEM − offset)`.
- **Programs written over MMIO** (the compute path, `borg_fpu.c` helpers):
  one that fits needs nothing. One longer than IMEM must be in DRAM, with
  `CODE_BASE` (0x318) written **before** its IMEM words, because the write
  flushes the cache and the IMEM writes that follow are the prefill.
- **Firmware limits to lift:** `borg_stage_shader` in
  `software/borg/borg_driver.c` rejects blobs longer than 32 (vertex) or
  `BORG_IMEM_FRAG_LEN` (fragment) words, and stages them into DRAM slots
  sized for those limits. Both must grow. The blob's 1-byte `num_instrs`
  (≤ 255) and the `0xB0` packet size (`RX_SHADER_MAX`) cap it next.

## Fragment shaders

### FTEX encoding

`FTEX` moved from the ALU-opcode R-type shape (funct7 `0x0C`) to the R4-type
shape `FMADD` already uses, to gain a third source operand (commit
`a1762418`).

| Field  | Bits    | FTEX value                                |
|--------|---------|-------------------------------------------|
| rs3    | 31:27   | register holding the binding slot         |
| funct2 | 26:25   | `1` (FMADD is `0`)                        |
| rs2    | 24:20   | V coordinate                              |
| rs1    | 19:15   | U coordinate                              |
| funct3 | 14:12   | uniform substitution, as for every op     |
| rd     | 11:7    | first of four destination registers       |
| opcode | 6:0     | `0x04` (bit 2 = R4-type)                  |

Base word `0x02000004`; C macro `BORG_INSTR_FTEX(rd, rs1, rs2, rs3, funct3)`.
funct7 `0x0C` is retired and decodes as nothing.

**borgc today:** `encode.rs` still emits `"FTEX" => bin(0x1800_0000)`, the old
encoding. It must emit the R4 form with a real `rs3`.

### FTEX writes four registers: RGBA

`FTEX rd` writes `rd` = R, `rd+1` = G, `rd+2` = B, `rd+3` = **A** (commit
`30aee6e9`). A sampled image is a vec4, and the texel format
(R16G16B16A16_SFLOAT) always carried alpha; the hardware used to discard it.
`rd` must be at most 28.

**borgc today:**

- `lib.rs` reserves only r20–r22 for the FTEX block and allocates r23 as a
  constant register (`CONST_REGS: [17, 18, 19, 23]`). An FTEX with `rd = 20`
  now overwrites r23. Reserve r20–r23 and move that constant elsewhere.
- `lib.rs` fakes texture alpha by reusing B (`(ftex_v, 2)` as the fourth
  component). Use `rd+3` instead.

### Texture binding slot (rs3)

`rs3` is a **register index**, like FMADD's own `rs3`: the hardware reads the
register and uses its low 2 bits to pick one of four texture base addresses
(`tex_base_addr0..3`). It is not an immediate: `rs3 = 1` means "read r1", not
"slot 1". Pin the binding index into a register, the same way
`push_const_reg`/`alloc_const_reg` already pin push-constant offsets, and
pass that register.

Only the base address is per slot. Texture size (`tex_config.log2_dim`) and
sampler state (filter, address modes, border) are shared, so every texture
bound at the same time must have the same size and sampler. 4 slots is below
Vulkan's reported minimum of 16 (`maxPerStageDescriptorSampledImages`);
raising it is a bounded RTL change (`BorgConfig.maxTextureBindings` plus the
RDL registers).

### ZTEST: early per-fragment tests

New instruction `ZTEST` (funct7 `0x40`, no operands; word `0x80000000`; C
macro `BORG_INSTR_ZTEST(funct3)`, funct3 = 0) runs the configured
depth/stencil test for the quad **at that point in the shader**, instead of
after it (commit `48bac59d`). It is how Borg implements SPIR-V's
`EarlyFragmentTests` execution mode: Borg computes fragment depth in the
shader itself (r29), so "before the shader" becomes "at ZTEST".

What the hardware does at ZTEST:

- tests every lane's r29 against the tile buffer, per sample, and writes
  depth and stencil right then;
- lanes that failed become helper invocations: they keep running (their
  values still feed derivatives) but their STOREs are dropped;
- at the end of the shader, colour goes to exactly the samples that passed.
  There is no second test and no second stencil update.

Rules for the compiler:

- **Emit it only when the shader declares `EarlyFragmentTests`.** Without
  that mode, Vulkan says the tests happen after the shader, and a shader that
  writes storage must still have its stores land for fragments that then
  fail the depth test. (The commit message for `48bac59d` suggests using
  ZTEST for any side-effecting shader; that is wrong, follow this page.)
- Emit it **once**, after the **final** write of r29 (the interpolated depth)
  and before the first STORE. A second ZTEST in the same invocation is a
  no-op.
- Emit it at **top level**, never inside an EXPUSH/EXELSE/EXPOP region. ZTEST
  tests every lane of the quad, and a lane masked off by divergence has not
  written its r29.
- r29 writes after ZTEST are ignored. Under `EarlyFragmentTests` a shader
  depth write has no effect, so `FragDepth` stores can simply be dropped.
- A discard (r25 write) after ZTEST is fine: depth and stencil were already
  written, which is what Vulkan specifies for early tests; the fragment's
  colour is dropped.
- Outside a rasterized fragment (a shader started over MMIO, or a compute
  dispatch) ZTEST completes immediately and does nothing.

### Side effects of helper and discarded lanes

Handled in hardware; no compiler action needed, but worth knowing (commit
`48bac59d`). A STORE is dropped for any lane that has no covered sample (a
helper invocation that exists only for derivatives), is outside the scissor,
has been discarded (r25), or failed ZTEST. LOADs are **not** dropped, since
derivatives of loaded values need them.

The r25 kill register is sticky and does not stop execution, so code after a
`discard` keeps running. Its stores are what this suppresses.

### Fragment register ABI (unchanged, for reference)

| Registers | Meaning                                                 |
|-----------|---------------------------------------------------------|
| r20–r23   | FTEX result block when `rd = 20` (compiler convention)  |
| r24       | alpha output (hasBlend builds)                          |
| r25       | kill / discard (sticky, nonzero = discard)              |
| r26–r28   | R, G, B output                                          |
| r29       | depth output; tested by ZTEST or at the end             |
| r30, r31  | pixel coordinates                                       |

## Compute shaders

The compute hardware (commits `afd602fc`, `fdaa028e`, `9d3a302e`, `c5da4d23`,
`93d0e690`) needs no new instructions beyond those below. Everything else is
a lowering convention, each pinned by a hand-written ISA test in
`BorgComputeTests` / `BorgCoreTestsD` that the compiler output should match.

- **Invocation IDs:** r30 = `LocalInvocationIndex`, r31 = `LocalInvocationID`
  packed `x | y << 10 | z << 20`, both raw integers. `WorkgroupID` x/y/z is in
  uniforms u29/u30/u31 of page 0. `GlobalInvocationID`, and `NumWorkGroups`
  (passed as a push constant), are ordinary integer arithmetic on these.
- **Integer ALU:** IADD, ISUB, IMUL, ISHL, ISHR (arithmetic), ISRL
  (logical, funct7 `0x4A`), IAND, IOR, IXOR, ISLT (signed), ISLTU (unsigned,
  funct7 `0x4C`), ISEQ, I2F, F2I -- RV32I's SLL/SRA/SRL/SLT/SLTU. The
  comparisons return 0/1, so they compose with BRZ/BRNZ/EXPUSH; the other
  relations come from swapping operands or flipping branch polarity, as in
  RV32I.
- **EXANY** is available wherever the execution mask is (every build), not
  only with compute: a fragment shader's divergent loop needs it too. Wafer
  now has compute (`BorgConfig.Wafer` no longer turns it off).
- **Address space:** 32-bit byte addresses, RV32's address space, for
  LOAD/STORE, the texture unit, the DMA, every base register and the memory
  port (`GpuMemIO.AddrBits`). Address arithmetic wraps mod 2^32. A 32-bit
  register holds any address, so Vulkan's `maxStorageBufferRange` (2^27)
  and a 4096x4096 RGBA32F image (256 MiB) are addressable. A board decodes
  only the memory it has: the ULX3S maps the low 24 bits onto its 16 MiB
  VRAM window. The chip-to-chip link carries all 32 bits (a V.A request is
  a header plus `addr[15:0]` and `addr[31:16]`), so silicon behind a larger
  memory can reach it.
- **BARRIER** (funct7 `0x3C`): **no register survives it.** Every value live
  across a barrier must be spilled to a per-invocation memory slot before it
  and reloaded after it, with the slot address recomputed from r30/r31.
  Those two registers are the only ones safe unspilled, because they are
  re-derived on every trigger. Barriers must be in uniform control flow; a
  divergent one sets `COMPUTE_CTRL` bit 3 (barrier fault) rather than
  hanging.
- **Atomics:** lower `atomicAdd` and friends as a per-lane critical section:
  for each lane `i`, `EXPUSH(LocalInvocationIndex == i)`, then LOAD, the ALU
  op and STORE, then `EXPOP`. Masked lanes perform no memory access at all,
  so this is race-free. Without the mask, all four lanes read the same old
  value and three of four updates are lost (see
  `atomic_add_without_expush_loses_updates`).
- **Divergent loops:** use `EXANY rd`, which writes 1 if any lane in the
  current exec mask is still active, and branch on it with BRNZ. BRZ/BRNZ
  only look at lane 0, so a per-lane loop counter must never drive the back
  edge directly.
- **Storage buffers (SSBOs):** leave `LS_BASE` at 0 and address with full
  word indices. A 32-bit register reaches the whole GPU address space
  (word indices up to 2^30), so each binding's base is a compiler-pinned
  constant register added
  to the element offset. That is the same pattern as v3d's
  `QUNIFORM_SSBO_OFFSET`, and it allows any number of bindings. `LOAD rd, rs1`
  reads `mem32[LS_BASE + (rs1 << 2)]`; `STORE rs1, rs2` writes it.
- **Memory ordering:** one quad at a time and every access stalls the core,
  so memory is sequentially consistent within a dispatch. Memory barriers
  can compile to nothing.

## Driver and firmware

### Texture binding registers

The texture base address moved from `tex_config.base_addr` to four
per-slot registers, `tex_base_addr0..3` at `0x304`–`0x310` (same encoding:
the byte address / 8, as a 16-bit field). **The hardware no longer reads
`tex_config.base_addr`.** `software/borg/borg_driver.c` still writes it, so
firmware texturing on this branch samples slot 0 at whatever
`tex_base_addr0` holds (0 after reset) until it also writes
`tex_base_addr0`. `tex_config` still carries `en`, `log2_dim` and
`frag_uses_fragpos`.

### Texture alpha

Texels are R16G16B16A16_SFLOAT: word +0 = `{G, R}`, word +4 = `{A, B}`.
`borg_upload_texture_row` writes only B into word +4, so every uploaded
texture has alpha 0. The borgvk wire format (`0xAF` rows, RGB FP16) carries
no alpha either. Before a shader uses FTEX's alpha, both need a fourth
channel. Until then, opaque textures should upload A = 1.0 (`0x3C00`).

### Colour attachment format

New `FLUSH_FORMAT` register (`0x314`, commit `601eb709`): 0 = R5G6B5
(reset, unchanged behaviour), 1 = R8G8B8A8_UNORM, 2 = B8G8R8A8_UNORM, in
Vulkan byte order. The 32-bit formats take 64 bytes per 4×4 tile instead of
32, so an autonomous render's colour tile stride doubles. Size the
framebuffer allocation accordingly. Alpha comes from the tile buffer's alpha
plane (hasBlend builds, else opaque) and is MSAA-averaged like colour.

### Depth attachment

`FLUSH_ZB_BASE` (nonzero = a depth attachment is bound) now advances per
tile in autonomous renders: tile `t` writes its 16 × D16_UNORM values at
`zb_base + 32*t`. Before `601eb709`, every tile wrote to the same 32 bytes.
No firmware uses it yet; `borgvk` must also report D16_UNORM as supported.

### Occlusion queries

Three registers (commit after `cb052720`): `OCC_CTRL` (`0x31C`: bit 0 enable,
bit 1 write-1 clear), `OCC_TRI_RANGE` (`0x320`: first triangle index in bits
15:0, one past the last in 31:16, reset 0..0xFFFF) and `OCC_COUNT`
(`0x324`, read-only).

- The count is **exact**: samples that pass coverage, scissor, discard and
  the depth/stencil tests, counted where the tests run (at `ZTEST` for a
  shader with early tests, at the end of the shader otherwise). Borg can
  report `occlusionQueryPrecise = VK_TRUE`.
- **Queries are triangle windows, not time windows.** A tile-based renderer
  shades every draw's fragments tile by tile, interleaved, so "between
  vkCmdBeginQuery and vkCmdEndQuery" has to be expressed as the triangle
  range of the draws recorded between them. The same problem is why v3d
  replays query state in every tile's command list. Program
  `OCC_TRI_RANGE` with that range, clear and enable, render, and read
  `OCC_COUNT`.
- **One window per render.** A render pass holding more than one occlusion
  query must be split into several renders. That needs attachment
  load/store between renders, which is still an open hardware item. It is
  also needed for a render pass that uses more than one fragment shader,
  since pass 2 loads exactly one.
- Outside a sequencer render (the MMIO pixel path) there is no triangle
  index, and every fragment counts while enabled.
- 32 bits; Vulkan's 64-bit result is the zero-extended value.

### Attachment load and stencil store

A render can now continue from a previous render's attachments
(`loadOp = LOAD`) instead of only starting from a clear. This is also what
lets the driver split one Vulkan render pass into several renders. That split
is needed whenever a render pass uses more than one fragment shader, since
pass 2 loads exactly one, or holds more than one occlusion query.

- **`TILE_LOAD`** (`0x328`): bit 0 colour (with alpha), bit 1 depth, bit 2
  stencil. The same bits as Vulkan's per-attachment `loadOp`. Every tile is
  cleared as before, then the selected aspects are loaded over the clear.
  Aspects not loaded keep their clear values, so `LOAD` colour with `CLEAR`
  depth works as expected.
- **Sources** are exactly where the flusher writes. Colour comes from the
  framebuffer in the format `FLUSH_FORMAT` selects: RGB565 loads as opaque,
  and the 32-bit formats carry alpha. Depth comes from `FLUSH_ZB_BASE` as
  D16_UNORM, and stencil from the new `FLUSH_SB_BASE` (`0x32C`).
- **Stencil store:** with `FLUSH_SB_BASE` nonzero, each tile's stencil plane
  goes out after depth as 16 bytes (S8_UINT) per tile, at
  `sb_base + 16 × tile index`. Before this commit stencil never left the
  chip, so no stencil attachment could survive a render pass.
- **Round-trip fidelity:** colour, stencil and depth are exact. Tile
  depth is FP32 on an FP32 build (`BorgConfig.tileDepthBits`): every D16
  value round-trips (checked for all 65,536), and D32_SFLOAT is stored bit
  for bit. FP16 depth could not tell apart 58,367 of the 65,535 adjacent D16
  pairs, and could not hold D32 at all.
- **`DEPTH_FORMAT`** (`0x330`): bit 0 selects D32_SFLOAT (64 bytes per tile)
  instead of D16_UNORM (32 bytes), for store and load alike.
- **`CLEAR_DEPTH`** (`0x334`): the tile clear depth as FP32. Until it is
  written, the FP16 depth field of `SEQ_CLEAR_LO` is widened and used. A
  Vulkan clear depth like 0.3 is not an FP16 value, so the driver should
  write this register.
- **Rounding to the attachment format** (a D16 attachment's fragment depth
  to the nearest `k/65535`) is the shader's job. It is a fixed conversion,
  and FP32 tile depth holds the result exactly.
- **Multisampled attachments** (`ATTACH_MS`, `0x338`): with bit 0 set,
  colour, depth and stencil are stored and loaded **per sample**. Each tile's
  region then holds `samples` consecutive per-sample tiles (colour 32/64,
  depth 32/64, stencil 16 bytes each), so a 4× attachment keeps every sample
  for a later load, `texelFetch(…, sample)` or a resolve. Use it for
  `storeOp = STORE` or `loadOp = LOAD` on a multisampled attachment. Leave it
  clear for a multisampled attachment that is only resolved (`storeOp =
  DONT_CARE` plus a resolve attachment): that path averages on chip and writes
  one tile. With every sample resident (ULX3S) one flush and one load walk all
  samples. At msaaMultiPass (Wafer) each pass loads and flushes its own sample
  instead of accumulating; empty tiles run every pass too.
- **Empty tiles are always flushed** when loading. The dirty-tile skip
  assumes DRAM already holds the clear colour, which no longer holds.

### Colour quantization

`quantize8` (FP16 → UNORM8) is now an exact `round(v*255)`; it used to
scale by 256 (commit `1343a207`). 8-bit colour results can move by one step
wherever it is used: narrow tile colour storage, blending, bilinear taps.
Pixel-exact reference images from renders with those paths enabled may need
regenerating.

## The draw front end

Everything a compiler and driver need for draws (`DRAW_CFG` mode 1) is on
its own page, [Geometry Front End](B1_geometry_front_end.md): the vertex
stage ABI (`VertexIndex`/`InstanceIndex` in r30/r31, position in r0-r3,
varyings via `SOUT`), the fragment ABI (barycentrics in r5-r7, `FATTR`,
FragCoord), the constant windows in the uniform bank, and the registers.
Legacy mode is unchanged, so nothing on this page is affected until the
compiler targets it.

## The texture unit

`TEX`/`TEXA` and the descriptor tables replace `FTEX` for anything beyond
the legacy RGBA16F path: [Texture Unit](B2_texture_unit.md) has the
encodings, the control word, the descriptor layouts and an example.

## Where each contract is tested

| Contract                         | Test                                                              |
|----------------------------------|-------------------------------------------------------------------|
| FTEX slot select via rs3         | `BorgCoreTestsC.ftex_rs3_routes_two_calls_to_different_textures`  |
| FTEX writes RGBA                 | `BorgCoreTestsC.ftex_writes_rgba_to_four_consecutive_registers`   |
| ZTEST stall, no register write   | `BorgCoreTestsC.ztest_stalls_until_done_and_writes_no_register`   |
| Helper-lane store suppression    | `BorgCoreTestsC.helper_lane_stores_are_suppressed_loads_are_not`  |
| ZTEST pass/fail/discard/stencil  | `BorgShaderDispatcherZTestTests` (7 tests, incl. 4× per-sample)   |
| ZTEST end to end                 | `BorgSequencerTests` scenario `ztest_suppresses_stores_of_hidden_fragments` |
| RGBA8/BGRA8 flush, depth per tile| `BorgTileFlusherTests`, scenario `sequencer_rgba8_and_depth_advance_per_tile` |
| Compute ABI, BARRIER, atomics    | `BorgComputeTests`                                                 |
| Occlusion count, triangle window | `BorgShaderDispatcherZTestTests` (count per test site), scenario `occlusion_query_counts_the_samples_of_the_triangle_window` |
| Attachment load, stencil store   | `BorgTileLoaderTests` (3), `BorgTileFlusherTests` stencil burst, scenario `attachments_store_and_load_across_renders` |
| EXANY divergent loop             | `BorgCoreTestsD.exany_implements_a_genuinely_divergent_loop`      |
| Programs longer than IMEM        | `BorgCoreTestsC.icache_*` (4 tests, 64- and 72-word IMEM)         |
| Long shader through DMA preload  | `BorgSequencerTests` scenario `icache_runs_a_fragment_shader_longer_than_imem` |
