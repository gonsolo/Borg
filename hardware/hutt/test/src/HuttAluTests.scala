// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3.{assert => _, test => _, _}
import chisel3.simulator.EphemeralSimulator._
import utest._

object HuttAluTests extends TestSuite {
  val XLEN = 64
  val MASK64 = (BigInt(1) << 64) - 1
  val MASK32 = (BigInt(1) << 32) - 1

  // Two's-complement encode a (possibly negative) mathematical BigInt into
  // its `bits`-wide unsigned bit pattern.
  def enc(v: BigInt, bits: Int): BigInt = v & ((BigInt(1) << bits) - 1)

  val DIV_OPS: Set[AluOp.Type] = Set(
    AluOp.Div, AluOp.Divu, AluOp.Rem, AluOp.Remu,
    AluOp.DivW, AluOp.DivuW, AluOp.RemW, AluOp.RemuW
  )

  def run(dut: HuttAlu, op: AluOp.Type, a: BigInt, b: BigInt): BigInt = {
    dut.io.op.poke(op)
    dut.io.a.poke(a.U(XLEN.W))
    dut.io.b.poke(b.U(XLEN.W))
    if (DIV_OPS.contains(op)) {
      dut.io.divStart.poke(true.B)
      dut.clock.step(1)
      dut.io.divStart.poke(false.B)
      var steps = 0
      while (!dut.io.divDone.peek().litToBoolean) {
        dut.clock.step(1)
        steps += 1
        if (steps > XLEN + 4) throw new RuntimeException(s"$op never asserted divDone")
      }
    }
    dut.io.out.peek().litValue
  }

