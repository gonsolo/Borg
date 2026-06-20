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

  // DRAM write port
  val gpuMem    = new GpuMemIO

  // Tile base address: absolute DRAM byte address of this tile's region.
  // Layout (RGB565): 16 entries × 2 bytes = 32 bytes per tile.
  //   word[i] = RGB565(entry[i])   (R[15:11] | G[10:5] | B[4:0])
  // Z is not written — depth lives only in the on-chip tile buffer (TBR renders
  // each tile fully on-chip, so DRAM never needs the depth value).
  // Firmware computes: tileBase = fbBase + tile_index * 32
  //   where tile_index = (ty >> 2) * tiles_per_row + (tx >> 2)
  val tileBase  = Input(UInt(25.W))
}

/** BorgTileFlusher -- bulk DMA from tile SRAM to DRAM, one burst per tile.
  *
  * Streams all 16 tile-buffer entries to SDRAM as ONE 16-word RGB565 burst.
  * Each pixel becomes a single 16-bit word (R5|G6|B5); depth is dropped (the
  * TBR renders each tile fully on-chip, so DRAM never needs the Z value).
  * This halves the flush bandwidth vs the previous 64-word FP16 R/G/B/Z burst.
  *
  * Two phases:
  *   sFill  -- read all 16 tile entries (pipelined), convert FP16->RGB565,
  *             stash into rgbVec.  The 2-cycle TileBuffer read latency is hidden
  *             by issuing one read per cycle and capturing 3 cycles later.
  *   sBurst -- stream the 16 RGB565 words from rgbVec as one burst.  rgbVec is
  *             a plain register read (no latency), so the word for the next beat
  *             is ready the cycle after `waccept` -- exactly when the controller
  *             samples it.  No burst-time read race.
  *
  * io.read.en/idx are REGISTERED outputs (set one cycle before they appear on
  * the wire) so arcilator can evaluate them from the state array without
  * circular combinational dependencies through the tile instance.
  *
  * Fill pipeline (mirrors the 3-cycle issue->data latency):
  *   cycle N:   readEnReg := true (register set; wire goes high next cycle)
  *   cycle N+1: io.read.en=1 visible; SyncReadMem latches address
  *   cycle N+2: SyncReadMem output travels through readDataHeld (cycle 2 of 2)
  *   cycle N+3: io.read.data valid; capture RGB565 into rgbVec
  */
class BorgTileFlusher(val dataBits: Int = 16) extends Module {
  val io = IO(new BorgTileFlusherIO(dataBits))

  val sIdle :: sFill :: sBurst :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // 16 RGB565 pixels staged before the burst (256 FFs).
  val rgbVec   = Reg(Vec(16, UInt(16.W)))
  val baseReg  = RegInit(0.U(25.W))
  val issueIdx = RegInit(0.U(5.W))  // next entry to issue a read for (0..16)
  val capIdx   = RegInit(0.U(5.W))  // next entry to capture into rgbVec (0..16)
  val burstIdx = RegInit(0.U(5.W))  // entry currently being streamed (0..15)

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

  // FP16 [0,1] -> unsigned N-bit channel (top N bits of an 8-bit conversion).
  // FP16: 1 sign + 5 exponent (bias=15) + 10 mantissa.  Clamp negatives to 0,
  // >=1.0 to all-ones; matches the scanout's old fp16ToRgb8 mapping.
  def fp16ToUnorm(fp16: UInt, bits: Int): UInt = {
    val sign = fp16(15)
    val exp  = fp16(14, 10)
    val mant = fp16(9, 0)
    val full = Cat(1.U(1.W), mant)  // 11-bit: 1.mantissa
    val rgb8 = Wire(UInt(8.W))
    when(sign || exp < 7.U) {
      rgb8 := 0.U
    }.elsewhen(exp >= 15.U) {
      rgb8 := 255.U
    }.otherwise {
      rgb8 := MuxLookup(exp, 0.U(8.W))(Seq(
        14.U -> full(10, 3),
        13.U -> Cat(0.U(1.W), full(10, 4)),
        12.U -> Cat(0.U(2.W), full(10, 5)),
        11.U -> Cat(0.U(3.W), full(10, 6)),
        10.U -> Cat(0.U(4.W), full(10, 7)),
         9.U -> Cat(0.U(5.W), full(10, 8)),
         8.U -> Cat(0.U(6.W), full(10, 9)),
         7.U -> Cat(0.U(7.W), full(10))
      ))
    }
    rgb8(7, 8 - bits)
  }

  // ColorZ.asUInt packs first field in MSBs: {r[63:48], g[47:32], b[31:16], z[15:0]}.
  def toRgb565(entry: UInt): UInt = {
    val r5 = fp16ToUnorm(entry(63, 48), 5)
    val g6 = fp16ToUnorm(entry(47, 32), 6)
    val b5 = fp16ToUnorm(entry(31, 16), 5)
    Cat(r5, g6, b5)
  }

  // Fill-pipeline valid tracking: a read issued this cycle yields data 3 cycles
  // later.  issueValid marks the issue; v3 marks the matching data-valid cycle.
  val issueValid = WireDefault(false.B)
  val v1 = RegNext(issueValid, false.B)
  val v2 = RegNext(v1, false.B)
  val v3 = RegNext(v2, false.B)
  when(v3) {
    rgbVec(capIdx(3, 0)) := toRgb565(io.read.data.asUInt)
    capIdx := capIdx + 1.U
  }

  switch(state) {

    is(sIdle) {
      when(io.start) {
        baseReg  := io.tileBase
        issueIdx := 0.U
        capIdx   := 0.U
        burstIdx := 0.U
        state    := sFill
      }
    }

    // Issue one read per cycle for entries 0..15; captures land via v3 above.
    // Advance to the burst once all 16 entries are captured.
    is(sFill) {
      when(issueIdx < 16.U) {
        readEnReg  := true.B
        readIdxReg := issueIdx(3, 0)
        issueValid := true.B
        issueIdx   := issueIdx + 1.U
      }
      when(capIdx === 16.U) {
        state := sBurst
      }
    }

    // Stream all 16 RGB565 words as one burst.  wdata is a register read of
    // rgbVec(burstIdx): when waccept advances burstIdx, the next word is ready
    // the following cycle, exactly when the controller samples it.
    is(sBurst) {
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := baseReg
      io.gpuMem.wdata := rgbVec(burstIdx(3, 0))
      io.gpuMem.wlen  := 16.U

      when(io.gpuMem.waccept) {
        if (BorgDebug.trace) printf("[FLUSH] entry=%d RGB565=0x%x\n",
          burstIdx, rgbVec(burstIdx(3, 0)))
        burstIdx := burstIdx + 1.U
      }

      when(io.gpuMem.ready) {
        state := sIdle
      }
    }
  }
}
