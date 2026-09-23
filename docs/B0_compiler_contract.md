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
- **Integer ALU:** IADD, ISUB, IMUL, ISHL, ISHR, IAND, IOR, IXOR, ISLT
  (signed), ISEQ, I2F, F2I. The comparisons return 0/1, so they compose
  with BRZ/BRNZ/EXPUSH; the other four relations come from swapping operands
  or flipping branch polarity, as in RV32I.
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
  word indices. A 32-bit register reaches all of the 25-bit GPU address
  space, so each binding's base is a compiler-pinned constant register added
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

### Colour quantization

`quantize8` (FP16 → UNORM8) is now an exact `round(v*255)`; it used to
scale by 256 (commit `1343a207`). 8-bit colour results can move by one step
wherever it is used: narrow tile colour storage, blending, bilinear taps.
Pixel-exact reference images from renders with those paths enabled may need
regenerating.

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
| EXANY divergent loop             | `BorgCoreTestsD.exany_implements_a_genuinely_divergent_loop`      |
