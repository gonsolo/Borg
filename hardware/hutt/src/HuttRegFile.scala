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

  // GPR taps for debug sim harnesses only.
  val dbgS1 = Output(UInt(xlen.W))
  val dbgA5 = Output(UInt(xlen.W))
  val dbgS3 = Output(UInt(xlen.W))
  val dbgS5 = Output(UInt(xlen.W))
  val dbgS2 = Output(UInt(xlen.W))
  val dbgA0 = Output(UInt(xlen.W))
  val dbgRa = Output(UInt(xlen.W))
}

/** RISC-V integer register file with x0 hardwired to zero and asynchronous reads. */
class HuttRegFile(val xlen: Int = 32, val hasDebugPorts: Boolean = true) extends Module {
  val io = IO(new HuttRegFileIO(xlen))

  // Two 16-entry halves, ECP5's TRELLIS_DPR16X4 depth: yosys's own depth-combine of a
  // Mem(32) built a long carry chain in the write-data routing.
  val memLo = Mem(16, UInt(xlen.W))
  val memHi = Mem(16, UInt(xlen.W))

  def readPort(addr: UInt): UInt = Mux(addr(4), memHi(addr(3, 0)), memLo(addr(3, 0)))

  io.rs1Data := Mux(io.rs1Addr === 0.U, 0.U, readPort(io.rs1Addr))
  io.rs2Data := Mux(io.rs2Addr === 0.U, 0.U, readPort(io.rs2Addr))
  // Each tap is a read-port mux: tied to 0 without hasDebugPorts.
  io.dbgS1 := (if (hasDebugPorts) readPort(9.U)  else 0.U)
  io.dbgA5 := (if (hasDebugPorts) readPort(15.U) else 0.U)
  io.dbgS3 := (if (hasDebugPorts) readPort(19.U) else 0.U)
  io.dbgS5 := (if (hasDebugPorts) readPort(21.U) else 0.U)
  io.dbgS2 := (if (hasDebugPorts) readPort(18.U) else 0.U)
  io.dbgA0 := (if (hasDebugPorts) readPort(10.U) else 0.U)
  io.dbgRa := (if (hasDebugPorts) readPort(1.U)  else 0.U)

  when(io.wen && io.wAddr =/= 0.U) {
    when(io.wAddr(4)) {
      memHi.write(io.wAddr(3, 0), io.wData)
    }.otherwise {
      memLo.write(io.wAddr(3, 0), io.wData)
    }
  }
}
