// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class TinyQVRegistersIO(val regAddrBits: Int) extends Bundle {
  val wr_en = Input(Bool())
  val counter = Input(UInt(3.W))
  val rs1 = Input(UInt(regAddrBits.W))
  val rs2 = Input(UInt(regAddrBits.W))
  val rd = Input(UInt(regAddrBits.W))
  val data_rs1 = Output(UInt(4.W))
  val data_rs2 = Output(UInt(4.W))
  val data_rd = Input(UInt(4.W))
  val return_addr = Output(UInt(23.W))
}

class TinyQVRegisters(val numRegs: Int = 16, val regAddrBits: Int = 4) extends Module {
  val io = IO(new TinyQVRegistersIO(regAddrBits))

    val registers = RegInit(VecInit(Seq.fill(numRegs)(0.U(32.W))))

    val reg_access = Wire(Vec(1 << regAddrBits, UInt(4.W)))

    for (i <- 0 until (1 << regAddrBits)) {
      if (i == 0 || i >= numRegs) {
        reg_access(i) := 0.U
      } else if (i == 3) {
        reg_access(i) := Cat(0.B, io.counter === 2.U, 0.B, io.counter === 6.U)
      } else if (i == 4) {
        reg_access(i) := Cat(io.counter === 6.U, 0.U(3.W))
      } else {
        val low_nibble = Mux(io.wr_en && (io.rd === i.U), io.data_rd, registers(i)(7, 4))
        registers(i) := Cat(registers(i)(3, 0), registers(i)(31, 8), low_nibble)
        reg_access(i) := registers(i)(7, 4)
      }
    }

    io.data_rs1 := reg_access(io.rs1)
    io.data_rs2 := reg_access(io.rs2)
    io.return_addr := registers(1)(31, 9)
}