  val tests = Tests {
    test("DIV/DIVU/REM/REMU basic signed and unsigned") {
      simulate(new HuttAlu(XLEN)) { dut =>
        assert(run(dut, AluOp.Div, 100, 7) == 14)
        assert(run(dut, AluOp.Rem, 100, 7) == 2)
        assert(run(dut, AluOp.Divu, 100, 7) == 14)
        assert(run(dut, AluOp.Remu, 100, 7) == 2)
      }
    }

    test("DIV/REM with negative operands (signed truncating division)") {
      simulate(new HuttAlu(XLEN)) { dut =>
        // -7 / 2 = -3 rem -1 (RISC-V: truncating toward zero, rem takes dividend's sign)
        assert(run(dut, AluOp.Div, enc(-7, XLEN), enc(2, XLEN)) == enc(-3, XLEN))
        assert(run(dut, AluOp.Rem, enc(-7, XLEN), enc(2, XLEN)) == enc(-1, XLEN))
        // 7 / -2 = -3 rem 1
        assert(run(dut, AluOp.Div, enc(7, XLEN), enc(-2, XLEN)) == enc(-3, XLEN))
        assert(run(dut, AluOp.Rem, enc(7, XLEN), enc(-2, XLEN)) == enc(1, XLEN))
        // -7 / -2 = 3 rem -1
        assert(run(dut, AluOp.Div, enc(-7, XLEN), enc(-2, XLEN)) == enc(3, XLEN))
        assert(run(dut, AluOp.Rem, enc(-7, XLEN), enc(-2, XLEN)) == enc(-1, XLEN))
      }
    }

    test("DIVU/REMU treat operands as unsigned even with high bit set") {
      simulate(new HuttAlu(XLEN)) { dut =>
        val big = enc(-1, XLEN)  // all-ones: huge unsigned value, -1 signed
        assert(run(dut, AluOp.Divu, big, 2) == big / 2)
        assert(run(dut, AluOp.Remu, big, 2) == big % 2)
      }
    }

    test("divide by zero: DIV/DIVU return -1, REM/REMU return dividend") {
      simulate(new HuttAlu(XLEN)) { dut =>
        assert(run(dut, AluOp.Div, 42, 0) == MASK64)
        assert(run(dut, AluOp.Divu, 42, 0) == MASK64)
        assert(run(dut, AluOp.Rem, 42, 0) == 42)
        assert(run(dut, AluOp.Remu, 42, 0) == 42)
        // Also with a negative dividend.
        assert(run(dut, AluOp.Div, enc(-5, XLEN), 0) == MASK64)
        assert(run(dut, AluOp.Rem, enc(-5, XLEN), 0) == enc(-5, XLEN))
      }
    }

    test("signed overflow: MIN_INT / -1 returns MIN_INT, remainder 0") {
      simulate(new HuttAlu(XLEN)) { dut =>
        val minInt64 = BigInt(1) << 63
        assert(run(dut, AluOp.Div, minInt64, MASK64) == minInt64)
        assert(run(dut, AluOp.Rem, minInt64, MASK64) == 0)
      }
    }

    test("DIVW/REMW word forms sign-extend from bit 31") {
      simulate(new HuttAlu(XLEN)) { dut =>
        // -7 / 2 = -3 rem -1, as 32-bit ops, sign-extended to 64 bits.
        assert(run(dut, AluOp.DivW, enc(-7, 32), enc(2, 32)) == enc(-3, XLEN))
        assert(run(dut, AluOp.RemW, enc(-7, 32), enc(2, 32)) == enc(-1, XLEN))
      }
    }

    test("DIVUW/REMUW treat the low 32 bits as unsigned, ignore upper 32") {
      simulate(new HuttAlu(XLEN)) { dut =>
        // Upper 32 bits of the 64-bit operand must be ignored for *W forms.
        val aWithGarbageUpper = (BigInt("DEADBEEF", 16) << 32) | 100
        assert(run(dut, AluOp.DivuW, aWithGarbageUpper, 7) == 14)
        assert(run(dut, AluOp.RemuW, aWithGarbageUpper, 7) == 2)
      }
    }

    test("word-form divide by zero and MIN_INT32/-1 overflow") {
      simulate(new HuttAlu(XLEN)) { dut =>
        assert(run(dut, AluOp.DivW, 5, 0) == MASK64)   // -1 sign-extended to 64 bits
        assert(run(dut, AluOp.RemW, 5, 0) == 5)
        val minInt32 = BigInt(1) << 31
        val overflowResult = run(dut, AluOp.DivW, minInt32, MASK32)
        // MIN_INT32 (bit pattern 0x80000000) sign-extended to 64 bits: since
        // it's negative as a 32-bit signed value, enc(-minInt32, ...) gives
        // the correctly sign-extended 64-bit pattern (0xFFFFFFFF80000000).
        assert(overflowResult == enc(-minInt32, XLEN))
        assert(run(dut, AluOp.RemW, minInt32, MASK32) == 0)
      }
    }

    test("MUL/MULH/MULHU/MULHSU are combinational (single-cycle, no divStart needed)") {
      simulate(new HuttAlu(XLEN)) { dut =>
        assert(run(dut, AluOp.Mul, 6, 7) == 42)
        // MULHU of two large unsigned values.
        val a = BigInt(1) << 40
        val b = BigInt(1) << 40
        val expected = ((a * b) >> 64) & MASK64
        assert(run(dut, AluOp.Mulhu, a, b) == expected)
      }
    }

    test("plain ALU ops still resolve combinationally (divBusy/divDone stay low)") {
      simulate(new HuttAlu(XLEN)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.clock.step(1)
        dut.io.op.poke(AluOp.Add)
        dut.io.a.poke(3.U(XLEN.W))
        dut.io.b.poke(4.U(XLEN.W))
        assert(dut.io.out.peek().litValue == 7)
        assert(!dut.io.divBusy.peek().litToBoolean)
        assert(!dut.io.divDone.peek().litToBoolean)
      }
    }

    test("divBusy is asserted throughout, divDone pulses exactly one cycle") {
      simulate(new HuttAlu(XLEN)) { dut =>
        dut.io.op.poke(AluOp.Div)
        dut.io.a.poke(100.U(XLEN.W))
        dut.io.b.poke(7.U(XLEN.W))
        dut.io.divStart.poke(true.B)
        dut.clock.step(1)
        dut.io.divStart.poke(false.B)
        var doneCycles = 0
        var steps = 0
        while (!dut.io.divDone.peek().litToBoolean) {
          assert(dut.io.divBusy.peek().litToBoolean)
          dut.clock.step(1)
          steps += 1
          if (steps > XLEN + 4) throw new RuntimeException("divDone never fired")
        }
        doneCycles += 1
        assert(doneCycles == 1)
      }
    }

    test("back-to-back divides produce independent correct results") {
      simulate(new HuttAlu(XLEN)) { dut =>
        assert(run(dut, AluOp.Div, 100, 7) == 14)
        assert(run(dut, AluOp.Div, 55, 5) == 11)
        assert(run(dut, AluOp.Rem, 7, 100) == 7)
      }
    }
  }
}
