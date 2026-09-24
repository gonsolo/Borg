# Texture Unit: Descriptors, Mipmaps, Formats

Status: implemented and tested in simulation, September 2026, on branch
`feat/vulkan-conformance-gaps` (`BorgConfig.samplerEnabled`: every FP32
build with memory ops, Wafer included). Module `BorgSampler`, format table
`TexFormat`.

The legacy texture path (`FTEX`, `BorgTextureUnit`) samples square
power-of-two RGBA16F textures of at most 256x256, one mip level, through four
base-address registers that share one size and one sampler. It stays for
today's firmware and `borgc`. The new unit, reached through `TEX`, is what
Vulkan 1.0 needs:

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

## Descriptors

Registers `TEX_DESC_BASE` (0x378) and `SAMPLER_DESC_BASE` (0x37C) point at
the tables. Writing either makes the unit forget the one texture and one
sampler descriptor it caches.

Texture descriptor, 16 words at `TEX_DESC_BASE + 64*index`:

| Word | Contents                                                             |
|------|----------------------------------------------------------------------|
| 0    | base byte address                                                    |
| 1    | width-1 [15:0], height-1 [31:16]                                     |
| 2    | depth or layers-1 [11:0], levels-1 [15:12], format [21:16] (`TexFormat` code), layout [25:24] (0 tiled 4x4, 1 linear), type [27:26] (0 1D, 1 2D, 2 3D, 3 cube) |
| 3    | tiled: bytes per array layer (all its levels); linear: bytes per row |
| 4-15 | byte offset of level 1 .. 12 from the base                          |

Sampler descriptor, 4 words at `SAMPLER_DESC_BASE + 16*index`:

| Word | Contents |
|------|----------|
| 0    | [0] mag linear, [1] min linear, [2] mip linear, [5:3] [8:6] [11:9] address mode U V W (`VkSamplerAddressMode` 0-4), [14:12] border colour (`VkBorderColor` 0-5), [15] compare, [18:16] compare op (`VkCompareOp`), [19] unnormalized coordinates |
| 1-3  | mip LOD bias, min LOD, max LOD (FP32) |

## Layouts

**Tiled** is the framebuffer's layout: 4x4 tiles of 16 texels, row-major
inside a tile, tiles row-major over the level (`ceil(width/4)` per row). So a
colour attachment is a texture with no copy: an RGBA8/BGRA8 flush is
`R8G8B8A8_UNORM`/`B8G8R8A8_UNORM` tiled, with the attachment's width. Level
`l` has size `max(1, size >> l)`; 3D slices follow each other inside a level;
array layers follow each other, each holding all its levels.

**Linear** is row-major with the descriptor's row pitch; one level and one
layer, which is all Vulkan requires of linear images.

## How a sample is computed

1. **LOD.** Implicit: `rho = max(|du/dx|*W + |dv/dx|*H + |dw/dx|*D, the
   same in y)` from lanes 1-0 and 2-0 (the approximation Vulkan allows; the
   w term only for 3D), `lod =
   log2(rho)` (a 64-entry table, 8 fraction bits). Plus the sampler's and
   TEXA's bias, clamped to [min, max]. `lod <= 0` magnifies (level 0, mag
   filter); otherwise the nearest level is `ceil(lod + 0.5) - 1`, or two
   levels weighted by the fraction (mip linear).
2. **Coordinates**, per axis and level. REPEAT and MIRRORED_REPEAT wrap the
   float exactly (mod 1 or 2) before scaling, so large coordinates keep
   their precision; clamping modes clamp to [-4, 4). Times the level's size,
   minus half a texel when filtering, in fixed point with 8 fraction bits;
   plus the offset. Each tap's texel index is then wrapped, mirrored,
   clamped or marked as border.
3. **Taps**: 1, or 2/4/8 (1D/2D/3D) per level when filtering. Each is
   fetched (1-4 words), decoded to FP32, compared if asked, and added to the
   result with its weight on one FP32 FMA. Single taps and integer formats
   bypass the FMA and are exact.
4. **Cube maps** filter seamlessly: a tap off a face reads the neighbouring
   face across the edge (the remap is derived from the face definitions at
   elaboration, `CubeEdges`), and a tap past a corner takes the average of
   the three texels meeting there. Address modes do not apply to cubes. The
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

## Tests

| What                                                        | Test |
|-------------------------------------------------------------|------|
| All 51 formats; 20 address mode/filter/border combinations on 5x3; explicit and implicit LOD, bias, clamp, trilinear on a 16x8 chain; 3D, 1D/2D arrays, linear layout, float filtering; compare (PCF), gather, texelFetch, offsets -- against a reference of Vulkan's rules | `BorgSamplerTests` |
| Seamless cube: every edge and corner of all six faces, against a reference that folds taps over the edge in 3D | `BorgSamplerTests.seamless_cube_edges_and_corners` |
| Render to texture and sample it, through the whole Borg     | `BorgDrawTests.render_to_texture_and_sample_it` |

## Not covered yet

Anisotropic filtering (an optional feature) and cube arrays (the optional
`imageCubeArray`). Compressed formats (ETC2) are
expected to be decoded by the driver into a supported format.
