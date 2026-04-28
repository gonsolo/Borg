// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Tile buffer read-port bundle — the flusher's view of the tile buffer.
  *
  * Matches the read-side signals of [[BorgTileBufferIO]].  Directions are
  * from the master's perspective: idx and en are outputs that the master
  * drives, data is the response from the tile buffer.
  *
  * Symmetric with [[TileWriteIO]].
  */
class BorgTileFlusherIO(val dataBits: Int = 16) extends Bundle {
  // Trigger interface
  val start     = Input(Bool())                   // one-cycle pulse to begin flush
  val busy      = Output(Bool())                  // high while flushing

  // Tile buffer read port
  val read      = new TileReadIO(dataBits)

  // PSRAM write port (shared with sTexFetch and DMA)
  val gpuMem    = new GpuMemIO

  // Configuration (from MMIO registers)
  val fbBase    = Input(UInt(20.W))               // framebuffer PSRAM base address
  val zbBase    = Input(UInt(20.W))               // Z-buffer PSRAM base address
  val fbWidth   = Input(UInt(9.W))                // framebuffer width (for address calc)
  val tileX     = Input(UInt(9.W))                // tile origin X
  val tileY     = Input(UInt(9.W))                // tile origin Y
}

/** BorgTileFlusher — hardware tile flush scaffold (Step 25.3g).
  *
  * Initial implementation: empty FSM (`sIdle → sBusy → sIdle`) with no
  * actual PSRAM writes.  Proves the module instantiates, wires correctly,
  * and the busy flag handshakes.  Step 25.4a will add the first pixel write.
  *
  * The flusher shares the [[GpuMemIO]] port with `BorgTextureUnit` (via the
  * dispatcher) and `BorgDMA`.  In practice there is no contention: the
  * flusher runs after the tile is complete, texture fetch runs during
  * per-pixel rasterization, and DMA runs between triangles.
  */
class BorgTileFlusher(val dataBits: Int = 16) extends Module {
  val io = IO(new BorgTileFlusherIO(dataBits))

  // --- FSM ---
  val sIdle :: sBusy :: Nil = Enum(2)
  val state = RegInit(sIdle)

  // --- Defaults: all outputs idle ---
  io.busy := false.B

  io.read.idx := 0.U
  io.read.en  := false.B

  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U

  switch(state) {
    is(sIdle) {
      when(io.start) {
        state := sBusy
      }
    }

    is(sBusy) {
      io.busy := true.B
      // Scaffold: immediately return to idle.
      // Step 25.4a will add actual tile buffer reads + PSRAM writes here.
      state := sIdle
    }
  }
}
