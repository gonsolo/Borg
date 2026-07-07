// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3.{assert => _, test => _, _}
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Tests for M-mode trap handling (ECALL, EBREAK, MRET, CSR R/W, timer IRQ).
  *
  * Results are stored to data memory and peeked back via the test harness.
  * Address 0x100 (word 0x40) is used as the result slot.
  */
object HuttTrapsTests extends TestSuite {

  val RESULT_ADDR = 0x100  // byte address where programs store their result

  // CSR instruction encoding (I-type, opcode=0x73).
  // funct3: 001=CSRRW, 010=CSRRS, 011=CSRRC, 101=CSRRWI, 110=CSRRSI, 111=CSRRCI
  def csrInstr(funct3: Int, csr: Int, rs1: Int, rd: Int): BigInt =
    (BigInt(csr & 0xFFF) << 20) | (BigInt(rs1 & 0x1F) << 15) |
    (BigInt(funct3 & 7) << 12) | (BigInt(rd & 0x1F) << 7) | BigInt(0x73)

  def csrrw(rd: Int, csr: Int, rs1: Int): BigInt = csrInstr(1, csr, rs1, rd)
  def csrrs(rd: Int, csr: Int, rs1: Int): BigInt = csrInstr(2, csr, rs1, rd)
  def csrrc(rd: Int, csr: Int, rs1: Int): BigInt = csrInstr(3, csr, rs1, rd)

  val ECALL  = BigInt(0x00000073L)
  val EBREAK = BigInt(0x00100073L)
  val MRET   = BigInt(0x30200073L)

  // CSR addresses
  val CSR_MSTATUS  = 0x300
  val CSR_MTVEC    = 0x305
  val CSR_MEPC     = 0x341
  val CSR_MCAUSE   = 0x342
  val CSR_MSCRATCH = 0x340
  val CSR_MIE      = 0x304
  val CSR_MIP      = 0x344
  val CSR_MHARTID  = 0xF14

  /** Run program and return 32-bit word stored at RESULT_ADDR in data memory. */
  def run(program: Seq[BigInt], maxCycles: Int = 500, irq: Boolean = false): BigInt = {
    var result: BigInt = -1
    simulate(new HuttTestHarness(program)) { dut =>
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
        addi(1, 0, 0x5A),              // x1 = 0x5A
        csrrw(0, CSR_MSCRATCH, 1),     // mscratch = x1 = 0x5A
        csrrs(2, CSR_MSCRATCH, 0),     // x2 = mscratch (rs1=x0 → no write)
        sw(2, RESULT_ADDR, 0),         // mem[RESULT_ADDR] = x2
        park()
      )
      assert(run(prog) == 0x5A)
    }

    test("mhartid reads zero") {
      import Asm._
      val prog = Seq(
        csrrs(1, CSR_MHARTID, 0),     // x1 = mhartid (should be 0)
        sw(1, RESULT_ADDR, 0),
        park()
      )
      assert(run(prog) == 0)
    }

