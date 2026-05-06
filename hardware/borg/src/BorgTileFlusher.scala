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
  * Replaces the 10-state pixel-by-pixel scatter FSM with a 6-state sequential
  * DMA engine:
  *
  * {{{
  *   sIdle      → sReadSram (latch tileBase, word_idx=0)
  *   sReadSram  → sWaitSram (assert read.en, read.idx = word_idx >> 1)
  *   sWaitSram  → sWaitSram2 (SyncReadMem pipeline bubble; data arrives next cycle)
  *   sWaitSram2 → sWriteLo  (readDataHeld stable; data valid on io.read.data)
  *   sWriteLo   → sWriteHi  (write entry bits[31:0]; wait for gpuMem.ready)
  *   sWriteHi   → sReadSram (write entry bits[63:32]; wait for gpuMem.ready;
  *                            word_idx += 2; if word_idx == 32 → sIdle)
  * }}}
  *
  * `io.read.data` is driven by BorgTileBuffer.readDataHeld, which holds its
  * value stable from the cycle after readEnDel fires until the next read.en.
  * No local entry_lo/entry_hi buffers are needed — saves ~64 LCs (Step 26.1).
  * No tileBase_reg + adder needed — running addrReg saves ~18 LCs (Step 26.2).
  *
  * Each tile SRAM entry is 64 bits: {R:16, G:16, B:16, Z:16} packed by Chisel.
  * Two PSRAM writes per entry → 32 writes total per tile.
  */
class BorgTileFlusher(val dataBits: Int = 16) extends Module {
  val io = IO(new BorgTileFlusherIO(dataBits))

  // FSM
  // BorgTileBuffer read latency: 2 cycles after read.en:
  //   Cycle 0 (sReadSram): assert read.en → SyncReadMem latches idx
  //   Cycle 1 (sWaitSram):  SyncReadMem output available (rgbzRead); readEnDel fires
  //   Cycle 2 (sWaitSram2): readDataHeld latches rgbzRead → io.read.data valid
  //   io.read.data stays stable until the next sReadSram triggers read.en again.
  //   No local entry_lo/entry_hi needed: directly slice io.read.data in sWriteLo/sWriteHi.
  val sIdle :: sReadSram :: sWaitSram :: sWaitSram2 :: sWriteLo :: sWriteHi :: Nil = Enum(6)
  val state = RegInit(0.U(3.W))   // 0 = sIdle

  // DMA state
  // addrReg: running PSRAM byte address, starts at io.tileBase and advances +4
  //          per write. Replaces tileBase_reg + (word_idx << 2) combinational
  //          adder — saves ~20 LCs (tileBase_reg FFs) + ~6 LCs (simpler adder).
  val addrReg  = RegInit(0.U(20.W))
  val word_idx = RegInit(0.U(6.W))   // 0..31: SRAM index (>> 1) + termination (== 32)
  // Local copy of tile buffer entry — immune to dispatcher read port corruption.
  val entryHeld = RegInit(0.U(64.W))

  // Default outputs
  io.busy := (state =/= sIdle) || io.start

  io.read.idx := 0.U
  io.read.en  := false.B

  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U

  switch(state) {

    is(sIdle) {
      when(io.start) {
        addrReg  := io.tileBase   // initialize running address directly
        word_idx := 0.U
        state    := sReadSram
      }
    }

    // Cycle 0: assert read.en; tile SRAM latches idx on rising edge
    is(sReadSram) {
      io.read.en  := true.B
      io.read.idx := word_idx >> 1.U
      printf("[FLUSH] readSram slot=%d wordIdx=%d\n", word_idx >> 1.U, word_idx)
      state       := sWaitSram
    }

    // Cycle 1: SyncReadMem output (rgbzRead) available; readEnDel fires
    is(sWaitSram) {
      state := sWaitSram2
    }

    // Cycle 2: readDataHeld captures rgbzRead → latch locally so
    // a concurrent dispatcher Z-read can't corrupt our data.
    is(sWaitSram2) {
      entryHeld := io.read.data.asUInt
      printf("[FLUSH] dataHeld slot R=0x%x G=0x%x B=0x%x Z=0x%x\n",
        io.read.data.r, io.read.data.g, io.read.data.b, io.read.data.z)
      state := sWriteLo
    }

    // Write low 32 bits ({b,z} = bits[31:0]) at current addrReg
    // entryHeld is our local copy — immune to dispatcher reads.
    is(sWriteLo) {
      io.gpuMem.req   := true.B
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := addrReg
      io.gpuMem.wdata := entryHeld(31, 0)   // {b, z}
      when(io.gpuMem.ready) {
        addrReg  := addrReg + 4.U
        word_idx := word_idx + 1.U
        state    := sWriteHi
      }
    }

    // Write high 32 bits ({r,g} = bits[63:32]) at current addrReg
    is(sWriteHi) {
      io.gpuMem.req   := true.B
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := addrReg
      io.gpuMem.wdata := entryHeld(63, 32)  // {r, g}
      when(io.gpuMem.ready) {
        addrReg  := addrReg + 4.U
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
