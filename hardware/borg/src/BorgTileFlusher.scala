// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** IO bundle for [[BorgTileFlusher]].
  *
  * Directions are from the flusher's perspective (master).
  */
class BorgTileFlusherIO(val dataBits: Int = 16) extends Bundle {
  // Trigger interface
  val start     = Input(Bool())    // one-cycle pulse to begin flush
  val busy      = Output(Bool())   // high while flushing

  // Tile SRAM read port (flusher drives idx/en, reads data)
  val read      = new TileReadIO(dataBits)

  // PSRAM write port
  val gpuMem    = new GpuMemIO

  // Tile base address: absolute PSRAM byte address of this tile's region.
  // Layout: 16 entries × 8 bytes = 128 bytes per tile.
  //   word[2*i]   = entry[i] bits[31:0]  (R|G)
  //   word[2*i+1] = entry[i] bits[63:32] (B|Z)
  // Firmware computes: tileBase = fbBase + tile_index * 128
  //   where tile_index = (ty >> 2) * tiles_per_row + (tx >> 2)
  val tileBase  = Input(UInt(25.W))
}

/** BorgTileFlusher — bulk DMA from tile SRAM to PSRAM, one burst per tile.
  *
  * Reads all 16 tile-buffer entries ({R:16, G:16, B:16, Z:16} each) into a local
  * buffer, then streams the whole tile to SDRAM as ONE 64-word burst write:
  *
  * {{{
  *   sIdle  → sRead (latch tileBase, fillIdx=0)
  *   sRead/sReadWait/sReadWait2 — read entry[fillIdx] (2-cycle BRAM latency) into
  *     wordBuf; loop 16 times, then → sBurst
  *   sBurst — assert gpuMem.wr with wlen=64 and present words R,G,B,Z of each pixel
  *     in raster order (64 contiguous words = 128 bytes); advance on gpuMem.waccept;
  *     finish on gpuMem.ready.
  * }}}
  *
  * One burst pays the MemoryController/backend round-trip ONCE instead of per word,
  * cutting the per-tile flush from ~336 cycles (48 single-word writes) to ~110.
  * The dead Z slot (+6) is now written too so the 64 words are contiguous — the
  * scanout still ignores Z (reads only B from the {Z,B} word), so the SDRAM layout
  * (R@+0 G@+2 B@+4 Z@+6 per pixel) is unchanged.
  *
  * wordBuf holds the whole tile in flops (16×64 b); a future area optimisation could
  * prefetch entries during the stream instead, but a flat buffer is unambiguously
  * correct and the burst itself is the win.
  */
class BorgTileFlusher(val dataBits: Int = 16) extends Module {
  val io = IO(new BorgTileFlusherIO(dataBits))

  // BorgTileBuffer read latency: io.read.data is valid 2 cycles after read.en.
  val sIdle :: sRead :: sReadWait :: sReadWait2 :: sBurst :: Nil = Enum(5)
  val state = RegInit(sIdle)

  // Whole-tile buffer: 16 entries × {R,G,B,Z} = 64 b each, captured up front so a
  // concurrent dispatcher read on the tile-buffer port cannot corrupt the burst.
  val wordBuf = Reg(Vec(16, UInt(64.W)))
  val baseReg = RegInit(0.U(25.W))
  val fillIdx = RegInit(0.U(5.W))   // 0..15 during read-in
  val wordIdx = RegInit(0.U(7.W))   // 0..63 during the burst

  // Default outputs
  io.busy := (state =/= sIdle) || io.start

  io.read.idx := fillIdx
  io.read.en  := false.B

  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U
  io.gpuMem.wlen  := 1.U

  // Burst word source: 4 words per entry, in SDRAM byte order R(+0) G(+2) B(+4) Z(+6).
  // ColorZ.asUInt packs first field in the MSBs → entry = {r[63:48],g,b,z[15:0]}.
  val curEntry = wordBuf(wordIdx(5, 2))   // pixel index 0..15 (4 bits)
  val curWord  = MuxLookup(wordIdx(1, 0), curEntry(63, 48))(Seq(
    0.U -> curEntry(63, 48),   // R
    1.U -> curEntry(47, 32),   // G
    2.U -> curEntry(31, 16),   // B
    3.U -> curEntry(15, 0)     // Z
  ))

  switch(state) {

    is(sIdle) {
      when(io.start) {
        baseReg := io.tileBase
        fillIdx := 0.U
        wordIdx := 0.U
        state   := sRead
      }
    }

    // ── Read all 16 entries into wordBuf (3-cycle BRAM read per entry) ──
    is(sRead) {
      io.read.en  := true.B
      io.read.idx := fillIdx
      state       := sReadWait
    }
    is(sReadWait) {
      state := sReadWait2
    }
    is(sReadWait2) {
      wordBuf(fillIdx(3, 0)) := io.read.data.asUInt
      if (BorgDebug.trace) printf("[FLUSH] fill entry=%d R=0x%x G=0x%x B=0x%x Z=0x%x\n",
        fillIdx, io.read.data.r, io.read.data.g, io.read.data.b, io.read.data.z)
      when(fillIdx === 15.U) {
        state := sBurst
      }.otherwise {
        fillIdx := fillIdx + 1.U
        state   := sRead
      }
    }

    // ── Stream the whole tile as one 64-word burst write ──
    // The MemoryController latches the base address and auto-increments per word;
    // we just present successive words and advance on waccept (registered producer:
    // a waccept this cycle makes wordIdx — and thus curWord — update next cycle).
    is(sBurst) {
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := baseReg
      io.gpuMem.wdata := curWord
      io.gpuMem.wlen  := 64.U
      when(io.gpuMem.waccept) {
        wordIdx := wordIdx + 1.U
      }
      when(io.gpuMem.ready) {
        state := sIdle
      }
    }
  }
}
