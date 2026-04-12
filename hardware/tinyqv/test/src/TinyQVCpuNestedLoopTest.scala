// Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0
//
// Test to reproduce nested loop hang bug in TinyQV CPU.
// Faithful translation of the Cocotb protocol from test_cpu.py.
// Updated for RV32E-only (no compressed instructions).

package tinyqv.cpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** Wrapper: turns RawModule TinyQVCpu into a regular Module for simulation. */
class TinyQVCpuWrapper extends Module {
  val instr_addr = IO(Output(UInt(23.W)))
  val instr_fetch_restart = IO(Output(Bool()))
  val instr_fetch_stall = IO(Output(Bool()))
  val instr_fetch_started = IO(Input(Bool()))
  val instr_fetch_stopped = IO(Input(Bool()))
  val instr_data = IO(Input(UInt(16.W)))
  val instr_ready = IO(Input(Bool()))
  val interrupt_req = IO(Input(UInt(16.W)))
  val data_addr = IO(Output(UInt(28.W)))
  val data_write_n = IO(Output(UInt(2.W)))
  val data_read_n = IO(Output(UInt(2.W)))
  val data_read_complete = IO(Output(Bool()))
  val data_out = IO(Output(UInt(32.W)))
  val data_continue = IO(Output(Bool()))
  val data_ready = IO(Input(Bool()))
  val data_in = IO(Input(UInt(32.W)))
  val time_pulse = IO(Input(Bool()))
  val debug_instr_complete = IO(Output(Bool()))
  val debug_instr_valid = IO(Output(Bool()))
  val debug_interrupt_pending = IO(Output(Bool()))
  val debug_branch = IO(Output(Bool()))
  val debug_early_branch = IO(Output(Bool()))
  val debug_ret = IO(Output(Bool()))
  val debug_reg_wen = IO(Output(Bool()))
  val debug_counter_0 = IO(Output(Bool()))
  val debug_rd = IO(Output(UInt(4.W)))
  val debug_pc = IO(Output(UInt(32.W)))
  val debug_imm = IO(Output(UInt(32.W)))
  val debug_counter_hi = IO(Output(UInt(3.W)))

  val cpu = Module(new TinyQVCpu(16, 4))

  instr_addr := cpu.io.instrFetch.instr_addr
  instr_fetch_restart := cpu.io.instrFetch.instr_fetch_restart
  instr_fetch_stall := cpu.io.instrFetch.instr_fetch_stall
  cpu.io.instrFetch.instr_fetch_started := instr_fetch_started
  cpu.io.instrFetch.instr_fetch_stopped := instr_fetch_stopped
  cpu.io.instrFetch.instr_data := instr_data
  cpu.io.instrFetch.instr_ready := instr_ready
  cpu.io.interrupt_req := interrupt_req
  data_addr := cpu.io.data_addr
  data_write_n := cpu.io.data_write_n
  data_read_n := cpu.io.data_read_n
  data_read_complete := cpu.io.data_read_complete
  data_out := cpu.io.data_out
  data_continue := cpu.io.data_continue
  cpu.io.data_ready := data_ready
  cpu.io.data_in := data_in
  cpu.io.time_pulse := time_pulse
  debug_instr_complete := cpu.io.debug_instr_complete
  debug_instr_valid := cpu.io.debug_instr_valid
  debug_interrupt_pending := cpu.io.debug_interrupt_pending
  debug_branch := cpu.io.debug_branch
  debug_early_branch := cpu.io.debug_early_branch
  debug_ret := cpu.io.debug_ret
  debug_reg_wen := cpu.io.debug_reg_wen
  debug_counter_0 := cpu.io.debug_counter_0
  debug_rd := cpu.io.debug_rd
  debug_pc := cpu.io.debug_pc
  debug_imm := cpu.io.debug_imm
  debug_counter_hi := cpu.io.debug_counter_hi
}

object TinyQVCpuNestedLoopTest extends TestSuite {

  // All programs are 32-bit only (RV32E, no C extension).
  // ROM stored as 16-bit halfwords (little-endian: low halfword first).

