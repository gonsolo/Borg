// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._

class MigScanoutIO extends Bundle {
  val mig      = new MigUi
  val pixClock = Input(Clock())
  val pixReset = Input(Bool())
  val tick     = Output(Bool())
  val tmds     = Output(Vec(4, UInt(10.W)))
  val de       = Output(Bool())
  val rgb      = Output(UInt(24.W))
}

class MigUiArbiterIO extends Bundle {
  val a   = Flipped(new MigUi)
  val b   = Flipped(new MigUi)
  val mig = new MigUi
}

/** 640x480 HDMI from a picture in the MIG's DDR3. Each frame starts with the 16-byte descriptor at
  * `descAddr`: base, pitch (bytes, both multiples of 16), width | height << 16, format (0 off,
  * 1 BGRA8, 2 RGBA8). The picture is centred; a line is fetched into a two-line buffer during the
  * line before it is shown. The MIG side runs in this module's clock, the pixels in `pixClock`
  * (125 MHz, a pixel every fifth cycle, as [[HdmiColourBars]]).
  */
class MigScanout(descAddr: Long) extends Module {
  val io = IO(new MigScanoutIO)
  val m = io.mig
  m.app_wdf_data := 0.U
  m.app_wdf_mask := 0.U
  m.app_wdf_wren := false.B
  m.app_wdf_end  := false.B
  m.app_cmd      := 1.U

  val lineBuf = SyncReadMem(512, UInt(128.W))   // two lines of up to 160 16-byte words

  // Descriptor, held stable while the pixel side's toggle crosses.
  val base   = Reg(UInt(32.W))
  val pitch  = Reg(UInt(32.W))
  val width  = RegInit(0.U(10.W))
  val height = RegInit(0.U(9.W))
  val format = RegInit(0.U(2.W))
  val descT  = RegInit(false.B)
  val frameT = Wire(Bool())
  val lineT  = Wire(Bool())
  val row    = Wire(UInt(9.W))

  val sIdle :: sDesc :: sDescWait :: sLine :: Nil = Enum(4)
  val state    = RegInit(sIdle)
  val frameSeen = RegInit(false.B)
  val lineSeen  = RegInit(false.B)
  val frameS   = ShiftRegister(frameT, 2, false.B, true.B)
  val lineS    = ShiftRegister(lineT, 2, false.B, true.B)
  val issued   = Reg(UInt(8.W))
  val received = Reg(UInt(8.W))
  val words    = (width +& 3.U) >> 2
  val half     = Reg(Bool())
  val lineBase = Reg(UInt(32.W))

  val byteAddr = Mux(state === sLine, lineBase + Cat(issued, 0.U(4.W)), descAddr.U(32.W))
  m.app_addr := Cat(byteAddr(28, 4), 0.U(3.W))
  m.app_en   := (state === sDesc) ||
                (state === sLine && issued =/= words && issued - received < 16.U && m.init_calib_complete)

  switch(state) {
    is(sIdle) {
      when(frameS =/= frameSeen && m.init_calib_complete) { frameSeen := frameS; state := sDesc }
        .elsewhen(lineS =/= lineSeen) {
          lineSeen := lineS
          lineBase := base + row * pitch
          half     := row(0)
          issued   := 0.U
          received := 0.U
          state    := sLine
        }
    }
    is(sDesc) { when(m.app_rdy) { state := sDescWait } }
    is(sDescWait) {
      when(m.app_rd_data_valid) {
        val d  = m.app_rd_data
        val w  = d(79, 64)
        val h  = d(95, 80)
        val ok = d(3, 0) === 0.U && d(35, 32) === 0.U && w <= 640.U && h <= 480.U && d(127, 98) === 0.U
        base   := d(31, 0)
        pitch  := d(63, 32)
        width  := w
        height := h
        format := Mux(ok, d(97, 96), 0.U)
        descT  := !descT
        state  := sIdle
      }
    }
    is(sLine) {
      when(m.app_en && m.app_rdy) { issued := issued + 1.U }
      when(m.app_rd_data_valid) {
        lineBuf.write(Cat(half, received), m.app_rd_data)
        received := received + 1.U
        when(received + 1.U === words) { state := sIdle }
      }
    }
  }

