// BootUartTest.scala — Minimal Flash→SDRAM boot + readback via UART.
// Sends hex dump of first 16 SDRAM bytes, repeating every 500ms.

package soc

import chisel3._
import chisel3.util._
import chisel3.experimental.{Analog, attach}
import _root_.circt.stage.ChiselStage
import memory._

class BootUartTest(clockMhz: Int = 125) extends RawModule {
  val clk_25mhz = IO(Input(Clock()))
  val rst_n     = IO(Input(Bool()))
  val flash_csn  = IO(Output(Bool()))
  val flash_mosi = IO(Output(Bool()))
  val flash_miso = IO(Input(Bool()))
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
  val ftdi_rxd = IO(Output(Bool()))
  val led      = IO(Output(UInt(8.W)))

  val pll = Module(new Ecp5PllWrapper(Ecp5PllParams(
    inHz = 25_000_000L, out0Hz = 125_000_000L,
    out1Hz = 125_000_000L, out1Deg = 90)))
  pll.io.clk_i := clk_25mhz
  val pllLocked  = pll.io.locked
  val sysClock   = pll.io.clk_o(0)
  sdram_clk := pll.io.clk_o(1)
  val pllRst = !pllLocked

  val flashBoot = withClockAndReset(sysClock, pllRst) { Module(new FlashBootLoader()) }
  val usrmclk = Module(new Usrmclk)
  usrmclk.USRMCLKI  := flashBoot.io.spi_clk.asClock
  usrmclk.USRMCLKTS := false.B
  flash_csn  := flashBoot.io.flash_csn
  flash_mosi := flashBoot.io.flash_mosi
  flashBoot.io.flash_miso := flash_miso

  val backend = withClockAndReset(sysClock, pllRst) { Module(new SdramBackend()) }
  val bootDone = flashBoot.io.boot_done

