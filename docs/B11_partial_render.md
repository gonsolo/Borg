# Partial render

Status: built 2026-10-27 (`BorgDrawTests.a_full_bin_renders_in_parts`,
`a_render_list_renders_in_parts`); ULX3S +205 LUTs, +24 FFs; Wafer `BorgSequencer` +5,615 µm².

## The gap

Pass 1 bins each triangle into every 4 × 4 tile its bounding box covers, at most 256 per tile
(`maxTrianglesPerTile`, `SEQ_MAX_TRI`). When a bin is full, `BorgBinner` drops the entry and sets
a sticky overflow; the sequencer then skips Pass 2, so the render writes nothing, and bit 1 of
`SEQ_TRIGGER` reports it. The firmware (`borg_core_list_flush`) re-renders a list's draws one
render each. A single draw that alone puts more than 256 triangles into one tile overflows again
and is silently missing from the frame. The retry is also not exact: it loads colour only
(`load | 9`), so a depth or stencil aspect the pass clears is cleared again by every draw after
the first.

## The design

When a bin is full, render what is binned so far, then continue: the hardware splits the render
in parts, as PowerVR's partial renders and Mali's incremental rendering do. Nothing is redone,
and no driver or firmware is involved.

### Binner

On a full bin the binner stops the triangle (no further tiles) and reports `full` with `done`.
The sticky overflow goes.

### Walker

In `sWaitBin`, `full` takes the walker to `sPaused`, holding triangle `t`'s index and bounding
box; `paused` tells the sequencer. On `resume` it reloads the vertex-shader constant window (Pass
2 uses both uniform pages) and goes back to `sBin`: `t` is binned again, into empty bins, so it
fits. Nothing else in the walker changes: the primitive it was on, its instance, the restart
base and the triangle numbering carry on, so every topology, restart and instancing work alike.

### Sequencer

`wPass1` on `paused` runs a partial Pass 2:

1. Pass 2 with `triLimit = t`: a bin entry at or above the limit ends the tile's list, so the
   parts of `t` binned before the full bin are not drawn twice (bin entries are in increasing
   order, so `t` is the last entry wherever it got in).
2. Clear the bin counts (`clearCounts`, `maxBinTiles` cycles).
3. In list mode, replay the current entry's state and parameter blocks: Pass 2 replays the
   state blocks of the triangles it renders, so the registers hold some later draw's.
4. `resume`, back to `wPass1`.

`part` counts the parts of a render. The first part runs with the application's `TILE_LOAD`;
every later part, including the last Pass 2 at the end of the draw or list, loads every aspect
that has an attachment and keeps the tiles no triangle reaches. `curBufIdx` toggles at the end
of the render only.

### Pass 2

`triLimit` (16 bits, the triangle index width), and the load override from the sequencer: colour,
depth when `FLUSH_ZB_BASE` is set, stencil when `FLUSH_SB_BASE` is set, `keep`.

### What a part stores

Every part flushes as a full render does: colour always; depth and stencil whenever their base
is set. The firmware sets those bases whenever the pass has the attachment, whatever its store
op, to its own fixed regions (`BORG_ZB_SPI`, `BORG_SB_SPI`), so the next part can always load
them. A 4× pass keeps its samples when it is stored per sample (`ATTACH_MS`, chosen by
`per_sample` in borgvk's `sim.rs`: up to 65,536 pixels and no extension plane).

## Limits

- **Cube (ULX3S, `hasDepthFlush = false`)**: no depth store, so a part's depth is lost to the
  next. cube.c's 12 triangles never fill a bin.
- **4× passes resolved on chip** (over 65,536 pixels, or with an extension plane): a part
  stores the resolved colour, so the next part's samples start from it.
- **Cost**: each part loads and stores every tile it touches once more.

## Software

The firmware's per-draw retry and the overflow bit of `SEQ_TRIGGER` go.

## Tests

- Chisel (`BorgDrawTests` rig): random triangles as a list, a strip, a fan, a fan with restart
  and two instances, depth ALWAYS and LESS with a stored depth buffer, 1x and (list) 4x per
  sample: with one entry per bin, so every triangle is a part, the colour and depth memory and
  the occlusion count equal those of one render. Without `triLimit` the occlusion count shows
  the triangles drawn twice.
- The render-list test with one entry per bin, its second state moving the viewport: without
  the replay of the current draw's blocks the image is wrong.
- direct_sim goldens unchanged; the borgc and driver corpora; the CTS tier 1 pack.

## Order

1. The test that fills a bin, failing (silently missing triangles) today.
2. Binner `full`, walker pause and resume, Pass 2 `triLimit` and load override, sequencer parts.
3. The firmware retry and the overflow bit removed.
4. ULX3S and Wafer area.