  withClockAndReset(io.pixClock, io.pixReset) {
    val count = RegInit(0.U(3.W))
    val tick  = count === 4.U
    count := Mux(tick, 0.U, count + 1.U)
    val h = RegInit(0.U(10.W))
    val v = RegInit(0.U(10.W))
    when(tick) {
      h := Mux(h === 799.U, 0.U, h + 1.U)
      when(h === 799.U) { v := Mux(v === 524.U, 0.U, v + 1.U) }
    }
    val de    = h < 640.U && v < 480.U
    val hsync = h >= 656.U && h < 752.U
    val vsync = v >= 490.U && v < 492.U

    // The descriptor, taken when its toggle arrives (in the blanking after the frame request).
    val dS    = ShiftRegister(descT, 2, false.B, true.B)
    val dSeen = RegInit(false.B)
    val pw    = RegInit(0.U(10.W))
    val ph    = RegInit(0.U(9.W))
    val pf    = RegInit(0.U(2.W))
    when(dS =/= dSeen) { dSeen := dS; pw := width; ph := height; pf := format }
    val x0 = (640.U - pw) >> 1
    val y0 = (480.U - ph) >> 1

    val fT = RegInit(false.B)
    val lT = RegInit(false.B)
    val r  = Reg(UInt(9.W))
    frameT := fT
    lineT  := lT
    row    := r
    val next = Mux(v === 524.U, 0.U, v + 1.U)
    when(tick && h === 0.U) {
      when(v === 480.U) { fT := !fT }
      when(pf =/= 0.U && next >= y0 && next < y0 + ph) { r := next - y0; lT := !lT }
    }

    val x   = h - x0
    val y   = v - y0
    val win = pf =/= 0.U && de && h >= x0 && h < x0 + pw && v >= y0 && v < y0 + ph
    val word = lineBuf.read(Cat(y(0), x(9, 2)), true.B, io.pixClock)
    val px   = (word >> Cat(RegNext(x(1, 0)), 0.U(5.W)))(31, 0)
    val on   = RegNext(win, false.B)
    val bgra = RegNext(pf === 1.U)
    val red   = Mux(on, Mux(bgra, px(23, 16), px(7, 0)), 0.U)
    val green = Mux(on, px(15, 8), 0.U)
    val blue  = Mux(on, Mux(bgra, px(7, 0), px(23, 16)), 0.U)

    val enc = Seq((blue, Cat(vsync, hsync)), (green, 0.U), (red, 0.U)).map { case (d, c) =>
      val e = Module(new TmdsEncoder)
      e.io.en   := tick
      e.io.data := d
      e.io.c    := c
      e.io.de   := de
      e.io.tmds
    }
    io.tick := tick
    io.tmds := VecInit(enc :+ "b0000011111".U(10.W))
    io.de   := de
    io.rgb  := Cat(red, green, blue)
  }
}

/** Two masters on one MIG: `b` (reads only, first) and `a`. Reads return in order, so a queue of
  * owners routes the data back.
  */
class MigUiArbiter extends Module {
  val io = IO(new MigUiArbiterIO)
  val m     = io.mig
  val useB  = io.b.app_en
  val owner = Module(new Queue(Bool(), 32))
  m.app_addr := Mux(useB, io.b.app_addr, io.a.app_addr)
  m.app_cmd  := Mux(useB, 1.U, io.a.app_cmd)
  m.app_en   := (useB || io.a.app_en) && owner.io.enq.ready
  m.app_wdf_data := io.a.app_wdf_data
  m.app_wdf_mask := io.a.app_wdf_mask
  m.app_wdf_wren := io.a.app_wdf_wren
  m.app_wdf_end  := io.a.app_wdf_end
  io.b.app_rdy     := m.app_rdy && owner.io.enq.ready
  io.a.app_rdy     := m.app_rdy && owner.io.enq.ready && !useB
  io.a.app_wdf_rdy := m.app_wdf_rdy
  io.b.app_wdf_rdy := false.B
  owner.io.enq.valid := m.app_en && m.app_rdy && m.app_cmd === 1.U
  owner.io.enq.bits  := useB
  owner.io.deq.ready := m.app_rd_data_valid
  for ((p, mine) <- Seq((io.a, false.B), (io.b, true.B))) {
    p.app_rd_data         := m.app_rd_data
    p.app_rd_data_valid   := m.app_rd_data_valid && owner.io.deq.bits === mine
    p.init_calib_complete := m.init_calib_complete
  }
}
