// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._
import chisel3.util._

/** Physical flash + backend IO for the bootloader. */
class FlashBootIO extends Bundle {
  val flash_csn  = Output(Bool())          // Winbond chip-select (active-low)
  val flash_mosi = Output(Bool())          // SPI data out
  val flash_miso = Input(Bool())           // SPI data in
  val spi_clk    = Output(Bool())          // → Usrmclk.USRMCLKI via .asClock
  val backend    = new MemBackendIO        // master: FlashBootLoader drives the SDRAM
  val boot_done  = Output(Bool())
}

/** BRAM-resident SPI-flash → SDRAM bootloader.
  *
  * Power-up sequence:
  *   1. Waits ~13 200 cycles for the SdramController to finish init.
  *   2. Sends READ (0x03) + 24-bit [[FLASH_FIRMWARE_OFFSET]] to the flash.
  *   3. Reads a 4-byte little-endian firmware-size header.
  *   4. Copies exactly that many bytes to SDRAM byte-address 0, two bytes per
  *      16-bit SDRAM word, using the [[MemBackendIO]] write protocol.
  *   5. Deasserts flash CS and asserts [[io.boot_done]] forever.
  *
  * SPI mode 0 (CPOL=0 CPHA=0), MSB-first.
  * SPI clock = system_clk / SPI_CLK_DIV ≈ 7.8 MHz at 125 MHz.
  */
class FlashBootLoader(
    val FLASH_FIRMWARE_OFFSET: Long = 0x400000L,
    val SPI_CLK_DIV:           Int  = 16,
    val SDRAM_INIT_CYCLES:     Int  = 13200
) extends Module {

  val io = IO(new FlashBootIO)

  // ── SPI clock generator ────────────────────────────────────────────────────
  val HALF      = (SPI_CLK_DIV / 2).U(5.W)
  val FULL      = (SPI_CLK_DIV - 1).U(5.W)
  val clkDiv    = RegInit(0.U(5.W))
  val spiClkReg = RegInit(false.B)
  val spiRise   = WireDefault(false.B)
  val spiFall   = WireDefault(false.B)

  clkDiv := clkDiv + 1.U
  when(clkDiv === HALF - 1.U) { spiClkReg := true.B;  spiRise := true.B }
  when(clkDiv === FULL)        { spiClkReg := false.B; spiFall := true.B; clkDiv := 0.U }

  // ── SPI shift registers ────────────────────────────────────────────────────
  val shiftOut = RegInit(0.U(8.W))
  val shiftIn  = RegInit(0.U(8.W))
  val bitCtr   = RegInit(0.U(3.W))
  val byteDone = WireDefault(false.B)

  when(spiRise) { shiftIn := Cat(shiftIn(6, 0), io.flash_miso) }
  when(spiFall) {
    when(bitCtr === 7.U) { bitCtr := 0.U; byteDone := true.B }
    .otherwise           { bitCtr := bitCtr + 1.U; shiftOut := Cat(shiftOut(6, 0), 0.U(1.W)) }
  }

  // ── FSM ────────────────────────────────────────────────────────────────────
  val sWaitSdram  = 0.U(4.W)
  val sSendCmd    = 1.U(4.W)
  val sSendAddr0  = 2.U(4.W)
  val sSendAddr1  = 3.U(4.W)
  val sSendAddr2  = 4.U(4.W)
  val sReadSize   = 5.U(4.W)
  val sReadByte0  = 6.U(4.W)
  val sReadByte1  = 7.U(4.W)
  val sWrStart    = 8.U(4.W)
  val sWrWaitDone = 9.U(4.W)
  val sDone       = 10.U(4.W)

  val state     = RegInit(sWaitSdram)
  val initCtr   = RegInit(0.U(15.W))
  val sizeCtr   = RegInit(0.U(2.W))
  val firmSize  = RegInit(0.U(32.W))
  val byteCtr   = RegInit(0.U(32.W))
  val sdramAddr = RegInit(0.U(25.W))
  val buf0      = RegInit(0.U(8.W))
  val buf1      = RegInit(0.U(8.W))
  val csnReg    = RegInit(true.B)

  // Pre-compute address bytes (big-endian, MSB first)
  val addrB2 = ((FLASH_FIRMWARE_OFFSET >> 16) & 0xFF).U(8.W)
  val addrB1 = ((FLASH_FIRMWARE_OFFSET >>  8) & 0xFF).U(8.W)
  val addrB0 = ( FLASH_FIRMWARE_OFFSET        & 0xFF).U(8.W)

  // ── IO defaults ────────────────────────────────────────────────────────────
  io.flash_csn          := csnReg
  io.flash_mosi         := shiftOut(7)
  io.spi_clk            := spiClkReg
  io.backend.addrIn     := sdramAddr
  io.backend.dataIn     := Mux(state === sWrWaitDone, buf1, buf0)
  io.backend.startRead  := false.B
  io.backend.startWrite := false.B
  io.backend.stallTxn   := false.B
  io.backend.stopTxn    := false.B
  io.boot_done          := (state === sDone)

  // ── FSM transitions ────────────────────────────────────────────────────────
  switch(state) {

    is(sWaitSdram) {
      initCtr := initCtr + 1.U
      when(initCtr === SDRAM_INIT_CYCLES.U) {
        csnReg   := false.B
        shiftOut := 0x03.U(8.W)
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
        // Odd-length: last byte → pad and write
        when(byteCtr + 1.U === firmSize) { buf1 := 0.U; state := sWrStart }
        .otherwise                       {               state := sReadByte1 }
      }
    }

    is(sReadByte1) {
      when(byteDone) { buf1 := shiftIn; state := sWrStart }
    }

    is(sWrStart) {
      // Pulse startWrite for one cycle; SdramBackend latches addrIn + dataIn (buf0)
      io.backend.startWrite := true.B
      state := sWrWaitDone
    }

    is(sWrWaitDone) {
      // Keep buf1 on dataIn so SdramBackend (in sWrWord) can latch it.
      // Write is complete when dataReq pulses with busy=false (backend returned to sIdle).
      io.backend.dataIn := buf1
      when(io.backend.dataReq && !io.backend.busy) {
        sdramAddr := sdramAddr + 2.U
        byteCtr   := byteCtr  + 2.U
        when(byteCtr + 2.U >= firmSize) { csnReg := true.B; state := sDone }
        .otherwise                      {                    state := sReadByte0 }
      }
    }

    is(sDone) { /* boot_done held high permanently */ }
  }
}
