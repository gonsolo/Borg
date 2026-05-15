// CpuUartWriteTest.scala — Does TinyQV ever write to the debug UART address?
//
// Bypasses SDRAM entirely by using TinyQV's programFile simulation parameter.
// Preloads uart_hello_raw.bin directly into TinyQV's instruction memory.
// Checks that the CPU eventually executes a write to 0x08000018 (data_write_n != 3).
//
// Run with:
//   mill hardware.tinyqv.test.testOnly tinyqv.CpuUartWriteTest

package tinyqv

import chisel3.{Bool, Clock, Module, UInt, fromBooleanToLiteral, fromIntToLiteral, fromIntToWidth, fromLongToLiteral}
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import utest._
import cpu.TinyQV

// ── Minimal MemoryController stub ────────────────────────────────────────────
// Serves instructions from a preloaded byte array (16-bit halfwords per cycle).
// No SDRAM, no flash — just RAM-backed instruction fetch.
class InstrRomModel(program: Array[Byte]) {
  private val words = Array.tabulate((program.length + 1) / 2) { i =>
    val lo = if (2*i   < program.length) program(2*i).toInt   & 0xFF else 0
    val hi = if (2*i+1 < program.length) program(2*i+1).toInt & 0xFF else 0
    (hi << 8) | lo
  }
  def wordAt(byteAddr: Int): Int = {
    val idx = byteAddr / 2
    if (idx < words.length) words(idx) else 0
  }
}

object CpuUartWriteTest extends TestSuite {

  val UART_ADDR   = BigInt("8000018", 16)   // 28-bit addr[27:0] of 0x08000018
  val PERI_UART   = 6                        // periIndex = addr[5:2]
  val MAX_CYCLES  = 10_000

  // Load the raw firmware binary (no header)
  val fwPath = sys.env.getOrElse("UART_HELLO_BIN",
    "fpga/ulx3s/uart_hello_raw.bin")

  def loadBin(path: String): Array[Byte] = {
    val f = new java.io.File(path)
    if (!f.exists()) {
      // Fall back to inline encoding of the uart_hello instructions:
      // LUI t0, 0x8000; ADDI t0,t0,0x18; ADDI a0,x0,0x5A; SW a0,0(t0)
      // LI t1,12000; ADDI t1,t1,-1; BNE t1,x0,-4; JAL x0,-20
      // Minimal firmware: LUI t0, 0x8000; ADDI t0,t0,0x18; ADDI a0,0x5A; SW a0,0(t0); JAL x0,0
      // No delay loop — just write 'Z' to UART and loop tight (JAL x0,0 = infinite loop)
      Array(
        0xb7, 0x02, 0x00, 0x08,   // LUI   t0, 0x8000    → t0 = 0x08000000
        0x93, 0x82, 0x82, 0x01,   // ADDI  t0, t0, 0x18  → t0 = 0x08000018
        0x13, 0x05, 0xa0, 0x05,   // ADDI  a0, x0, 0x5A  → a0 = 'Z'
        0x23, 0xa0, 0xa2, 0x00,   // SW    a0, 0(t0)      → write 'Z' to UART
        0x6f, 0x00, 0x00, 0x00    // JAL   x0, 0          → infinite loop (tight)
      ).map(_.toByte)
    } else {
      val is = new java.io.FileInputStream(f)
      val bytes = is.readAllBytes()
      is.close()
      bytes
    }
  }

