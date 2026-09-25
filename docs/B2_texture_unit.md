# Texture Unit: Descriptors, Mipmaps, Formats

Status: implemented and tested in simulation, September 2026, on branch
`feat/vulkan-conformance-gaps` (`BorgConfig.samplerEnabled`: every FP32
build with memory ops, Wafer included). Module `BorgSampler`, format table
`TexFormat`.

It is Borg's only texture path. It replaced the legacy one (`FTEX`,
`BorgTextureUnit`), removed on 2026-09-25, which sampled square power-of-two
RGBA16F textures of at most 256x256, one mip level, through four
base-address registers that shared one size and one sampler. The comparison
that motivated the new unit:

| Limit                        | Legacy `FTEX`              | `TEX`                                      | Vulkan 1.0 |
|------------------------------|----------------------------|--------------------------------------------|------------|
| Size                         | square, power of two, 256  | any width/height/depth up to 4096          | 4096 (3D 256) |
| Mipmaps                      | none                       | 13 levels, nearest/linear, LOD from the quad, bias, clamps | full chains |
| Types                        | 2D                         | 1D, 2D, 3D, cube (face as layer), arrays   | all        |
| Formats                      | RGBA16F                    | 51 (every mandatory sampled format)        | table      |
| Bindings                     | 4, one size and sampler    | 256 textures and 256 samplers per table    | 16 each    |
| Filtering                    | bilinear via UNORM8        | nearest, bilinear, trilinear in FP32       | same       |
| Address modes                | per axis, border only on neighbours | per axis, all five, six border colours | same   |
| Compare, gather, texelFetch, offsets | none               | all                                        | core       |
| Sub-texel / mipmap precision | 3 bits above 128 texels    | 8 bits / 8 bits                            | 4 / 4      |

## Instructions

**`TEX rd, rs1, rs2, rs3`** (R4-type, funct2 2): sample with `u = rs1`,
`v = rs2`; `rs3` names the register holding the **control word** (usually a
uniform, `funct3 = 3`, from the fragment shader's constant window). The
result's R, G, B, A go to `rd .. rd+3` (FP32; raw integers for integer
formats), in every active lane. All four lanes are sampled together, so the
unit takes an implicit LOD from the quad's coordinates.

**`TEXA rs1, rs2, rs3`** (R4-type, funct2 3): this lane's extra arguments,
kept for every `TEX` after it: `rs1` = w (3D) or array layer (1D arrays use
v, 2D arrays and cubes w), `rs2` = LOD (explicit) or bias, `rs3` = depth
reference. For `texelFetch`, integers.

Control word:

| Bits  | Field                                                              |
|-------|--------------------------------------------------------------------|
| 7:0   | texture index into `TEX_DESC_BASE`                                  |
| 15:8  | sampler index into `SAMPLER_DESC_BASE`                              |
| 17:16 | operation: 0 sample, 1 fetch (`texelFetch`: integer u, v, TEXA layer and level), 2 gather |
| 19:18 | gather component                                                    |
| 20    | depth compare against TEXA's reference                             |
| 22:21 | LOD: 0 implicit (quad derivatives), 1 implicit + TEXA bias, 2 TEXA LOD |
| 26:23 | texel offset u (signed, -8..7)                                      |
| 30:27 | texel offset v                                                      |
| 31, 20:18 | 3D images only: texel offset w (sign in 31), in the gather and compare bits a 3D image cannot use |

## Descriptors

Registers `TEX_DESC_BASE` (0x378) and `SAMPLER_DESC_BASE` (0x37C) point at
the tables. Writing either makes the unit forget the one texture and one
sampler descriptor it caches.

Texture descriptor, 16 words at `TEX_DESC_BASE + 64*index`:

