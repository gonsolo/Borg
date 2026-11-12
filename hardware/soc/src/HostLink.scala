// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._
import borg.GpuMemIO
import hutt.HuttBus

/** The direct simulator's protocol (`simulation/direct/main.cpp`) in hardware: a byte stream from
  * the host drives Borg's register bus and the memory, so borgvk's device path (`BORGVK_HW`) runs
  * on a board as on `direct_sim`. Words are little-endian.
  *
  * {{{
  *   'W' n (off val)*n   register writes      'R' off -> val
  *   'M' off len data    memory write         'm' off len -> data
  *   'P' off mask val    -> val once (reg & mask) == val, or after 200M cycles
  *   'S' -> 1            'D' -> 0 (no pass cache)
  * }}}
  */
class HostLinkIO extends Bundle {
  val in   = Flipped(Decoupled(UInt(8.W)))
  val out  = Decoupled(UInt(8.W))
  val mmio = new HuttBus(10)
  val mem  = new GpuMemIO
}

class HostLink extends Module {
  val io = IO(new HostLinkIO)

  val sCmd :: sArgs :: sExec :: sMmio :: sMmioResp :: sSend :: sData :: sBurstGo :: sBurst :: sRead :: Nil = Enum(10)
  val state = RegInit(sCmd)
  val cmd   = Reg(UInt(8.W))
  val args  = Reg(Vec(3, UInt(32.W)))
  val arg   = Reg(UInt(2.W))
  val nargs = Reg(UInt(2.W))
  val nbyte = RegInit(0.U(2.W))
  val word  = Reg(UInt(32.W))
  val count = Reg(UInt(32.W))
  val addr  = Reg(UInt(32.W))
  val base  = Reg(UInt(32.W))
  val tx    = Reg(UInt(32.W))
  val ntx   = Reg(UInt(3.W))
  val after = Reg(UInt(4.W))
  val timer = Reg(UInt(28.W))
  val wlen  = Reg(UInt(7.W))
  val half  = RegInit(false.B)

  val q = Module(new Queue(UInt(32.W), 32))
  val full = Cat(io.in.bits, word(31, 8))
  def cmdIs(c: Char) = cmd === c.toInt.U
  def send(v: UInt, n: Int, next: UInt) = { tx := v; ntx := n.U; after := next; state := sSend }

  io.in.ready := state === sCmd || state === sArgs || (state === sData && (nbyte =/= 3.U || q.io.enq.ready))
  io.out.valid := state === sSend
  io.out.bits := tx(7, 0)

  io.mmio.req.valid      := state === sMmio
  io.mmio.req.bits.addr  := Mux(cmdIs('W'), args(1), args(0))
  io.mmio.req.bits.write := cmdIs('W')
  io.mmio.req.bits.size  := 2.U
  io.mmio.req.bits.data  := args(2)
  io.mmio.resp.ready     := state === sMmioResp

  io.mem.addr  := Mux(state === sRead, addr, base)
  io.mem.req   := state === sRead
  io.mem.wr    := state === sBurst
  io.mem.wlen  := wlen
  io.mem.wdata := Mux(half, q.io.deq.bits(31, 16), q.io.deq.bits(15, 0))
  q.io.enq.valid := state === sData && io.in.valid && nbyte === 3.U
  q.io.enq.bits  := full
  q.io.deq.ready := state === sBurst && ((io.mem.waccept && half) || io.mem.ready)

