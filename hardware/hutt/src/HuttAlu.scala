// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._
import HuttOpcodes._

class HuttAluIO(val xlen: Int = 32) extends Bundle {
  val op  = Input(AluOp())
  val a   = Input(UInt(xlen.W))
  val b   = Input(UInt(xlen.W))
  val out = Output(UInt(xlen.W))
  // Divide handshake: drive divStart and wait for divDone; every other op is combinational.
  val divStart = Input(Bool())
  val divBusy  = Output(Bool())
  val divDone  = Output(Bool())
}

/** RV32I/RV64I ALU. Divides are multi-cycle (HuttDivider): a combinational 64-bit
  * divider would be a large part of the whole SoC.
  *
  * The `*W` ops work on the low 32 bits, shift by `b[4:0]`, and sign-extend the
  * result; RV32 never selects them.
  */
class HuttAlu(val xlen: Int = 32) extends Module {
  val io = IO(new HuttAluIO(xlen))

  val shamt  = io.b(log2Ceil(xlen) - 1, 0)   // full-width shift amount
  val shamtW = io.b(4, 0)                     // 32-bit (word) shift amount

  // Sign-extend a 32-bit word result to XLEN.
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
    AluOp.AddW -> sextW(aw + bw),
    AluOp.SubW -> sextW(aw - bw),
    AluOp.SllW -> sextW((aw << shamtW)(31, 0)),
    AluOp.SrlW -> sextW(aw >> shamtW),
    AluOp.SraW -> sextW((aw.asSInt >> shamtW).asUInt)
  )

  io.divBusy := false.B
  io.divDone := false.B

  // M extension, xlen=64 only; OpenSBI needs integer divide.
  val mulDivOps: Seq[(AluOp.Type, UInt)] = if (xlen == 64) {
    // One shared (xlen+1)-bit signed multiplier for all four MUL ops; extending each
    // operand by a sign or zero bit is exact, and MUL's low bits ignore signedness.
    val aSigned = io.op =/= AluOp.Mulhu
    val bSigned = io.op =/= AluOp.Mulhu && io.op =/= AluOp.Mulhsu
    val aExt = Cat(Mux(aSigned, io.a(xlen - 1), 0.U(1.W)), io.a).asSInt
    val bExt = Cat(Mux(bSigned, io.b(xlen - 1), 0.U(1.W)), io.b).asSInt
    val prod = (aExt * bExt).asUInt  // 2*(xlen+1) bits

    val isWordOp = io.op === AluOp.DivW || io.op === AluOp.DivuW ||
                    io.op === AluOp.RemW || io.op === AluOp.RemuW
    val isSignedOp = io.op === AluOp.Div || io.op === AluOp.DivW ||
                      io.op === AluOp.Rem || io.op === AluOp.RemW
    val isRemOp = io.op === AluOp.Rem || io.op === AluOp.Remu ||
                  io.op === AluOp.RemW || io.op === AluOp.RemuW

    // Sign-extended for signed *W ops so negation gives the right 32-bit magnitude.
    val opA64 = Mux(isWordOp, Mux(isSignedOp, sextW(aw), Cat(0.U(32.W), aw)), io.a)
    val opB64 = Mux(isWordOp, Mux(isSignedOp, sextW(bw), Cat(0.U(32.W), bw)), io.b)
    val negA  = isSignedOp && opA64(xlen - 1)
    val negB  = isSignedOp && opB64(xlen - 1)
    val magA  = Mux(negA, -opA64, opA64)
    val magB  = Mux(negB, -opB64, opB64)

    val divider = Module(new HuttDivider(xlen))
    divider.io.start    := io.divStart
    divider.io.dividend := magA
    divider.io.divisor  := magB
    io.divBusy := divider.io.busy
    io.divDone := divider.io.done

    // RISC-V special cases: divide by zero, and MIN_INT/-1 at the op's width.
    val divZero = opB64 === 0.U
    val divOverflowFull = isSignedOp && !isWordOp &&
      (opA64 === (BigInt(1) << (xlen - 1)).U(xlen.W)) && (opB64 === ~(0.U(xlen.W)))
    val divOverflowWord = isSignedOp && isWordOp &&
      (opA64(31, 0) === (BigInt(1) << 31).U(32.W)) && (opB64(31, 0) === ~(0.U(32.W)))
    val divOverflow = Mux(isWordOp, divOverflowWord, divOverflowFull)

    // Truncating division: the remainder takes the dividend's sign.
    val quoNeg = isSignedOp && (negA =/= negB)
    val remNeg = isSignedOp && negA
    val rawQuotient  = Mux(quoNeg, -divider.io.quotient,  divider.io.quotient)
    val rawRemainder = Mux(remNeg, -divider.io.remainder, divider.io.remainder)

    // MIN_INT at the op's width: bit 31 for *W, which divResultOut sign-extends.
    val minIntPattern = Mux(isWordOp, (BigInt(1) << 31).U(xlen.W), (BigInt(1) << (xlen - 1)).U(xlen.W))
    val divResult = Mux(isRemOp,
      Mux(divZero, opA64, Mux(divOverflow, 0.U(xlen.W), rawRemainder)),
      Mux(divZero, ~(0.U(xlen.W)), Mux(divOverflow, minIntPattern, rawQuotient))
    )
    // *W results are always sign-extended, even for unsigned ops.
    val divResultOut = Mux(isWordOp, sextW(divResult(31, 0)), divResult)

    Seq(
      AluOp.Mul    -> prod(xlen - 1, 0),
      AluOp.Mulh   -> prod(2 * xlen - 1, xlen),
      AluOp.Mulhsu -> prod(2 * xlen - 1, xlen),
      AluOp.Mulhu  -> prod(2 * xlen - 1, xlen),
      AluOp.Div    -> divResultOut,
      AluOp.Divu   -> divResultOut,
      AluOp.Rem    -> divResultOut,
      AluOp.Remu   -> divResultOut,
      AluOp.MulW   -> sextW((aw * bw)(31, 0)),
      AluOp.DivW   -> divResultOut,
      AluOp.DivuW  -> divResultOut,
      AluOp.RemW   -> divResultOut,
      AluOp.RemuW  -> divResultOut
    )
  } else {
    Seq.empty
  }

  io.out := MuxLookup(io.op, 0.U(xlen.W))(baseOps ++ mulDivOps)
}

