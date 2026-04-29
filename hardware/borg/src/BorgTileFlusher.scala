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

/** BorgTileFlusher — autonomous hardware tile flush for all 16 pixels (Step 25.4.2).
  *
  * Iterates over all 16 pixels (4×4 tile) and flushes each from the tile buffer
  * to PSRAM via a hardware FSM.  The CPU tile-write loop is fully removed.
  *
  * === Pixel loop ===
  * The 4-bit `pixel_idx` counter encodes `{row[1:0], col[1:0]}`:
  * {{{
  *   col   = pixel_idx(1, 0)    abs_x = tileX + col
  *   row   = pixel_idx(3, 2)    abs_y = tileY + row
  * }}}
  * After the last write of each pixel, `sNextPixel` increments `pixel_idx`
  * and loops back to `sReadTile`, or returns to `sIdle` after pixel 15.
  *
  * === Depth test ===
  * Before writing, the flusher reads the existing Z value from PSRAM and
  * compares it with the new Z from the tile buffer.  The write is skipped
  * if the new Z is not closer (new_z >= old_z), matching the CPU-side depth
  * test in `shade_and_write_pixel`.  FP16 Z values are unsigned-compared
  * since negative-Z fragments are already rejected by the shader.
  *
  * === Skip condition ===
  * Pixels with `z >= FP16_MAX_DEPTH` (0x7BFF) were not shaded or are outside
  * the triangle.  The flusher skips PSRAM writes for these pixels and advances
  * directly to `sNextPixel`.
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
  *   pixel_off = (abs_y << fbWidthLog2) + abs_x       // pixel index within FB
  *   fb_addr   = fbBase + pixel_off * 12              // 3 channels × 4 bytes each
  *   zb_addr   = zbBase + pixel_off * 4               // 1 Z value × 4 bytes
  *   R at fb_addr+0, G at fb_addr+4, B at fb_addr+8
  *   Z at zb_addr
  * }}}
  * Multiplication by 12 = (<<3) + (<<2); by 4 = (<<2) — no DSP inference.
  */
class BorgTileFlusher(val dataBits: Int = 16) extends Module {
  val io = IO(new BorgTileFlusherIO(dataBits))

  val FP16_MAX_DEPTH = 0x7BFF.U(16.W)

  // --- FSM (10 states) ---
  val sIdle :: sReadTile :: sWaitBram :: sLatchData :: sReadOldZ :: sWriteR :: sWriteG :: sWriteB :: sWriteZ :: sNextPixel :: Nil = Enum(10)
  val state = RegInit(sIdle)

  // --- Latched config (captured at start) ---
  val fbBase_reg      = RegInit(0.U(20.W))
  val zbBase_reg      = RegInit(0.U(20.W))
  val fbWidthLog2_reg = RegInit(0.U(4.W))
  val tileX_reg       = RegInit(0.U(9.W))
  val tileY_reg       = RegInit(0.U(9.W))

  // --- Pixel loop counter ---
  val pixel_idx = RegInit(0.U(4.W))

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
        fbBase_reg      := io.fbBase
        zbBase_reg      := io.zbBase
        fbWidthLog2_reg := io.fbWidthLog2
        tileX_reg       := io.tileX
        tileY_reg       := io.tileY
        pixel_idx       := 0.U
        state           := sReadTile
      }
    }

    // --- Tile buffer read (current pixel_idx) ---
    is(sReadTile) {
      io.read.en  := true.B
      io.read.idx := pixel_idx
      state       := sWaitBram
    }

    // One pipeline bubble for SyncReadMem + readDataHeld latch
    is(sWaitBram) {
      state := sLatchData
    }

    // read.data is stable — latch RGBZ and compute byte addresses for current pixel
    is(sLatchData) {
      r_reg := io.read.data.r
      g_reg := io.read.data.g
      b_reg := io.read.data.b
      z_reg := io.read.data.z

      // abs_x = tileX + pixel_idx[1:0]  (col within 4×4 tile)
      // abs_y = tileY + pixel_idx[3:2]  (row within 4×4 tile)
      val abs_x = Wire(UInt(11.W))
      val abs_y = Wire(UInt(11.W))
      abs_x := tileX_reg + pixel_idx(1, 0)
      abs_y := tileY_reg + pixel_idx(3, 2)

      // pixel_off = (abs_y << fbWidthLog2) + abs_x
      // Max: abs_y(511) << 8 + abs_x(511) = 131327 → 17 bits needed; 18 is safe.
      val pixel_off = Wire(UInt(18.W))
      pixel_off := (abs_y(8, 0) << fbWidthLog2_reg)(17, 0) + abs_x

      // FB byte address: fbBase + pixel_off * 12
      //   * 12 = (pixel_off << 3) + (pixel_off << 2), no multiplier
      val fb_pixel_off_bytes = (pixel_off << 3) + (pixel_off << 2)
      fb_addr_reg := fbBase_reg + fb_pixel_off_bytes

      // ZB byte address: zbBase + pixel_off * 4
      zb_addr_reg := zbBase_reg + (pixel_off << 2)

      // Skip to next pixel if this pixel was never shaded
      when(io.read.data.z >= FP16_MAX_DEPTH) {
        state := sNextPixel
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
          // Depth test failed: new pixel is not closer — advance to next pixel
          state := sNextPixel
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
        state := sNextPixel
      }
    }

    // --- Advance to next pixel or return to idle after pixel 15 ---
    is(sNextPixel) {
      when(pixel_idx === 15.U) {
        state := sIdle
      } .otherwise {
        pixel_idx := pixel_idx + 1.U
        state     := sReadTile
      }
    }
  }
}
