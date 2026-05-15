// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Native Chisel SDRAM controller — faithful port of sdram_pnru.v.
//
// Simplistic controller for 4×4M×16 SDRAM chips (e.g. IS42S16160G on ULX3S):
//   - One SDRAM access per system request (no bursts)
//   - Can keep all 4 banks open simultaneously
//   - Distributed auto-refresh
//
// The system interface is word-oriented (16-bit).  The byte-serial
// bridge (SdramBackend) sits above this module.

package memory

import chisel3._
import chisel3.util._

// ---------------------------------------------------------------------------
// System-side interface (word-oriented, directly connected by SdramBackend)
// ---------------------------------------------------------------------------

class SdramSysIO extends Bundle {
  val rd  = Input(Bool())         // request read  (active for 1 cycle)
  val wr  = Input(Bool())         // request write (active for 1 cycle)
  val rdy = Output(Bool())        // controller ready / data valid
  val ack = Input(Bool())         // system acknowledges current r/w
  val ab  = Input(UInt(24.W))     // 24-bit word address
  val di  = Input(UInt(16.W))     // write data
  val do_ = Output(UInt(16.W))    // read data
  val debug_state = Output(UInt(3.W))  // FSM state for debug
}

// ---------------------------------------------------------------------------
// SDRAM controller
// ---------------------------------------------------------------------------

class SdramControllerIO extends Bundle {
  val sys  = new SdramSysIO
  val pins = new SdramPinsIO
}

class SdramController(clockMhz: Int = 125) extends Module {
  val io = IO(new SdramControllerIO)

  // ── SDRAM timing parameters (scaled to actual clock) ──
  val tRP  = math.max((clockMhz * 20  + 999) / 1000, 2) // tRP  = 20 ns min
  val tMRD = 2                                           // tMRD = 2 cycles
  val tRCD = math.max((clockMhz * 20  + 999) / 1000, 2) // tRCD = 20 ns min
  val tRC  = math.max((clockMhz * 63  + 999) / 1000, 2) // tRC  = 63 ns min
  val CL   = if (clockMhz > 100) 3 else 2                // CAS latency
  val INITLEN = clockMhz * 100                           // 100 µs
  val RFTIME  = (clockMhz * 7800 + 999) / 1000           // 7.8 µs

  // ── SDRAM command encodings: {nCS, nRAS, nCAS, nWE} ──
  val CMD_NOP      = "b1000".U(4.W)
  val CMD_PRECHRG  = "b0001".U(4.W)
  val CMD_AUTORFRSH= "b0100".U(4.W)
  val CMD_MODESET  = "b0000".U(4.W)
  val CMD_READ     = "b0110".U(4.W)
  val CMD_WRITE    = "b0010".U(4.W)
  val CMD_ACTIVATE = "b0101".U(4.W)

  // ── SDRAM mode register: CL=2 or 3, burst length=1, sequential ──
  // Bits: 13'b000_1_00_CCC_0_000  where CCC = CAS latency
  val MODE = Cat(0.U(3.W), 1.U(1.W), 0.U(2.W), CL.U(3.W), 0.U(1.W), 0.U(3.W))

  // ── FSM states ──
  val sIDLE   = 0.U(3.W)
  val sRFRSH1 = 1.U(3.W)
  val sRFRSH2 = 2.U(3.W)
  val sCONFIG = 3.U(3.W)
  val sRDWR   = 4.U(3.W)
  val sRWRDY  = 5.U(3.W)
  val sACKWT  = 6.U(3.W)
  val sWAIT   = 7.U(3.W)

  // ── Registers ──

  // Cross-clock-domain input registers (matching the Verilog's registered inputs)
  val di  = RegNext(io.sys.di)
  val ab  = RegNext(io.sys.ab)
  val rd  = RegNext(io.sys.rd)
  val wr  = RegNext(io.sys.wr)
  val ack = RegNext(io.sys.ack)

  // Controller state
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

  // ── Convenience address decoding ──
  val col = ab(8, 0)    // 9-bit column
  val row = ab(23, 11)  // 13-bit row
  val ba  = ab(10, 9)   // 2-bit bank

  // ── init flag: DQM==2'b11 during init, 2'b00 thereafter ──
  val init = sdrDqm(0)

  // ── Data bus direction ──
  val dqOe  = (state === sRWRDY) && wr
  val dqOut = di

  // ── FSM ──
  ctr := ctr + 1.U
  dly := dly - 1.U

  // defaults (overridden per-state)
  sdrCmd := CMD_NOP
  state  := sWAIT

  switch(state) {
    is(sIDLE) {
      when(init) {
        // Remain in idle for >100 µs during power-up
        state := Mux(ctr === INITLEN.U, sRFRSH1, sIDLE)
      }.otherwise {
        sysRdy := false.B
        when(ctr >= RFTIME.U) {
          state := sRFRSH1                         // time for a refresh
        }.elsewhen(rd || wr) {
          state := sRDWR                            // serve a read/write
        }.otherwise {
          sysRdy := true.B
          state  := sIDLE                           // nothing to do
        }
      }
    }

    is(sRFRSH1) {
      // Close all banks before refresh
      sdrCmd := CMD_PRECHRG
      sdrAb  := sdrAb | (1.U << 10)   // A10=1 → all banks
      open   := 0.U
      dly    := (tRP - 2).U
      next   := Mux(init, sCONFIG, sRFRSH2)
    }

    is(sRFRSH2) {
      // Auto-refresh, then back to idle (or repeat during init)
      sdrCmd := CMD_AUTORFRSH
      sdrDqm := "b00".U               // end init
      ctr    := 0.U                    // reset refresh timer
      dly    := (tRC - 2).U
      next   := Mux(init, sRFRSH2, sIDLE)
    }

    is(sCONFIG) {
      // Load mode register
      sdrCmd := CMD_MODESET
      sdrAb  := MODE
      dly    := (tMRD - 2).U
      next   := sRFRSH2
    }

    is(sRDWR) {
      when(!open(ba)) {
        // Bank not open → activate row
        sdrCmd := CMD_ACTIVATE
        sdrAb  := row
        open   := open | (1.U << ba)
        opnrow(ba) := row
        dly    := (tRCD - 2).U
        next   := sRDWR
      }.elsewhen(opnrow(ba) =/= row) {
        // Wrong row open → precharge this bank, then retry
        sdrCmd := CMD_PRECHRG
        sdrAb  := sdrAb & ~(1.U(13.W) << 10)  // A10=0 → single bank
        open   := open & ~(1.U << ba)
        dly    := (tRP - 2).U
        next   := sRDWR
      }.otherwise {
        // Row already open → issue read or write
        sdrCmd := Mux(rd, CMD_READ, CMD_WRITE)
        sdrAb  := Cat(0.U(4.W), col)
        dly    := (CL - 1).U
        next   := sRWRDY
        when(wr) {
          state := sRWRDY  // writes need no CAS latency wait
        }
      }
    }

    is(sRWRDY) {
      // Latch read data or present write data
      sysRdy := true.B
      when(rd) { sysDo := io.pins.dq_in }
      state := sACKWT
    }

    is(sACKWT) {
      // Wait for system to acknowledge the transfer
      state := Mux(ack, sIDLE, sACKWT)
    }

    is(sWAIT) {
      // Wait `dly` cycles, then go to `next`
      state := Mux(dly.orR, sWAIT, next)
    }
  }

  // ── Output wiring ──
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
  io.pins.cke    := true.B   // always enabled
}
