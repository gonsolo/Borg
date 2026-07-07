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

  // Two explicit 16-entry halves (matching ECP5's native TRELLIS_DPR16X4
  // primitive depth) instead of one Mem(32, ...). A single Mem(32, ...)
  // needs yosys's memory_libmap to synthesize the depth-combine (2 halves
  // -> address bit 4 picks one) itself; measured on real hardware this
  // produced a ~189ns wide carry-chain in the write-data routing
  // (regFile.mem_ext), matching in size the CSR-read pathology fixed in
  // Hutt.scala. Splitting explicitly and combining with a plain Chisel
  // Mux keeps the depth-select a single, cheap 2-way pick instead of
  // something yosys has to infer. Each 16-entry half still needs no
  // width-combine logic: xlen/4 DPR16X4 primitives in parallel at the
  // same address, entirely independent of each other.
  val memLo = Mem(16, UInt(xlen.W))
  val memHi = Mem(16, UInt(xlen.W))

  def readPort(addr: UInt): UInt = Mux(addr(4), memHi(addr(3, 0)), memLo(addr(3, 0)))

  io.rs1Data := Mux(io.rs1Addr === 0.U, 0.U, readPort(io.rs1Addr))
  io.rs2Data := Mux(io.rs2Addr === 0.U, 0.U, readPort(io.rs2Addr))

  when(io.wen && io.wAddr =/= 0.U) {
    when(io.wAddr(4)) {
      memHi.write(io.wAddr(3, 0), io.wData)
    }.otherwise {
      memLo.write(io.wAddr(3, 0), io.wData)
    }
  }
}
