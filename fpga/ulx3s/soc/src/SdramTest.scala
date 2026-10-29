// Minimal SDRAM test, no CPU or GPU: writes 0xC3A5 to address 4, reads it back, shows it on LEDs.

package memory

import chisel3._
import chisel3.util._
import chisel3.experimental.{Analog, attach}
import _root_.circt.stage.ChiselStage

/** ECP5 bidirectional buffer primitive. */
class Ecp5BiDirBufTest extends ExtModule {
  val I = IO(Input(Bool()))
  val T = IO(Input(Bool()))
  val O = IO(Output(Bool()))
  val B = IO(Analog(1.W))
  override val desiredName = "BB"
}

class SdramTest extends RawModule {

  val clk_25mhz = IO(Input(Clock()))
  val rst_n      = IO(Input(Bool()))

  val sdram_clk  = IO(Output(Clock()))
  val sdram_cke  = IO(Output(Bool()))
  val sdram_csn  = IO(Output(Bool()))
  val sdram_wen  = IO(Output(Bool()))
  val sdram_rasn = IO(Output(Bool()))
  val sdram_casn = IO(Output(Bool()))
  val sdram_a    = IO(Output(UInt(13.W)))
  val sdram_ba   = IO(Output(UInt(2.W)))
  val sdram_dqm  = IO(Output(UInt(2.W)))
  val sdram_d    = IO(Vec(16, Analog(1.W)))

  val led      = IO(Output(UInt(8.W)))
  val ftdi_rxd = IO(Output(Bool()))

  val pll = Module(new Ecp5PllWrapper(Ecp5PllParams(
    inHz   = 25_000_000L,
    out0Hz = 125_000_000L,
    out1Hz = 125_000_000L, out1Deg = 90
  )))
  pll.io.clk_i  := clk_25mhz
  val pllLocked  = pll.io.locked
  val sysClock   = pll.io.clk_o(0)
  val sdramClock = pll.io.clk_o(1)
  sdram_clk := sdramClock

  val pllRst = !pllLocked

  val sdramBackend = withClockAndReset(sysClock, pllRst) {
    Module(new SdramBackend())
  }

  val pins = sdramBackend.io.sdramPins
  sdram_csn  := pins.cs_n
  sdram_rasn := pins.ras_n
  sdram_casn := pins.cas_n
  sdram_wen  := pins.we_n
  sdram_cke  := pins.cke
  sdram_a    := pins.addr
  sdram_ba   := pins.ba
  sdram_dqm  := pins.dqm

  val dqIn = Wire(Vec(16, Bool()))
  for (i <- 0 until 16) {
    val bb = Module(new Ecp5BiDirBufTest())
    bb.T := !pins.dq_oe
    bb.I := pins.dq_out(i)
    dqIn(i) := bb.O
    attach(sdram_d(i), bb.B)
  }
  pins.dq_in := dqIn.asUInt

  val testFsm = withClockAndReset(sysClock, pllRst) {

    val sWaitRdy = 0.U(4.W)
    val sWr      = 1.U(4.W)
    val sWrLow   = 2.U(4.W)
    val sWrHigh  = 3.U(4.W)
    val sWrDone  = 4.U(4.W)
    val sRead    = 5.U(4.W)
    val sRdWait  = 6.U(4.W)
    val sRdByte0 = 7.U(4.W)
    val sRdByte1 = 8.U(4.W)
    val sDone    = 9.U(4.W)

    val state = RegInit(sWaitRdy)
    val byte0 = RegInit(0.U(8.W))
    val byte1 = RegInit(0.U(8.W))

    val ramRd = RegInit(false.B)
    val ramWr = RegInit(false.B)
    val ramAb = RegInit(0.U(24.W))
    val ramDi = RegInit(0.U(16.W))

    val sdramSys = sdramBackend.io

    sdramBackend.io.backend.startRead  := false.B
    sdramBackend.io.backend.startWrite := false.B
    sdramBackend.io.backend.byteEnIn   := "b11".U
    sdramBackend.io.backend.addrIn     := 0.U
    sdramBackend.io.backend.dataIn     := 0.U

    switch(state) {
      is(sWaitRdy) {
        when(!sdramBackend.io.backend.busy) { state := sWr }
      }
      is(sWr) {
        sdramBackend.io.backend.addrIn     := 4.U
        sdramBackend.io.backend.dataIn     := 0xC3A5.U
        sdramBackend.io.backend.startWrite := true.B
        state := sWrLow
      }
      is(sWrLow) {
        when(sdramBackend.io.backend.done) { state := sWrHigh }
      }
      is(sWrHigh) {
        when(!sdramBackend.io.backend.busy) { state := sWrDone }
      }
      is(sWrDone) { state := sRead }

      is(sRead) {
        sdramBackend.io.backend.addrIn    := 4.U
        sdramBackend.io.backend.startRead := true.B
        state := sRdWait
      }
      is(sRdWait) {
        when(sdramBackend.io.backend.done) {
          byte0 := sdramBackend.io.backend.dataOut(7, 0)
          byte1 := sdramBackend.io.backend.dataOut(15, 8)
          state := sDone
        }
      }
      is(sRdByte0) { state := sDone }
      is(sRdByte1) { state := sDone }
      is(sDone) {
        // Hold forever
      }
    }

    // When done: LED[7] heartbeat, [6] pass, [5:0] the low read byte.
    val hb = RegInit(0.U(27.W))
    hb := hb + 1.U
    val rw = sdramBackend.io.debug_readWord
    val pass = (byte0 === 0xA5.U) && (byte1 === 0xC3.U)
    val debugLeds = Mux(state === sDone,
      Cat(hb(26), pass, byte0(5, 0)),
      Cat(pllLocked, sdramBackend.io.backend.busy,
          sdramBackend.io.debug_ctrl_state, state(2,0)))
    debugLeds
  }

  led := testFsm

  ftdi_rxd := true.B  // idle
}

object EmitSdramTest extends App {
  ChiselStage.emitSystemVerilogFile(
    new SdramTest(),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "--lowering-options=disallowLocalVariables"
    ),
    args = Array("--target-dir", "generated/sdram_test")
  )
}
