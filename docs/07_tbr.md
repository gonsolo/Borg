# Tile-Based Rendering

Borg uses a **Tile-Based Rendering (TBR)** architecture — the same fundamental
approach used by virtually every modern mobile GPU (ARM Mali, Apple GPU, Qualcomm
Adreno, Imagination PowerVR). This chapter explains why TBR exists, how Borg
implements it in hardware, and how the autonomous sequencer orchestrates both
rendering passes without CPU intervention.

## Why Tile-Based?

The naive approach to 3D rendering is **immediate mode**: process each triangle
in submission order, rasterize its pixels directly into the framebuffer in PSRAM,
and perform depth testing with a read-modify-write on every pixel.

This is expensive. PSRAM access is slow (QSPI, ~20× slower than on-chip SRAM)
and every depth test requires a round-trip: read the stored Z, compare, write
back if closer. For a 128×128 framebuffer at 4× overdraw (a typical scene), that
means ~65,000 PSRAM accesses *per frame* just for depth testing.

TBR avoids this by splitting rendering into two passes:

1. **Geometry pass** — process all triangles, bin them by which screen tiles they
   overlap. Store pre-computed per-triangle data in PSRAM.
2. **Tile render pass** — render one tile at a time. All rasterization and depth
   testing happens in a small, fast on-chip SRAM buffer. The result is flushed to
   PSRAM *once* per tile when complete.

The key insight: PSRAM is only written once per tile per frame, regardless of how
many triangles overlap that tile. Depth testing is free (on-chip).

## Memory Layout

```
PSRAM address space:
─────────────────────────────────────────────────────────
  0x85000 +  framebuffer:   fb_width × fb_height pixels × 2 words (lo/hi)
           ├─ lo word:      { B[31:16], Z[15:0] }
           └─ hi word:      { R[31:16], G[15:0] }
─────────────────────────────────────────────────────────
  tbr_bin_base:
  bin list:    num_tiles × SEQ_MAX_TRI × 2 bytes (triangle indices, uint16)
               Each tile has one row: bin_list[tile][0..count-1]
─────────────────────────────────────────────────────────
  tbr_setup_base:
  setup store: SEQ_MAX_TRI × 128 bytes (31 FP16 uniforms per triangle)
               addr = setupBase + triIdx × 128
─────────────────────────────────────────────────────────
```

Per-tile triangle counts live in an **on-chip `SyncReadMem`** inside `BorgBinner`
(up to 1024 tiles × 10-bit counter). They are never written to PSRAM — the
sequencer queries them directly via the `countRead` port.

## Pass 1: Geometry Pass

For each triangle (driven by `BorgSequencer`):

```
sLoadShader → sRunVert (×3 vertices)
  sWriteSetupInputs → sLoadSetupShader → sRunSetup
    sLoadBBox → sBinTri → sWaitBinner
      sStageUniforms → sStoreSetup
        sNextTriangle
```

| State | What happens |
|---|---|
| `sRunVert` | Vertex shader: NDC → screen-space position (×3 vertices) |
| `sRunSetup` | Setup shader: edge vectors, signed area, inv_area |
| `sLoadBBox` | DMA-read tile-aligned bounding box from descriptor |
| `sBinTri` | Trigger `BorgBinner`: writes triangle index to PSRAM bin list for every tile in bbox |
| `sStageUniforms` | Compute 31 uniforms (edge constants, vertex positions, colors, z) → write to uniform buffer |
| `sStoreSetup` | DMA-write all 31 uniforms to PSRAM setup store at `setupBase + tri × 128` |

After all triangles are processed, Pass 1 is complete. No rasterization occurs.

## The BorgBinner

`BorgBinner` is a small autonomous FSM that runs once per triangle during Pass 1.
Given a triangle index and its tile-aligned bounding box, it iterates over every
tile in the bbox and writes the triangle's index to that tile's bin list in PSRAM.

```
sIdle → sReadCount → sWaitCount → sWritePsram → sStoreCount → sNextTile → ...
```

- **sReadCount**: reads the current triangle count for the tile from on-chip SRAM.
- **sWritePsram**: writes `triIdx` (uint16) to `binBase + tile × binRowBytes + count × 2`.
- **sStoreCount**: increments and stores the count back to on-chip SRAM.

Per-tile counts are stored in a `SyncReadMem(1024, UInt(10.W))` — 10 bits supports
up to 1023 triangles per tile. Counts are zeroed once per frame at `sIdle` via the
`clearCounts` pulse.

The binner has **lowest PSRAM priority** in the arbitration mux
(DMA > Flusher > Geo > Rast), but in practice it only runs during geometry pass
when the rasterizer and flusher are both idle — so contention never occurs.

## Pass 2: Tile Render Pass

Once all triangles are binned, shaders are loaded once into IMEM and the sequencer
iterates every framebuffer tile:

```
sStartPass2 → sReadBinCount → sWaitBinCount → sClearTile
  │  (binTriCount == 0)          └──────────→ sWaitFlush → sWaitFlushSync → sNextRenderTile
  │  (binTriCount > 0)
  └─ sReadBinEntry → sWaitBinEntry → sLoadTriSetup → sEnqueueTile
       → sIteratePixels → sWaitRast → sNextBinTri → (loop or sWaitFlush)
```