  switch(state) {
    is(sCmd) {
      when(io.in.fire) {
        cmd := io.in.bits
        arg := 0.U
        nargs := MuxLookup(io.in.bits, 0.U)(Seq('W'.toInt.U -> 1.U, 'R'.toInt.U -> 1.U, 'M'.toInt.U -> 2.U,
          'm'.toInt.U -> 2.U, 'P'.toInt.U -> 3.U))
        state := MuxLookup(io.in.bits, sCmd)(Seq('W'.toInt.U -> sArgs, 'R'.toInt.U -> sArgs, 'M'.toInt.U -> sArgs,
          'm'.toInt.U -> sArgs, 'P'.toInt.U -> sArgs, 'S'.toInt.U -> sExec, 'D'.toInt.U -> sExec))
      }
    }
    is(sArgs) {
      when(io.in.fire) {
        word := full
        nbyte := nbyte + 1.U
        when(nbyte === 3.U) {
          args(arg) := full
          arg := arg + 1.U
          // W's pairs arrive as args(1), args(2).
          when(arg + 1.U === nargs) { state := Mux(cmdIs('W') && nargs === 3.U, sMmio, sExec) }
        }
      }
    }
    is(sExec) {
      timer := 0.U
      when(cmdIs('W')) {
        count := args(0)
        arg := 1.U
        nargs := 3.U
        state := Mux(args(0) === 0.U, sCmd, sArgs)
      }.elsewhen(cmdIs('R') || cmdIs('P')) {
        state := sMmio
      }.elsewhen(cmdIs('M')) {
        addr := args(0); base := args(0); count := args(1)
        state := Mux(args(1) === 0.U, sCmd, sData)
      }.elsewhen(cmdIs('m')) {
        addr := args(0); count := args(1)
        state := Mux(args(1) === 0.U, sCmd, sRead)
      }.elsewhen(cmdIs('S')) {
        send(1.U, 1, sCmd)
      }.otherwise {
        send(0.U, 4, sCmd)
      }
    }
    is(sMmio) {
      timer := timer + 1.U
      when(io.mmio.req.fire) { state := sMmioResp }
    }
    is(sMmioResp) {
      timer := timer + 1.U
      when(io.mmio.resp.fire) {
        val v = io.mmio.resp.bits
        when(cmdIs('W')) {
          count := count - 1.U
          arg := 1.U
          state := Mux(count === 1.U, sCmd, sArgs)
        }.elsewhen(cmdIs('R') || (v & args(1)) === args(2) || timer >= 200000000.U) {
          send(v, 4, sCmd)
        }.otherwise {
          state := sMmio
        }
      }
    }
    is(sSend) {
      when(io.out.fire) {
        tx := tx >> 8
        ntx := ntx - 1.U
        when(ntx === 1.U) { state := after }
      }
    }
    is(sData) {
      when(io.in.fire) {
        word := full
        nbyte := nbyte + 1.U
        when(nbyte === 3.U) {
          count := count - 4.U
          addr := addr + 4.U
          // A burst never crosses 128 bytes, the queue's size.
          when(count === 4.U || (addr + 4.U)(6, 0) === 0.U) { state := sBurstGo }
        }
      }
    }
    is(sBurstGo) {
      wlen := q.io.count << 1
      state := sBurst
    }
    is(sBurst) {
      when(io.mem.waccept) { half := !half }
      when(io.mem.ready) {
        half := false.B
        base := addr
        state := Mux(count === 0.U, sCmd, sData)
      }
    }
    is(sRead) {
      when(io.mem.ready) {
        addr := addr + 4.U
        count := count - 4.U
        send(io.mem.data, 4, Mux(count === 4.U, sCmd, sRead))
      }
    }
  }
}

/** Masters in priority order onto one memory port; a grant lasts until the port's `ready`. */
class GpuMemArbiterIO(n: Int) extends Bundle {
  val in  = Vec(n, Flipped(new GpuMemIO))
  val out = new GpuMemIO
}

class GpuMemArbiter(n: Int) extends Module {
  val io = IO(new GpuMemArbiterIO(n))
  val busy  = RegInit(false.B)
  val sel   = Reg(UInt(log2Ceil(n).W))
  val wants = VecInit(io.in.map(m => m.req || m.wr))
  val cur   = Mux(busy, sel, PriorityEncoder(wants))
  when(!busy && wants.asUInt.orR) { busy := true.B; sel := cur }
  when(io.out.ready) { busy := false.B }

  val m = io.in(cur)
  io.out.addr  := m.addr
  io.out.req   := m.req
  io.out.wr    := m.wr
  io.out.wdata := m.wdata
  io.out.wlen  := m.wlen
  for ((s, i) <- io.in.zipWithIndex) {
    s.data    := io.out.data
    s.ready   := io.out.ready && cur === i.U
    s.waccept := io.out.waccept && cur === i.U
  }
}
