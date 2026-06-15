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

/** BorgTileFlusher -- bulk DMA from tile SRAM to PSRAM, one burst per tile.
  *
  * Streams all 16 tile-buffer entries to SDRAM as ONE 64-word burst write.
  * TileBuffer reads are pipelined into the burst: entry[i+1] is pre-fetched
  * during the stream of entry[i], so the 48-cycle sequential fill phase is
  * eliminated entirely.
  *
  * {{{
  *   sIdle   -- issue TileBuffer read for entry 0; latch tileBase
  *   sPrime1 -- cycle 1 of 2-cycle TileBuffer latency
  *   sPrime2 -- cycle 2; entry 0 valid; capture into entryReg; start burst
  *   sBurst  -- stream R/G/B/Z of entryReg; at wordSub==1 pre-fetch next entry
  *              (data arrives at wordSub==3); repeat for all 16 entries (64 words)
  * }}}
  *
  * Pre-fetch timing (2-cycle TileBuffer read latency):
  *   wordSub==1: io.read.en=1 for nextFill
  *   wordSub==2: SyncReadMem latches address (cycle 1 of 2)
  *   wordSub==3: readDataHeld valid; entryReg := io.read.data
  *
  * Total flush = 2 (prime) + 64 (burst) = 66 cycles vs 112 cycles previously.
  * Area: 1 x 64-b staging register instead of 16 x 64-b wordBuf (~960 fewer FFs).
  */
class BorgTileFlusher(val dataBits: Int = 16) extends Module {
  val io = IO(new BorgTileFlusherIO(dataBits))

  val sIdle :: sPrime1 :: sPrime2 :: sBurst :: Nil = Enum(4)
  val state = RegInit(sIdle)

  // 1-entry staging buffer: 64 FFs replacing the 1024-FF 16-entry wordBuf.
  val entryReg  = Reg(UInt(64.W))
  val baseReg   = RegInit(0.U(25.W))
  val fillIdx   = RegInit(0.U(4.W))  // entry currently being streamed (0..15)
  val nextFill  = RegInit(0.U(4.W))  // next entry to pre-fetch (starts at 1)
  val wordSub   = RegInit(0.U(2.W))  // word within current entry: 0=R 1=G 2=B 3=Z

  // Default outputs
  io.busy := (state =/= sIdle) || io.start

  io.read.idx := 0.U
  io.read.en  := false.B

  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U
  io.gpuMem.wlen  := 1.U

  // ColorZ.asUInt packs first field in MSBs: {r[63:48], g[47:32], b[31:16], z[15:0]}.
  val curWord = MuxLookup(wordSub, entryReg(63, 48))(Seq(
    0.U -> entryReg(63, 48),  // R
    1.U -> entryReg(47, 32),  // G
    2.U -> entryReg(31, 16),  // B
    3.U -> entryReg(15, 0)    // Z
  ))

  switch(state) {

    is(sIdle) {
      when(io.start) {
        baseReg  := io.tileBase
        fillIdx  := 0.U
        nextFill := 1.U
        wordSub  := 0.U
        // Issue TileBuffer read for entry 0; data valid in sPrime2 (2 cycles later).
        io.read.en  := true.B
        io.read.idx := 0.U
        state := sPrime1
      }
    }

    is(sPrime1) {
      // SyncReadMem address latched; wait for readDataHeld to capture.
      state := sPrime2
    }

    is(sPrime2) {
      // Entry 0 data valid on io.read.data; capture and begin burst.
      entryReg := io.read.data.asUInt
      state    := sBurst
    }

    // Stream all 64 words (16 entries x 4 words each).
    // Pre-fetch at wordSub==1 overlaps with the 2-cycle TileBuffer latency so
    // the next entry is always ready in entryReg by wordSub==3.
    is(sBurst) {
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := baseReg
      io.gpuMem.wdata := curWord
      io.gpuMem.wlen  := 64.U

      when(io.gpuMem.waccept) {
        wordSub := wordSub + 1.U

        // Pre-fetch next entry at word 1 (G): 2-cycle latency delivers data at word 3 (Z).
        when(wordSub === 1.U && nextFill < 16.U) {
          io.read.en  := true.B
          io.read.idx := nextFill
          nextFill    := nextFill + 1.U
        }

        // At word 3 (Z), capture pre-fetched data and advance to next pixel.
        when(wordSub === 3.U) {
          if (BorgDebug.trace) printf("[FLUSH] entry=%d R=0x%x G=0x%x B=0x%x Z=0x%x\n",
            fillIdx, entryReg(63, 48), entryReg(47, 32), entryReg(31, 16), entryReg(15, 0))
          entryReg := io.read.data.asUInt
          fillIdx  := fillIdx + 1.U
          wordSub  := 0.U
        }
      }

      when(io.gpuMem.ready) {
        state := sIdle
      }
    }
  }
}
