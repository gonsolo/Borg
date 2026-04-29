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
  val tileBase  = Input(UInt(20.W))
}

/** BorgTileFlusher — bulk DMA from tile SRAM to PSRAM (Step 25.4.2 Option A).
  *
  * Replaces the 10-state pixel-by-pixel scatter FSM with a 5-state sequential
  * DMA engine:
  *
  * {{{
  *   sIdle      → sReadSram (latch tileBase, word_idx=0)
  *   sReadSram  → sWaitSram (assert read.en, read.idx = word_idx >> 1)
  *   sWaitSram  → sWriteLo  (SyncReadMem pipeline bubble; data arrives next cycle)
  *   sWriteLo   → sWriteHi  (write entry bits[31:0]; wait for gpuMem.ready)
  *   sWriteHi   → sReadSram (write entry bits[63:32]; wait for gpuMem.ready;
  *                            word_idx += 2; if word_idx == 32 → sIdle)
  * }}}
  *
  * Each tile SRAM entry is 64 bits: {R:16, G:16, B:16, Z:16} packed by Chisel.
  * Two PSRAM writes per entry → 32 writes total per tile.
  *
  * No depth test, no per-pixel address arithmetic.  The tile SRAM is pre-loaded
  * with old data (DMA-in) and the depth test is performed by the dispatcher
  * during rasterization.
  */
class BorgTileFlusher(val dataBits: Int = 16) extends Module {
  val io = IO(new BorgTileFlusherIO(dataBits))

  // FSM
  // BorgTileBuffer read latency: 2 cycles after read.en:
  //   Cycle 0 (sReadSram): assert read.en → SyncReadMem latches idx
  //   Cycle 1 (sWaitSram):  SyncReadMem output available (rgbzRead); readEnDel fires
  //   Cycle 2 (sWaitSram2): readDataHeld latches rgbzRead → io.read.data valid
  //   Cycle 3 (sLatchData): capture io.read.data into entry_lo / entry_hi
  val sIdle :: sReadSram :: sWaitSram :: sWaitSram2 :: sLatchData :: sWriteLo :: sWriteHi :: Nil = Enum(7)
  val state = RegInit(0.U(3.W))   // 0 = sIdle

  // DMA state
  val tileBase_reg = RegInit(0.U(20.W))
  val word_idx     = RegInit(0.U(6.W))   // 0..31, indexes PSRAM words (2 per SRAM entry)

  // Latched SRAM output (captured in sLatchData)
  // Chisel Bundle.asUInt: first field = MSB → ColorZ(r,g,b,z) = {r,g,b,z}
  val entry_lo = RegInit(0.U(32.W))  // bits[31:0]  = {b, z}
  val entry_hi = RegInit(0.U(32.W))  // bits[63:32] = {r, g}

  // Default outputs
  io.busy := (state =/= sIdle)

  io.read.idx := 0.U
  io.read.en  := false.B

  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U

  switch(state) {

    is(sIdle) {
      when(io.start) {
        tileBase_reg := io.tileBase
        word_idx     := 0.U
        state        := sReadSram
      }
    }

    // Cycle 0: assert read.en; tile SRAM latches idx on rising edge
    is(sReadSram) {
      io.read.en  := true.B
      io.read.idx := word_idx >> 1.U
      state       := sWaitSram
    }

    // Cycle 1: SyncReadMem output (rgbzRead) available; readEnDel fires
    is(sWaitSram) {
      state := sWaitSram2
    }

    // Cycle 2: readDataHeld captures rgbzRead → io.read.data now valid
    is(sWaitSram2) {
      state := sLatchData
    }

    // Cycle 3: capture io.read.data
    is(sLatchData) {
      val packed = io.read.data.asUInt   // 64 bits: {r[63:48],g[47:32],b[31:16],z[15:0]}
      entry_lo := packed(31,  0)         // {b, z}
      entry_hi := packed(63, 32)         // {r, g}
      state    := sWriteLo
    }

    // Write low 32 bits (R|G) at tileBase + word_idx * 4
    is(sWriteLo) {
      io.gpuMem.req   := true.B
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := tileBase_reg + (word_idx << 2)
      io.gpuMem.wdata := entry_lo
      when(io.gpuMem.ready) {
        word_idx := word_idx + 1.U
        state    := sWriteHi
      }
    }

    // Write high 32 bits (B|Z) at tileBase + word_idx * 4
    is(sWriteHi) {
      io.gpuMem.req   := true.B
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := tileBase_reg + (word_idx << 2)
      io.gpuMem.wdata := entry_hi
      when(io.gpuMem.ready) {
        val next_word = word_idx + 1.U
        word_idx := next_word
        when(next_word === 32.U) {
          state := sIdle
        } .otherwise {
          state := sReadSram
        }
      }
    }
  }
}