/** Decode `(funct3, funct7)` from an OP-IMM / OP instruction into [[AluOp]].
  *
  *  - `isOpReg = true`: R-type; funct7[5] selects SUB/SRA, funct7=0b0000001 the M extension.
  *  - `isOpReg = false`: I-type; funct7[5] selects SRAI vs SRLI.
  */
object HuttAluDecode {
  def apply(funct3: UInt, funct7: UInt, isOpReg: Bool, isWord: Bool = false.B): AluOp.Type = {
    val funct7_b5 = funct7(5)
    val isMulDiv  = isOpReg && (funct7 === FUNCT7_MUL.U(7.W))
    val op = Wire(AluOp())
    op := Mux(isWord, AluOp.AddW, AluOp.Add)
    when(isMulDiv) {
      switch(funct3) {
        is(FUNCT3_MUL.U) { op := Mux(isWord, AluOp.MulW,    AluOp.Mul) }
        is(FUNCT3_MULH.U) { op := AluOp.Mulh }                              // no *W form
        is(FUNCT3_MULHSU.U) { op := AluOp.Mulhsu }                            // no *W form
        is(FUNCT3_MULHU.U) { op := AluOp.Mulhu }                             // no *W form
        is(FUNCT3_DIV.U) { op := Mux(isWord, AluOp.DivW,    AluOp.Div) }
        is(FUNCT3_DIVU.U) { op := Mux(isWord, AluOp.DivuW,   AluOp.Divu) }
        is(FUNCT3_REM.U) { op := Mux(isWord, AluOp.RemW,    AluOp.Rem) }
        is(FUNCT3_REMU.U) { op := Mux(isWord, AluOp.RemuW,   AluOp.Remu) }
      }
    }.otherwise {
      switch(funct3) {
        is(FUNCT3_ADD.U) { op := Mux(isWord,
                                 Mux(isOpReg && funct7_b5, AluOp.SubW, AluOp.AddW),
                                 Mux(isOpReg && funct7_b5, AluOp.Sub,  AluOp.Add)) }
        is(FUNCT3_SLL.U) { op := Mux(isWord, AluOp.SllW, AluOp.Sll) }
        is(FUNCT3_SLT.U) { op := AluOp.Slt }
        is(FUNCT3_SLTU.U) { op := AluOp.Sltu }
        is(FUNCT3_XOR.U) { op := AluOp.Xor }
        is(FUNCT3_SRL.U) { op := Mux(isWord,
                                 Mux(funct7_b5, AluOp.SraW, AluOp.SrlW),
                                 Mux(funct7_b5, AluOp.Sra,  AluOp.Srl)) }
        is(FUNCT3_OR.U) { op := AluOp.Or }
        is(FUNCT3_AND.U) { op := AluOp.And }
      }
    }
    op
  }
}
