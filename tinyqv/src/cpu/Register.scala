// Copyright Michael Bell 2024
// CERN-OHL-S-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class TinyQVRegisters(val numRegs: Int = 16, val regAddrBits: Int = 4) extends RawModule {
  override val desiredName = "tinyqv_registers"
  
  val clk = IO(Input(Clock()))
  val rstn = IO(Input(Bool()))
  val wr_en = IO(Input(Bool()))
  val counter = IO(Input(UInt(3.W)))
  val rs1 = IO(Input(UInt(regAddrBits.W)))
  val rs2 = IO(Input(UInt(regAddrBits.W)))
  val rd = IO(Input(UInt(regAddrBits.W)))
  val data_rs1 = IO(Output(UInt(4.W)))
  val data_rs2 = IO(Output(UInt(4.W)))
  val data_rd = IO(Input(UInt(4.W)))
  val return_addr = IO(Output(UInt(23.W)))

  withClockAndReset(clk, !rstn) {
    // Registers 1 to numRegs-1. x0 is hardcoded 0.
    // We create numRegs registers, though x0, x3 (gp), and x4 (tp) won't be used as storage registers
    // for reg_access, but we keep them to match the array structure and potentially return_addr.
    val registers = Reg(Vec(numRegs, UInt(32.W)))

    val reg_access = Wire(Vec(1 << regAddrBits, UInt(4.W)))

    for (i <- 0 until (1 << regAddrBits)) {
      if (i == 0 || i >= numRegs) {
        reg_access(i) := 0.U
      } else if (i == 3) {
        // gp is hardcoded to 0x01000400
        reg_access(i) := Cat(0.B, counter === 2.U, 0.B, counter === 6.U)
      } else if (i == 4) {
        // tp is hardcoded to 0x08000000
        reg_access(i) := Cat(counter === 6.U, 0.U(3.W))
      } else {
        // Normal register rotation
        val low_nibble = Mux(wr_en && (rd === i.U), data_rd, registers(i)(7, 4))
        registers(i) := Cat(registers(i)(3, 0), registers(i)(31, 8), low_nibble)
        reg_access(i) := registers(i)(7, 4)
      }
    }

    data_rs1 := reg_access(rs1)
    data_rs2 := reg_access(rs2)
    return_addr := registers(1)(31, 9)
  }
}
