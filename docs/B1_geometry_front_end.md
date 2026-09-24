# Geometry Front End: Draws, Homogeneous Setup and Varyings

Status: implemented and tested in simulation, September 2026, on branch
`feat/vulkan-conformance-gaps` (`BorgConfig.drawEnabled`, i.e. any FP32
build with memory ops, Wafer included).

The limits check against Vulkan 1.0 found that the geometry front end falls
short on most of the vertex-to-fragment path. This page describes how the
hardware closes those gaps. The legacy path stays in place until `borgc` and
`borgvk` move over (see [Coexistence](#coexistence-with-the-legacy-path)).

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
| `DRAW_VS_CONST`       | 0x370   | the vertex shader's constant window (10 words), 0 = none       |
| `DRAW_FS_CONST`       | 0x374   | the fragment shader's constant window (16 words), 0 = none     |

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
| strip, p odd   | p+1         | p             | p+2           |
| fan            | p+1         | p+2           | 0             |

Strips swap the first two corners on odd primitives, which keeps the winding
the same. Without an index buffer, `VertexIndex = firstVertex + n`. With one,
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
| u22-u31  | the constant window, loaded from `DRAW_VS_CONST` once per draw |
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
viewport, the depth range and four constants into u0-u21, and runs
`BorgSetupRom` (121 words). With the viewport folded into the homogeneous
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
the mantissa); both ROMs add one Newton step, for about 22 bits.

## Raster ROM and fragment ABI

With `DRAW_CFG.mode` set, the dispatcher's per-pixel trigger runs
`BorgRasterRom.drawInstructions` (26 words) instead of the legacy edge test.
It evaluates `E0, E1, E2, Zn, Zf` into r0-r4; the dispatcher snoops all five
for coverage, per sample at 4x MSAA (the far plane's sample thresholds are
the depth plane's with the signs swapped). Pixel centres are built in FP32,
exact at any framebuffer size.

| Register | At fragment-shader start                                   |
|----------|------------------------------------------------------------|
| r0-r2    | `E0..E2` (screen-linear; `Ek*Wk` is the noperspective barycentric) |
| r3, r4   | `Zn = z_ndc` and `Zf = 1 - z_ndc`                           |
| r5-r7    | perspective-correct barycentrics `λk = Ek / sum(E)`        |
| r8       | `FragCoord.w = sum(E) = 1/w`                               |
| r9, r10  | clobbered                                                  |
| r29      | `FragCoord.z`; the fragment's depth unless the shader writes r29 |
| r30, r31 | `FragCoord.xy`, the pixel centre                           |
| u16-u31  | the constant window, loaded from `DRAW_FS_CONST` once per render |

**`FATTR rd, index`** (funct7 0x44) loads component `index`'s three
per-vertex values into `rd, rd+1, rd+2` of every active lane (one memory
read per word for the whole quad). A varying is then

    v = λ0*a0 + λ1*a1 + λ2*a2         (FMUL + 2 FMADD)

`flat` uses `a0`, the provoking vertex. `FATTR` reads the record in memory;
caching attributes on chip is a possible later optimization, not part of the
contract.

## Records and the uniform bank

Triangle `t`'s record is at `SEQ_SETUP_BASE + (t << DRAW_CFG.record_shift)`:

| Words  | Contents                                                          |
|--------|-------------------------------------------------------------------|
| 0-11   | planes a, b, c of E0, E1, E2, Zn                                  |
| 12-14  | depth scale, depth offset, 1.0                                    |
| 15     | meta: bit 1 = back-facing (written by the walker)                 |
| 32-39  | MSAA sample deltas d0, d1 of each of the four planes              |
| 48-... | varyings, three words per component (`SOUT`, `FATTR`)             |

Pass 2 DMAs words 0-15 into the uniform bank per triangle (the setup cache
keeps one or two triangles resident) and words 32-39 into the coverage
logic. The uniform bank is thus split in both passes: the low words are the
hardware's per-triangle data, the high words the stage's constant window,
which the per-triangle loads never touch.

| Pass | Hardware words             | Constant window                    |
|------|----------------------------|------------------------------------|
| 1    | u0-u21: setup ROM inputs   | u22-u31: vertex shader (10 words)  |
| 2    | u0-u15: record words 0-15  | u16-u31: fragment shader (16 words)|

The driver picks the smallest record shift that fits the pipeline's outputs:
`48 + 3*N` words for `N` varying components, so 8 (256 bytes) for up to five
and 10 (1 KB) for 64.

## Coexistence with the legacy path

`DRAW_CFG.mode = 0` (reset) keeps today's per-triangle descriptors, driver
setup shader, raster ROM and uniform layout, so the firmware, `borgvk` and
`borgc` keep working untouched. Mode 1 is everything above. The legacy path
can be deleted once the compiler emits the new ABI.

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
- The fragment constant window, 16 words: here word 0 = 0.0.
- Room for the records: `triangles << record_shift` bytes at
  `SEQ_SETUP_BASE`, plus the bin table and the framebuffer as usual.

**Vertex shader** (varying `k` = `SOUT` index `k`):

    IMUL  r9, r30, u23        ; VertexIndex * stride        (funct3 = 2)
    IADD  r9, r9, u22         ; + vertex buffer word address
    LOAD  r0, r9              ; X      then IADD r9, r9, u24 between loads
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
    FMUL  r28, r5, u16        ; blue = 0.0 from the constant window
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

## Not covered yet

Points and lines (the driver can expand them into triangles meanwhile), the
top-left fill rule, per-sample depth at MSAA, centroid interpolation, and
the sample mask and alpha-to-coverage. All of them build on the same planes.
