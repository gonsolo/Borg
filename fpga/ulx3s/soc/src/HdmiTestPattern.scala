package soc

import chisel3._
import chisel3.util._
import memory.{Ecp5PllParams, Ecp5PllWrapper}
import _root_.circt.stage.ChiselStage
import soc.Emit

class ODDRX1F extends ExtModule {
  override def desiredName = "ODDRX1F"
  val D0 = IO(Input(Bool()))
  val D1 = IO(Input(Bool()))
  val SCLK = IO(Input(Clock()))
  val RST = IO(Input(Bool()))
  val Q = IO(Output(Bool()))
}

class TmdsEncoderIO extends Bundle {
  val en   = Input(Bool())
  val data = Input(UInt(8.W))
  val c    = Input(UInt(2.W)) // c1, c0
  val de   = Input(Bool())
  val tmds = Output(UInt(10.W))
}

class TmdsEncoder extends Module {
  val io = IO(new TmdsEncoderIO)

  val ones = PopCount(io.data)
  val use_xnor = (ones > 4.U) || (ones === 4.U && io.data(0) === 0.U)

  val q_m = Wire(Vec(9, Bool()))
  q_m(0) := io.data(0)
  for (i <- 1 to 7) {
    q_m(i) := Mux(use_xnor, q_m(i-1) === io.data(i), q_m(i-1) =/= io.data(i))
  }
  q_m(8) := !use_xnor

  val q_m_uint = q_m.asUInt

  val disp = RegInit(0.S(5.W))
  val ones_q_m = PopCount(q_m_uint(7, 0))
  val zeros_q_m = 8.U - ones_q_m
  val diff_q_m = ones_q_m.zext - zeros_q_m.zext

  val out = WireDefault(0.U(10.W))
  val disp_next = WireDefault(disp)

  when(!io.de) {
    disp_next := 0.S
    switch(io.c) {
      is(0.U) { out := "b1101010100".U }
      is(1.U) { out := "b0010101011".U }
      is(2.U) { out := "b0101010100".U }
      is(3.U) { out := "b1010101011".U }
    }
  } .otherwise {
    when(disp === 0.S || ones_q_m === zeros_q_m) {
      out := Cat(!q_m(8), q_m(8), Mux(q_m(8), q_m_uint(7, 0), ~q_m_uint(7, 0)))
      disp_next := disp + Mux(q_m(8), diff_q_m, -diff_q_m)
    } .otherwise {
      val disp_is_pos = disp > 0.S
      val diff_is_pos = ones_q_m > zeros_q_m
      
      when(disp_is_pos === diff_is_pos) {
        out := Cat(true.B, q_m(8), ~q_m_uint(7, 0))
        disp_next := disp + Mux(q_m(8), 2.S, 0.S) - diff_q_m
      } .otherwise {
        out := Cat(false.B, q_m(8), q_m_uint(7, 0))
        disp_next := disp - Mux(q_m(8), 0.S, 2.S) + diff_q_m
      }
    }
  }

  when(io.en) {
    disp := disp_next
  }

  io.tmds := out
}

class TmdsSerializerIO extends Bundle {
  val en   = Input(Bool())
  val tmds = Input(UInt(10.W))
  val out  = Output(Bool())
}

class TmdsSerializer extends Module {
  val io = IO(new TmdsSerializerIO)

  val shift = RegInit(0.U(10.W))
  
  when(io.en) {
    shift := io.tmds
  } .otherwise {
    shift := shift >> 2
  }

  val oddr = Module(new ODDRX1F)
  oddr.SCLK := clock
  oddr.RST := reset.asBool
  oddr.D0 := shift(0)
  oddr.D1 := shift(1)
  io.out := oddr.Q
}

class HdmiTestPattern extends RawModule {
  val clk_25mhz = IO(Input(Clock()))
  val rst_n     = IO(Input(Bool()))

  val gpdi_dp = IO(Output(UInt(4.W))) // 0:B, 1:G, 2:R, 3:Clk
  val led     = IO(Output(UInt(8.W)))

  val pll = Module(new Ecp5PllWrapper(Ecp5PllParams(
    inHz   = 25_000_000L,
    out0Hz = 125_000_000L
  )))
  pll.io.clk_i := clk_25mhz
  val clk125 = pll.io.clk_o(0)
  val locked = pll.io.locked

  withClockAndReset(clk125, !locked) {
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

    // Color bars: 8 vertical bars
    // hActive is 640. 640 / 8 = 80 pixels per bar.
    // Bar index = hCount / 80
    val barIdx = hCount / 80.U
    
    // Colors: White, Yellow, Cyan, Green, Magenta, Red, Blue, Black
    val red   = Mux(de && (barIdx === 0.U || barIdx === 1.U || barIdx === 4.U || barIdx === 5.U), 255.U(8.W), 0.U(8.W))
    val green = Mux(de && (barIdx === 0.U || barIdx === 1.U || barIdx === 2.U || barIdx === 3.U), 255.U(8.W), 0.U(8.W))
    val blue  = Mux(de && (barIdx === 0.U || barIdx === 2.U || barIdx === 4.U || barIdx === 6.U), 255.U(8.W), 0.U(8.W))

    val encB = Module(new TmdsEncoder)
    encB.io.en   := tick25
    encB.io.data := blue
    encB.io.c    := Cat(vsync, hsync)
    encB.io.de   := de

    val encG = Module(new TmdsEncoder)
    encG.io.en   := tick25
    encG.io.data := green
    encG.io.c    := 0.U
    encG.io.de   := de

    val encR = Module(new TmdsEncoder)
    encR.io.en   := tick25
    encR.io.data := red
    encR.io.c    := 0.U
    encR.io.de   := de

    val serB = Module(new TmdsSerializer)
    serB.io.en   := tick25
    serB.io.tmds := encB.io.tmds

    val serG = Module(new TmdsSerializer)
    serG.io.en   := tick25
    serG.io.tmds := encG.io.tmds

    val serR = Module(new TmdsSerializer)
    serR.io.en   := tick25
    serR.io.tmds := encR.io.tmds

    val serClk = Module(new TmdsSerializer)
    serClk.io.en   := tick25
    serClk.io.tmds := "b0000011111".U // Clock pattern

    gpdi_dp := Cat(serClk.io.out, serR.io.out, serG.io.out, serB.io.out)
    
    // LEDs for debug
    led := Cat(locked, de, hsync, vsync, count, 0.U(1.W))
  }
}

object HdmiTestMain extends App {
  val targetDir = sys.env.getOrElse("TARGET_DIR", "out/ulx3s/hdmi_test")
  new java.io.File(targetDir).mkdirs()

  ChiselStage.emitSystemVerilogFile(
    gen         = new HdmiTestPattern(),
    args        = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts
  )
}
