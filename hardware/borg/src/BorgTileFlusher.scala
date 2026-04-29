// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** IO bundle for [[BorgTileFlusher]].
  *
  * Directions are from the master's (flusher's) perspective.
  */
class BorgTileFlusherIO(val dataBits: Int = 16) extends Bundle {
  // Trigger interface
  val start     = Input(Bool())    // one-cycle pulse to begin flush
  val busy      = Output(Bool())   // high while flushing

  // Tile buffer read port (master perspective: flusher drives idx/en, reads data)
  val read      = new TileReadIO(dataBits)

  // PSRAM write port (shared with sTexFetch and DMA via arbiter in Borg.scala)
  val gpuMem    = new GpuMemIO

  // Configuration (latched at start; written by firmware once at startup)
  //   fbBase / zbBase : absolute PSRAM byte addresses (including PSRAM_OUT_OFFSET)
  //   fbWidthLog2     : log2(framebuffer width in pixels), e.g. 5 for 32-wide
  //   tileX / tileY   : tile origin in pixel coordinates
  val fbBase       = Input(UInt(20.W))
  val zbBase       = Input(UInt(20.W))
  val fbWidthLog2  = Input(UInt(4.W))  // replaces raw fbWidth — avoids multiplier
  val tileX        = Input(UInt(9.W))
  val tileY        = Input(UInt(9.W))
}

/** BorgTileFlusher — hardware tile flush, pixel 0 (Step 25.4.1).
  *
  * Flushes tile pixel index 0 (the top-left pixel of the 4×4 tile) from the
  * tile buffer to PSRAM.  Pixels 1–15 are still flushed by the CPU tile-write
  * loop in the firmware (removed in Step 25.4.2).
  *
  * === Depth test ===
  * Before writing, the flusher reads the existing Z value from PSRAM and
  * compares it with the new Z from the tile buffer.  The write is skipped
  * if the new Z is not closer (new_z >= old_z), matching the CPU-side depth
  * test in `shade_and_write_pixel`.  FP16 Z values are unsigned-compared
  * since negative-Z fragments are already rejected by the shader.
  *
  * === PSRAM write protocol ===
  * - Assert `gpuMem.wr=1` with stable `addr`/`wdata`; hold until `ready` pulses.
  * - On `ready`: update `addr`/`wdata` for next channel (keep `wr=1`).
  * - This mirrors the [[BorgTextureUnit]] read pattern (hold req, advance on ready).
  *
  * === BRAM read timing ===
  * - Cycle 0 (sReadTile):   `read.en=1`, `read.idx` registered by SyncReadMem.
  * - Cycle 1 (sWaitBram):   BRAM output clocks through; readEnDel latches data.
  * - Cycle 2 (sLatchData):  `read.data` (readDataHeld) is stable and valid.
  *
  * === Address arithmetic ===
  * All PSRAM addresses are byte addresses (same unit as [[GpuMemIO.addr]]).
  * {{{
  *   pixel_off = (tileY << fbWidthLog2) + tileX       // pixel index within FB
  *   fb_addr   = fbBase + pixel_off * 12              // 3 channels × 4 bytes each
  *   zb_addr   = zbBase + pixel_off * 4               // 1 Z value × 4 bytes
  *   R at fb_addr+0, G at fb_addr+4, B at fb_addr+8
  *   Z at zb_addr
  * }}}
  * Multiplication by 12 = (<<3) + (<<2); by 4 = (<<2) — no DSP inference.
  *
  * === Skip condition ===
  * Pixels with `z >= FP16_MAX_DEPTH` (0x7BFF) were not shaded or are outside the
  * triangle.  The flusher skips PSRAM writes for these pixels (goes directly to sIdle).
  */
class BorgTileFlusher(val dataBits: Int = 16) extends Module {
  val io = IO(new BorgTileFlusherIO(dataBits))

  val FP16_MAX_DEPTH = 0x7BFF.U(16.W)

  // --- FSM ---
  val sIdle :: sReadTile :: sWaitBram :: sLatchData :: sReadOldZ :: sWriteR :: sWriteG :: sWriteB :: sWriteZ :: Nil = Enum(9)
  val state = RegInit(sIdle)

  // --- Latched config (captured at start) ---
  val fbBase_reg      = RegInit(0.U(20.W))
  val zbBase_reg      = RegInit(0.U(20.W))
  val fbWidthLog2_reg = RegInit(0.U(4.W))
  val tileX_reg       = RegInit(0.U(9.W))
  val tileY_reg       = RegInit(0.U(9.W))

