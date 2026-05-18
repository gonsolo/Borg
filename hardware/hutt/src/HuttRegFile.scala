// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

class HuttRegFileIO extends Bundle {
  val rs1Addr = Input(UInt(5.W))
  val rs2Addr = Input(UInt(5.W))
  val rs1Data = Output(UInt(32.W))
  val rs2Data = Output(UInt(32.W))

  val wen   = Input(Bool())
  val wAddr = Input(UInt(5.W))
  val wData = Input(UInt(32.W))
}

/** 32-entry x 32-bit RISC-V integer register file.
  *
  * x0 is hardwired to zero: writes are silently dropped, reads always return 0.
  * Both read ports are asynchronous; write happens on rising clock when `wen`.
  */
class HuttRegFile extends Module {
  val io = IO(new HuttRegFileIO)

  val regs = Reg(Vec(32, UInt(32.W)))

  io.rs1Data := Mux(io.rs1Addr === 0.U, 0.U, regs(io.rs1Addr))
  io.rs2Data := Mux(io.rs2Addr === 0.U, 0.U, regs(io.rs2Addr))

  when(io.wen && io.wAddr =/= 0.U) {
    regs(io.wAddr) := io.wData
  }
}
