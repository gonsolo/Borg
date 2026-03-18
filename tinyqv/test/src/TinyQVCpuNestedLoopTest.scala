// Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0
//
// Test to reproduce nested loop hang bug in TinyQV CPU.
// Faithful translation of the Cocotb protocol from test_cpu.py.

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
  val instr_data_in = IO(Input(UInt(16.W)))
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

  instr_addr := cpu.io.instr_addr
  instr_fetch_restart := cpu.io.instr_fetch_restart
  instr_fetch_stall := cpu.io.instr_fetch_stall
  cpu.io.instr_fetch_started := instr_fetch_started
  cpu.io.instr_fetch_stopped := instr_fetch_stopped
  cpu.io.instr_data_in := instr_data_in
  cpu.io.instr_ready := instr_ready
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

  // Nested loop: 4x4 iterations with jal/ret (verified with riscv32-none-elf-as)
  // _start:     lui sp, 0x11
  //             j outer_start
  // func:       addi a0,a0,1
  //             ret
  // outer_start: li s1,0
  // outer:      li s0,0
  //             li a0,0
  // inner:      jal ra,func
  //             addi s0,s0,1
  //             li a5,4
  //             bne s0,a5,inner
  //             addi s1,s1,1
  //             li a5,4
  //             bne s1,a5,outer
  //             lui a4,0xe
  //             addi a4,a4,-339
  //             lui a5,0x1001
  //             sw a4,1280(a5)
  // hang:       j hang
  val nestedProgram: Array[Int] = Array(
    0x1137, 0x0001, // 0x00: lui sp, 0x11
    0xa019,         // 0x04: j outer_start
    0x0505,         // 0x06: addi a0,a0,1   [func]
    0x8082,         // 0x08: ret
    0x4481,         // 0x0a: li s1,0         [outer_start]
    0x4401,         // 0x0c: li s0,0         [outer]
    0x4501,         // 0x0e: li a0,0
    0xf0ef, 0xff7f, // 0x10: jal ra,func     [inner]
    0x0405,         // 0x14: addi s0,s0,1
    0x4791,         // 0x16: li a5,4
    0x1ce3, 0xfef4, // 0x18: bne s0,a5,inner
    0x0485,         // 0x1c: addi s1,s1,1
    0x4791,         // 0x1e: li a5,4
    0x96e3, 0xfef4, // 0x20: bne s1,a5,outer
    0x6739,         // 0x24: lui a4,0xe
    0x0713, 0xead7, // 0x26: addi a4,a4,-339
    0x17b7, 0x0100, // 0x2a: lui a5,0x1001
    0xa023, 0x50e7, // 0x2e: sw a4,1280(a5)
    0xa001           // 0x32: j hang
  )

  // Flat loop: 16 iterations, no nesting (verified with riscv32-none-elf-as)
  val flatProgram: Array[Int] = Array(
    0x1137, 0x0001, // 0x00: lui sp, 0x11
    0xa019,         // 0x04: j loop_start
    0x0505,         // 0x06: addi a0,a0,1   [func]
    0x8082,         // 0x08: ret
    0x4481,         // 0x0a: li s1,0         [loop_start]
    0x4501,         // 0x0c: li a0,0
    0xf0ef, 0xff9f, // 0x0e: jal ra,func     [loop]
    0x0485,         // 0x12: addi s1,s1,1
    0x47c1,         // 0x14: li a5,16
    0x9ce3, 0xfef4, // 0x16: bne s1,a5,loop
    0x6739,         // 0x1a: lui a4,0xe
    0x0713, 0xead7, // 0x1c: addi a4,a4,-339
    0x17b7, 0x0100, // 0x20: lui a5,0x1001
    0xa023, 0x50e7, // 0x24: sw a4,1280(a5)
    0xa001           // 0x28: j hang
  )

  case class TestResult(
    totalCycles: Int, instrCount: Int, hung: Boolean,
    doneWritten: Boolean, doneValue: Long, storeCount: Int
  )

  /**
   * Run a CPU test following the EXACT Cocotb protocol from test_cpu.py.
   *
   * The protocol for each instruction:
   * 1. send_instr: 1 cycle gap (instr_ready=0), 7 idle cycles, present first halfword (instr_ready=1)
   *    For 32-bit: 1 cycle (instr_ready=0), 7 idle cycles, present second halfword (instr_ready=1)
   * 2. After instruction: check for branch/store signals
   *    - expect_branch: poll instr_fetch_restart for up to 24 cycles
   *    - expect_store: poll data_write_n for up to 24 cycles
   *
   * For running a program from ROM, we track a PC and decide what to do after each instruction.
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
      // ===== Helper: step and track total cycles =====
      def step(n: Int = 1): Unit = {
        for (_ <- 0 until n) {
          dut.clock.step(1)
          totalCycles += 1
        }
      }

      // ===== Cocotb-faithful: send_instr =====
      // Matches the Python send_instr function exactly.
      // After return, instr_ready stays HIGH for the last halfword.
      def sendInstr(instr: Int, len: Int = 4): Unit = {
        // 1 cycle gap
        step(1)
        dut.instr_fetch_started.poke(false.B)
        dut.instr_ready.poke(false.B)
        dut.time_pulse.poke(false.B)

        // 7 idle cycles (= total 8 clocks between halfwords)
        step(7)

        // Present first (low) halfword
        dut.instr_data_in.poke((instr & 0xFFFF).U)
        dut.instr_ready.poke(true.B)

        if (len == 4) {
          step(1)
          dut.instr_ready.poke(false.B)

          // 7 idle cycles
          step(7)

          // Present second (high) halfword
          dut.instr_data_in.poke(((instr >> 16) & 0xFFFF).U)
          dut.instr_ready.poke(true.B)
        }
        // Leave instr_ready=1; next sendInstr or expectBranch will clear it.
      }

      // ===== Cocotb-faithful: expect_branch =====
      // Wait for instr_fetch_restart, then ack with instr_fetch_started.
      // Returns the branch target as halfword address.
      def expectBranch(early: Boolean = false): Int = {
        step(1)
        dut.instr_ready.poke(false.B)

        for (i <- 0 until 24) {
          step(1)
          val restart = dut.instr_fetch_restart.peek().litToBoolean
          val addr = dut.instr_addr.peek().litValue.toInt
          val ch = dut.debug_counter_hi.peek().litValue.toInt
          val pc = dut.debug_pc.peek().litValue.toLong
          val imm = dut.debug_imm.peek().litValue.toLong
          val stall = dut.instr_fetch_stall.peek().litToBoolean
          if (debug) println(f"[$label]   poll[$i] ch=$ch restart=$restart addr=$addr%d(0x${addr*2}%04x) pc=0x$pc%08x imm=0x$imm%08x stall=$stall")
          if (restart) {
            dut.instr_fetch_started.poke(true.B)
            return addr
          }
        }
        println(s"[$label] ERROR: Timed out waiting for branch!")
        finished = true
        -1
      }

      // ===== Cocotb-faithful: expect_store =====
      // Wait for data_write_n != 3, then ack with data_ready pulse.
      // Returns the stored data.
      def expectStore(): Long = {
        step(1)
        dut.instr_ready.poke(false.B)
        // Cocotb asserts data_write_n == 3 here; we just check

        for (_ <- 0 until 24) {
          step(1)
          val writeN = dut.data_write_n.peek().litValue.toInt
          if (writeN != 3) {
            val addr = dut.data_addr.peek().litValue.toLong
            val data = dut.data_out.peek().litValue.toLong
            storeCount += 1
            if (debug) println(f"[$label] STORE addr=0x$addr%08x data=0x$data%08x")

            // Wait 1 cycle then ack
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

      // ===== Reset (matching Cocotb start() function) =====
      dut.reset.poke(true.B)     // rstn=0 (active-low reset)
      dut.interrupt_req.poke(0.U)
      dut.time_pulse.poke(false.B)
      step(1)                    // Cocotb: await ClockCycles(1) then set rstn=0
      dut.reset.poke(true.B)
      step(2)                    // Cocotb: await ClockCycles(2)

      dut.instr_fetch_started.poke(false.B)
      dut.instr_fetch_stopped.poke(false.B)
      dut.instr_data_in.poke(0.U)
      dut.instr_ready.poke(false.B)
      dut.data_ready.poke(false.B)
      dut.data_in.poke(0.U)
      step(10)                   // Cocotb: await ClockCycles(10)

      // De-assert reset
      dut.reset.poke(false.B)   // rstn=1
      step(1)                    // Cocotb: await ClockCycles(1)

      // Check initial state
      val initAddr = dut.instr_addr.peek().litValue.toInt
      val initRestart = dut.instr_fetch_restart.peek().litToBoolean
      if (debug) println(f"[$label] After reset: addr=$initAddr, restart=$initRestart")

      Predef.assert(initRestart, s"[$label] No initial instr_fetch_restart!")

      dut.instr_fetch_started.poke(true.B)
      // Leave started=1; next sendInstr will clear it.

      // ===== ROM execution engine =====
      // Track PC as byte address. Determine instruction type and handle accordingly.
      var pc = 0 // byte address

      // Helper: read instruction at halfword index from ROM
      def romHW(hwIdx: Int): Int = if (hwIdx < rom.length) rom(hwIdx) else 0

      // Helper: read 32-bit instruction at byte address
      def readInstr32(byteAddr: Int): Int = {
        val hwIdx = byteAddr / 2
        romHW(hwIdx) | (romHW(hwIdx + 1) << 16)
      }

      // Helper: check if halfword starts a 32-bit instruction
      def is32bit(hw: Int): Boolean = (hw & 3) == 3

      // Helper: decode branch target from instruction
      // For our programs, we know which instructions are branches.
      // We use instruction type detection to decide what to do after sending.

      // RISC-V instruction type detection
      def instrType(instr: Int, compressed: Boolean): String = {
        if (compressed) {
          val op = instr & 3
          val funct3 = (instr >> 13) & 7
          (op, funct3) match {
            case (1, 5) => "c.j"       // C.J: funct3=101, op=01
            case (1, 1) => "c.jal"     // C.JAL (RV32 only): funct3=001, op=01
            case (2, _) if ((instr >> 12) & 1) == 1 && ((instr >> 7) & 0x1f) != 0 && ((instr >> 2) & 0x1f) == 0 =>
              if (((instr >> 7) & 0x1f) == 1) "c.jalr" else "c.jr"
            case (2, _) if ((instr >> 12) & 1) == 0 && ((instr >> 7) & 0x1f) != 0 && ((instr >> 2) & 0x1f) == 0 =>
              "c.jr"
            case _ => "other_c"
          }
        } else {
          val opcode = instr & 0x7f
          opcode match {
            case 0x6f => "jal"      // JAL
            case 0x67 => "jalr"     // JALR
            case 0x63 => "branch"   // BEQ/BNE/BLT/BGE/BLTU/BGEU
            case 0x23 => "store"    // SW/SH/SB
            case 0x03 => "load"     // LW/LH/LB/LHU/LBU
            case _ => "other"
          }
        }
      }

      // Decode branch offset for B-type instruction
      def decodeBranchOffset(instr: Int): Int = {
        val imm12 = (instr >> 31) & 1
        val imm10_5 = (instr >> 25) & 0x3f
        val imm4_1 = (instr >> 8) & 0xf
        val imm11 = (instr >> 7) & 1
        var imm = (imm12 << 12) | (imm11 << 11) | (imm10_5 << 5) | (imm4_1 << 1)
        if (imm12 != 0) imm -= (1 << 13) // sign extend
        imm
      }

      // Decode JAL offset
      def decodeJalOffset(instr: Int): Int = {
        val imm20 = (instr >> 31) & 1
        val imm10_1 = (instr >> 21) & 0x3ff
        val imm11 = (instr >> 20) & 1
        val imm19_12 = (instr >> 12) & 0xff
        var imm = (imm20 << 20) | (imm19_12 << 12) | (imm11 << 11) | (imm10_1 << 1)
        if (imm20 != 0) imm -= (1 << 21) // sign extend
        imm
      }

      // Decode C.J offset (compressed JAL/J)
      def decodeCJOffset(instr: Int): Int = {
        // C.J encoding: imm[11|4|9:8|10|6|7|3:1|5] at bits [12:2]
        val bits = (instr >> 2) & 0x7ff
        val bit5 = (bits >> 0) & 1
        val bit3_1 = (bits >> 1) & 7
        val bit7 = (bits >> 4) & 1
        val bit6 = (bits >> 5) & 1
        val bit10 = (bits >> 6) & 1
        val bit9_8 = (bits >> 7) & 3
        val bit4 = (bits >> 9) & 1
        val bit11 = (bits >> 10) & 1
        var imm = (bit11 << 11) | (bit10 << 10) | (bit9_8 << 8) | (bit7 << 7) | (bit6 << 6) | (bit5 << 5) | (bit4 << 4) | (bit3_1 << 1)
        if (bit11 != 0) imm -= (1 << 12)
        imm
      }

      // Decode C.BEQZ/C.BNEZ offset
      def decodeCBOffset(instr: Int): Int = {
        val bit5 = (instr >> 2) & 1
        val bit2_1 = (instr >> 3) & 3
        val bit7_6 = (instr >> 5) & 3
        val bit3_4 = (instr >> 10) & 3  // actually bits [4:3]
        val bit8 = (instr >> 12) & 1
        var imm = (bit8 << 8) | (bit7_6 << 6) | (bit5 << 5) | (bit3_4 << 3) | (bit2_1 << 1)
        if (bit8 != 0) imm -= (1 << 9)
        imm
      }

      var maxInstr = 500 // safety limit
      while (!finished && instrCount < maxInstr && totalCycles < maxCycles) {
        val hwIdx = pc / 2
        val hw = romHW(hwIdx)
        val compressed = !is32bit(hw)

        val (instr, instrLen) = if (compressed) {
          (hw, 2)
        } else {
          (readInstr32(pc), 4)
        }

        val itype = instrType(instr, compressed)
        if (debug) println(f"[$label] PC=0x$pc%04x instr=0x$instr%08x ($itype) [#${instrCount+1}]")

        // Send instruction
        if (compressed) {
          sendInstr(instr, 2)
        } else {
          sendInstr(instr, 4)
        }
        instrCount += 1

        // Handle post-instruction actions
        itype match {
          case "jal" =>
            val offset = decodeJalOffset(instr)
            val target = pc + offset
            if (debug) println(f"[$label]   JAL target=0x$target%04x (offset=$offset)")
            val branchAddr = expectBranch(early = true)
            if (branchAddr >= 0) {
              pc = branchAddr * 2
              if (debug) println(f"[$label]   Branch to 0x$pc%04x")
            }

          case "c.j" | "c.jal" =>
            val offset = decodeCJOffset(instr)
            val target = pc + offset
            if (debug) println(f"[$label]   C.J target=0x$target%04x (offset=$offset)")
            val branchAddr = expectBranch(early = true)
            if (branchAddr >= 0) {
              pc = branchAddr * 2
              if (debug) println(f"[$label]   Branch to 0x$pc%04x")
            }

          case "branch" =>
            // Branch may or may not be taken. Check for restart.
            val offset = decodeBranchOffset(instr)
            val target = pc + offset
            if (debug) println(f"[$label]   BNE/branch target=0x$target%04x if taken (offset=$offset)")
            // Poll for branch - but branch might not be taken!
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
                if (debug) println(f"[$label]   Branch taken to 0x$pc%04x")
              }
            }
            if (!taken) {
              // Branch not taken, advance PC
              pc += instrLen
              if (debug) println(f"[$label]   Branch not taken, PC=0x$pc%04x")
            }

          case "c.jr" | "c.jalr" =>
            if (debug) println(f"[$label]   RET/JALR")
            val branchAddr = expectBranch(early = true)
            if (branchAddr >= 0) {
              pc = branchAddr * 2
              if (debug) println(f"[$label]   Return to 0x$pc%04x")
            }

          case "jalr" =>
            if (debug) println(f"[$label]   JALR")
            val branchAddr = expectBranch()
            if (branchAddr >= 0) {
              pc = branchAddr * 2
              if (debug) println(f"[$label]   JALR to 0x$pc%04x")
            }

          case "store" =>
            if (debug) println(f"[$label]   STORE instruction")
            val data = expectStore()
            if (data == 0xDEADL) {
              doneWritten = true
              doneValue = data
              finished = true
              if (debug) println(f"[$label]   DONE marker written!")
            }
            pc += instrLen

          case "load" =>
            if (debug) println(f"[$label]   LOAD instruction")
            // Expect load and provide 0
            step(1)
            dut.instr_ready.poke(false.B)
            var loadDone = false
            for (_ <- 0 until 24 if !finished && !loadDone) {
              step(1)
              val readN = dut.data_read_n.peek().litValue.toInt
              if (readN != 3) {
                step(1) // delay
                dut.data_in.poke(0.U)
                dut.data_ready.poke(true.B)
                step(1)
                dut.data_ready.poke(false.B)
                loadDone = true
              }
            }
            pc += instrLen

          case _ =>
            // Non-branch, non-store instruction: just advance PC
            pc += instrLen
        }

        // Check for hang in case of infinite j hang loop
        if (itype == "c.j" || itype == "c.jal") {
          val offset = decodeCJOffset(instr)
          if (offset == 0) {
            if (debug) println(f"[$label]   Infinite loop detected at PC=0x${pc}%04x")
            finished = true
          }
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