  // Nested loop: 4x4 iterations with jal/ret
  // 0x00: lui sp, 0x11        0x04: j outer_start(0x10)
  // 0x08: addi a0,a0,1 [func] 0x0C: ret (jalr x0,ra,0)
  // 0x10: li s1,0              0x14: li s0,0 [outer]
  // 0x18: li a0,0              0x1C: jal ra,func [inner]
  // 0x20: addi s0,s0,1         0x24: li a5,4
  // 0x28: bne s0,a5,inner      0x2C: addi s1,s1,1
  // 0x30: li a5,4              0x34: bne s1,a5,outer
  // 0x38: lui a4,0xe           0x3C: addi a4,a4,-339
  // 0x40: lui a5,0x1001        0x44: sw a4,1280(a5)
  // 0x48: j hang
  val nestedProgram: Array[Int] = Array(
    0x1137, 0x0001,   // 0x00: lui sp, 0x11
    0x006F, 0x00C0,   // 0x04: jal x0, +12 (→0x10)
    0x0513, 0x0015,   // 0x08: addi a0, a0, 1
    0x8067, 0x0000,   // 0x0C: jalr x0, ra, 0
    0x0493, 0x0000,   // 0x10: addi s1, x0, 0
    0x0413, 0x0000,   // 0x14: addi s0, x0, 0
    0x0513, 0x0000,   // 0x18: addi a0, x0, 0
    0xF0EF, 0xFEDF,   // 0x1C: jal ra, -20 (→0x08)   [FEDFF0EF]
    0x0413, 0x0014,   // 0x20: addi s0, s0, 1
    0x0793, 0x0040,   // 0x24: addi a5, x0, 4
    0x1AE3, 0xFEF4,   // 0x28: bne s0, a5, -12 (→0x1C) [FEF41AE3]
    0x8493, 0x0014,   // 0x2C: addi s1, s1, 1
    0x0793, 0x0040,   // 0x30: addi a5, x0, 4
    0x90E3, 0xFEF4,   // 0x34: bne s1, a5, -32 (→0x14) [FEF490E3]
    0xE737, 0x0000,   // 0x38: lui a4, 0xe
    0x0713, 0xEAD7,   // 0x3C: addi a4, a4, -339
    0x17B7, 0x0100,   // 0x40: lui a5, 0x1001
    0xA023, 0x50E7,   // 0x44: sw a4, 1280(a5)
    0x006F, 0x0000    // 0x48: jal x0, 0 (infinite loop)
  )

  // Flat loop: 16 iterations, no nesting
  // 0x00: lui sp, 0x11          0x04: j loop_start(0x10)
  // 0x08: addi a0,a0,1 [func]   0x0C: ret
  // 0x10: li s1,0                0x14: li a0,0
  // 0x18: jal ra,func [loop]     0x1C: addi s1,s1,1
  // 0x20: li a5,16               0x24: bne s1,a5,loop
  // 0x28: lui a4,0xe             0x2C: addi a4,a4,-339
  // 0x30: lui a5,0x1001          0x34: sw a4,1280(a5)
  // 0x38: j hang
  val flatProgram: Array[Int] = Array(
    0x1137, 0x0001,   // 0x00: lui sp, 0x11
    0x006F, 0x00C0,   // 0x04: jal x0, +12 (→0x10)
    0x0513, 0x0015,   // 0x08: addi a0, a0, 1
    0x8067, 0x0000,   // 0x0C: jalr x0, ra, 0
    0x0493, 0x0000,   // 0x10: addi s1, x0, 0
    0x0513, 0x0000,   // 0x14: addi a0, x0, 0
    0xF0EF, 0xFF1F,   // 0x18: jal ra, -16 (→0x08)    [FF1FF0EF]
    0x8493, 0x0014,   // 0x1C: addi s1, s1, 1
    0x0793, 0x0100,   // 0x20: addi a5, x0, 16
    0x9AE3, 0xFEF4,   // 0x24: bne s1, a5, -12 (→0x18) [FEF49AE3]
    0xE737, 0x0000,   // 0x28: lui a4, 0xe
    0x0713, 0xEAD7,   // 0x2C: addi a4, a4, -339
    0x17B7, 0x0100,   // 0x30: lui a5, 0x1001
    0xA023, 0x50E7,   // 0x34: sw a4, 1280(a5)
    0x006F, 0x0000    // 0x38: jal x0, 0 (infinite loop)
  )