| Word | Contents                                                             |
|------|----------------------------------------------------------------------|
| 0    | base byte address                                                    |
| 1    | width-1 [15:0] (to 65536: texel buffers), height-1 [27:16], type [29:28] (0 1D, 1 2D, 2 3D, 3 cube), layout [30] (0 tiled 4x4, 1 linear) |
| 2    | depth or layers-1 [9:0], levels-1 [13:10], format [19:14] (`TexFormat` code), component swizzle R [22:20], G [25:23], B [28:26], A [31:29] (`VkComponentSwizzle`: 0 identity, 1 zero, 2 one, 3-6 R-A) |
| 3    | tiled: bytes per array layer (all its levels); linear: bytes per row |
| 4-15 | byte offset of level 1 .. 12 from the base                          |

Sampler descriptor, 4 words at `SAMPLER_DESC_BASE + 16*index`:

| Word | Contents |
|------|----------|
| 0    | [0] mag linear, [1] min linear, [2] mip linear, [5:3] [8:6] [11:9] address mode U V W (`VkSamplerAddressMode` 0-4), [14:12] border colour (`VkBorderColor` 0-5), [15] compare, [18:16] compare op (`VkCompareOp`), [19] unnormalized coordinates |
| 1-3  | mip LOD bias, min LOD, max LOD (FP32) |

The image view's **component swizzle** applies to the result (and, for
gather, to the component gathered). A **depth compare** against a UNORM
format clamps the reference to [0, 1] first.

## Layouts

**Tiled** is the framebuffer's layout: 4x4 tiles of 16 texels, row-major
inside a tile, tiles row-major over the level (`ceil(width/4)` per row). So a
colour attachment is a texture with no copy: an RGBA8/BGRA8 flush is
`R8G8B8A8_UNORM`/`B8G8R8A8_UNORM` tiled, with the attachment's width. Level
`l` has size `max(1, size >> l)`; 3D slices follow each other inside a level;
array layers follow each other, each holding all its levels.

A **16-byte** texel is the exception: its tile's first 128 bytes hold bytes
0-7 of the 16 texels and the next 128 bytes bytes 8-15, which is what a
RAW128 colour attachment renders in its two slices
([B3](B3_colour_formats.md)).

**Linear** is row-major with the descriptor's row pitch; one level and one
layer, which is all Vulkan requires of linear images.

## How a sample is computed

1. **LOD.** Implicit: `rho = max(|du/dx|*W + |dv/dx|*H + |dw/dx|*D, the
   same in y)` from lanes 1-0 and 2-0 (the approximation Vulkan allows; the
   w term only for 3D), `lod =
   log2(rho)` (a 65-entry table interpolated on 8 more mantissa bits,
   within 0.54/256 of the true log2; the bare table was 5.9/256 off). Plus
   the sampler's and TEXA's bias, their sum clamped to +-15
   (`maxSamplerLodBias`), then to [min, max]. 1D arrays leave the layer (in
   v) out of rho. `lod <= 0` magnifies (level 0, mag
   filter); otherwise the nearest level is `ceil(lod + 0.5) - 1`, or two
   levels weighted by the fraction (mip linear).
2. **Coordinates**, per axis and level. REPEAT and MIRRORED_REPEAT wrap the
   float exactly (mod 1 or 2) before scaling, so large coordinates keep
   their precision; clamping modes clamp to [-4, 4). Times the level's size,
   minus half a texel when filtering, in fixed point with 8 fraction bits;
   plus the offset. Each tap's texel index is then wrapped, mirrored,
   clamped or marked as border. A border texel takes the border colour in
   the channels its format has; the others read (0, 0, 0, 1) as any texel's
   do.
3. **Taps**: 1, or 2/4/8 (1D/2D/3D) per level when filtering. Each is
   fetched (1-4 words), decoded to FP32, compared if asked, and added to the
   result with its weight on one FP32 FMA. Single taps and integer formats
   bypass the FMA and are exact.
