// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import borg.GpuMemIO

/** The Xilinx 7-series MIG's user interface: 16-byte lines, `app_addr` in halfwords. */
class MigUi extends Bundle {
  val app_addr            = Output(UInt(29.W))
  val app_cmd             = Output(UInt(3.W))
  val app_en              = Output(Bool())
  val app_wdf_data        = Output(UInt(128.W))
  val app_wdf_mask        = Output(UInt(16.W))
  val app_wdf_wren        = Output(Bool())
  val app_wdf_end         = Output(Bool())
  val app_rdy             = Input(Bool())
  val app_wdf_rdy         = Input(Bool())
  val app_rd_data         = Input(UInt(128.W))
  val app_rd_data_valid   = Input(Bool())
  val init_calib_complete = Input(Bool())
}

class GpuMemMigIO extends Bundle {
  val gpu     = Flipped(new GpuMemIO)
  val uiClock = Input(Clock())
  val uiReset = Input(Bool())
  val mig     = new MigUi
}

/** [[GpuMemIO]] on the MIG's DDR3 (512 MB). A write burst is gathered on this side, then crosses
  * whole: its lines go out with byte masks. A read (any halfword address, so it may span two
  * lines) fetches its line, which stays for the next read until a write touches it. One request
  * crosses at a time, by toggles through two flops; the data beside them is stable meanwhile.
  */
class GpuMemMig extends Module {
  val io = IO(new GpuMemMigIO)
  val g  = io.gpu

  val sIdle :: sBeat :: sWait :: sHit :: Nil = Enum(4)
  val state = RegInit(sIdle)
  val write = Reg(Bool())
  val addr  = Reg(UInt(32.W))
  val buf   = Reg(Vec(72, UInt(16.W)))
  val en    = Reg(Vec(72, Bool()))
  val pos   = Reg(UInt(7.W))
  val left  = Reg(UInt(7.W))
  val lines = Reg(UInt(4.W))
  val line  = Reg(UInt(128.W))
  val tag   = Reg(UInt(28.W))
  val fetch = Reg(UInt(28.W))
  val cross = Reg(Bool())
  val lo    = Reg(UInt(16.W))
  val valid = RegInit(false.B)
  val reqT  = RegInit(false.B)
  val doneT = Wire(Bool())
  val uiLine = Wire(UInt(128.W))

  val d    = ShiftRegister(doneT, 2, false.B, true.B)
  val done = d =/= RegNext(d, false.B)

  g.ready   := false.B
  g.waccept := false.B
  g.data    := Mux(cross, Cat(line(15, 0), lo), (line >> Cat(addr(3, 1), 0.U(4.W)))(31, 0))

  switch(state) {
    is(sIdle) {
      val off = g.addr(3, 1)
      when(g.wr) {
        write := true.B
        addr := g.addr
        en := VecInit(Seq.fill(72)(false.B))
        buf(off) := g.wdata(15, 0)
        en(off) := true.B
        pos := off +& 1.U
        left := g.wlen - 1.U
        lines := (off +& g.wlen + 7.U) >> 3
        g.waccept := g.wlen > 1.U
        when(g.wlen > 1.U) { state := sBeat }.otherwise { reqT := !reqT; state := sWait }
      }.elsewhen(g.req) {
        val l = g.addr(31, 4)
        val c = g.addr(3, 1) === 7.U
        val hit = valid && tag === l
        write := false.B
        addr := g.addr
        cross := c
        lo := line(127, 112)
        fetch := Mux(hit, l + 1.U, l)
        when(hit && !c) { state := sHit }.otherwise { reqT := !reqT; state := sWait }
      }
    }
    is(sBeat) {
      buf(pos) := g.wdata(15, 0)
      en(pos) := true.B
      pos := pos + 1.U
      left := left - 1.U
      g.waccept := left > 1.U
      when(left === 1.U) { reqT := !reqT; state := sWait }
    }
    is(sWait) {
      when(done) {
        when(write) {
          when(tag - addr(31, 4) < lines) { valid := false.B }
          g.ready := true.B
          state := sIdle
        }.otherwise {
          line := uiLine
          tag := fetch
          valid := true.B
          // A read across two lines keeps the first one's top halfword and fetches the second.
          when(cross && fetch === addr(31, 4)) {
            lo := uiLine(127, 112)
            fetch := fetch + 1.U
            reqT := !reqT
          }.otherwise { state := sHit }
        }
      }
    }
    is(sHit) {
      g.ready := true.B
      state := sIdle
    }
  }

  withClockAndReset(io.uiClock, io.uiReset) {
    val uIdle :: uData :: uCmd :: uRead :: Nil = Enum(4)
    val u     = RegInit(uIdle)
    val r     = ShiftRegister(reqT, 2, false.B, true.B)
    val taken = RegInit(false.B)
    val k     = Reg(UInt(4.W))
    val dT    = RegInit(false.B)
    val rd    = Reg(UInt(128.W))
    doneT := dT
    uiLine := rd

    val lineData = VecInit((0 until 9).map(i => Cat((7 to 0 by -1).map(j => buf(8 * i + j)))))
    val lineMask = VecInit((0 until 9).map(i => ~Cat((7 to 0 by -1).map(j => Fill(2, en(8 * i + j))))))
    val m = io.mig
    m.app_addr     := Cat(Mux(write, addr(31, 4) + k, fetch)(24, 0), 0.U(3.W))
    m.app_cmd      := Mux(write, 0.U, 1.U)
    m.app_en       := u === uCmd
    m.app_wdf_data := lineData(k)
    m.app_wdf_mask := lineMask(k)
    m.app_wdf_wren := u === uData
    m.app_wdf_end  := u === uData

    switch(u) {
      is(uIdle) {
        when(r =/= taken && m.init_calib_complete) {
          taken := r
          k := 0.U
          u := Mux(write, uData, uCmd)
        }
      }
      is(uData) {
        when(m.app_wdf_rdy) { u := uCmd }
      }
      is(uCmd) {
        when(m.app_rdy) {
          when(!write) { u := uRead }
            .elsewhen(k + 1.U === lines) { dT := !dT; u := uIdle }
            .otherwise { k := k + 1.U; u := uData }
        }
      }
      is(uRead) {
        when(m.app_rd_data_valid) { rd := m.app_rd_data; dT := !dT; u := uIdle }
      }
    }
  }
}
