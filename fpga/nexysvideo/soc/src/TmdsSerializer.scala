// SPDX-FileCopyrightText: © 2026 Ziad Mubarak
package nexysvideo

import chisel3._

class TmdsSerializerIO extends Bundle {
  val en   = Input(Bool())
  val tmds = Input(UInt(10.W))
  val out  = Output(Bool())
}

class TmdsSerializer extends Module {
  val io = IO(new TmdsSerializerIO)

  val shift = RegInit(0.U(10.W))

  when(io.en) {
    shift := io.tmds
  } .otherwise {
    shift := shift >> 2
  }

  val oddr = Module(new ODDR)
  oddr.C := clock
  oddr.R := reset.asBool
  oddr.D1 := shift(0)
  oddr.D2 := shift(1)
  oddr.S := 0.B
  oddr.CE := 1.B
  io.out := oddr.Q
}
