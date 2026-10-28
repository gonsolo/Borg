// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._

class TmdsEncoderIO extends Bundle {
  val en   = Input(Bool())
  val data = Input(UInt(8.W))
  val c    = Input(UInt(2.W)) // c1, c0
  val de   = Input(Bool())
  val tmds = Output(UInt(10.W))
}

class TmdsEncoder extends Module {
  val io = IO(new TmdsEncoderIO)

  // ── Stage 1: parallel q_m (breaks the 8-deep serial XOR chain) ───────────
  // Mathematical identity: q_m(i) = XOR(data[0..i]) XOR (use_xnor if i odd)
  // Proof: q_m(i) = q_m(i-1) XOR data(i) XOR use_xnor, unrolled gives a
  // prefix XOR where each use_xnor term cancels at even positions.
  // This reduces depth from O(n) serial to O(log n) prefix tree.
  val ones     = PopCount(io.data)
  val use_xnor = (ones > 4.U) || (ones === 4.U && io.data(0) === 0.U)

  val q_m = Wire(Vec(9, Bool()))
  for (i <- 0 to 7) {
    val prefixXor = (0 to i).map(j => io.data(j)).reduce(_ ^ _)
    val ux        = if (i % 2 == 1) use_xnor else false.B
    q_m(i) := prefixXor ^ ux
  }
  q_m(8) := !use_xnor

  // Pipeline register: cuts the path before the disparity/PopCount logic
  val q_m_reg = RegNext(q_m.asUInt)
  val de_reg  = RegNext(io.de,    false.B)
  val c_reg   = RegNext(io.c,     0.U)
  val en_reg  = RegNext(io.en,    false.B)

  // ── Stage 2a: PopCount/diff, registered ───────────────────────────────────
  // Once the frame buffer's own BRAM-read critical path was fixed (see
  // HdmiScanoutFp16's display() pipeline), THIS became the new critical
  // path on real hardware: PopCount + the disp-dependent Mux/add chain
  // below, all in one combinational hop from q_m_reg to the serializer's
  // shift register (measured ~10.4 ns against an 8 ns budget at 125 MHz).
  // Registering PopCount/diff here (one more pipeline stage, one more cycle
  // of overall latency) breaks that into two shorter hops. Correctness:
  // `disp` is a running total across pixels, updated once per pixel
  // (en_reg2 fires once per hdmiTick25) -- delaying WHEN in the pipeline a
  // pixel's diff becomes available doesn't change the ORDER pixels update
  // disp in, only adds one more cycle of constant latency before the first
  // pixel's result appears, so TMDS's running-disparity protocol still
  // sees the same sequence of decisions.
  val ones_q_m  = PopCount(q_m_reg(7, 0))
  val zeros_q_m = 8.U - ones_q_m
  val diff_q_m  = ones_q_m.zext - zeros_q_m.zext

  val q_m_reg2  = RegNext(q_m_reg)
  val de_reg2   = RegNext(de_reg, false.B)
  val c_reg2    = RegNext(c_reg, 0.U)
  val en_reg2   = RegNext(en_reg, false.B)
  val ones_reg  = RegNext(ones_q_m)
  val zeros_reg = RegNext(zeros_q_m)
  val diff_reg  = RegNext(diff_q_m)

  // ── Stage 2b: disparity → output ──────────────────────────────────────────
  val disp      = RegInit(0.S(5.W))
  val out       = WireDefault(0.U(10.W))
  val disp_next = WireDefault(disp)

  when(!de_reg2) {
    disp_next := 0.S
    switch(c_reg2) {
      is(0.U) { out := "b1101010100".U }
      is(1.U) { out := "b0010101011".U }
      is(2.U) { out := "b0101010100".U }
      is(3.U) { out := "b1010101011".U }
    }
  } .otherwise {
    when(disp === 0.S || ones_reg === zeros_reg) {
      out := Cat(!q_m_reg2(8), q_m_reg2(8), Mux(q_m_reg2(8), q_m_reg2(7, 0), ~q_m_reg2(7, 0)))
      disp_next := disp + Mux(q_m_reg2(8), diff_reg, -diff_reg)
    } .otherwise {
      val disp_is_pos = disp > 0.S
      val diff_is_pos = ones_reg > zeros_reg
      when(disp_is_pos === diff_is_pos) {
        out := Cat(true.B,  q_m_reg2(8), ~q_m_reg2(7, 0))
        disp_next := disp + Mux(q_m_reg2(8), 2.S, 0.S) - diff_reg
      } .otherwise {
        out := Cat(false.B, q_m_reg2(8),  q_m_reg2(7, 0))
        disp_next := disp - Mux(q_m_reg2(8), 0.S, 2.S) + diff_reg
      }
    }
  }

  when(en_reg2) { disp := disp_next }
  io.tmds := out
}

class HdmiColourBarsIO extends Bundle {
  val tick  = Output(Bool())
  val tmds  = Output(Vec(4, UInt(10.W)))
  val de    = Output(Bool())
  val hsync = Output(Bool())
  val vsync = Output(Bool())
}

/** 640x480 colour bars in a 125 MHz domain, a pixel every fifth cycle (`tick`): TMDS words for B, G, R and the clock. */
class HdmiColourBars extends Module {
  val io = IO(new HdmiColourBarsIO)

  val count = RegInit(0.U(3.W))
  val tick25 = (count === 4.U)
  when(tick25) { count := 0.U } .otherwise { count := count + 1.U }

  val hCount = RegInit(0.U(10.W))
  val vCount = RegInit(0.U(10.W))

  // 640x480 @ 60 Hz (25.175 MHz pixel clock approx 25.0 MHz)
  val hTotal = 800.U
  val vTotal = 525.U
  val hActive = 640.U
  val vActive = 480.U
  val hFront = 16.U
  val hSync = 96.U
  val vFront = 10.U
  val vSync = 2.U

  when(tick25) {
    when(hCount === hTotal - 1.U) {
      hCount := 0.U
      when(vCount === vTotal - 1.U) {
        vCount := 0.U
      } .otherwise {
        vCount := vCount + 1.U
      }
    } .otherwise {
      hCount := hCount + 1.U
    }
  }

  val de = (hCount < hActive) && (vCount < vActive)
  val hsync = (hCount >= (hActive + hFront)) && (hCount < (hActive + hFront + hSync))
  val vsync = (vCount >= (vActive + vFront)) && (vCount < (vActive + vFront + vSync))

  // Colour bars: 8 vertical bars, White/Yellow/Cyan/Green/Magenta/Red/Blue/Black.
  val barIdx = hCount / 80.U
  val red   = Mux(de && (barIdx === 0.U || barIdx === 1.U || barIdx === 4.U || barIdx === 5.U), 255.U(8.W), 0.U(8.W))
  val green = Mux(de && (barIdx === 0.U || barIdx === 1.U || barIdx === 2.U || barIdx === 3.U), 255.U(8.W), 0.U(8.W))
  val blue  = Mux(de && (barIdx === 0.U || barIdx === 2.U || barIdx === 4.U || barIdx === 6.U), 255.U(8.W), 0.U(8.W))

  val enc = Seq((blue, Cat(vsync, hsync)), (green, 0.U), (red, 0.U)).map { case (data, c) =>
    val e = Module(new TmdsEncoder)
    e.io.en   := tick25
    e.io.data := data
    e.io.c    := c
    e.io.de   := de
    e.io.tmds
  }

  io.tick  := tick25
  io.tmds  := VecInit(enc :+ "b0000011111".U(10.W))
  io.de    := de
  io.hsync := hsync
  io.vsync := vsync
}
