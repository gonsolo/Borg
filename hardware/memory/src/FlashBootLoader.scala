// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._
import chisel3.util._

object FlashBootState extends ChiselEnum {
  val sWaitSdram,
      sFlashRst0, sFlashRst1, sFlashRst2, sFlashRst3,
      sSendCmd,
      sSendAddr0, sSendAddr1, sSendAddr2,
      sReadSize,
      sReadByte0, sReadByte1,
      sWrStart, sWrWaitDone,
      // Warm reload: SDRAM scratch to SDRAM 0, no flash.
      sWarmSz0, sWarmSz0W, sWarmSz1, sWarmSz1W,
      sWarmRd, sWarmRdW, sWarmWr, sWarmWrW,
      sDone = Value
}

class FlashBootIO extends Bundle {
  val flash_csn  = Output(Bool())          // active-low
  val flash_mosi = Output(Bool())
  val flash_miso = Input(Bool())
  val spi_clk    = Output(Bool())          // to Usrmclk.USRMCLKI via .asClock
  val backend    = new MemBackendIO
  // Copy the image the host streamed into scratch instead of reading flash. The warm reset
  // spares the video clock domain, so HDMI keeps sync across a firmware reload.
  val warmBoot   = Input(Bool())
  val boot_done  = Output(Bool())
  val debug_state    = Output(UInt(4.W))
  val debug_sizeCtr  = Output(UInt(2.W))
  val debug_bitCtr   = Output(UInt(3.W))
}

/** SPI-flash to SDRAM bootloader: after SDRAM init and a flash software reset (recovering from
  * ECP5 configuration), READ a little-endian u32 size and that many bytes from
  * FLASH_FIRMWARE_OFFSET to SDRAM 0. SPI mode 0, MSB first.
  */
