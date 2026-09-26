# Geometry Front End: Draws, Homogeneous Setup and Varyings

Status: implemented and tested in simulation, September 2026, on branch
`feat/vulkan-conformance-gaps` (`BorgConfig.drawEnabled`, i.e. any FP32
build with memory ops, Wafer included).

The limits check against Vulkan 1.0 found that the geometry front end falls
short on most of the vertex-to-fragment path. This page describes how the
hardware closes those gaps. It is the only geometry path: `borgc`, `borgvk`
and the firmware target it, and the legacy per-triangle descriptors were
removed (see [The legacy path](#the-legacy-path)).

| Limit                   | Legacy Borg                         | Draw front end                       | Vulkan 1.0              |
|-------------------------|-------------------------------------|--------------------------------------|-------------------------|
| Vertex shader outputs   | position only (r0-r2, screen space) | position + any number of `SOUT`s      | 64 components           |
| Fragment shader inputs  | 5 components + z, fixed layout      | `FATTR` of any component             | 64 components           |
| Interpolation           | screen-linear (affine)              | perspective-correct                  | perspective-correct     |
| Clipping                | none; w <= 0 breaks                 | exact, without clipping              | view volume             |
| Vertex input            | fixed 8 words, pre-assembled        | vertex pulling with `LOAD`           | 16 attributes, indices, instancing |
| Topologies              | triangle list only                  | list, strip, fan, restart            | + points, lines         |
| Triangles per render    | 31                                  | 65,535                               | unbounded (driver splits renders) |

## Design in one paragraph

A render is a **draw** walked by hardware (`BorgDrawWalker`). Primitive
assembly (list, strip or fan, with optional 16- or 32-bit indices and
primitive restart) gives every triangle three vertex indices. **One**
vertex-shader run per triangle shades all three corners at once, one per SIMT
lane, with `VertexIndex`/`InstanceIndex` in r30/r31. The shader fetches its
own attributes with `LOAD` (vertex pulling), leaves the clip-space position in
r0-r3, and writes varyings to the triangle's record in memory with a new
store, `SOUT`. A fixed-function **setup program in ROM** (`BorgSetupRom`)
turns the three clip-space positions into screen-space planes by inverting the
3x3 homogeneous vertex matrix (**2D homogeneous rasterization**, Olano and
Greer 1997). That needs no clipping: the three edge planes are correct for
vertices behind the eye, and the near and far planes are a fourth and fifth.
Per pixel, the **raster ROM** evaluates the planes for coverage and hands the
fragment shader **perspective-correct barycentrics** and its depth. The
fragment shader reads a varying's three per-vertex values with a new load,
`FATTR`, and interpolates with three FMAs.

## Registers

All `nogen`, decoded in `Borg.wireDraw`.

| Register              | Offset  | Contents                                                       |
|-----------------------|---------|----------------------------------------------------------------|
| `DRAW_CFG`            | 0x33C   | bit 0 mode (1 = draw), 2:1 topology (0 list, 1 strip, 2 fan), 4:3 index type (0 none, 1 u16, 2 u32), 5 primitive restart, 9:6 record shift (reset 8) |
| `DRAW_VERTEX_COUNT`   | 0x340   | vertices (or indices) per instance                             |
| `DRAW_INSTANCE_COUNT` | 0x344   | instances                                                      |
| `DRAW_FIRST_VERTEX`   | 0x348   | added to the position of a non-indexed vertex                  |
| `DRAW_FIRST_INSTANCE` | 0x34C   | added to the instance number                                   |
| `DRAW_VERTEX_OFFSET`  | 0x350   | added to every index (signed)                                  |
| `DRAW_INDEX_BASE`     | 0x354   | index buffer, byte address                                     |
| `VIEWPORT_SX/SY/OX/OY`| 0x358-0x364 | FP32: screen = ndc * s + o                                 |
| `DEPTH_SCALE/OFFSET`  | 0x368, 0x36C | FP32: FragCoord.z = z_ndc * scale + offset              |
| `DRAW_VS_CONST`       | 0x370   | the vertex shader's constant window (7 words), 0 = none        |
| `DRAW_FS_CONST`       | 0x374   | the fragment shader's constant window (12 words), 0 = none     |
| `SAMPLE_MASK_CFG`     | 0x380   | [3:0] sample mask, [4] alpha to coverage, [5] shader mask in r19, [6] one sample |
| `SEQ_TILE_ROWS`       | 0x384   | the render window's tile rows (0 = `SEQ_TILES_PER_ROW`)          |
| `FB_ORIGIN`           | 0x388   | the window's first tile: x [11:0], y [27:16]                     |
| `FB_PITCH`            | 0x38C   | the framebuffer's tiles per row (0 = `SEQ_TILES_PER_ROW`)        |
| `ATT_CFG`             | 0x390   | [1:0] colour attachments - 1, [4:2] loadOp LOAD of attachments 1-3 |
| `ATT_FORMAT`          | 0x394   | `FLUSH_FORMAT` of attachments 1-3: [2:0], [5:3], [8:6] (B3)      |
| `ATT_BASE1..3`        | 0x398-0x3A0 | colour base of attachments 1-3                               |
| `ATT_CLEAR_RG1..3`    | 0x3A4-0x3AC | clear R, G (FP16) of attachments 1-3                         |
| `ATT_CLEAR_BA1..3`    | 0x3B0-0x3B8 | clear B (FP16 [31:16]) and A (UNORM8 [7:0]) of attachments 1-3 |
| `DEPTH_BIAS_CONST`    | 0x3BC   | FP32 `depthBiasConstantFactor` (see Depth bias)                  |
| `DEPTH_BIAS_SLOPE`    | 0x3C0   | FP32 `depthBiasSlopeFactor`                                      |
| `ATT_CLEAR_EXT0..3`   | 0x3C4-0x3D0 | RAW64 clear word 1 of attachments 0-3 (B3)                   |
| `ATT_CLEAR_W2_0..3`, `ATT_CLEAR_W3_0..3` | 0x3D4-0x3F0 | RAW128 clear words 2, 3 (B3)                    |

The draw also uses the sequencer's existing registers: vertex and fragment
shader address and length, bin base and row size, `SEQ_SETUP_BASE` (the
records), the framebuffer, clear values, `CULL_CFG`, and the attachment
registers. `SEQ_TRI_COUNT` is ignored.

## Primitive assembly

For primitive `p` of an instance (or of the current strip or fan after a
restart), the vertex positions `n0, n1, n2` are:

| Topology       | n0          | n1            | n2            |
|----------------|-------------|---------------|---------------|
| list           | 3p          | 3p+1          | 3p+2          |
| strip, p even  | p           | p+1           | p+2           |
| strip, p odd   | p           | p+2           | p+1           |
| fan            | p+1         | p+2           | 0             |

These are Vulkan's orders, and `n0` is the provoking vertex (flat shading
reads corner 0). Odd strip primitives swap their last two corners, which
keeps the winding the same (`rasterization.flatshading.triangle_strip`
checks exactly this; an earlier order swapped the first two). Without an index buffer, `VertexIndex = firstVertex + n`. With one,
`VertexIndex = index[n] + vertexOffset`. With restart enabled on a strip or
fan, an index of all ones (of the index type) ends the strip: assembly
continues with a new strip after the last restart index the primitive
touched.

`InstanceIndex = firstInstance + i`. The **triangle index** counts every
primitive assembled, culled or not, over all instances, so record `t` always
belongs to primitive `t`. It is 16 bits: a draw of more than 65,535
triangles is split into renders by the driver, which attachment load/store
allows.

## Vertex stage

| Register | At vertex-shader start                                     |
|----------|------------------------------------------------------------|
| r30      | `VertexIndex` (integer)                                    |
| r31      | `InstanceIndex` (integer)                                  |
| u25-u31  | the constant window, loaded from `DRAW_VS_CONST` once per draw |
| r0-r3    | output: clip-space X, Y, Z, W, when the shader halts       |

Lanes 0-2 are the triangle's three corners; lane 3 is masked off. A
single-lane build runs the shader three times. The shader reaches vertex
buffers, push constants and uniform buffers through `LOAD`, with base
addresses from its constant window. Varyings go to memory:

**`SOUT rs2, index`** (funct7 0x42) stores `rs2` as output component `index`
of this invocation's corner, at `recordBase + 4*48 + 4*(3*index + corner)`:
a component's three per-vertex values sit next to each other. The 10-bit
index is split over the `rd` and `rs1` fields, as RISC-V S-type stores split
theirs. Masked lanes store nothing.

The shader runs once per triangle, not once per unique vertex, which Vulkan
allows. Vertex-stage stores and atomics are the optional
`vertexPipelineStoresAndAtomics` feature, which Borg does not report.

## Setup ROM

After the vertex shader, the walker stages the twelve clip coordinates, the
viewport, the depth range, four constants and the depth bias into u0-u24,
and runs `BorgSetupRom`. With the viewport folded into the homogeneous
coordinates,

    X'k = Xk*sx + Wk*ox,   Y'k = Yk*sy + Wk*oy,   M = [X'; Y'; W]  (columns = corners)

the rows of `M^-1` are three planes `Ek(x, y) = ak*x + bk*y + ck`, with

    Ek = (perspective-correct barycentric k) / w,   E0 + E1 + E2 = 1/w.

A pixel is inside when all three `Ek >= 0`, which is exact for vertices
behind the eye: the region the test accepts is the part of the triangle with
`w > 0`. `z_ndc = sum(Ek*Zk)` is linear in screen space, and its plane `Zn`
is the fourth plane; `Zf = 1 - Zn` is the fifth. Together they clip to
Vulkan's `0 <= z <= w`. The facing is the sign of `det M`, which is the
orientation of the visible part even when some `w < 0`; with
`CULL_CFG.front_face_invert` clear, front is Vulkan's default
counter-clockwise (`det M < 0`).

The ROM writes the record with `SOUT` (stride 1) and leaves the corners'
screen positions in r0-r5 and `det M` in r6. The walker culls a triangle when
`det M` is zero, when its facing is culled, or when every `W <= 0`, and bins
the whole grid when some `W <= 0`. Otherwise the bounding box comes from the
screen positions, with a pixel of slack for the reciprocal's rounding.

`FRCP` and `FRSQ` keep FP32's exponent range for this (the LUT core sees only
the mantissa); one Newton step gives about 22 bits, enough for the bounding
box and the raster ROM's `1/sum(E)`. `1/det M` takes two, to full FP32: it
scales the depth plane, and with one a flat `z = 0.5` rasterized some 2^-20
low -- more than a D32_SFLOAT ulp.

