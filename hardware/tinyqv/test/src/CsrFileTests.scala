// Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

object CsrFileTests extends TestSuite {

  def withCsr(testFn: CsrFile => Unit): Unit = {
    simulate(new CsrFile) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(1)
      dut.reset.poke(false.B)
      // Default: no CSR operations, no interrupts
      dut.io.is_csr_write.poke(false.B)
      dut.io.is_csr_set.poke(false.B)
      dut.io.is_csr_clear.poke(false.B)
      dut.io.is_exception.poke(false.B)
      dut.io.is_trap.poke(false.B)
      dut.io.is_mret.poke(false.B)
      dut.io.is_interrupt.poke(false.B)
      dut.io.interrupt_req.poke(0.U)
      dut.io.timer_interrupt.poke(false.B)
      dut.io.imm_lo.poke(0.U)
      dut.io.imm.poke(0.U)
      dut.io.data_rs1.poke(0.U)
      dut.io.pc.poke(0.U)
      dut.io.counter.poke(0.U)
      testFn(dut)
    }
  }

  // Run a full 8-nibble rotation (one instruction cycle)
  def fullRotation(dut: CsrFile): Unit = {
    for (i <- 0 until 8) {
      dut.io.counter.poke(i.U)
      dut.clock.step(1)
    }
  }

  // Read a CSR value as a 32-bit word by cycling through all 8 nibbles
  def readCsr(dut: CsrFile, addr: Int): Long = {
    dut.io.imm_lo.poke(addr.U)
    var value: Long = 0
    for (i <- 0 until 8) {
      dut.io.counter.poke(i.U)
      value |= (dut.io.csr_read.peek().litValue.toLong << (i * 4))
      dut.clock.step(1)
    }
    value
  }

  // Write a nibble to a CSR at a given counter position
  def writeNibble(dut: CsrFile, addr: Int, counter: Int, nibble: Int): Unit = {
    dut.io.imm_lo.poke(addr.U)
    dut.io.counter.poke(counter.U)
    dut.io.data_rs1.poke(nibble.U)
    dut.io.is_csr_write.poke(true.B)
    dut.clock.step(1)
    dut.io.is_csr_write.poke(false.B)
  }

  val tests = Tests {

    utest.test("mstatus default: mie=1, mte=1, mpie=0") {
      withCsr { dut =>
        val mstatus = readCsr(dut, 0x300)
        // mstatus bits: [3]=mie, [2]=mte, [7]=mpie
        val mie = (mstatus >> 3) & 1
        val mte = (mstatus >> 2) & 1
        val mpie = (mstatus >> 7) & 1
        utest.assert(mie == 1)
        utest.assert(mte == 1)
        utest.assert(mpie == 0)
      }
    }

    utest.test("misa reads correctly") {
      withCsr { dut =>
        val misa = readCsr(dut, 0x301)
        val nibble0 = misa & 0xF
        val nibble1 = (misa >> 4) & 0xF
        val nibble7 = (misa >> 28) & 0xF
        utest.assert(nibble0 == 0x4)
        utest.assert(nibble1 == 0x1)
        utest.assert(nibble7 == 0x4)
      }
    }

    utest.test("mie write and read back") {
      withCsr { dut =>
        // Write mie[3:0] = 0xA at counter=4
        writeNibble(dut, 0x304, 4, 0xA)
        // Read it back
        val mie = readCsr(dut, 0x304)
        val nibble4 = (mie >> 16) & 0xF
        utest.assert(nibble4 == 0xA)
      }
    }

    utest.test("interrupt pending when mie and mip match") {
      withCsr { dut =>
        // Initially no interrupt pending
        dut.io.counter.poke(0.U)
        utest.assert(dut.io.interrupt_pending.peek().litToBoolean == false)

        // Enable external interrupt bit 2 in mie (at nibble counter=4, bit position=2)
        writeNibble(dut, 0x304, 4, 0x4) // mie bit 2

        // Assert interrupt_req bit 2 (maps to mip bit 2 which is direct from input)
        dut.io.interrupt_req.poke(0x0004.U) // bit 2

        // Run a rotation to let it propagate
        fullRotation(dut)

        dut.io.counter.poke(0.U)
        dut.clock.step(1)
        utest.assert(dut.io.interrupt_pending.peek().litToBoolean == true)
      }
    }

    utest.test("timer interrupt sets pending") {
      withCsr { dut =>
        // Enable timer interrupt (mie bit 16) via counter=1, data_rs1 bit 3
        writeNibble(dut, 0x304, 1, 0x8) // sets mie_16

        // Assert timer interrupt
        dut.io.timer_interrupt.poke(true.B)

        fullRotation(dut)
        dut.io.counter.poke(0.U)
        dut.clock.step(1)
        utest.assert(dut.io.interrupt_pending.peek().litToBoolean == true)
      }
    }

    utest.test("mstatus mie cleared on exception") {
      withCsr { dut =>
        // Verify mie starts as 1
        dut.io.counter.poke(0.U)
        dut.io.imm_lo.poke(0x300.U)
        dut.clock.step(1)
        val mie_before = (dut.io.csr_read.peek().litValue.toLong >> 3) & 1
        utest.assert(mie_before == 1)

        // Trigger exception
        dut.io.is_exception.poke(true.B)
        dut.io.is_trap.poke(true.B)
        dut.io.counter.poke(0.U)
        dut.clock.step(1)
        dut.io.is_exception.poke(false.B)
        dut.io.is_trap.poke(false.B)

        // Read mstatus again - mie should be cleared, mpie should be set
        dut.io.imm_lo.poke(0x300.U)
        dut.io.counter.poke(0.U)
        dut.clock.step(1)
        val mie_after = (dut.io.csr_read.peek().litValue.toLong >> 3) & 1
        utest.assert(mie_after == 0)

        // mpie should now be 1 (saved old mie)
        dut.io.counter.poke(1.U)
        dut.clock.step(1)
        val mpie_after = (dut.io.csr_read.peek().litValue.toLong >> 3) & 1
        utest.assert(mpie_after == 1)
      }
    }

    utest.test("mret restores mie from mpie") {
      withCsr { dut =>
        // First trigger exception to save mie->mpie and clear mie
        dut.io.is_exception.poke(true.B)
        dut.io.is_trap.poke(true.B)
        dut.io.counter.poke(0.U)
        dut.clock.step(1)
        dut.io.is_exception.poke(false.B)
        dut.io.is_trap.poke(false.B)

        // Now mret should restore
        dut.io.is_mret.poke(true.B)
        dut.clock.step(1)
        dut.io.is_mret.poke(false.B)

        // Check mie restored to 1
        dut.io.imm_lo.poke(0x300.U)
        dut.io.counter.poke(0.U)
        dut.clock.step(1)
        val mie_restored = (dut.io.csr_read.peek().litValue.toLong >> 3) & 1
        utest.assert(mie_restored == 1)
      }
    }

    utest.test("mimpid reads correctly") {
      withCsr { dut =>
        val mimpid = readCsr(dut, 0xF13)
        utest.assert((mimpid & 0xF) == 3)
      }
    }
  }
}