class FlashBootLoader(
    val FLASH_FIRMWARE_OFFSET: Long = 0x400000L,
    val SPI_CLK_DIV:           Int  = 16,
    val SDRAM_INIT_CYCLES:     Int  = 13200,
    // 16-bit word address of [u32 size LE][image], byte 0x800000: the unused upper ROM half.
    val WARM_SCRATCH_WORD:     Long = 0x400000L,
    // Caps the copy when scratch holds garbage.
    val WARM_MAX_BYTES:        Long = 0x80000L
) extends Module {

  val io = IO(new FlashBootIO)

  val csnReg    = RegInit(true.B)

  // The SPI clock runs only while CS is asserted, so bitCtr starts with the transaction.
  val HALF      = (SPI_CLK_DIV / 2).U(5.W)
  val FULL      = (SPI_CLK_DIV - 1).U(5.W)
  val clkDiv    = RegInit(0.U(5.W))
  val spiClkReg = RegInit(false.B)
  val spiRise   = WireDefault(false.B)
  val spiFall   = WireDefault(false.B)

  when(csnReg) {
    clkDiv    := 0.U
    spiClkReg := false.B
  } .otherwise {
    clkDiv := clkDiv + 1.U
    when(clkDiv === HALF - 1.U) { spiClkReg := true.B;  spiRise := true.B }
    when(clkDiv === FULL)        { spiClkReg := false.B; spiFall := true.B; clkDiv := 0.U }
  }

  val shiftOut = RegInit(0.U(8.W))
  val shiftIn  = RegInit(0.U(8.W))
  val bitCtr   = RegInit(0.U(3.W))
  val byteDone = WireDefault(false.B)

  when(spiRise) { shiftIn := Cat(shiftIn(6, 0), io.flash_miso) }
  when(spiFall) {
    when(bitCtr === 7.U) { bitCtr := 0.U; byteDone := true.B }
    .otherwise           { bitCtr := bitCtr + 1.U; shiftOut := Cat(shiftOut(6, 0), 0.U(1.W)) }
  }

  import FlashBootState._

  val state     = RegInit(sWaitSdram)
  val initCtr   = RegInit(0.U(15.W))
  val sizeCtr   = RegInit(0.U(2.W))
  val firmSize  = RegInit(0.U(32.W))
  val byteCtr   = RegInit(0.U(32.W))
  val sdramAddr = RegInit(0.U(25.W))
  val buf0      = RegInit(0.U(8.W))
  val buf1      = RegInit(0.U(8.W))

  // Warm reload, in 16-bit word addresses.
  val rdAddr    = RegInit(0.U(24.W))
  val wrAddr    = RegInit(0.U(24.W))
  val sizeLo    = RegInit(0.U(16.W))
  val wordData  = RegInit(0.U(16.W))

  val warmReadState  = (state === sWarmSz0) || (state === sWarmSz1) || (state === sWarmRd)
  val warmWriteState = (state === sWarmWr)

  val addrB2 = ((FLASH_FIRMWARE_OFFSET >> 16) & 0xFF).U(8.W)
  val addrB1 = ((FLASH_FIRMWARE_OFFSET >>  8) & 0xFF).U(8.W)
  val addrB0 = ( FLASH_FIRMWARE_OFFSET        & 0xFF).U(8.W)

  io.flash_csn          := csnReg
  io.flash_mosi         := shiftOut(7)
  io.spi_clk            := spiClkReg
  io.backend.addrIn     := Mux(warmReadState,  rdAddr,
                           Mux(warmWriteState, wrAddr, sdramAddr(24, 1)))
  io.backend.dataIn     := Mux(warmWriteState, wordData, Cat(buf1, buf0))
  io.backend.byteEnIn   := "b11".U
  io.backend.lenIn      := 1.U
  // Gated on !busy so consecutive warm transactions never overlap.
  io.backend.startRead  := warmReadState && !io.backend.busy
  io.backend.startWrite := (state === sWrStart) || (warmWriteState && !io.backend.busy)
  io.boot_done          := (state === sDone)
  io.debug_state        := state.asUInt(3, 0)
  io.debug_sizeCtr      := sizeCtr
  io.debug_bitCtr       := bitCtr

  switch(state) {

    is(sWaitSdram) {
      when(io.warmBoot) {
        // SDRAM's clock domain was never reset: no init wait.
        rdAddr := WARM_SCRATCH_WORD.U
        state  := sWarmSz0
      } .otherwise {
        initCtr := initCtr + 1.U
        when(initCtr === SDRAM_INIT_CYCLES.U) {
          // Enable Reset
          csnReg   := false.B
          shiftOut := 0x66.U(8.W)
          bitCtr   := 0.U
          state    := sFlashRst0
        }
      }
    }

    // W25Q software reset: 0x66, CS high, 0x99, then tRST >= 30 us before READ.
    is(sFlashRst0) {
      when(byteDone) { csnReg := true.B; initCtr := 0.U; state := sFlashRst1 }
    }

    is(sFlashRst1) {
      // CS high >= 50 ns
      initCtr := initCtr + 1.U
      when(initCtr === 8.U) {
        csnReg   := false.B
        shiftOut := 0x99.U(8.W)
        bitCtr   := 0.U
        state    := sFlashRst2
      }
    }

    is(sFlashRst2) {
      when(byteDone) { csnReg := true.B; initCtr := 0.U; state := sFlashRst3 }
    }

    is(sFlashRst3) {
      // tRST: 4000 cycles are 32 us at 125 MHz.
      initCtr := initCtr + 1.U
      when(initCtr === 4000.U) {
        csnReg   := false.B
        shiftOut := 0x03.U(8.W)     // READ
        bitCtr   := 0.U
        state    := sSendCmd
      }
    }

    is(sSendCmd)   { when(byteDone) { shiftOut := addrB2; state := sSendAddr0 } }
    is(sSendAddr0) { when(byteDone) { shiftOut := addrB1; state := sSendAddr1 } }
    is(sSendAddr1) { when(byteDone) { shiftOut := addrB0; state := sSendAddr2 } }

    is(sSendAddr2) {
      when(byteDone) {
        sizeCtr := 0.U
        firmSize := 0.U
        state   := sReadSize
      }
    }

    is(sReadSize) {
      when(byteDone) {
        firmSize := firmSize | (shiftIn << (sizeCtr ## 0.U(3.W)))
        sizeCtr  := sizeCtr + 1.U
        when(sizeCtr === 3.U) {
          byteCtr   := 0.U
          sdramAddr := 0.U
          state     := sReadByte0
        }
      }
    }

    is(sReadByte0) {
      when(byteDone) {
        buf0 := shiftIn
        // Odd length: pad the last byte.
        when(byteCtr + 1.U === firmSize) { buf1 := 0.U; state := sWrStart }
        .otherwise                       {               state := sReadByte1 }
      }
    }

    is(sReadByte1) {
      when(byteDone) { buf1 := shiftIn; state := sWrStart }
    }

    is(sWrStart) {
      state := sWrWaitDone
    }

    is(sWrWaitDone) {
      when(io.backend.done) {
        sdramAddr := sdramAddr + 2.U
        byteCtr   := byteCtr  + 2.U
        when(byteCtr + 2.U >= firmSize) { csnReg := true.B; state := sDone }
        .otherwise                      {                    state := sReadByte0 }
      }
    }

    // Scratch: word 0 = size[15:0], word 1 = size[31:16], then the image.
    is(sWarmSz0)  { when(!io.backend.busy) { state := sWarmSz0W } }
    is(sWarmSz0W) {
      when(io.backend.done) {
        sizeLo := io.backend.dataOut
        rdAddr := rdAddr + 1.U
        state  := sWarmSz1
      }
    }
    is(sWarmSz1)  { when(!io.backend.busy) { state := sWarmSz1W } }
    is(sWarmSz1W) {
      when(io.backend.done) {
        val full = Cat(io.backend.dataOut, sizeLo)
        firmSize := Mux(full > WARM_MAX_BYTES.U, WARM_MAX_BYTES.U, full)
        rdAddr   := rdAddr + 1.U
        wrAddr   := 0.U
        byteCtr  := 0.U
        state    := sWarmRd
      }
    }
    is(sWarmRd)  { when(!io.backend.busy) { state := sWarmRdW } }
    is(sWarmRdW) {
      when(io.backend.done) {
        wordData := io.backend.dataOut
        state    := sWarmWr
      }
    }
    is(sWarmWr)  { when(!io.backend.busy) { state := sWarmWrW } }
    is(sWarmWrW) {
      when(io.backend.done) {
        rdAddr  := rdAddr + 1.U
        wrAddr  := wrAddr + 1.U
        byteCtr := byteCtr + 2.U
        when(byteCtr + 2.U >= firmSize) { state := sDone }
        .otherwise                      { state := sWarmRd }
      }
    }

    is(sDone) { /* boot_done stays high */ }
  }
}