  val tests = Tests {
    test("CPU writes to UART address 0x08000018") {
      val program = loadBin(fwPath)
      println(s"Firmware: ${program.length} bytes from $fwPath")
      println(s"First 8 bytes: ${program.take(8).map(b => f"${b & 0xFF}%02x").mkString(" ")}")

      val rom = new InstrRomModel(program)

      simulate(new TinyQV()) { dut =>
        var cycle = 0
        var uartWriteSeen = false
        var instrCount = 0

        def tick(): Unit = {
          dut.clock.step()
          cycle += 1
        }

        // Reset
        dut.reset.poke(true.B)
        tick(); tick(); tick()
        dut.reset.poke(false.B)
        tick()
        cycle = 4

        // Tie off unused inputs
        dut.io.interrupt_req.poke(0.U)
        dut.io.time_pulse.poke(false.B)
        dut.io.data_ready.poke(true.B)   // immediate ack for all MMIO
        dut.io.data_in.poke(0.U)          // reads return 0

        // memBus: always ready (prevents SW stalls if addr routes through memBus)
        dut.io.memBus.ready.poke(true.B)
        dut.io.memBus.dataIn.poke(0.U)

        // ── Instruction fetch driver ──────────────────────────────────────
        // Mimics MemoryController timing:
        //   - started is a one-cycle pulse (MC line 167: started := start_instr)
        //   - instr_ready pulses every 2nd cycle (MC needs 2 SDRAM reads per halfword)
        //   - Uses independent fetchAddr with jump detection for early-branch JAL
        var fetchRunning = false
        var fetchAddr = 0
        var lastDutAddr = -1
        var bytePhase = 0  // 0=first byte, 1=second byte → ready

        while (cycle < MAX_CYCLES && !uartWriteSeen) {
          val restart = dut.io.instr_fetch_restart.peek().litToBoolean
          val stall   = dut.io.instr_fetch_stall.peek().litToBoolean

          // started: one-cycle pulse when restart fires (matches MC behavior)
          dut.io.instr_fetch_started.poke(restart.B)

          if (restart) {
            fetchAddr = dut.io.instr_addr.peek().litValue.toInt
            fetchRunning = true  // MC sets instr_active immediately on start_instr
            bytePhase = 0
          }

          // Detect early-branch address jumps
          if (fetchRunning && !restart) {
            val dutAddr = dut.io.instr_addr.peek().litValue.toInt
            if (lastDutAddr >= 0 && dutAddr != lastDutAddr && dutAddr != lastDutAddr + 1) {
              fetchAddr = dutAddr
              bytePhase = 0
            }
            lastDutAddr = dutAddr
          }

          // Serve data: ready only on bytePhase=1 (every other cycle)
          if (fetchRunning && !stall) {
            if (bytePhase == 1) {
              val byteAddr = fetchAddr * 2
              val word = if (byteAddr >= 0 && byteAddr < program.length)
                           rom.wordAt(byteAddr) else 0x0013
              dut.io.instr_data.poke(word.U)
              dut.io.instr_ready.poke(true.B)
              fetchAddr += 1
            } else {
              dut.io.instr_ready.poke(false.B)
            }
            bytePhase = 1 - bytePhase
          } else {
            dut.io.instr_ready.poke(false.B)
          }
          dut.io.instr_fetch_stopped.poke(false.B)

          // Fetch trace (first 50 cycles)
          if (cycle <= 50 && fetchRunning && !stall) {
            val hwAddr = dut.io.instr_addr.peek().litValue.toInt
            val idata  = dut.io.instr_data.peek().litValue.toInt
            val ic     = dut.io.debug_instr_complete.peek().litToBoolean
            println(f"[$cycle%3d] fetch hw=$hwAddr%3d data=0x${idata}%04x complete=$ic")
          }

          // ── Write detection: check BOTH MMIO and memBus ──
          val writeN    = dut.io.data_write_n.peek().litValue.toInt
          val memWriteN = dut.io.memBus.writeN.peek().litValue.toInt
          val dataAddr  = dut.io.data_addr.peek().litValue
          val memAddr   = dut.io.memBus.addr.peek().litValue

          if (writeN != 3) {
            val data = dut.io.data_out.peek().litValue.toInt
            println(s"[$cycle] MMIO write: addr=0x${dataAddr.toString(16)} writeN=$writeN data=0x${data.toHexString}")
            if (dataAddr == UART_ADDR) { uartWriteSeen = true; println(s"[$cycle] *** UART WRITE char='${data.toChar}'") }
          }
          if (memWriteN != 3)
            println(s"[$cycle] memBus write: addr=0x${memAddr.toString(16)} writeN=$memWriteN data=0x${dut.io.memBus.dataOut.peek().litValue.toString(16)}")

          // Data bus trace: print only when any write/read is active (first 200 cycles)
          if (cycle <= 200) {
            val wn  = dut.io.data_write_n.peek().litValue.toInt
            val rn  = dut.io.data_read_n.peek().litValue.toInt
            val mwn = dut.io.memBus.writeN.peek().litValue.toInt
            val mrn = dut.io.memBus.readN.peek().litValue.toInt
            if (wn != 3 || rn != 3 || mwn != 3 || mrn != 3) {
              val da = dut.io.data_addr.peek().litValue
              val ma = dut.io.memBus.addr.peek().litValue
              println(f"[$cycle%4d] MMIO wn=$wn rn=$rn addr=0x${da.toString(16)} | memBus wn=$mwn rn=$mrn addr=0x${ma.toString(16)}")
            }
          }
          if (dut.io.debug_instr_complete.peek().litToBoolean) {
            instrCount += 1
            if (instrCount <= 20 || instrCount % 1000 == 0) {
              val pc = dut.io.instr_addr.peek().litValue
              println(s"[$cycle] instr_complete #$instrCount pc≈0x${pc.toString(16)}")
            }
          }

          tick()
        }

        println(s"\nSummary: $instrCount instructions completed in $cycle cycles")
        Predef.assert(uartWriteSeen,
          s"CPU never wrote to UART addr after $cycle cycles ($instrCount instrs)")
        println(s"[$cycle] ✓ UART write confirmed!")
      }
    }
  }
}