### Depth bias

`DEPTH_BIAS_CONST` and `DEPTH_BIAS_SLOPE` (FP32, reset 0) are Vulkan's
`depthBiasConstantFactor` and `depthBiasSlopeFactor`; the driver writes 0
for both when `depthBiasEnable` is off, and for points and lines, which
Vulkan does not bias. The setup ROM adds

    o = m * slope + r * constant

to the triangle's depth offset, so every sample of every pixel carries it:

- `m = max(|dz/dx|, |dz/dy|)` of FragCoord.z, the approximation the spec
  allows;
- `r = 2^-15` for D16_UNORM (the spec's largest; 2^-16 is a hair under
  one D16 step); for D32_SFLOAT `2^(e - 23)`, `e` the exponent
  of the triangle's largest depth, which is exactly `ulp(max z)`. The largest
  depth is the depth plane at the three corners, or 1.0 with a corner behind
  the eye.

`depthBiasClamp` is an optional feature Borg does not report.

The depth test compares in float order (a negative depth, which bias can
produce, is below every positive one; -0 equals +0), and a draw's fragment
depth into a D16 attachment is clamped to [0, 1] and **rounded to D16**
first -- per sample, which only hardware can do at 4x -- with the flush's
and load's own conversions, so a depth stored, reloaded and drawn again
with EQUAL passes (`d16_depth_is_invariant_across_store_and_reload`).

