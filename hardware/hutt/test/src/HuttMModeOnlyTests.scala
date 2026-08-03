// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3.{assert => _, test => _, _}
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Regression coverage for `hasSupervisorMode = false` (the ASIC/TT
  * configuration, see Hutt.scala's constructor doc and
  * asic/tt/src/TTTop.scala) -- confirms M-mode CSR/trap handling is
  * unaffected by removing S-mode, that traps which could delegate to S-mode
  * go straight to M instead, that a stray SRET (never emitted by ASIC
  * firmware) behaves as a safe no-op rather than an undefined state, and
  * that reading/writing a now-nonexistent S-mode CSR address behaves like
  * any other unimplemented CSR (reads 0, write silently ignored) instead of
  * crashing or corrupting state.
  */
object HuttMModeOnlyTests extends TestSuite {
  import HuttTrapsTests._

  def run(program: Seq[BigInt], maxCycles: Int = 500, irq: Boolean = false): BigInt = {
    var result: BigInt = -1
    simulate(new HuttTestHarness(program, hasSupervisorMode = false)) { dut =>
      dut.reset.poke(true.B)
      dut.io.peekAddr.poke(0.U)
      dut.io.interrupt.poke(false.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      if (irq) {
        dut.clock.step(100)
        dut.io.interrupt.poke(true.B)
        dut.clock.step(maxCycles - 100)
      } else {
        dut.clock.step(maxCycles)
      }
      dut.io.peekAddr.poke(RESULT_ADDR.U)
      dut.clock.step(1)
      result = dut.io.peekData.peek().litValue
    }
    result
  }

  val tests = Tests {

    test("CSRRW writes mscratch; CSRRS reads it back") {
      import Asm._
      val prog = Seq(
        addi(1, 0, 0x5A),
        csrrw(0, CSR_MSCRATCH, 1),
        csrrs(2, CSR_MSCRATCH, 0),
        sw(2, RESULT_ADDR, 0),
        park()
      )
      assert(run(prog) == 0x5A)
    }

    test("ECALL traps straight to M (no S-mode to delegate to); mepc/mcause correct") {
      import Asm._
      val HANDLER = 0x20
      val prog = Seq(
        addi(3, 0, HANDLER),
        csrrw(0, CSR_MTVEC, 3),
        ECALL,
        park(),
        park(), park(), park(), park(),
        csrrs(1, CSR_MEPC, 0),
        csrrs(2, CSR_MCAUSE, 0),
        sw(1, RESULT_ADDR, 0),
        sw(2, RESULT_ADDR + 4, 0),
        park()
      )
      var results = Seq.fill(2)(BigInt(-1))
      simulate(new HuttTestHarness(prog, hasSupervisorMode = false)) { dut =>
        dut.reset.poke(true.B)
        dut.io.peekAddr.poke(0.U)
        dut.io.interrupt.poke(false.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.clock.step(500)
        results = Seq(RESULT_ADDR, RESULT_ADDR + 4).map { addr =>
          dut.io.peekAddr.poke(addr.U)
          dut.clock.step(1)
          dut.io.peekData.peek().litValue
        }
      }
      assert(results(0) == 0x08)   // mepc = PC of ECALL
      assert(results(1) == 11)     // mcause = 11 (M-mode environment call) -- never 9 (S-mode)
    }

    test("EBREAK traps to mtvec with mcause=3") {
      import Asm._
      val HANDLER = 0x20
      val prog = Seq(
        addi(3, 0, HANDLER),
        csrrw(0, CSR_MTVEC, 3),
        EBREAK,
        park(), park(), park(), park(), park(),
        csrrs(1, CSR_MCAUSE, 0),
        sw(1, RESULT_ADDR, 0),
        park()
      )
      assert(run(prog) == 3)
    }

    test("MRET returns execution to mepc") {
      import Asm._
      val HANDLER = 0x20
      val TARGET  = 0x30
      val prog = Seq(
        addi(3, 0, HANDLER),
        csrrw(0, CSR_MTVEC, 3),
        ECALL,
        park(), park(), park(), park(), park(),
        addi(4, 0, TARGET),
        csrrw(0, CSR_MEPC, 4),
        MRET,
        park(),
        addi(1, 0, 0x42),
        sw(1, RESULT_ADDR, 0),
        park()
      )
      assert(run(prog, maxCycles = 800) == 0x42)
    }

    test("reading unimplemented AIA CSR mtopi (0xFB0) still traps illegal instruction, straight to M") {
      import Asm._
      val HANDLER = 0x20
      val CSR_MTOPI = 0xFB0
      val prog = Seq(
        addi(3, 0, HANDLER),
        csrrw(0, CSR_MTVEC, 3),
        csrrs(1, CSR_MTOPI, 0),
        park(), park(), park(), park(), park(),
        csrrs(1, CSR_MCAUSE, 0),
        sw(1, RESULT_ADDR, 0),
        park()
      )
      assert(run(prog) == 2)
    }

    test("SRET behaves as a safe no-op (never emitted by ASIC firmware, but must not hang/corrupt)") {
      import Asm._
      val SRET = BigInt("10200073", 16)
      val prog = Seq(
        addi(1, 0, 0x11),
        SRET,                 // should just skip to the next instruction
        addi(1, 1, 0x01),     // x1 = 0x12 if SRET was correctly treated as a nop
        sw(1, RESULT_ADDR, 0),
        park()
      )
      assert(run(prog) == 0x12)
    }

    test("a stale S-mode CSR address (sscratch, 0x140) reads 0 like any unimplemented CSR") {
      import Asm._
      val CSR_SSCRATCH = 0x140
      val prog = Seq(
        addi(1, 0, 0x7F),          // poison x1 so a wrong (non-zero) read is visible
        csrrs(1, CSR_SSCRATCH, 0), // x1 = sscratch (should read 0, not trap, not keep 0x7F)
        sw(1, RESULT_ADDR, 0),
        park()
      )
      assert(run(prog) == 0)
    }

    test("writing a stale S-mode CSR address (sscratch) is silently ignored, not a trap") {
      import Asm._
      val CSR_SSCRATCH = 0x140
      val prog = Seq(
        addi(1, 0, 0x5A),
        csrrw(0, CSR_SSCRATCH, 1),  // write should be a no-op, not trap
        addi(2, 0, 0x99),
        sw(2, RESULT_ADDR, 0),      // proof execution continued normally past the write
        park()
      )
      assert(run(prog) == 0x99)
    }

    test("M-mode timer interrupt still fires with MIE+MTIE enabled (mcause=0x80000007)") {
      import Asm._
      val HANDLER = 0x30
      val prog = Seq(
        addi(3, 0, HANDLER),
        csrrw(0, CSR_MTVEC, 3),
        addi(3, 0, 0x80),
        csrrw(0, CSR_MIE, 3),
        addi(3, 0, 0x8),
        csrrw(0, CSR_MSTATUS, 3),
        park(),
        park(), park(), park(), park(), park(),
        csrrs(1, CSR_MCAUSE, 0),
        sw(1, RESULT_ADDR, 0),
        park()
      )
      val mc = run(prog, maxCycles = 400, irq = true) & BigInt("FFFFFFFF", 16)
      assert(mc == BigInt("80000007", 16))
    }
  }
}
