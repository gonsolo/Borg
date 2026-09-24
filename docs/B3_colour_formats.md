# Colour Attachment Formats: RAW Words and Shader Blending

Status: implemented and tested in simulation, September 2026, on branch
`feat/vulkan-conformance-gaps`, in every build with the draw front end and
blending (`BorgConfig.drawEnabled && hasBlend`, Wafer included).

Vulkan 1.0 requires some thirty colour attachment formats: 8, 16 and 32-bit
UNORM, SINT, UINT and SFLOAT channels, sRGB, 10-bit, up to 128 bits per
pixel. It also requires blending on the UNORM, sRGB and 16-bit float ones.
The fixed-function colour path (`BorgBlend`, the UNORM8 tile, three flush
formats) covers R5G6B5, R8G8B8A8 and B8G8R8A8 UNORM. Everything else is
the **RAW** path. The attachment holds raw bytes the fragment shader packs,
and the shader blends, masks and converts, reading the destination with
`TLD`. No per-format hardware was needed, which is how tile GPUs with
programmable blending do it.

## Formats

`FLUSH_FORMAT` (attachment 0) and `ATT_FORMAT` (attachments 1-3, 3 bits each
at [2:0], [5:3], [8:6]) take:

| Code | Format    | Bytes/pixel | Tile bytes | Written from | Blended by |
|------|-----------|-------------|------------|--------------|------------|
| 0    | R5G6B5    | 2  | 32  | r26-r28 (FP) | `BorgBlend` |
| 1    | R8G8B8A8  | 4  | 64  | r24, r26-r28 | `BorgBlend` |
| 2    | B8G8R8A8  | 4  | 64  | r24, r26-r28 | `BorgBlend` |
| 3    | RAW32     | 4  | 64  | r26, raw     | the shader |
| 4    | RAW16     | 2 (bytes 0-1 of r26) | 32 | r26 | the shader |
| 5    | RAW8      | 1 (byte 0 of r26)    | 16 | r26 | the shader |
| 6    | RAW64     | 8 (r26, then r27)    | 128 | r26, r27 | the shader |
| 7    | RAW128    | 16, in two passes    | 256 | r26, r27 per slice | none required |

Byte 0 is at the lowest address, and a tile is 4x4 pixels in row-major order,
the texture unit's tiled layout. So an attachment can be sampled with
`TEX` under the Vulkan format of its bytes and no copy.

## How a RAW word lives in the tile

The tile's colour is UNORM8 R, G, B plus the UNORM8 alpha plane: 32 bits per
sample. A RAW word rides it exactly: byte k in channel k, byte 3 in alpha.
`dequantize8` and `quantize8` round-trip every byte. RAW64's second word
lives in the **extension plane**. On a multi-pass MSAA build (Wafer) that
plane is the resolve accumulator, which a RAW attachment never uses, so it
costs a mux and no storage. Elsewhere it is one 16 x 32 plane per sample. It
is written and cleared only while the attachment is RAW64 or RAW128, so a
UNORM attachment's accumulator survives the clear between MSAA passes.

## The shader side

- **Output.** For a RAW attachment the dispatcher stores r26 (and r27) as
  they are: no conversion, no blending, no colour write mask.
- **`TLD rd, k`** (funct7 `0x4E`, `k` in funct3) returns word `k` of this
  lane's destination, the attachment's current value at the pixel. The
  dispatcher reads it for each lane before the fragment shader starts, and
  only for a RAW attachment. Quads of one triangle cover distinct pixels, and
  the next triangle starts only once this one is written, so the word cannot
  go stale. `ZTEST` writes only depth and stencil.
- **`ATTIDX rd`** returns attachment + 4 x slice: a RAW128 attachment
  renders twice, slice 0 writing bytes 0-7 and slice 1 bytes 8-15.

The compiler therefore does per format:

| Vulkan format(s) | Code | Pack | Blend |
|------------------|------|------|-------|
| R8_UNORM/UINT/SINT | RAW8 | round(v*255) or the integer, masked | UNORM: shader blend on the unpacked value |
| R8G8_*, R16_UINT/SINT/SFLOAT, A1R5G5B5 | RAW16 | shifts and ORs; F16 by bit arithmetic | UNORM and R16_SFLOAT: shader blend |
| R8G8B8A8/A8B8G8R8 UINT/SINT | RAW32 | bytes | none (integer) |
| R8G8B8A8/B8G8R8A8/A8B8G8R8 SRGB | RAW32 | `FSRGB` per colour channel | decode `TLD` (a 256-entry table), blend in linear, encode |
| A2B10G10R10_UNORM/UINT | RAW32 | 10/10/10/2 bits | UNORM: shader blend |
| R16G16_*, R32_* | RAW32 | halves / the word | R16G16_SFLOAT: shader blend |
| R16G16B16A16_*, R32G32_* | RAW64 | two words | R16G16B16A16_SFLOAT: shader blend (both words resident: DST_ALPHA works) |
| R32G32B32A32_* | RAW128 | words 0-1, then 2-3 by slice | not required |

A masked channel keeps its `TLD` value. Integer formats neither blend nor
convert: Vulkan writes their value as is.

## Clears, loads, MSAA

- **Clear value**, word 0: the ordinary clear registers, byte k as channel k:
  R/G/B as the FP16 `k/255` (exact), and A in `PLANE_CLEAR.alpha` or
  `ATT_CLEAR_BAk[7:0]`. Word 1 (RAW64, and slice 0 of RAW128):
  `ATT_CLEAR_EXTk`. Words 2 and 3 (RAW128 slice 1): `ATT_CLEAR_W2_k`,
  `ATT_CLEAR_W3_k`, raw.
- **loadOp LOAD** reads the bytes back (`BorgTileLoader`). Bytes a narrow
  format lacks read as 0.
- **4x MSAA**: RAW words are not averaged, so a 4x RAW attachment must be
  stored per sample (`ATTACH_MS`) and resolved by the driver (a compute
  pass), which Vulkan allows: a resolve is its own operation. With
  `SAMPLE_MASK_CFG` bit 6 (one sample) there is nothing to resolve. On a
  multi-pass build `TLD` returns the pass's own sample, which is exact. A
  resident-sample build returns sample 0, which is approximate at edges:
  another reason Wafer renders MSAA in passes.
- **Passes.** One per attachment, two per RAW128 attachment, up to 8. Depth
  and stencil are stored in the last pass; the occlusion count is taken in
  the first.

## 128-bit texels

A 16-byte texel in the tiled layout is stored as two 8-byte halves: a tile's
first 128 bytes hold bytes 0-7 of its 16 texels, and the next 128 bytes hold
bytes 8-15. This is what a RAW128 attachment's two slices write, and
`BorgSampler` fetches word k from `+128*(k >> 1) + 4*(k & 1)` (B2). The
linear layout is unchanged.

## Tests

| What | Test |
|------|------|
| RAW32/16/8/64/128: store, clear per word and slice, load, `TLD`, rewrite; single-sample; resident and Wafer multi-pass | `BorgDrawTests.raw_colour_formats_and_tld` |
| R32_SFLOAT additive blending in FP32; R32G32_SFLOAT blending that needs both words of the pixel | same |
| 4x RAW64 stored per sample, each sample's own coverage | same |
| The 16-byte split tiled layout, every 128-bit format | `BorgSamplerTests.every_format_decodes` |

## Found on the way

The dispatcher's `phase` output was 3 bits wide. The four `TLD` prefetch
states pushed the enum to 11 states, and state 8 truncated to 0, which reads
as idle. A tile then flushed under a quad still in flight, losing the first
tile's last quad whenever the shader was long enough (17+ words). The
dispatcher now exports `idle` explicitly, and a `require` ties the port
width to the enum.