    test("ECALL traps to mtvec; mepc = ECALL PC; mcause = 11") {
      import Asm._
      // Layout:
      //   PC 0x00: set mtvec = 0x20; run ecall at PC=0x0C
      //   PC 0x20: handler reads mepc→x1, mcause→x2; stores both; parks
      // Note: the handler address must fit in an addi immediate.
      val HANDLER = 0x20
      val prog = Seq(
        // PC 0x00 (word 0): set mtvec
        addi(3, 0, HANDLER),           // x3 = 0x20
        csrrw(0, CSR_MTVEC, 3),       // mtvec = 0x20
        ECALL,                        // PC 0x08 → trap; mepc = 0x08
        park(),                       // should NOT execute (word 3, PC 0x0C)
        park(), park(), park(), park(),// padding to word 8
        // PC 0x20 (word 8): trap handler
        csrrs(1, CSR_MEPC, 0),        // x1 = mepc
        csrrs(2, CSR_MCAUSE, 0),      // x2 = mcause
        sw(1, RESULT_ADDR, 0),        // mem[RESULT_ADDR] = mepc (= 0x08)
        sw(2, RESULT_ADDR + 4, 0),    // mem[RESULT_ADDR+4] = mcause (= 11)
        park()
      )
      var results = Seq.fill(2)(BigInt(-1))
      simulate(new HuttTestHarness(prog)) { dut =>
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
      assert(results(0) == 0x08)   // mepc should be PC of ECALL = 0x08
      assert(results(1) == 11)    // mcause = 11 (environment call from M-mode)
    }

    test("EBREAK traps to mtvec with mcause=3") {
      import Asm._
      val HANDLER = 0x20
      val prog = Seq(
        addi(3, 0, HANDLER),
        csrrw(0, CSR_MTVEC, 3),       // mtvec = 0x20
        EBREAK,                       // PC 0x08 → trap; mcause = 3
        park(), park(), park(), park(), park(),
        // PC 0x20 (word 8)
        csrrs(1, CSR_MCAUSE, 0),
        sw(1, RESULT_ADDR, 0),
        park()
      )
      assert(run(prog) == 3)
    }

    test("reading unimplemented AIA CSR mtopi (0xFB0) traps illegal instruction") {
      // Regression test: OpenSBI's sbi_hart.c detects the Smaia extension by
      // reading CSR_MTOPI and checking whether it traps. If Hutt silently
      // returns 0 instead, OpenSBI wrongly believes Smaia is present and
      // dispatches M-mode interrupts via `while (csr_read(CSR_MTOPI)) {...}`
      // instead of checking mip/mie directly — since mtopi always reads 0,
      // that loop body (which clears MIE.MTIP / raises mip.STIP) never runs,
      // so the M-mode timer interrupt keeps re-firing every cycle forever.
      import Asm._
      val HANDLER = 0x20
      val CSR_MTOPI = 0xFB0
      val prog = Seq(
        addi(3, 0, HANDLER),
        csrrw(0, CSR_MTVEC, 3),        // mtvec = 0x20
        csrrs(1, CSR_MTOPI, 0),        // PC 0x08 → must trap; mcause = 2 (illegal)
        park(), park(), park(), park(), park(),
        // PC 0x20 (word 8)
        csrrs(1, CSR_MCAUSE, 0),
        sw(1, RESULT_ADDR, 0),
        park()
      )
      assert(run(prog) == 2)
    }

    test("MRET returns execution to mepc") {
      import Asm._
      // ECALL → handler (word 8, PC=0x20) sets mepc=0x30 (TARGET) → MRET → target stores 0x42
      val HANDLER = 0x20   // word 8 * 4
      val TARGET  = 0x30   // word 12 * 4
      val prog = Seq(
        // word 0 (PC 0x00): setup
        addi(3, 0, HANDLER),           // x3 = 0x20
        csrrw(0, CSR_MTVEC, 3),        // mtvec = 0x20
        ECALL,                         // PC 0x08 → trap to 0x20
        park(), park(), park(), park(), park(),   // words 3-7 (not executed)
        // word 8 (PC 0x20): handler
        addi(4, 0, TARGET),            // x4 = 0x30
        csrrw(0, CSR_MEPC, 4),         // mepc = 0x30 (TARGET)
        MRET,                          // PC ← mepc = 0x30
        park(),                        // word 11 (PC 0x2C, not executed)
        // word 12 (PC 0x30): post-MRET target
        addi(1, 0, 0x42),              // x1 = 0x42 (proof we arrived)
        sw(1, RESULT_ADDR, 0),
        park()
      )
      assert(run(prog, maxCycles = 800) == 0x42)
    }

    test("timer interrupt traps with cause=0x80000007 (MIE+MTIE enabled)") {
      import Asm._
      val HANDLER = 0x30
      val prog = Seq(
        // PC 0x00: set mtvec, enable MTIE (bit7 of mie) and MIE (bit3 of mstatus)
        addi(3, 0, HANDLER),
        csrrw(0, CSR_MTVEC, 3),       // mtvec = 0x30
        addi(3, 0, 0x80),             // x3 = 0x80 (MTIE bit)
        csrrw(0, CSR_MIE, 3),         // mie.MTIE = 1
        addi(3, 0, 0x8),              // x3 = 0x8 (MIE bit in mstatus)
        csrrw(0, CSR_MSTATUS, 3),     // mstatus.MIE = 1
        // PC 0x18: spin (tight loop — interrupt will break it at instruction boundary)
        park(),                       // word 6 (PC 0x18): self-loop
        park(), park(), park(), park(), park(), // words 7-11 (pad to word 12)
        // PC 0x30 (word 12): trap handler
        csrrs(1, CSR_MCAUSE, 0),      // x1 = mcause (expect 0x80000007)
        sw(1, RESULT_ADDR, 0),
        park()
      )
      // mcause on RV32: interrupt bit = bit 31 → 0x80000007
      val mc = run(prog, maxCycles = 400, irq = true) & BigInt("FFFFFFFF", 16)
      assert(mc == BigInt("80000007", 16))
    }

    test("CSRRS sets bits in mscratch without clobbering others") {
      import Asm._
      val prog = Seq(
        addi(1, 0, 0x10),             // x1 = 0x10
        csrrw(0, CSR_MSCRATCH, 1),    // mscratch = 0x10
        addi(2, 0, 0x05),             // x2 = 0x05
        csrrs(0, CSR_MSCRATCH, 2),    // mscratch |= 0x05 → 0x15
        csrrs(3, CSR_MSCRATCH, 0),    // x3 = mscratch (rs1=x0 → no write)
        sw(3, RESULT_ADDR, 0),
        park()
      )
      assert(run(prog) == 0x15)
    }

    test("CSRRC clears bits in mscratch without clobbering others") {
      import Asm._
      val prog = Seq(
        addi(1, 0, 0x17),             // x1 = 0x17 (0b10111)
        csrrw(0, CSR_MSCRATCH, 1),    // mscratch = 0x17
        addi(2, 0, 0x05),             // x2 = 0x05 (0b00101) — bits to clear
        csrrc(0, CSR_MSCRATCH, 2),    // mscratch &= ~0x05 → 0x12
        csrrs(3, CSR_MSCRATCH, 0),    // x3 = mscratch (read)
        sw(3, RESULT_ADDR, 0),
        park()
      )
      assert(run(prog) == 0x12)
    }
  }
}
