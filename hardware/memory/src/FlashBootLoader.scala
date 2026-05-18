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
      sDone = Value
}

/** Physical flash + backend IO for the bootloader. */
class FlashBootIO extends Bundle {
  val flash_csn  = Output(Bool())          // Winbond chip-select (active-low)
  val flash_mosi = Output(Bool())          // SPI data out
  val flash_miso = Input(Bool())           // SPI data in
  val spi_clk    = Output(Bool())          // → Usrmclk.USRMCLKI via .asClock
  val backend    = new MemBackendIO        // master: FlashBootLoader drives the SDRAM
  val boot_done  = Output(Bool())
  val debug_state    = Output(UInt(4.W))   // FSM state for LED diagnostics
  val debug_sizeCtr  = Output(UInt(2.W))   // 0..3 — bytes of size header read so far
  val debug_bitCtr   = Output(UInt(3.W))   // current SPI bit position
}

/** BRAM-resident SPI-flash → SDRAM bootloader.
  *
  * Power-up sequence:
  *   1. Waits ~13 200 cycles for the SdramController to finish init.
  *   2. Sends flash software-reset (0x66 + 0x99) to recover from any
  *      residual state left by the ECP5 configuration logic.
  *   3. Sends READ (0x03) + 24-bit [[FLASH_FIRMWARE_OFFSET]] to the flash.
  *   4. Reads a 4-byte little-endian firmware-size header.
  *   5. Copies exactly that many bytes to SDRAM byte-address 0, two bytes per
  *      16-bit SDRAM word, using the [[MemBackendIO]] write protocol.
  *   6. Deasserts flash CS and asserts [[io.boot_done]] forever.
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

  // Declare csnReg early — needed by the gated clock block below.
  val csnReg    = RegInit(true.B)

  // ── SPI clock generator ────────────────────────────────────────────────────
  // Clock is GATED: only runs when CS is asserted (csnReg=false).
  // This prevents bitCtr from advancing before any transaction begins.
  val HALF      = (SPI_CLK_DIV / 2).U(5.W)
  val FULL      = (SPI_CLK_DIV - 1).U(5.W)
  val clkDiv    = RegInit(0.U(5.W))
  val spiClkReg = RegInit(false.B)
  val spiRise   = WireDefault(false.B)
  val spiFall   = WireDefault(false.B)

  // Gate: reset divider when CS is deasserted; only count when CS is low.
  when(csnReg) {
    clkDiv    := 0.U
    spiClkReg := false.B
  } .otherwise {
    clkDiv := clkDiv + 1.U
    when(clkDiv === HALF - 1.U) { spiClkReg := true.B;  spiRise := true.B }
    when(clkDiv === FULL)        { spiClkReg := false.B; spiFall := true.B; clkDiv := 0.U }
  }

  // ── SPI shift registers ────────────────────────────────────────────────────
  val shiftOut = RegInit(0.U(8.W))
  val shiftIn  = RegInit(0.U(8.W))
  val bitCtr   = RegInit(0.U(3.W))
  val byteDone = WireDefault(false.B)

  // Sample MISO on rising edge (SPI mode 0)
  when(spiRise) { shiftIn := Cat(shiftIn(6, 0), io.flash_miso) }
  // On falling edge: check if byte is done, then shift out next bit
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
  io.backend.stopTxn    := (state === sWrWaitDone)
  io.boot_done          := (state === sDone)
  io.debug_state        := state.asUInt
  io.debug_sizeCtr      := sizeCtr
  io.debug_bitCtr       := bitCtr

  // ── FSM transitions ────────────────────────────────────────────────────────
  switch(state) {

    is(sWaitSdram) {
      initCtr := initCtr + 1.U
      when(initCtr === SDRAM_INIT_CYCLES.U) {
        // Start flash software-reset: assert CS, send 0x66 (Enable Reset)
        csnReg   := false.B
        shiftOut := 0x66.U(8.W)
        bitCtr   := 0.U
        state    := sFlashRst0
      }
    }

    // ── Flash software reset sequence ──────────────────────────────────
    // W25Q protocol: CS low → 0x66 → CS high → CS low → 0x99 → CS high
    // Then wait ≥30 µs (tRST) before issuing READ.
    is(sFlashRst0) {
      // Send 0x66 (Enable Reset); when byte done, deassert CS
      when(byteDone) { csnReg := true.B; initCtr := 0.U; state := sFlashRst1 }
    }

    is(sFlashRst1) {
      // CS high gap (≥50 ns, a few SPI clocks); then assert CS for 0x99
      initCtr := initCtr + 1.U
      when(initCtr === 8.U) {
        csnReg   := false.B
        shiftOut := 0x99.U(8.W)
        bitCtr   := 0.U
        state    := sFlashRst2
      }
    }

    is(sFlashRst2) {
      // Send 0x99 (Reset Device); when done, deassert CS and wait tRST
      when(byteDone) { csnReg := true.B; initCtr := 0.U; state := sFlashRst3 }
    }

    is(sFlashRst3) {
      // Wait ≥30 µs for flash reset to complete (tRST).
      // At 125 MHz, 4000 cycles ≈ 32 µs.
      initCtr := initCtr + 1.U
      when(initCtr === 4000.U) {
        csnReg   := false.B
        shiftOut := 0x03.U(8.W)     // READ command
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
      // Write is complete when the backend returns to idle (busy=false).
      // NOTE: Do NOT use dataReq here! dataReq fires TWICE per write:
      //   1) sWrReq: requests second byte (buf1) — this is NOT completion!
      //   2) sWrWait: SDRAM rdy fires — this IS completion, but so is !busy.
      // Using dataReq caused the bootloader to advance prematurely on the
      // sWrReq pulse, writing Cat(buf0,buf0) instead of Cat(buf1,buf0).
      io.backend.dataIn := buf1
      when(!io.backend.busy) {
        sdramAddr := sdramAddr + 2.U
        byteCtr   := byteCtr  + 2.U
        when(byteCtr + 2.U >= firmSize) { csnReg := true.B; state := sDone }
        .otherwise                      {                    state := sReadByte0 }
      }
    }

    is(sDone) { /* boot_done held high permanently */ }
  }
}
