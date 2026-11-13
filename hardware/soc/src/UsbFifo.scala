// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

import chisel3._
import chisel3.util._

class UsbFifoPins extends Bundle {
  val dIn   = Input(UInt(8.W))
  val dOut  = Output(UInt(8.W))
  val dOe   = Output(Bool())
  val rxfN  = Input(Bool())
  val txeN  = Input(Bool())
  val rdN   = Output(Bool())
  val wrN   = Output(Bool())
  val oeN   = Output(Bool())
  val siwuN = Output(Bool())
}

class UsbFifoIO extends Bundle {
  val pins = new UsbFifoPins
  val rx   = Decoupled(UInt(8.W))
  val tx   = Flipped(Decoupled(UInt(8.W)))
  val txEnd = Input(Bool())     // the byte on `tx` ends a reply: send it at once
}

/** An FT245-style synchronous FIFO (FT2232H channel A, Digilent's DPTI), in the chip's 60 MHz
  * clock. Every pin is a register; a byte moves at a rising edge that sees RD# (or WR#) and RXF#
  * (or TXE#) low, so a write stays on the bus until it is taken. SIWU# sends a reply at once.
  */
class UsbFifo extends Module {
  val io = IO(new UsbFifoIO)
  val p  = io.pins

  val sIdle :: sTurn :: sRead :: sWrite :: Nil = Enum(4)
  val state = RegInit(sIdle)
  val rdN   = RegInit(true.B)
  val wrN   = RegInit(true.B)
  val oeN   = RegInit(true.B)
  val siwuN = RegInit(true.B)
  val oe    = RegInit(false.B)
  val hold  = Reg(UInt(8.W))
  val held  = RegInit(false.B)
  val end   = RegInit(false.B)
  val rxfN  = RegNext(p.rxfN, true.B)
  val txeN  = RegNext(p.txeN, true.B)
  val din   = RegNext(p.dIn)

  // RD# goes high a cycle after the queue fills up, so keep room for the bytes still coming.
  val q = Module(new Queue(UInt(8.W), 16))
  io.rx <> q.io.deq
  val room = q.io.count < 12.U
  q.io.enq.valid := RegNext(!rdN && !p.rxfN, false.B)
  q.io.enq.bits  := din

  // The chip takes the byte at the edge that ends this cycle.
  val took = !wrN && !p.txeN
  val load = state === sWrite && (!held || took) && io.tx.valid
  io.tx.ready := load
  when(load) { hold := io.tx.bits; end := io.txEnd }
  when(load) { held := true.B }.elsewhen(took) { held := false.B }

  p.dOut  := hold
  p.dOe   := oe
  p.rdN   := rdN
  p.wrN   := wrN
  p.oeN   := oeN
  p.siwuN := siwuN

  siwuN := true.B
  switch(state) {
    is(sIdle) {
      when(io.tx.valid && !txeN) { oe := true.B; state := sWrite }
        .elsewhen(!rxfN && room) { oeN := false.B; state := sTurn }
    }
    is(sTurn) { rdN := false.B; state := sRead }
    is(sRead) {
      when(rxfN || !room) { rdN := true.B; oeN := true.B; state := sIdle }
    }
    is(sWrite) {
      wrN := !((held && !took) || load)
      // Only a reply's end goes out at once; a gap inside one waits for a full USB packet.
      when(!load && (!held || took)) { wrN := true.B; siwuN := !end; oe := false.B; state := sIdle }
    }
  }
}

/** A byte queue between two clocks: Gray-coded pointers through two flops each way. */
class AsyncFifo(width: Int, depthLog2: Int) extends RawModule {
  val enqClock = IO(Input(Clock()))
  val enqReset = IO(Input(Bool()))
  val deqClock = IO(Input(Clock()))
  val deqReset = IO(Input(Bool()))
  val enq      = IO(Flipped(Decoupled(UInt(width.W))))
  val deq      = IO(Decoupled(UInt(width.W)))

  val n = depthLog2
  def gray(b: UInt) = b ^ (b >> 1)
  val mem = withClock(enqClock) { Reg(Vec(1 << n, UInt(width.W))) }

  val wGraySync = Wire(UInt((n + 1).W))
  val rGraySync = Wire(UInt((n + 1).W))
  withClockAndReset(enqClock, enqReset) {
    val w     = RegInit(0.U((n + 1).W))
    val wGray = RegInit(0.U((n + 1).W))
    val r     = ShiftRegister(rGraySync, 2, 0.U, true.B)
    enq.ready := wGray =/= Cat(~r(n, n - 1), r(n - 2, 0))
    when(enq.fire) {
      mem(w(n - 1, 0)) := enq.bits
      w := w + 1.U
      wGray := gray(w + 1.U)
    }
    wGraySync := wGray
  }
  withClockAndReset(deqClock, deqReset) {
    val r     = RegInit(0.U((n + 1).W))
    val rGray = RegInit(0.U((n + 1).W))
    val w     = ShiftRegister(wGraySync, 2, 0.U, true.B)
    deq.valid := rGray =/= w
    deq.bits  := mem(r(n - 1, 0))
    when(deq.fire) {
      r := r + 1.U
      rGray := gray(r + 1.U)
    }
    rGraySync := rGray
  }
}
