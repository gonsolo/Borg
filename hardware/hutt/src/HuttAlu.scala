// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

class HuttAluIO extends Bundle {
  val op  = Input(AluOp())
  val a   = Input(UInt(32.W))
  val b   = Input(UInt(32.W))
  val out = Output(UInt(32.W))
}

/** Purely combinational RV32I ALU.
  *
  * Shift amount uses `b[4:0]` as required by the spec; `Sra` does an arithmetic
  * right shift on the signed interpretation of `a`.
  */
class HuttAlu extends Module {
  val io = IO(new HuttAluIO)

  val shamt = io.b(4, 0)

  io.out := MuxLookup(io.op, 0.U(32.W))(Seq(
    AluOp.Add  -> (io.a + io.b),
    AluOp.Sub  -> (io.a - io.b),
    AluOp.Sll  -> (io.a << shamt)(31, 0),
    AluOp.Slt  -> Mux(io.a.asSInt < io.b.asSInt, 1.U(32.W), 0.U(32.W)),
    AluOp.Sltu -> Mux(io.a < io.b,               1.U(32.W), 0.U(32.W)),
    AluOp.Xor  -> (io.a ^ io.b),
    AluOp.Srl  -> (io.a >> shamt),
    AluOp.Sra  -> (io.a.asSInt >> shamt).asUInt,
    AluOp.Or   -> (io.a | io.b),
    AluOp.And  -> (io.a & io.b)
  ))
}

/** Decode `(funct3, funct7[5])` from an OP-IMM / OP instruction into [[AluOp]].
  *
  *  - `isOpReg = true`  → R-type:  funct7[5] selects SUB vs ADD and SRA vs SRL
  *  - `isOpReg = false` → I-type:  funct7[5] selects SRAI vs SRLI (only for shifts)
  *
  * All other ops ignore funct7[5].
  */
object HuttAluDecode {
  def apply(funct3: UInt, funct7_b5: Bool, isOpReg: Bool): AluOp.Type = {
    val op = Wire(AluOp())
    op := AluOp.Add
    switch(funct3) {
      is("b000".U) { op := Mux(isOpReg && funct7_b5, AluOp.Sub, AluOp.Add) }
      is("b001".U) { op := AluOp.Sll }
      is("b010".U) { op := AluOp.Slt }
      is("b011".U) { op := AluOp.Sltu }
      is("b100".U) { op := AluOp.Xor }
      is("b101".U) { op := Mux(funct7_b5, AluOp.Sra, AluOp.Srl) }
      is("b110".U) { op := AluOp.Or }
      is("b111".U) { op := AluOp.And }
    }
    op
  }
}

/** Branch condition evaluator for BEQ/BNE/BLT/BGE/BLTU/BGEU.
  *
  * funct3 encoding (per RV32I):
  *   - 000 BEQ, 001 BNE, 100 BLT, 101 BGE, 110 BLTU, 111 BGEU
  */
object HuttBranchTaken {
  def apply(funct3: UInt, a: UInt, b: UInt): Bool = {
    val eq  = a === b
    val lts = a.asSInt < b.asSInt
    val ltu = a < b
    val taken = WireDefault(false.B)
    switch(funct3) {
      is("b000".U) { taken := eq }
      is("b001".U) { taken := !eq }
      is("b100".U) { taken := lts }
      is("b101".U) { taken := !lts }
      is("b110".U) { taken := ltu }
      is("b111".U) { taken := !ltu }
    }
    taken
  }
}
