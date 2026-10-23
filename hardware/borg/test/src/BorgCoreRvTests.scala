// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.SharedSim._
import utest._

/** ISA v2 step 1 (docs/B8_isa_v2.md): RISC-V OP, OP-IMM, LUI, AUIPC and x0 next to v1. */
object BorgCoreRvTests extends TestSuite {
  import BorgCoreTestHelpers._
  import Opcodes._

  private val M = BigInt(0xFFFFFFFFL)
  private def u(x: Long) = BigInt(x) & M
  private def s(x: BigInt) = x.toLong.toInt

  /** Run `prog` with the given registers preset; returns register reads afterwards. */
  private def run(core: BorgCore, prog: Seq[BigInt], regs: Map[Int, BigInt] = Map()): Int => BigInt = {
    resetCore(core)
    for ((r, v) <- regs) writeReg(core, r, v)
    for ((w, i) <- (prog :+ BigInt(0)).zipWithIndex) writeImem(core, i, w)
    startAndWait(core)
    r => readReg(core, r)
  }

  private val a = u(0x9abcdef0L); private val b = u(0x00000007L); private val c = u(0x80000001L)

  def ref(name: String, x: BigInt, y: BigInt): BigInt = {
    val sh = (y & 31).toInt
    name match {
      case "add" => (x + y) & M
      case "sub" => (x - y) & M
      case "sll" => (x << sh) & M
      case "slt" => if (s(x) < s(y)) 1 else 0
      case "sltu" => if (x < y) 1 else 0
      case "xor" => x ^ y
      case "srl" => x >> sh
      case "sra" => u(s(x).toLong >> sh)
      case "or" => x | y
      case "and" => x & y
    }
  }