A primitive **in the far plane** (Z = W at every corner: a skybox) gets
the exact depth plane Zn = 1: rebuilt from rounded coefficients it came out
1 +- an ulp, losing samples to the far test at 4x and failing LEQUAL
against a 1.0 clear at D32 (`far_and_near_plane_primitives_are_inside`).

## Raster ROM and fragment ABI

In a render (and on the MMIO pixel path with `DRAW_CFG.mode` set) the
dispatcher's per-pixel trigger runs `BorgRasterRom.drawInstructions` instead
of the three-plane edge test. It
evaluates `E0, E1, E2, Zn, Zf` into r0-r4; the dispatcher snoops all five for
coverage, per sample at 4x MSAA (the far plane's sample thresholds are the
depth plane's with the signs swapped). Pixel centres are built in FP32, exact
at any framebuffer size.

| Register | At fragment-shader start                                   |
|----------|------------------------------------------------------------|
| r0-r2    | `E0..E2`: the edge planes, unscaled (see [Watertight edges](#watertight-edges)) |
| r3, r4   | `Zn = z_ndc` and `Zf = 1 - z_ndc`                           |
| r5-r7    | perspective-correct barycentrics `λk = Ek / sum(E)`        |
| r8       | `FragCoord.w = sum(E) * |1/det M| = 1/w`                    |
| r9, r10  | clobbered                                                  |
| r11-r14  | the depth at each MSAA sample, not written at one sample (see [Per-sample depth](#per-sample-depth)) |
| r29      | `FragCoord.z`; the fragment's depth if the shader writes r29 |
| r30, r31 | `FragCoord.xy`, the pixel centre                           |
| u0-u19   | the triangle's record image: its planes (see below)        |
| u20-u31  | the constant window, loaded from `DRAW_FS_CONST` once per render |

The noperspective barycentric of corner k is `Ek * Wk * |1/det M|`
(`Wk` is available if the vertex shader outputs it as a varying).

**`FATTR rd, index`** (funct7 0x44) loads component `index`'s three
per-vertex values into `rd, rd+1, rd+2` of every active lane (one memory
read per word for the whole quad). A varying is then

    v = λ0*a0 + λ1*a1 + λ2*a2         (FMUL + 2 FMADD)

`flat` uses `a0`, the provoking vertex. `FATTR` reads the record in memory;
caching attributes on chip is a possible later optimization, not part of the
contract.

### Watertight edges

A sample on an edge two triangles share must be covered by exactly one of
them. The setup ROM therefore stores each edge plane **unscaled**: the cross
product of the edge's two corners, each component `round(p) - round(q)` of
two separately rounded products (no FMA), with its sign flipped when
`det M < 0` so that inside is `>= 0`. The triangle on the other side of the
edge computes the exact negation, and the per-pixel evaluation and the MSAA
deltas are sign-symmetric too, so the two triangles' values are exact
opposites at every sample. A sample exactly on an edge (value 0) is then
covered only by the triangle for which the edge is a **top or left** edge:
inward normal `(a, b)` with `a > 0`, or `a == 0` and `b > 0`. The tile
sequencer derives those flags from the planes as they arrive. The division
by `det M` that used to scale the planes cancels in `Ek / sum(E)`; it
survives only as `|1/det M|` for `FragCoord.w` and in the depth plane.

### Per-sample depth

At 4x MSAA the depth test uses each sample's own depth, not the centre's:
the raster ROM computes `FragCoord.z + {d0, d1, -d1, -d0}` into r11-r14 from
the depth plane's sample deltas (record words 17-18, already in framebuffer
depth), and the dispatcher tests and stores sample s with its own value. If
the shader writes r29 (`FragDepth`), that value is used for every sample.
Builds without blend or stencil write one depth per pixel (their tile writes
are broadcast), and keep the centre's.

At one sample (`SAMPLE_MASK_CFG` bit 6) every sample sits at the pixel centre
and all four depths equal `FragCoord.z`, so the ROM halts before computing
them: r11-r14 keep whatever they held and the depth test uses r29.

### Sample masks and coverage in the shader

`SAMPLE_MASK_CFG` (0x380): bits [3:0] the pipeline's static sample mask
(`pSampleMask`, reset all ones), bit 4 alpha to coverage, bit 5 the fragment
shader writes `gl_SampleMask` to **r19**. The static mask applies before
shading; alpha to coverage (the first `round(alpha * 4)` samples) and the
shader's mask after it, and after `ZTEST`'s early depth writes.

**One sample.** Bit 6 is `rasterizationSamples = 1`, which Vulkan requires
alongside 4 for every attachment. The walker puts all four sample offsets at
the pixel centre, so coverage and depth are the centre's; every mask acts on
sample 0 (alpha to coverage covers it from alpha 0.5, the shader's mask is
its bit 0) and holds for all four; `SMASK` returns one bit; each pixel counts
once for occlusion. The four copies are identical, so the resolve is exact:
a pixel is wholly the triangle's or untouched. A multi-pass (Wafer) build
renders one pass instead of four. Attachments are then single-sample; do not
combine with `ATTACH_MS`.

**`SMASK rd`** (funct7 0x46) returns the lane's coverage mask after the
static mask: `gl_SampleMaskIn`. It also gives the compiler **centroid**
interpolation: when the mask is not full, evaluate the edge planes (in
u0-u8) at the first covered sample's offset, `Ek + ak*ox + bk*oy`, and
normalize as usual.

## Render windows

A render covers a **window** of `SEQ_TILES_PER_ROW x SEQ_TILE_ROWS` tiles
whose first tile is `FB_ORIGIN`, inside a framebuffer `FB_PITCH` tiles wide.
Bins, the tile walk and `coordWidth` are window-relative; flush and load
addresses, pixel centres (FragCoord), the scissor and the viewport are the
framebuffer's. A framebuffer larger than one render's bin table (256x256
pixels on the ULX3S/sim build) is drawn as several windows -- the same draw,
once per window; a triangle entirely outside a window is not binned there.
With the registers at reset the window is the whole, square framebuffer.

**Bin capacity.** A tile's bin holds `SEQ_BIN_ROW_BYTES / 2` triangles
(at most `maxTrianglesPerTile`), and a window's records need
`SEQ_SETUP_BASE` room for every triangle of the draw. Records are the
driver's to size, since it knows the draw's triangle count; bins depend on
where triangles land, so the hardware checks them. A triangle that finds its
bin full is dropped, not written into the next tile's row, and the render is
**abandoned whole**: binning finishes, but no tile is rendered, flushed or
counted. Reading `SEQ_TRIGGER` returns bit 0 done, bit 1 **bin overflow**.
The attachments are untouched, so the driver can bin optimistically and, on
overflow, re-issue the window as smaller draws (the first with the original
loadOp, the rest with LOAD, `ATTACH_MS` under MSAA) or with larger bin rows.

## Colour attachments

`ATT_CFG` sets 1-4 colour attachments. Attachment 0 is the historical one
(`SEQ_FB_BASE`, `FLUSH_FORMAT`, the sequencer's clear colour, `TILE_LOAD`
bit 0); 1-3 have their own base, format, clear colour and loadOp. Each tile
is rendered **once per attachment** (twice for a RAW128 one, see
[B3](B3_colour_formats.md)), clearing or loading that attachment, running
every triangle and flushing to it. **`ATTIDX rd`** (funct7 0x48) returns the
attachment the pass renders plus 4 x its slice, and the fragment shader
writes that attachment's colour to r24/r26-r28 (a RAW format: its words to
r26, r27). Depth and stencil are the same in every
pass and are stored in the last; the occlusion count is taken in the first.
Like multi-pass MSAA, a fragment shader's stores would run once per
attachment, which the optional `fragmentStoresAndAtomics` feature (not
reported) would forbid.

## Points and lines

No hardware: they are expanded into triangles in the vertex stage. The
driver draws a triangle list of 6 vertices per point or line segment; each
vertex-shader run derives its primitive and corner from `VertexIndex`,
pulls the endpoint(s) itself (through the index buffer, if any), clips a
segment to the near plane in clip space, and offsets its corner by half a
pixel -- for a line, along the minor axis, which is the parallelogram Vulkan
allows for non-strict lines (`strictLines = false`); for a point, a 1x1
square (`largePoints` not reported). The corners carry their endpoint's
varyings, so attributes interpolate along the line with perspective
correction; `gl_PointCoord` is a varying the vertex stage adds.

## Records and the uniform bank

Triangle `t`'s record is at `SEQ_SETUP_BASE + (t << DRAW_CFG.record_shift)`:

| Words  | Contents                                                          |
|--------|-------------------------------------------------------------------|
| 0-8    | edge planes a, b, c of E0, E1, E2 (unscaled, sign-normalized)     |
| 9-11   | depth plane Zn: a, b, c                                           |
| 12-14  | depth scale, depth offset, 1.0                                    |
| 15     | meta: bit 1 = back-facing (written by the walker)                 |
| 16     | `|1/det M|`                                                       |
| 17-18  | the depth plane's sample deltas in framebuffer depth              |
| 32-39  | MSAA sample deltas d0, d1 of each of the four planes              |
| 48-... | varyings, three words per component (`SOUT`, `FATTR`)             |

Pass 2 DMAs words 0-19 into the uniform bank per triangle (the setup cache
keeps one or two triangles resident) and words 32-39 into the coverage
logic. The uniform bank is thus split in both passes: the low words are the
hardware's per-triangle data, the high words the stage's constant window,
which the per-triangle loads never touch.

| Pass | Hardware words             | Constant window                    |
|------|----------------------------|------------------------------------|
| 1    | u0-u24: setup ROM inputs   | u25-u31: vertex shader (7 words)   |
| 2    | u0-u19: record words 0-19  | u20-u31: fragment shader (12 words)|

The driver picks the smallest record shift that fits the pipeline's outputs:
`48 + 3*N` words for `N` varying components, so 8 (256 bytes) for up to five
and 10 (1 KB) for 64.

## The legacy path

Before the draw front end, the firmware wrote a descriptor per triangle, and
a Pass-1 sequencer (`BorgGeometrySequencer`) ran a driver-supplied vertex and
setup shader and staged a fixed uniform layout. It was removed on 2026-09-26,
once `borgc` compiled for the draw front end by default, to fit the ULX3S.
Its registers (`SEQ_DESC_BASE`, `SEQ_SETUP_ADDR/LEN`, `SEQ_INV_WIDTH`,
`SEQ_TRI_COUNT`, `SEQ_RAST_ADDR/LEN`) keep their slots but have no hardware
behind them. `SEQ_TRIGGER` always renders a draw; `DRAW_CFG.mode` only picks
the MMIO pixel path's (`CMD_ENQUEUE`/`ITER`) program: 0 the three-plane edge
test (`BorgRasterRom.instructions`), 1 the draw raster program.

## Using it: a minimal draw

This is exactly what `BorgDrawTests.DrawRig` does, and it renders correctly
on every configuration. All addresses are GPU byte addresses.

**Memory.**

- Vertex shader and fragment shader code, contiguous (the instruction
  cache fetches past IMEM from there).
- A vertex buffer in whatever layout the vertex shader pulls, here six
  words per vertex: X, Y, Z, W (clip space), then two varyings r, g.
- Optionally an index buffer (16- or 32-bit).
- The vertex constant window, 10 words: here word 0 = vertex buffer word
  address (`vb / 4`), word 1 = 6 (stride), word 2 = 1.
- The fragment constant window, 12 words: here word 0 = 0.0.
- Room for the records: `triangles << record_shift` bytes at
  `SEQ_SETUP_BASE`, plus the bin table and the framebuffer as usual.

**Vertex shader** (varying `k` = `SOUT` index `k`):

    IMUL  r9, r30, u26        ; VertexIndex * stride        (funct3 = 2)
    IADD  r9, r9, u25         ; + vertex buffer word address
    LOAD  r0, r9              ; X      then IADD r9, r9, u27 between loads
    LOAD  r1, r9              ; Y
    LOAD  r2, r9              ; Z
    LOAD  r3, r9              ; W      -> r0..r3 is the clip-space position
    LOAD  r10, r9             ; r
    LOAD  r11, r9             ; g
    SOUT  r10, 0              ; varying 0
    SOUT  r11, 1              ; varying 1
    HALT                      ; (word 0)

**Fragment shader** (loaded at IMEM offset 1, `FRAG_PC` = 1):

    FATTR r10, 0              ; r10..r12 = varying 0 at corners 0, 1, 2
    FMUL  r26, r5, r10        ; red = l0*a0 + l1*a1 + l2*a2
    FMADD r26, r6, r11, r26
    FMADD r26, r7, r12, r26
    FATTR r10, 1              ; green likewise into r27
    ...
    FMUL  r28, r5, u20        ; blue = 0.0 from the constant window
    HALT                      ; depth: r29 already holds FragCoord.z

**Registers**, then `SEQ_TRIGGER`:

    SEQ_VERT_ADDR/LEN, SEQ_FRAG_ADDR/LEN, FRAG_PC = 1
    SEQ_BIN_BASE, SEQ_BIN_ROW_BYTES, SEQ_SETUP_BASE (records)
    SEQ_FB_BASE, SEQ_TILES_PER_ROW, SEQ_CLEAR_LO/HI, FLUSH_FORMAT, DEPTH_CFG, CULL_CFG
    DRAW_CFG            = 1 | topology << 1 | index_type << 3 | restart << 5 | record_shift << 6
    DRAW_VERTEX_COUNT   = vertices (or indices) per instance
    DRAW_INSTANCE_COUNT = 1 or more
    DRAW_FIRST_VERTEX, DRAW_FIRST_INSTANCE, DRAW_VERTEX_OFFSET, DRAW_INDEX_BASE
    VIEWPORT_SX/SY      = width/2, height/2      (FP32)
    VIEWPORT_OX/OY      = x + width/2, y + height/2
    DEPTH_SCALE/OFFSET  = maxDepth - minDepth, minDepth
    DRAW_VS_CONST, DRAW_FS_CONST
    SEQ_TRIGGER = 1     ; then poll STATUS bit 5 (sequencer busy) until clear

`SEQ_TRI_COUNT` is not used in draw mode. With `record_shift` 8 a record
holds five varying components; use 10 for up to 64.

## Tests

| What                                                        | Test                                             |
|-------------------------------------------------------------|--------------------------------------------------|
| `SOUT` by corner, `FATTR` to every lane, the vertex indices | `BorgCoreDrawTests`                              |
| `FRCP`/`FRSQ` over FP32's range                             | `FrcpFp32RangeTests`                             |
| Setup ROM vs a double-precision inverse, corners behind the eye | `BorgSetupRomTests`                          |
| Raster ROM: planes, barycentrics, 1/w, depth                | `BorgSetupRomTests`                              |
| Whole draws: perspective-correct varyings, depth planes, corners behind the eye, lists, strips, fans, restart, instancing; single-lane, 4-lane and Wafer sizing | `BorgDrawTests` |
| Shared edges covered exactly once (an edge through samples, a random perspective fan) | `BorgDrawTests.shared_edges_are_covered_exactly_once` |
| Static sample mask, alpha to coverage, shader mask, SMASK    | `BorgDrawTests.sample_mask_alpha_to_coverage_and_smask` |
| Depth tested at each sample's position                      | `BorgDrawTests.depth_is_tested_per_sample` |
| A 16x8 framebuffer as one non-square window and as two windows | `BorgDrawTests.render_windows_and_non_square_framebuffers` |
| Three colour attachments (cleared, BGRA, loaded) with depth LESS | `BorgDrawTests.several_colour_attachments` |
| A full bin: nothing rendered or written, overflow reported; with room, the same draw renders | `BorgDrawTests.bin_overflow_renders_nothing_and_reports` |
| One sample: centre coverage and depth, one count per pixel, exact resolve, masks on sample 0; resident and Wafer multi-pass | `BorgDrawTests.single_sample_rasterization` |
| Depth bias against a reference, random triangles incl. corners behind the eye | `BorgSetupRomTests.setup_rom_adds_depth_bias` |
| Depth bias in a draw: the constant term to r for D16 and D32_SFLOAT, the slope term to the sample, negative biased depth | `BorgDrawTests.depth_bias_constant_and_slope` |

## Not covered yet

Nothing in the hardware list. The firmware's side is still cube-specific:
it rebuilds cube.vert's UBO from the 0xAE geometry packet, and draws with
vertex-buffer input (the CTS mailbox path) are not wired up.
