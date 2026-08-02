// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3.{assert => _, test => _, _}
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Regression coverage for `pipelinedCsrRead = false` (the ASIC/TT
  * configuration, see Hutt.scala's constructor doc and asic/tt/src/TTTop.scala)
  * -- reruns a representative subset of HuttTrapsTests's CSR/trap programs
  * against the single-cycle CSR read+write path instead of the default
  * two-stage (sExec + sCsrSel) one, to prove csrWriteDecode behaves
  * identically regardless of which group-select signal (registered vs.
  * combinational) drives it.
  */
object HuttNonPipelinedCsrTests extends TestSuite {
  import HuttTrapsTests._

  def run(program: Seq[BigInt], maxCycles: Int = 500): BigInt = {
    var result: BigInt = -1
    simulate(new HuttTestHarness(program, pipelinedCsrRead = false)) { dut =>
      dut.reset.poke(true.B)
      dut.io.peekAddr.poke(0.U)
      dut.io.interrupt.poke(false.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step(maxCycles)
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

    test("CSRRS sets bits in mscratch without clobbering others") {
      import Asm._
      val prog = Seq(
        addi(1, 0, 0x10),
        csrrw(0, CSR_MSCRATCH, 1),
        addi(2, 0, 0x05),
        csrrs(0, CSR_MSCRATCH, 2),
        csrrs(3, CSR_MSCRATCH, 0),
        sw(3, RESULT_ADDR, 0),
        park()
      )
      assert(run(prog) == 0x15)
    }

    test("CSRRC clears bits in mscratch without clobbering others") {
      import Asm._
      val prog = Seq(
        addi(1, 0, 0x17),
        csrrw(0, CSR_MSCRATCH, 1),
        addi(2, 0, 0x05),
        csrrc(0, CSR_MSCRATCH, 2),
        csrrs(3, CSR_MSCRATCH, 0),
        sw(3, RESULT_ADDR, 0),
        park()
      )
      assert(run(prog) == 0x12)
    }

    test("ECALL traps to mtvec; mepc = ECALL PC; mcause = 11") {
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
      simulate(new HuttTestHarness(prog, pipelinedCsrRead = false)) { dut =>
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
      assert(results(0) == 0x08)
      assert(results(1) == 11)
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

    test("reading unimplemented AIA CSR mtopi (0xFB0) traps illegal instruction") {
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
  }
}
