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
    val bars = Module(new HdmiColourBars)
    val ser = bars.io.tmds.map { w =>
      val s = Module(new TmdsSerializer)
      s.io.en   := bars.io.tick
      s.io.tmds := w
      s.io.out
    }
    gpdi_dp := Cat(ser.reverse)
    led := Cat(locked, bars.io.de, bars.io.hsync, bars.io.vsync, 0.U(4.W))
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