| State | What happens |
|---|---|
| `sReadBinCount` | Query binner's on-chip SRAM for number of triangles in this tile |
| `sClearTile` | Write clear color to all 16 slots of the on-chip tile buffer SRAM |
| `sReadBinEntry` | DMA-read the next triangle index from PSRAM bin list (1 word, snooped) |
| `sLoadTriSetup` | DMA-read 31 uniforms from PSRAM setup store → uniform buffer |
| `sEnqueueTile` | Push tile (x, y) into dispatcher FIFO |
| `sIteratePixels` | Send advance pulses; rasterizer+fragment shaders execute per pixel |
| `sWaitRast` | Wait for `tileComplete` (all 16 pixels shaded) |
| `sNextBinTri` | If more triangles for this tile: loop back to `sReadBinEntry` |
| `sWaitFlush` | Trigger HW flusher: writes tile SRAM → PSRAM framebuffer (once per tile) |
| `sNextRenderTile` | Advance to next tile; query its bin count |

**Empty tiles** (binTriCount == 0) skip the inner triangle loop entirely and flush
the clear-filled tile buffer directly. No stale data from a previous frame is ever
visible — every tile is flushed every frame.

**Shader IMEM** stays loaded across all tile iterations. Only the 31 setup uniforms
change per triangle (loaded from the PSRAM setup store). This avoids the IMEM
reload cost from Step 31.

## Sequencer FSM State Count

| Step | States added | Total |
|---|---|---|
| 29: Vertex sequencer | sIdle … sWaitSetup (10) | 10 |
| 29.3: Uniform staging | sStageUniforms | 11 |
| 31.2: Shader reload | sLoadRastShader, sLoadFragShader | 13 |
| 31.4: Tile iteration | sLoadBBox, sClearTile, sEnqueueTile … sNextTile | 21 |
| 31.3: Triangle loop | sNextTriangle | 22 |
| 32.2: Binning | sBinTri, sWaitBinner | 24 |
| 32.3: Two-pass TBR | sStoreSetup, sStartPass2, sReadBinCount, sWaitBinCount, sReadBinEntry, sWaitBinEntry, sLoadTriSetup, sNextBinTri, sNextRenderTile | **33** |

## Hardware Components

```
BorgSequencer (FSM, 33 states)
  ├── BorgDMA          — PSRAM burst read/write (vertex descs, shaders, bin entries, uniforms)
  ├── BorgBinner       — triangle→tile mapping, PSRAM bin list writes
  │     └── countMem   — SyncReadMem(1024, 10.W): per-tile triangle counts (on-chip)
  ├── BorgCore         — FP16 FMA shader processor (vertex + setup shaders in Pass 1)
  ├── BorgRasterizer   — edge-function iterator (fragment shading in Pass 2)
  ├── BorgTileBuffer   — 16-pixel on-chip SRAM (clear fill + depth test in Pass 2)
  └── BorgFlusher      — DMA tile SRAM → PSRAM (once per tile in Pass 2)
```

PSRAM arbitration priority (highest → lowest):

```
DMA  >  Flusher  >  Geo (Binner + SeqStore)  >  Rasterizer
```

## Driver API

From the CPU's perspective, two-pass TBR is a single function call:

```c
// In borgCreateDevice(): compute TBR region bases after framebuffer
tbr_bin_base   = fb_end_spi;
tbr_setup_base = tbr_bin_base + num_tiles * TBR_BIN_ROW_BYTES;

// In borg_present() → borgBinRenderAutonomous():
BORG_GPU->seq_bin_base      = tbr_bin_base;    // PSRAM addr of bin lists
BORG_GPU->seq_bin_row_bytes = TBR_BIN_ROW_BYTES; // bytes per tile row
BORG_GPU->seq_setup_base    = tbr_setup_base;  // PSRAM addr of setup store
BORG_GPU->seq_tri_count     = draw_call_count;
BORG_GPU->seq_trigger       = 1;               // both passes run autonomously
while (BORG_GPU->status & STATUS_REG_T__SEQ_BUSY_bm);
```

The CPU triggers once and waits. Both passes — geometry binning and tile rendering
— run autonomously in hardware without further CPU involvement.

## Performance Characteristics

For a 128×128 framebuffer with 32 tiles (8×4) and 12 triangles (vk_cube scene):

| Metric | Immediate Mode | TBR |
|---|---|---|
| PSRAM writes per frame | O(overdraw × pixels) | O(tiles × 32 words) = 1024 |
| Depth test cost | PSRAM round-trip | On-chip (free) |
| CPU involvement in render | Per-tile loop | **Zero** (autonomous) |
| Clear-fill PSRAM writes | `fb_w × fb_h × 2` | **0** (sClearTile on-chip) |

The clear-fill loop that previously dominated `borgBinRenderAutonomous()` (16,384
PSRAM writes for a 64×64 framebuffer) is now completely eliminated.