4. **Cube maps** filter seamlessly: a tap off a face reads the neighbouring
   face across the edge (the remap is derived from the face definitions at
   elaboration, `CubeEdges`). A tap past a corner has no face; it becomes
   the average of the three texels meeting there, which are the other three
   taps of its 2x2 footprint. That is the spec's recommendation ("Cube Map
   Corner Texel": the corner texel *should* be the average of the three,
   *may* be something else as long as three equal texels give that value),
   and it is what dEQP's reference (`tcuTexture.cpp`, `getCubeLinearSamples`)
   computes. Gather returns the averaged corner texel too. Integer formats,
   which cannot be averaged, keep the corner's own-face texel, one of the
   three, which the spec's "may" allows. NEAREST clamps to the face's edge,
   as the spec requires; other address modes do not apply to cubes. The
   face comes from TEXA's layer (0-5, +X -X +Y -Y +Z -Z); the direction's
   major-axis projection to (face, s, t) is the compiler's.

Everything is sequential: one multiplier, one FMA, one field extractor. A
sample costs tens to hundreds of cycles, which is the design point:
conformance at the area of a state machine.

## Using it: a textured fragment shader

    ; the texture and sampler descriptors in memory, and
    TEX_DESC_BASE, SAMPLER_DESC_BASE = their tables
    ; u21 (from DRAW_FS_CONST) = control word: texture 0, sampler 0, implicit LOD
    FATTR r10, 0                   ; interpolate u into r13 ...
    FATTR r10, 1                   ; ... and v into r14 (see B1)
    TEX   r20, r13, r14, u21       ; funct3 = 3: rs3 is a uniform
    ; r20..r23 = RGBA

`BorgDrawTests.renderToTexture` renders a triangle into an RGBA8 attachment,
then draws a quad whose fragment shader samples that attachment with `TEX`,
and checks that the second image equals the first pixel for pixel.

## Software

- **borgc** (`mesa/src/borg/compiler/`) lowers a plain `texture()` to `TEX
  r20, u, v, ctl` with the control word 0 (texture 0, sampler 0, implicit
  LOD), built in the shader as `FSTEP(FNEG(r30))` rather than pinned in a
  constant register. R, G, B, A land in r20-r23, so r23 is a constant
  register only in shaders that never sample. Other texture ops and more
  than one texture are reported, not yet compiled.
- **Firmware** (`software/borg/borg_driver.c`) stores the texture linear
  RGBA8 at `TEX_TEXEL_ADDR`, and `borg_set_texture` writes texture
  descriptor 0 and sampler descriptor 0 at the start of the texture region
  (`borg_layout.h`), then `TEX_DESC_BASE` and `SAMPLER_DESC_BASE`.
- **borgvk** packs the sampler descriptor from the app's `VkSampler` at
  `vkCreateSampler` and sends it in every `0xAF` row packet (marker, row,
  four descriptor words, 64 RGBA8 texels, checksum).

## Tests

| What                                                        | Test |
|-------------------------------------------------------------|------|
| All 51 formats; 20 address mode/filter/border combinations on 5x3; explicit and implicit LOD, bias, clamp, trilinear on a 16x8 chain; 3D, 1D/2D arrays, linear layout, float filtering; compare (PCF), gather, texelFetch, offsets -- against a reference of Vulkan's rules | `BorgSamplerTests` |
| Component swizzles (sample, integer, gather), border colours on 1- and 2-channel and depth formats, Dref clamp for D16 (not D32), 3D w offset, bias clamp, 1D-array LOD | `BorgSamplerTests.swizzle_borders_dref_3d_offsets_bias_clamp_1d_lod` |
| Seamless cube: every edge and corner of all six faces, filtered and gathered (UNORM and UINT), against a reference that folds taps over the edge in 3D | `BorgSamplerTests.seamless_cube_edges_and_corners` |
| Render to texture and sample it, through the whole Borg     | `BorgDrawTests.render_to_texture_and_sample_it` |

## Not covered yet

Anisotropic filtering (an optional feature) and cube arrays (the optional
`imageCubeArray`). Compressed formats (ETC2) are
expected to be decoded by the driver into a supported format.