  withClockAndReset(sysClock, pllRst) {

    // ── UART TX with registered send interface ──
    val BAUD_DIV = 1085.U(11.W)
    val txBusy   = RegInit(false.B)
    val txShift  = RegInit(0x3FF.U(10.W))
    val txCtr    = RegInit(0.U(11.W))
    val txBitIdx = RegInit(0.U(4.W))
    val txSend   = RegInit(false.B)
    val txData   = RegInit(0.U(8.W))

    when(txBusy) {
      when(txCtr === 0.U) {
        txCtr := BAUD_DIV - 1.U
        txShift := Cat(1.U(1.W), txShift(9, 1))
        txBitIdx := txBitIdx + 1.U
        when(txBitIdx === 9.U) { txBusy := false.B }
      }.otherwise {
        txCtr := txCtr - 1.U
      }
    }.elsewhen(txSend) {
      txShift := Cat(1.U(1.W), txData, 0.U(1.W))
      txCtr := BAUD_DIV - 1.U
      txBitIdx := 0.U
      txBusy := true.B
      txSend := false.B
    }

    val txReady = !txBusy && !txSend

    // ── Character buffer: queue one byte at a time ──
    // To send a byte: set charToSend + charValid when txReady
    val charToSend = RegInit(0.U(8.W))
    val charValid  = RegInit(false.B)

    when(charValid && txReady) {
      txSend := true.B
      txData := charToSend
      charValid := false.B
    }

    // Hex nibble → ASCII
    // Lookup table avoids width-truncation bugs in n + 0x30.U
    val hexLut = VecInit("0123456789ABCDEF".map(_.U(8.W)))
    def hexNib(n: UInt): UInt = hexLut(n)

    // ── FSM ──
    val st       = RegInit(0.U(5.W))
    val rdAddr   = RegInit(0.U(25.W))
    val rdByte   = RegInit(0.U(8.W))
    val rdByte1  = RegInit(0.U(8.W))
    val sendB1   = RegInit(false.B)
    val byteIdx  = RegInit(0.U(5.W))
    val delayCtr = RegInit(0.U(26.W))
    val nibIdx   = RegInit(0.U(1.W))  // 0=hi nibble, 1=lo nibble

    val curByte = Mux(sendB1, rdByte1, rdByte)
    val curNib  = Mux(nibIdx === 0.U, curByte(7, 4), curByte(3, 0))

    switch(st) {
      // Wait for boot
      is(0.U) { when(bootDone) { rdAddr := 0.U; byteIdx := 0.U; st := 1.U } }
      // Start SDRAM read
      is(1.U) { st := 2.U }
      // Wait for byte 0
      is(2.U) {
        when(backend.io.backend.dataReady) {
          rdByte := backend.io.backend.dataOut; st := 3.U
        }
      }
      // Wait for byte 1
      is(3.U) {
        when(backend.io.backend.dataReady) {
          rdByte1 := backend.io.backend.dataOut
          sendB1 := false.B; nibIdx := 0.U; st := 4.U
        }
      }
      // Send one hex nibble
      is(4.U) {
        when(!charValid && txReady) {
          charToSend := hexNib(curNib)
          charValid := true.B
          when(nibIdx === 1.U) {
            // Done with this byte
            st := 5.U
          }.otherwise {
            nibIdx := 1.U  // do lo nibble next cycle
          }
        }
      }
      // After one byte: send byte1 or space?
      is(5.U) {
        when(!charValid && txReady) {
          byteIdx := byteIdx + 1.U
          when(!sendB1) {
            sendB1 := true.B; nibIdx := 0.U; st := 4.U
          }.otherwise {
            rdAddr := rdAddr + 2.U
            // Send space
            charToSend := 0x20.U; charValid := true.B; st := 6.U
          }
        }
      }
      // After space: more words or CR?
      is(6.U) {
        when(!charValid && txReady) {
          when(byteIdx >= 16.U) {
            charToSend := 0x0D.U; charValid := true.B; st := 7.U  // CR
          }.otherwise {
            st := 1.U  // next word
          }
        }
      }
      // Send LF
      is(7.U) {
        when(!charValid && txReady) {
          charToSend := 0x0A.U; charValid := true.B; st := 8.U
        }
      }
      // Delay then repeat
      is(8.U) {
        when(!charValid && txReady) {
          delayCtr := 0.U; st := 9.U
        }
      }
      is(9.U) {
        delayCtr := delayCtr + 1.U
        when(delayCtr >= 62500000.U) {
          rdAddr := 0.U; byteIdx := 0.U; st := 1.U
        }
      }
    }

    // ── Backend mux ──
    backend.io.backend.addrIn     := Mux(bootDone, rdAddr, flashBoot.io.backend.addrIn)
    backend.io.backend.dataIn     := Mux(bootDone, 0.U,    flashBoot.io.backend.dataIn)
    backend.io.backend.startRead  := Mux(bootDone, st === 1.U, false.B)
    backend.io.backend.startWrite := Mux(bootDone, false.B, flashBoot.io.backend.startWrite)
    backend.io.backend.stallTxn   := false.B
    backend.io.backend.stopTxn    := Mux(bootDone,
      (st === 3.U && backend.io.backend.dataReady), false.B)
    flashBoot.io.backend.dataOut   := backend.io.backend.dataOut
    flashBoot.io.backend.dataReq   := Mux(!bootDone, backend.io.backend.dataReq, false.B)
    flashBoot.io.backend.dataReady := false.B
    flashBoot.io.backend.busy      := Mux(!bootDone, backend.io.backend.busy, false.B)

    led := Cat(pllLocked, bootDone, st(4, 3), flashBoot.io.debug_state)
    ftdi_rxd := txShift(0)
  }

  val pins = backend.io.sdramPins
  sdram_cke := pins.cke; sdram_csn := pins.cs_n; sdram_wen := pins.we_n
  sdram_rasn := pins.ras_n; sdram_casn := pins.cas_n
  sdram_a := pins.addr; sdram_ba := pins.ba; sdram_dqm := pins.dqm
  val dqIn = Wire(Vec(16, Bool()))
  for (i <- 0 until 16) {
    val bb = Module(new Ecp5BiDirBuf()); bb.T := !pins.dq_oe
    bb.I := pins.dq_out(i); dqIn(i) := bb.O; attach(sdram_d(i), bb.B)
  }
  pins.dq_in := dqIn.asUInt
}

object BootUartTestMain extends App {
  val targetDir = "out/boot_uart_test"
  new java.io.File(targetDir).mkdirs()
  ChiselStage.emitSystemVerilogFile(
    gen = new BootUartTest(), args = Array("--target-dir", targetDir),
    firtoolOpts = Emit.firtoolOpts)
}
