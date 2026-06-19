// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

class HuttAluIO(val xlen: Int = 32) extends Bundle {
  val op  = Input(AluOp())
  val a   = Input(UInt(xlen.W))
  val b   = Input(UInt(xlen.W))
  val out = Output(UInt(xlen.W))
}

/** Purely combinational RV32I/RV64I ALU.
  *
  * Full-width shifts use `b[log2(xlen)-1:0]` (b[4:0] for RV32, b[5:0] for RV64).
  * The `*W` ops (RV64 word forms: ADDW/SUBW/SLLW/SRLW/SRAW and the I-immediate
  * variants) operate on the low 32 bits and sign-extend the 32-bit result back to
  * XLEN; their shift amount is `b[4:0]`.  On RV32 (xlen=32) the `*W` ops are never
  * selected by the decoder, so they are inert.
  */
class HuttAlu(val xlen: Int = 32) extends Module {
  val io = IO(new HuttAluIO(xlen))

  val shamt  = io.b(log2Ceil(xlen) - 1, 0)   // full-width shift amount
  val shamtW = io.b(4, 0)                     // 32-bit (word) shift amount

  // 32-bit word results, sign-extended to XLEN (for RV64 *W instructions).
  def sextW(x: UInt): UInt =
    if (xlen <= 32) x(xlen - 1, 0) else Cat(Fill(xlen - 32, x(31)), x(31, 0))
  val aw = io.a(31, 0)
  val bw = io.b(31, 0)

  io.out := MuxLookup(io.op, 0.U(xlen.W))(Seq(
    AluOp.Add  -> (io.a + io.b),
    AluOp.Sub  -> (io.a - io.b),
    AluOp.Sll  -> (io.a << shamt)(xlen - 1, 0),
    AluOp.Slt  -> Mux(io.a.asSInt < io.b.asSInt, 1.U(xlen.W), 0.U(xlen.W)),
    AluOp.Sltu -> Mux(io.a < io.b,               1.U(xlen.W), 0.U(xlen.W)),
    AluOp.Xor  -> (io.a ^ io.b),
    AluOp.Srl  -> (io.a >> shamt),
    AluOp.Sra  -> (io.a.asSInt >> shamt).asUInt,
    AluOp.Or   -> (io.a | io.b),
    AluOp.And  -> (io.a & io.b),
    // RV64 word ops (sign-extended 32-bit results).
    AluOp.AddW -> sextW(aw + bw),
    AluOp.SubW -> sextW(aw - bw),
    AluOp.SllW -> sextW((aw << shamtW)(31, 0)),
    AluOp.SrlW -> sextW(aw >> shamtW),
    AluOp.SraW -> sextW((aw.asSInt >> shamtW).asUInt)
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
  def apply(funct3: UInt, funct7_b5: Bool, isOpReg: Bool, isWord: Bool = false.B): AluOp.Type = {
    val op = Wire(AluOp())
    op := Mux(isWord, AluOp.AddW, AluOp.Add)
    switch(funct3) {
      is("b000".U) { op := Mux(isWord,
                               Mux(isOpReg && funct7_b5, AluOp.SubW, AluOp.AddW),
                               Mux(isOpReg && funct7_b5, AluOp.Sub,  AluOp.Add)) }
      is("b001".U) { op := Mux(isWord, AluOp.SllW, AluOp.Sll) }
      is("b010".U) { op := AluOp.Slt }
      is("b011".U) { op := AluOp.Sltu }
      is("b100".U) { op := AluOp.Xor }
      is("b101".U) { op := Mux(isWord,
                               Mux(funct7_b5, AluOp.SraW, AluOp.SrlW),
                               Mux(funct7_b5, AluOp.Sra,  AluOp.Srl)) }
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