  val tests = Tests {
    utest.test("op_matches_riscv") {
      simulate(new BorgCore(config)) { core =>
        idleInputs(core)
        val ops = Seq[(String, (Int, Int, Int) => BigInt)](
          "add" -> (ADD(_, _, _)), "sub" -> (SUB(_, _, _)), "sll" -> (SLL(_, _, _)), "slt" -> (SLT(_, _, _)),
          "sltu" -> (SLTU(_, _, _)), "xor" -> (XOR(_, _, _)), "srl" -> (SRL(_, _, _)), "sra" -> (SRA(_, _, _)),
          "or" -> (OR(_, _, _)), "and" -> (AND(_, _, _)))
        for (((name, op), k) <- ops.zipWithIndex) {
          val rd = run(core, Seq(op(10, 1, 2), op(11, 3, 2), op(12, 2, 1)), Map(1 -> a, 2 -> b, 3 -> c))
          val got = Seq(rd(10), rd(11), rd(12))
          val want = Seq(ref(name, a, b), ref(name, c, b), ref(name, b, a))
          println(s"  $name: ${got.map(_.toString(16))} want ${want.map(_.toString(16))}")
          utest.assert(got == want)
        }
      }
    }

    utest.test("op_imm_matches_riscv") {
      simulate(new BorgCore(config)) { core =>
        idleInputs(core)
        val rd = run(core, Seq(
          ADDI(10, 1, -5), SLTI(11, 1, -1), SLTIU(12, 1, -1), XORI(13, 1, 0x7ff), ORI(14, 1, -16),
          ANDI(15, 1, 0x0f0), SLLI(16, 1, 4), SRLI(17, 1, 28), SRAI(18, 1, 28)), Map(1 -> a))
        val want = Seq(u(0x9abcdeebL), BigInt(1), BigInt(1), a ^ 0x7ff, a | u(-16), a & 0xf0,
                       (a << 4) & M, a >> 28, u(s(a).toLong >> 28))
        val got = (10 to 18).map(rd)
        println(s"  ${got.map(_.toString(16))}")
        utest.assert(got == want)
      }
    }

    utest.test("lui_addi_and_auipc") {
      simulate(new BorgCore(config)) { core =>
        idleInputs(core)
        // 0x12345678 = LUI 0x12345 + 0x678; 0xdeadbeef needs LUI 0xdeadc + (-0x111).
        val rd = run(core, Seq(LUI(1, 0x12345), ADDI(1, 1, 0x678), LUI(2, 0xdeadc), ADDI(2, 2, -0x111),
                               AUIPC(3, 1)))
        println(f"  ${rd(1)}%x ${rd(2)}%x ${rd(3)}%x")
        utest.assert(rd(1) == 0x12345678 && rd(2) == u(0xdeadbeefL) && rd(3) == 0x1000 + 4 * 4)
      }
    }

    utest.test("csrrw_drives_the_csr_port_of_active_lanes") {
      simulate(new BorgCore(BorgConfig.Simt)) { core =>
        idleInputs(core)
        pokeQuad(core)
        resetCore(core)
        // r1 = 77; lane 0 masked off (r30 = lane x: 0, 1, 0, 1) for the second write.
        for ((w, i) <- Seq(ADDI(1, 0, 77), CSRRW(0, 1, Csr.OutC2), Instructions.F2I(30, 5),
                           Instructions.EXPUSH(rs1 = 5), CSRRW(0, 1, Csr.OutDepth), Instructions.EXPOP(),
                           BigInt(0)).zipWithIndex) writeImem(core, i, w)
        core.io.control.start.poke(true.B); core.clock.step(1); core.io.control.start.poke(false.B)
        val seen = scala.collection.mutable.ArrayBuffer[(Int, Int, BigInt)]()
        var n = 0
        while (n < 400 && (n < 2 || core.io.status.running.peek().litToBoolean)) {
          for (l <- 0 until 4) {
            val c = core.io.csrWrite(l)
            if (c.en.peek().litToBoolean) seen += ((l, c.addr.peek().litValue.toInt, c.data.peek().litValue))
          }
          core.clock.step(1); n += 1
        }
        println(s"  CSR writes: $seen")
        val want = (0 until 4).map(l => (l, Csr.OutC2, BigInt(77))) ++ Seq(1, 3).map(l => (l, Csr.OutDepth, BigInt(77)))
        utest.assert(seen.toSet == want.toSet && seen.size == want.size)
        utest.assert(readReg(core, 0) != 77)
      }
    }

    utest.test("loads_stores_and_csr_root") {
      simulate(new BorgCore(config)) { core =>
        idleInputs(core)
        resetCore(core)
        core.io.root.poke(0xABCD0.U)
        val mem = scala.collection.mutable.Map[BigInt, BigInt](BigInt(0x100) -> BigInt(0x8081F27FL), BigInt(0xFC) -> BigInt(0x1234))
        writeReg(core, 0, 5)
        for ((w, i) <- Seq(ADDI(1, 0, 0x100), LW(2, 1, 0), LB(3, 1, 1), LBU(4, 1, 2), LH(5, 1, 2), LHU(6, 1, 0),
                           ADDI(7, 0, 0x55), SW(8, 1, 7), LW(8, 1, -4), CSRRS(9, 0, Csr.Root), LW(0, 1, 0),
                           BigInt(0)).zipWithIndex) writeImem(core, i, w)
        startAndWaitWithMem(core, mem)
        val got = (0 to 9).map(readReg(core, _))
        println(s"  ${got.map(_.toString(16))} mem(0x108)=${mem.get(0x108)}")
        utest.assert(got == Seq(5, 0x100, 0x8081F27FL, 0xFFFFFFF2L, 0x81, 0xFFFF8081L, 0xF27F, 0x55, 0x1234, 0xABCD0).map(BigInt(_)))
        utest.assert(mem.get(0x108).contains(BigInt(0x55)))
      }
    }

    utest.test("branches_and_jal") {
      simulate(new BorgCore(config)) { core =>
        idleInputs(core)
        val skip = 8   // bytes: over one instruction
        val rd = run(core, Seq(
          ADDI(1, 0, 0), ADDI(2, 0, 5), ADDI(1, 1, 1), BNE(-4, 1, 2),       // count x1 to 5
          ADDI(3, 0, -1), BLT(skip, 3, 0), ADDI(4, 0, 99),                  // -1 < 0: taken
          BLTU(skip, 3, 0), ADDI(5, 0, 7),                                  // 0xffffffff < 0: not taken
          BGEU(skip, 3, 2), ADDI(6, 0, 99), JAL(0, skip), ADDI(7, 0, 99),
          BEQ(skip, 0, 0), ADDI(8, 0, 99), BGE(skip, 2, 1), ADDI(9, 0, 99)),
          (4 to 9).map(_ -> BigInt(0)).toMap)
        val got = (1 to 9).map(rd)
        println(s"  x1..x9 = ${got.map(_.toString(16))}")
        utest.assert(got == Seq(5, 5, 0xFFFFFFFFL, 0, 7, 0, 0, 0, 0).map(BigInt(_)))
      }
    }

    utest.test("zmmul_zicond_zbb_match_riscv") {
      simulate(new BorgCore(config)) { core =>
        idleInputs(core)
        def sx(x: BigInt) = BigInt(s(x)); def p(x: BigInt) = x & M
        def clz(x: BigInt) = if (x == 0) 32 else 31 - (x.bitLength - 1)
        def ctz(x: BigInt) = if (x == 0) 32 else x.lowestSetBit
        def rol(x: BigInt, n: Int) = p((x << n) | (x >> (32 - n)))
        val two = Seq[(String, (Int, Int, Int) => BigInt, (BigInt, BigInt) => BigInt)](
          ("mul", MUL(_, _, _), (x, y) => p(x * y)), ("mulh", MULH(_, _, _), (x, y) => p((sx(x) * sx(y)) >> 32)),
          ("mulhsu", MULHSU(_, _, _), (x, y) => p((sx(x) * y) >> 32)), ("mulhu", MULHU(_, _, _), (x, y) => p((x * y) >> 32)),
          ("czero.eqz", CZERO_EQZ(_, _, _), (x, y) => if (y == 0) 0 else x), ("czero.nez", CZERO_NEZ(_, _, _), (x, y) => if (y != 0) 0 else x),
          ("andn", ANDN(_, _, _), (x, y) => x & p(~y)), ("orn", ORN(_, _, _), (x, y) => p(x | p(~y))),
          ("xnor", XNOR(_, _, _), (x, y) => p(~(x ^ y))),
          ("min", MIN(_, _, _), (x, y) => if (s(x) < s(y)) x else y), ("minu", MINU(_, _, _), (x, y) => x.min(y)),
          ("max", MAX(_, _, _), (x, y) => if (s(x) < s(y)) y else x), ("maxu", MAXU(_, _, _), (x, y) => x.max(y)),
          ("rol", ROL(_, _, _), (x, y) => rol(x, (y & 31).toInt)), ("ror", ROR(_, _, _), (x, y) => rol(x, (32 - (y & 31).toInt) % 32)))
        val zero = BigInt(0)
        for ((name, op, f) <- two) {
          val rd = run(core, Seq(op(10, 1, 2), op(11, 3, 2), op(12, 2, 1), op(13, 1, 4)), Map(1 -> a, 2 -> b, 3 -> c, 4 -> zero))
          val got = (10 to 13).map(rd); val want = Seq(f(a, b), f(c, b), f(b, a), f(a, zero))
          if (got != want) println(s"  $name: ${got.map(_.toString(16))} want ${want.map(_.toString(16))}")
          utest.assert(got == want)
        }
        val one = Seq[(String, (Int, Int) => BigInt, BigInt => BigInt)](
          ("clz", CLZ(_, _), x => clz(x)), ("ctz", CTZ(_, _), x => ctz(x)), ("cpop", CPOP(_, _), x => x.bitCount),
          ("sext.b", SEXT_B(_, _), x => p(BigInt((x & 0xFF).toByte.toInt))), ("sext.h", SEXT_H(_, _), x => p(BigInt((x & 0xFFFF).toShort.toInt))),
          ("zext.h", ZEXT_H(_, _), x => x & 0xFFFF), ("rev8", REV8(_, _), x => BigInt(Integer.reverseBytes(x.toInt)) & M),
          ("rori", RORI(_, _, 12), x => rol(x, 20)))
        for ((name, op, f) <- one) {
          val rd = run(core, Seq(op(10, 1), op(11, 3), op(12, 4)), Map(1 -> a, 3 -> c, 4 -> zero))
          val got = Seq(10, 11, 12).map(rd); val want = Seq(f(a), f(c), f(zero))
          if (got != want) println(s"  $name: ${got.map(_.toString(16))} want ${want.map(_.toString(16))}")
          utest.assert(got == want)
        }
      }
    }

    utest.test("x0_reads_zero_and_drops_writes_for_riscv_only") {
      simulate(new BorgCore(config)) { core =>
        idleInputs(core)
        // r0 holds 5: a RISC-V read sees 0, a RISC-V write is dropped, v1 still uses r0.
        val rd = run(core, Seq(ADD(1, 0, 0), ADDI(2, 0, 9), ADDI(0, 2, 1), Instructions.IADD(0, 0, 3),
                               OR(4, 2, 0)), Map(0 -> 5))
        println(s"  r1 ${rd(1)} r2 ${rd(2)} r0 ${rd(0)} r3 ${rd(3)} r4 ${rd(4)}")
        utest.assert(rd(1) == 0 && rd(2) == 9 && rd(0) == 5 && rd(3) == 10 && rd(4) == 9)
      }
    }
  }
}
