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
  * io.read.en/idx are REGISTERED outputs (set one cycle before they appear
  * on the wire) so that arcilator can evaluate them from the state array
  * without circular combinational dependencies through the tile instance.
  *
  * {{{
  *   sIdle   -- set readEnReg for entry 0; latch tileBase
  *   sPrime1 -- io.read.en visible (from reg); cycle 1 of 2-cycle TileBuffer latency
  *   sPrime2 -- cycle 2 of TileBuffer latency
  *   sPrime3 -- entry 0 valid; capture into entryReg; start burst
  *   sBurst  -- stream R/G/B/Z of entryReg; at wordSub==0 set readEnReg for next entry
  *              (io.read.en appears at wordSub==1; data arrives at wordSub==3); repeat
  * }}}
  *
  * Pre-fetch timing (2-cycle TileBuffer read latency):
  *   wordSub==0: readEnReg := true (register set; wire goes high next cycle)
  *   wordSub==1: io.read.en=1 for nextFill (register visible)
  *   wordSub==2: SyncReadMem latches address (cycle 1 of 2)
  *   wordSub==3: readDataHeld valid; entryReg := io.read.data
  *
  * Total flush = 3 (prime) + 64 (burst) = 67 cycles vs 112 cycles previously.
  * Area: 1 x 64-b staging register instead of 16 x 64-b wordBuf (~960 fewer FFs).
  */
class BorgTileFlusher(val dataBits: Int = 16) extends Module {
  val io = IO(new BorgTileFlusherIO(dataBits))

  val sIdle :: sPrime1 :: sPrime2 :: sPrime3 :: sBurst :: Nil = Enum(5)
  val state = RegInit(sIdle)

  // 1-entry staging buffer: 64 FFs replacing the 1024-FF 16-entry wordBuf.
  val entryReg   = Reg(UInt(64.W))
  val baseReg    = RegInit(0.U(25.W))
  val fillIdx    = RegInit(0.U(4.W))  // entry currently being streamed (0..15)
  val nextFill   = RegInit(0.U(4.W))  // next entry to pre-fetch (starts at 1)
  val wordSub    = RegInit(0.U(2.W))  // word within current entry: 0=R 1=G 2=B 3=Z

  // Registered read-port outputs: set one cycle early so arcilator reads them
  // from the state array (always up-to-date), avoiding comb ordering issues.
  val readEnReg  = RegInit(false.B)
  val readIdxReg = RegInit(0.U(4.W))

  // Default outputs
  io.busy := (state =/= sIdle) || io.start

  io.read.en  := readEnReg
  io.read.idx := readIdxReg

  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U
  io.gpuMem.wlen  := 1.U

  // Auto-clear the read-enable register each cycle; overridden below when needed.
  readEnReg := false.B

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
        baseReg    := io.tileBase
        fillIdx    := 0.U
        nextFill   := 1.U
        wordSub    := 0.U
        // Stage the read for entry 0; io.read.en goes high next cycle (sPrime1).
        readEnReg  := true.B
        readIdxReg := 0.U
        state      := sPrime1
      }
    }

    is(sPrime1) {
      // io.read.en=1 visible this cycle; SyncReadMem latches address.
      state := sPrime2
    }

    is(sPrime2) {
      // SyncReadMem output travels through readDataHeld (cycle 2 of 2).
      state := sPrime3
    }

    is(sPrime3) {
      // Entry 0 data valid on io.read.data; capture and begin burst.
      entryReg := io.read.data.asUInt
      state    := sBurst
    }

    // Stream all 64 words (16 entries x 4 words each).
    // Pre-fetch at wordSub==0 (register set) so io.read.en appears at wordSub==1,
    // overlapping the 2-cycle TileBuffer latency so the next entry is in entryReg
    // by wordSub==3.
    is(sBurst) {
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := baseReg
      io.gpuMem.wdata := curWord
      io.gpuMem.wlen  := 64.U

      when(io.gpuMem.waccept) {
        wordSub := wordSub + 1.U

        // Stage pre-fetch at word 0 (R): io.read.en appears at word 1 (G),
        // delivering data at word 3 (Z) with 2-cycle TileBuffer latency.
        when(wordSub === 0.U && nextFill < 16.U) {
          readEnReg  := true.B
          readIdxReg := nextFill
          nextFill   := nextFill + 1.U
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
