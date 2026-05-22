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
  //   entryHeld latches the data locally so a concurrent dispatcher read can't corrupt it.
  val sIdle :: sReadSram :: sWaitSram :: sWaitSram2 :: sWriteR :: sWriteG :: sWriteB :: sWriteZ :: Nil = Enum(8)
  val state = RegInit(0.U(3.W))   // 0 = sIdle

  // DMA state
  // addrReg: running PSRAM byte address, starts at io.tileBase and advances +2
  //          per write (each write = 1 SDRAM word = 2 bytes).
  //          4 writes per pixel × 16 pixels = 64 writes total.
  val addrReg  = RegInit(0.U(25.W))
  val word_idx = RegInit(0.U(5.W))   // 0..15: pixel index, terminates at 16
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
      io.read.idx := word_idx
      if (BorgDebug.trace) printf("[FLUSH] readSram slot=%d\n", word_idx)
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
      if (BorgDebug.trace) printf("[FLUSH] dataHeld slot R=0x%x G=0x%x B=0x%x Z=0x%x\n",
        io.read.data.r, io.read.data.g, io.read.data.b, io.read.data.z)
      state := sWriteR
    }

    // MemoryController writes 16 bits (1 SDRAM word) per GPU write transaction
    // (data_txn_len=1).  Only wdata[15:0] reaches SDRAM.
    // We write 4 channels × 16 bits each at stride +2 per channel.
    //
    // ColorZ.asUInt packing (Chisel: first declared field = MSBs):
    //   entryHeld = { r[63:48], g[47:32], b[31:16], z[15:0] }
    //
    // SDRAM layout per pixel (byte address):
    //   base + 0: r[15:0]
    //   base + 2: g[15:0]
    //   base + 4: b[15:0]
    //   base + 6: z[15:0]

    // Write R channel
    is(sWriteR) {
      io.gpuMem.req   := true.B
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := addrReg
      io.gpuMem.wdata := entryHeld(63, 48)   // r
      when(io.gpuMem.ready) {
        addrReg := addrReg + 2.U
        state   := sWriteG
      }
    }

    // Write G channel
    is(sWriteG) {
      io.gpuMem.req   := true.B
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := addrReg
      io.gpuMem.wdata := entryHeld(47, 32)   // g
      when(io.gpuMem.ready) {
        addrReg := addrReg + 2.U
        state   := sWriteB
      }
    }

    // Write B channel
    is(sWriteB) {
      io.gpuMem.req   := true.B
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := addrReg
      io.gpuMem.wdata := entryHeld(31, 16)   // b
      when(io.gpuMem.ready) {
        addrReg := addrReg + 2.U
        state   := sWriteZ
      }
    }

    // Write Z channel
    is(sWriteZ) {
      io.gpuMem.req   := true.B
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := addrReg
      io.gpuMem.wdata := entryHeld(15, 0)    // z
      when(io.gpuMem.ready) {
        addrReg := addrReg + 2.U
        val next_word = word_idx + 1.U
        word_idx := next_word
        when(next_word === 16.U) {   // 16 pixels done
          state := sIdle
        } .otherwise {
          state := sReadSram
        }
      }
    }
  }
}