  case class TestResult(
    totalCycles: Int, instrCount: Int, hung: Boolean,
    doneWritten: Boolean, doneValue: Long, storeCount: Int
  )

  /**
   * Run a CPU test following the EXACT Cocotb protocol from test_cpu.py.
   * Updated for 32-bit-only instructions (no compressed).
   */
  def runCpuTest(rom: Array[Int], label: String, maxCycles: Int = 100000, debug: Boolean = false): TestResult = {
    var totalCycles = 0
    var instrCount = 0
    var hungDetected = false
    var doneWritten = false
    var doneValue = 0L
    var storeCount = 0
    var finished = false

    simulate(new TinyQVCpuWrapper) { dut =>
      def step(n: Int = 1): Unit = {
        for (_ <- 0 until n) {
          dut.clock.step(1)
          totalCycles += 1
        }
      }

      // Send a 32-bit instruction (always 2 halfwords)
      def sendInstr(instr: Int): Unit = {
        step(1)
        dut.instr_fetch_started.poke(false.B)
        dut.instr_ready.poke(false.B)
        dut.time_pulse.poke(false.B)
        step(7)

        // Low halfword
        dut.instr_data.poke((instr & 0xFFFF).U)
        dut.instr_ready.poke(true.B)

        step(1)
        dut.instr_ready.poke(false.B)
        step(7)

        // High halfword
        dut.instr_data.poke(((instr >> 16) & 0xFFFF).U)
        dut.instr_ready.poke(true.B)
      }

      def expectBranch(early: Boolean = false): Int = {
        step(1)
        dut.instr_ready.poke(false.B)
        for (i <- 0 until 24) {
          step(1)
          if (dut.instr_fetch_restart.peek().litToBoolean) {
            val addr = dut.instr_addr.peek().litValue.toInt
            if (debug) println(f"[$label]   Branch → 0x${addr*2}%04x")
            dut.instr_fetch_started.poke(true.B)
            return addr
          }
        }
        println(s"[$label] ERROR: Timed out waiting for branch!")
        finished = true
        -1
      }

      def expectStore(): Long = {
        step(1)
        dut.instr_ready.poke(false.B)
        for (_ <- 0 until 24) {
          step(1)
          if (dut.data_write_n.peek().litValue.toInt != 3) {
            val data = dut.data_out.peek().litValue.toLong
            storeCount += 1
            if (debug) println(f"[$label]   STORE data=0x$data%08x")
            step(1)
            dut.data_ready.poke(true.B)
            step(1)
            dut.data_ready.poke(false.B)
            return data
          }
        }
        println(s"[$label] ERROR: Timed out waiting for store!")
        finished = true
        0L
      }

      // Reset
      dut.reset.poke(true.B)
      dut.interrupt_req.poke(0.U)
      dut.time_pulse.poke(false.B)
      step(1)
      dut.reset.poke(true.B)
      step(2)
      dut.instr_fetch_started.poke(false.B)
      dut.instr_fetch_stopped.poke(false.B)
      dut.instr_data.poke(0.U)
      dut.instr_ready.poke(false.B)
      dut.data_ready.poke(false.B)
      dut.data_in.poke(0.U)
      step(10)
      dut.reset.poke(false.B)
      step(1)

      Predef.assert(dut.instr_fetch_restart.peek().litToBoolean,
        s"[$label] No initial instr_fetch_restart!")
      dut.instr_fetch_started.poke(true.B)

      // ROM execution — all 32-bit instructions
      var pc = 0

      def romHW(hwIdx: Int): Int = if (hwIdx < rom.length) rom(hwIdx) else 0
      def readInstr32(addr: Int): Int = romHW(addr/2) | (romHW(addr/2 + 1) << 16)

      def instrType(instr: Int): String = (instr & 0x7f) match {
        case 0x6f => "jal"
        case 0x67 => "jalr"
        case 0x63 => "branch"
        case 0x23 => "store"
        case 0x03 => "load"
        case _    => "other"
      }

      def decodeJalOffset(instr: Int): Int = {
        val imm20 = (instr >> 31) & 1
        val imm10_1 = (instr >> 21) & 0x3ff
        val imm11 = (instr >> 20) & 1
        val imm19_12 = (instr >> 12) & 0xff
        var imm = (imm20 << 20) | (imm19_12 << 12) | (imm11 << 11) | (imm10_1 << 1)
        if (imm20 != 0) imm -= (1 << 21)
        imm
      }

      def decodeBranchOffset(instr: Int): Int = {
        val imm12 = (instr >> 31) & 1
        val imm10_5 = (instr >> 25) & 0x3f
        val imm4_1 = (instr >> 8) & 0xf
        val imm11 = (instr >> 7) & 1
        var imm = (imm12 << 12) | (imm11 << 11) | (imm10_5 << 5) | (imm4_1 << 1)
        if (imm12 != 0) imm -= (1 << 13)
        imm
      }

      var maxInstr = 500
      while (!finished && instrCount < maxInstr && totalCycles < maxCycles) {
        val instr = readInstr32(pc)
        val itype = instrType(instr)
        if (debug) println(f"[$label] PC=0x$pc%04x instr=0x$instr%08x ($itype) [#${instrCount+1}]")

        sendInstr(instr)
        instrCount += 1

        itype match {
          case "jal" =>
            val offset = decodeJalOffset(instr)
            if (offset == 0) {
              if (debug) println(f"[$label]   Infinite loop at PC=0x$pc%04x")
              finished = true
            } else {
              val branchAddr = expectBranch(early = true)
              if (branchAddr >= 0) pc = branchAddr * 2
            }

          case "jalr" =>
            val branchAddr = expectBranch()
            if (branchAddr >= 0) pc = branchAddr * 2

          case "branch" =>
            step(1)
            dut.instr_ready.poke(false.B)
            var taken = false
            for (_ <- 0 until 24 if !taken && !finished) {
              step(1)
              if (dut.instr_fetch_restart.peek().litToBoolean) {
                val addr = dut.instr_addr.peek().litValue.toInt
                dut.instr_fetch_started.poke(true.B)
                pc = addr * 2
                taken = true
                if (debug) println(f"[$label]   Branch taken → 0x$pc%04x")
              }
            }
            if (!taken) {
              pc += 4
              if (debug) println(f"[$label]   Not taken, PC=0x$pc%04x")
            }

          case "store" =>
            val data = expectStore()
            if (data == 0xDEADL) {
              doneWritten = true
              doneValue = data
              finished = true
            }
            pc += 4

          case "load" =>
            step(1)
            dut.instr_ready.poke(false.B)
            var loadDone = false
            for (_ <- 0 until 24 if !finished && !loadDone) {
              step(1)
              if (dut.data_read_n.peek().litValue.toInt != 3) {
                step(1)
                dut.data_in.poke(0.U)
                dut.data_ready.poke(true.B)
                step(1)
                dut.data_ready.poke(false.B)
                loadDone = true
              }
            }
            pc += 4

          case _ =>
            pc += 4
        }
      }
    }

    val result = TestResult(totalCycles, instrCount, hungDetected, doneWritten, doneValue, storeCount)
    println(s"[$label] Cycles=${result.totalCycles}, Instructions=${result.instrCount}, " +
            s"Hung=${result.hung}, DoneWritten=${result.doneWritten}, " +
            f"DoneValue=0x${result.doneValue}%x, Stores=${result.storeCount}")
    result
  }

  val tests = Tests {
    utest.test("flat loop should complete as baseline") {
      val result = runCpuTest(flatProgram, "FLAT", debug = true)
      utest.assert(result.doneWritten)
      utest.assert(result.doneValue == 0xDEADL)
    }

    utest.test("nested loop should complete without hanging") {
      val result = runCpuTest(nestedProgram, "NESTED", debug = true)
      utest.assert(result.doneWritten)
      utest.assert(result.doneValue == 0xDEADL)
    }
  }
}
