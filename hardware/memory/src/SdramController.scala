// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

import chisel3._
import chisel3.util._

class SdramSysIO extends Bundle {
  val rd  = Input(Bool())         // one-cycle request
  val wr  = Input(Bool())
  val rdy = Output(Bool())        // ready / read data valid
  val ack = Input(Bool())
  val ab  = Input(UInt(24.W))     // 16-bit word address
  val di  = Input(UInt(16.W))
  val do_ = Output(UInt(16.W))
  val debug_state = Output(UInt(3.W))
}

class SdramControllerIO extends Bundle {
  val sys  = new SdramSysIO
  val pins = new SdramPinsIO
}

/** Port of sdram_pnru.v for 4x4Mx16 SDRAM (IS42S16160G on ULX3S): one access per request, all
  * four banks kept open, distributed auto-refresh. */
class SdramController(clockMhz: Int = 125) extends Module {
  val io = IO(new SdramControllerIO)

  val tRP  = math.max((clockMhz * 20  + 999) / 1000, 2) // tRP  = 20 ns min
  val tMRD = 2
  val tRCD = math.max((clockMhz * 20  + 999) / 1000, 2) // tRCD = 20 ns min
  val tRC  = math.max((clockMhz * 63  + 999) / 1000, 2) // tRC  = 63 ns min
  // Write recovery above the ~14 ns minimum, so a following precharge cannot truncate the write
  // and DQ turns around before a read; shorter loses sporadic back-to-back halfword writes.
  val tWR  = math.max((clockMhz * 30  + 999) / 1000, 3) // ~30 ns / ≥3 cycles
  val CL   = if (clockMhz > 100) 3 else 2
  val INITLEN = clockMhz * 100                           // 100 us
  val RFTIME  = (clockMhz * 7800 + 999) / 1000           // 7.8 us

  // {nCS, nWE, nRAS, nCAS}
  val CMD_NOP      = "b1000".U(4.W)
  val CMD_PRECHRG  = "b0001".U(4.W)
  val CMD_AUTORFRSH= "b0100".U(4.W)
  val CMD_MODESET  = "b0000".U(4.W)
  val CMD_READ     = "b0110".U(4.W)
  val CMD_WRITE    = "b0010".U(4.W)
  val CMD_ACTIVATE = "b0101".U(4.W)

  // Single-location write burst, CAS latency, sequential, burst length 1.
  val MODE = Cat(0.U(3.W), 1.U(1.W), 0.U(2.W), CL.U(3.W), 0.U(1.W), 0.U(3.W))

  val sIDLE   = 0.U(3.W)
  val sRFRSH1 = 1.U(3.W)
  val sRFRSH2 = 2.U(3.W)
  val sCONFIG = 3.U(3.W)
  val sRDWR   = 4.U(3.W)
  val sRWRDY  = 5.U(3.W)
  val sACKWT  = 6.U(3.W)
  val sWAIT   = 7.U(3.W)

  val di  = RegNext(io.sys.di)
  val ab  = RegNext(io.sys.ab)
  val rd  = RegNext(io.sys.rd)
  val wr  = RegNext(io.sys.wr)
  val ack = RegNext(io.sys.ack)

  val sdrCmd = RegInit(CMD_NOP)
  val state  = RegInit(sIDLE)
  val next   = RegInit(sIDLE)
  val ctr    = RegInit(0.U(16.W))
  val dly    = RegInit(0.U(7.W))
  val open   = RegInit(0.U(4.W))
  val opnrow = Reg(Vec(4, UInt(13.W)))
  val sdrDqm = RegInit("b11".U(2.W))
  val sysRdy = RegInit(false.B)
  val sysDo  = RegInit(0.U(16.W))
  val sdrAb  = RegInit(0.U(13.W))

  val col = ab(8, 0)
  val row = ab(23, 11)
  val ba  = ab(10, 9)

  // DQM is 11 during init only.
  val init = sdrDqm(0)

  val dqOe  = (state === sRWRDY) && wr
  val dqOut = di

  ctr := ctr + 1.U
  dly := dly - 1.U

  sdrCmd := CMD_NOP
  state  := sWAIT

  switch(state) {
    is(sIDLE) {
      when(init) {
        state := Mux(ctr === INITLEN.U, sRFRSH1, sIDLE)
      }.otherwise {
        sysRdy := false.B
        when(ctr >= RFTIME.U) {
          state := sRFRSH1
        }.elsewhen(rd || wr) {
          state := sRDWR
        }.otherwise {
          sysRdy := true.B
          state  := sIDLE
        }
      }
    }

    is(sRFRSH1) {
      sdrCmd := CMD_PRECHRG
      sdrAb  := sdrAb | (1.U << 10)   // A10: all banks
      open   := 0.U
      dly    := (tRP - 2).U
      next   := Mux(init, sCONFIG, sRFRSH2)
    }

    is(sRFRSH2) {
      // Repeats during init.
      sdrCmd := CMD_AUTORFRSH
      sdrDqm := "b00".U               // end init
      ctr    := 0.U
      dly    := (tRC - 2).U
      next   := Mux(init, sRFRSH2, sIDLE)
    }

    is(sCONFIG) {
      sdrCmd := CMD_MODESET
      sdrAb  := MODE
      dly    := (tMRD - 2).U
      next   := sRFRSH2
    }

    is(sRDWR) {
      when(!open(ba)) {
        sdrCmd := CMD_ACTIVATE
        sdrAb  := row
        open   := open | (1.U << ba)
        opnrow(ba) := row
        dly    := (tRCD - 2).U
        next   := sRDWR
      }.elsewhen(opnrow(ba) =/= row) {
        // Wrong row open: precharge this bank, then retry.
        sdrCmd := CMD_PRECHRG
        sdrAb  := sdrAb & ~(1.U(13.W) << 10)  // A10: single bank
        open   := open & ~(1.U << ba)
        dly    := (tRP - 2).U
        next   := sRDWR
      }.otherwise {
        // CL, not CL-1: the SDRAM clock lags the SoC clock by 90 degrees.
        sdrCmd := Mux(rd, CMD_READ, CMD_WRITE)
        sdrAb  := Cat(0.U(4.W), col)
        dly    := CL.U
        next   := sRWRDY
        when(wr) {
          state := sRWRDY
        }
      }
    }

    is(sRWRDY) {
      // A write drives DQ this cycle and completes after tWR.
      when(rd) {
        sysDo  := io.pins.dq_in
        sysRdy := true.B
        state  := sACKWT
      }.otherwise {
        dly   := (tWR - 2).U
        next  := sACKWT
        state := sWAIT
      }
    }

    is(sACKWT) {
      sysRdy := true.B
      state := Mux(ack, sIDLE, sACKWT)
    }

    is(sWAIT) {
      state := Mux(dly.orR, sWAIT, next)
    }
  }

  io.sys.rdy  := sysRdy
  io.sys.do_  := sysDo
  io.sys.debug_state := state

  io.pins.cs_n   := sdrCmd(3)
  io.pins.we_n   := sdrCmd(2)
  io.pins.ras_n  := sdrCmd(1)
  io.pins.cas_n  := sdrCmd(0)
  io.pins.ba     := Mux(state === sCONFIG, 0.U, ab(10, 9))
  io.pins.addr   := sdrAb
  io.pins.dqm    := sdrDqm
  io.pins.dq_out := dqOut
  io.pins.dq_oe  := dqOe
  io.pins.cke    := true.B
}
