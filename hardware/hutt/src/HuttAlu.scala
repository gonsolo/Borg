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

  val baseOps = Seq(
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
  )

  // M extension (MUL/DIV/REM + *W forms) — hardware only exists for xlen=64
  // builds (RV32/ASIC never emits these; see docs/A3_hutt_cpu.md). Required
  // for OpenSBI/Linux: lib/sbi/sbi_init.c unconditionally uses integer
  // divide, and riscv_atomic.c requires the ISA to declare real atomics.
  // Division follows RISC-V's special cases: divide-by-zero returns -1
  // (DIV/DIVU) or the dividend unchanged (REM/REMU); MIN_INT/-1 overflow
  // returns MIN_INT (DIV) or 0 (REM).
  val mulDivOps: Seq[(AluOp.Type, UInt)] = if (xlen == 64) {
    val prodSS = io.a.asSInt * io.b.asSInt                 // 2*xlen-bit signed product
    val prodUU = io.a * io.b                               // 2*xlen-bit unsigned product
    val prodSU = io.a.asSInt * Cat(0.U(1.W), io.b).asSInt  // signed(a) * unsigned(b)

    val minInt      = (-(BigInt(1) << (xlen - 1))).S(xlen.W)
    val divZero     = io.b === 0.U
    val divOverflow = (io.a.asSInt === minInt) && (io.b.asSInt === -1.S(xlen.W))
    val divS = Mux(divZero, -1.S(xlen.W), Mux(divOverflow, minInt, io.a.asSInt / io.b.asSInt))
    val remS = Mux(divZero, io.a.asSInt, Mux(divOverflow, 0.S(xlen.W), io.a.asSInt % io.b.asSInt))
    val divU = Mux(divZero, ((BigInt(1) << xlen) - 1).U(xlen.W), io.a / io.b)
    val remU = Mux(divZero, io.a, io.a % io.b)

    val minIntW      = (-(BigInt(1) << 31)).S(32.W)
    val divZeroW     = bw === 0.U
    val divOverflowW = (aw.asSInt === minIntW) && (bw.asSInt === -1.S(32.W))
    val divSW = Mux(divZeroW, -1.S(32.W), Mux(divOverflowW, minIntW, aw.asSInt / bw.asSInt))
    val remSW = Mux(divZeroW, aw.asSInt, Mux(divOverflowW, 0.S(32.W), aw.asSInt % bw.asSInt))
    val divUW = Mux(divZeroW, ((BigInt(1) << 32) - 1).U(32.W), aw / bw)
    val remUW = Mux(divZeroW, aw, aw % bw)

    Seq(
      AluOp.Mul    -> (io.a * io.b)(xlen - 1, 0),
      AluOp.Mulh   -> prodSS.asUInt(2 * xlen - 1, xlen),
      AluOp.Mulhsu -> prodSU.asUInt(2 * xlen - 1, xlen),
      AluOp.Mulhu  -> prodUU(2 * xlen - 1, xlen),
      AluOp.Div    -> divS.asUInt,
      AluOp.Divu   -> divU,
      AluOp.Rem    -> remS.asUInt,
      AluOp.Remu   -> remU,
      AluOp.MulW   -> sextW((aw * bw)(31, 0)),
      AluOp.DivW   -> sextW(divSW.asUInt),
      AluOp.DivuW  -> sextW(divUW),
      AluOp.RemW   -> sextW(remSW.asUInt),
      AluOp.RemuW  -> sextW(remUW)
    )
  } else {
    Seq.empty
  }

  io.out := MuxLookup(io.op, 0.U(xlen.W))(baseOps ++ mulDivOps)
}

/** Decode `(funct3, funct7)` from an OP-IMM / OP instruction into [[AluOp]].
  *
  *  - `isOpReg = true`  → R-type:  funct7[5] selects SUB vs ADD and SRA vs SRL;
  *                        funct7=0b0000001 selects the M-extension (MUL/DIV/REM
  *                        family) instead — OP-IMM never sets this (there is no
  *                        immediate form of any M-extension op).
  *  - `isOpReg = false` → I-type:  funct7[5] selects SRAI vs SRLI (only for shifts)
  *
  * All other ops ignore funct7[5].
  */
object HuttAluDecode {
  def apply(funct3: UInt, funct7: UInt, isOpReg: Bool, isWord: Bool = false.B): AluOp.Type = {
    val funct7_b5 = funct7(5)
    val isMulDiv  = isOpReg && (funct7 === "b0000001".U)
    val op = Wire(AluOp())
    op := Mux(isWord, AluOp.AddW, AluOp.Add)
    when(isMulDiv) {
      switch(funct3) {
        is("b000".U) { op := Mux(isWord, AluOp.MulW,    AluOp.Mul) }
        is("b001".U) { op := AluOp.Mulh }                              // no *W form
        is("b010".U) { op := AluOp.Mulhsu }                            // no *W form
        is("b011".U) { op := AluOp.Mulhu }                             // no *W form
        is("b100".U) { op := Mux(isWord, AluOp.DivW,    AluOp.Div) }
        is("b101".U) { op := Mux(isWord, AluOp.DivuW,   AluOp.Divu) }
        is("b110".U) { op := Mux(isWord, AluOp.RemW,    AluOp.Rem) }
        is("b111".U) { op := Mux(isWord, AluOp.RemuW,   AluOp.Remu) }
      }
    }.otherwise {
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
