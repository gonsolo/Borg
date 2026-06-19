// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

class HuttRegFileIO(val xlen: Int = 32) extends Bundle {
  val rs1Addr = Input(UInt(5.W))
  val rs2Addr = Input(UInt(5.W))
  val rs1Data = Output(UInt(xlen.W))
  val rs2Data = Output(UInt(xlen.W))

  val wen   = Input(Bool())
  val wAddr = Input(UInt(5.W))
  val wData = Input(UInt(xlen.W))
}

/** 32-entry x XLEN-bit RISC-V integer register file (RV32I: xlen=32, RV64I: 64).
  *
  * x0 is hardwired to zero: writes are silently dropped, reads always return 0.
  * Both read ports are asynchronous; write happens on rising clock when `wen`.
  */
class HuttRegFile(val xlen: Int = 32) extends Module {
  val io = IO(new HuttRegFileIO(xlen))

  val regs = RegInit(VecInit(Seq.fill(32)(0.U(xlen.W))))

  io.rs1Data := Mux(io.rs1Addr === 0.U, 0.U, regs(io.rs1Addr))
  io.rs2Data := Mux(io.rs2Addr === 0.U, 0.U, regs(io.rs2Addr))

  // Explicit per-register writes force firtool to emit equality comparisons for
  // all addresses, avoiding the all-bits-AND shorthand it generates for x31 with
  // a Vec write, which Yosys synthesizes incorrectly on ECP5.
  for (i <- 1 until 32) {
    when(io.wen && io.wAddr === i.U) {
      regs(i) := io.wData
    }
  }
}