  // --- Pixel RGBZ registers (latched from tile buffer) ---
  val r_reg = RegInit(0.U(16.W))
  val g_reg = RegInit(0.U(16.W))
  val b_reg = RegInit(0.U(16.W))
  val z_reg = RegInit(0.U(16.W))

  // --- Computed byte addresses (registered in sLatchData) ---
  val fb_addr_reg = RegInit(0.U(20.W))
  val zb_addr_reg = RegInit(0.U(20.W))

  // --- Defaults: all outputs quiet ---
  io.busy := (state =/= sIdle)

  io.read.idx := 0.U
  io.read.en  := false.B

  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U

  // --- FSM ---
  switch(state) {

    is(sIdle) {
      when(io.start) {
        // Latch all config; Step 25.4.1 always flushes pixel index 0
        fbBase_reg      := io.fbBase
        zbBase_reg      := io.zbBase
        fbWidthLog2_reg := io.fbWidthLog2
        tileX_reg       := io.tileX
        tileY_reg       := io.tileY
        state           := sReadTile
      }
    }

    // --- Tile buffer read (pixel index 0) ---
    is(sReadTile) {
      io.read.en  := true.B
      io.read.idx := 0.U   // pixel 0: top-left of tile
      state       := sWaitBram
    }

    // One pipeline bubble for SyncReadMem + readDataHeld latch
    is(sWaitBram) {
      state := sLatchData
    }

    // read.data is stable — latch RGBZ and compute byte addresses
    is(sLatchData) {
      r_reg := io.read.data.r
      g_reg := io.read.data.g
      b_reg := io.read.data.b
      z_reg := io.read.data.z

      // pixel_off = (tileY << fbWidthLog2) + tileX
      // Use a 18-bit Wire so Chisel knows the result width before the shift,
      // preventing Verilator from seeing unused upper bits in the shift intermediate.
      // Max: tileY(511) << 8 + tileX(511) = 131327 → 17 bits needed; 18 is safe.
      val pixel_off = Wire(UInt(18.W))
      pixel_off := (tileY_reg(8, 0) << fbWidthLog2_reg)(17, 0) + tileX_reg

      // FB byte address: fbBase + pixel_off * 12
      //   * 12 = (pixel_off << 3) + (pixel_off << 2), no multiplier
      val fb_pixel_off_bytes = (pixel_off << 3) + (pixel_off << 2)
      fb_addr_reg := fbBase_reg + fb_pixel_off_bytes

      // ZB byte address: zbBase + pixel_off * 4
      zb_addr_reg := zbBase_reg + (pixel_off << 2)

      // Skip to idle if pixel was never shaded
      when(io.read.data.z >= FP16_MAX_DEPTH) {
        state := sIdle
      } .otherwise {
        state := sReadOldZ
      }
    }

    // --- Depth test: read existing Z from PSRAM, compare with new Z ---
    // FP16 Z values are unsigned-comparable for positive depths (negative-Z
    // fragments were already rejected by the shader's fp16_ge_zero check).
    is(sReadOldZ) {
      io.gpuMem.req  := true.B
      io.gpuMem.addr := zb_addr_reg
      when(io.gpuMem.ready) {
        // old_z is in the low 16 bits of the 32-bit PSRAM word
        val old_z = io.gpuMem.data(15, 0)
        when(z_reg >= old_z) {
          // Depth test failed: new pixel is not closer — skip writes
          state := sIdle
        } .otherwise {
          // Depth test passed: new pixel is closer — proceed to write
          state := sWriteR
        }
      }
    }

    // --- Four sequential PSRAM writes (hold wr=1, advance on ready) ---
    is(sWriteR) {
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := fb_addr_reg
      io.gpuMem.wdata := Cat(0.U(16.W), r_reg)
      when(io.gpuMem.ready) {
        state := sWriteG
      }
    }

    is(sWriteG) {
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := fb_addr_reg + 4.U
      io.gpuMem.wdata := Cat(0.U(16.W), g_reg)
      when(io.gpuMem.ready) {
        state := sWriteB
      }
    }

    is(sWriteB) {
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := fb_addr_reg + 8.U
      io.gpuMem.wdata := Cat(0.U(16.W), b_reg)
      when(io.gpuMem.ready) {
        state := sWriteZ
      }
    }

    is(sWriteZ) {
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := zb_addr_reg
      io.gpuMem.wdata := Cat(0.U(16.W), z_reg)
      when(io.gpuMem.ready) {
        state := sIdle
      }
    }
  }
}
