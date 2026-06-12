// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Validate SdramChipModel against the REAL SdramBackend+SdramController: write a
// few words, read them back, and confirm round-trip.  Sweeps readLatency to pin
// the cycle alignment that matches how the controller samples DQ.
package memory

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import utest._

class SdramRTHarnessIO extends Bundle {
  val startRead  = Input(Bool())
  val startWrite = Input(Bool())
  val addrIn     = Input(UInt(24.W))
  val dataIn     = Input(UInt(16.W))
  val byteEnIn   = Input(UInt(2.W))
  val lenIn      = Input(UInt(7.W))
  val dataOut    = Output(UInt(16.W))
  val done       = Output(Bool())
  val busy       = Output(Bool())
  val ctrlState  = Output(UInt(3.W))
}

class SdramRTHarness(clockMhz: Int = 25, readLatency: Int = 2) extends Module {
  val io      = IO(new SdramRTHarnessIO)
  val backend = Module(new SdramBackend(clockMhz))
  val chip    = Module(new SdramChipModel(readLatency = readLatency))

  // Chip drives dq_in; controller drives the rest.
  chip.io <> backend.io.sdramPins
  chip.dbg.we    := false.B
  chip.dbg.waddr := 0.U
  chip.dbg.wdata := 0.U
  chip.dbg.raddr := 0.U

  backend.io.backend.startRead  := io.startRead
  backend.io.backend.startWrite := io.startWrite
  backend.io.backend.addrIn     := io.addrIn
  backend.io.backend.dataIn     := io.dataIn
  backend.io.backend.byteEnIn   := io.byteEnIn
  backend.io.backend.lenIn      := io.lenIn
  io.dataOut   := backend.io.backend.dataOut
  io.done      := backend.io.backend.done
  io.busy      := backend.io.backend.busy
  io.ctrlState := backend.io.debug_ctrl_state
}

object SdramChipModelTests extends TestSuite {
  val tests = Tests {
    utest.test("SdramChipModel round-trips through the real SdramBackend") {
      def rt(readLatency: Int): Boolean = {
        var ok = false
        simulate(new SdramRTHarness(clockMhz = 25, readLatency = readLatency)) { dut =>
          dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B)
          dut.io.startRead.poke(false.B); dut.io.startWrite.poke(false.B)
          dut.io.lenIn.poke(1.U); dut.io.byteEnIn.poke(0.U)
          // Let init + first refresh complete (INITLEN=2500 @25MHz + margin).
          dut.clock.step(6000)

          def waitDone(max: Int = 400): Unit = {
            var n = 0
            while (dut.io.done.peek().litValue == 0 && n < max) { dut.clock.step(1); n += 1 }
            dut.clock.step(1)
          }
          def write(addr: Int, data: Int): Unit = {
            dut.io.addrIn.poke(addr.U); dut.io.dataIn.poke(data.U); dut.io.byteEnIn.poke(0.U)
            dut.io.startWrite.poke(true.B); dut.clock.step(1); dut.io.startWrite.poke(false.B)
            waitDone()
          }
          def read(addr: Int): BigInt = {
            dut.io.addrIn.poke(addr.U)
            dut.io.startRead.poke(true.B); dut.clock.step(1); dut.io.startRead.poke(false.B)
            // capture dataOut on the done pulse
            var n = 0; var v = BigInt(-1)
            while (n < 400) {
              if (dut.io.done.peek().litValue == 1) { v = dut.io.dataOut.peek().litValue }
              dut.clock.step(1); n += 1
              if (v >= 0) { dut.clock.step(1); return v }
            }
            v
          }
          val tv = Seq(0x100 -> 0x1234, 0x101 -> 0x7bff, 0x102 -> 0x3266, 0x200 -> 0xabcd)
          tv.foreach { case (a, d) => write(a, d) }
          val rd = tv.map { case (a, d) => (a, d, read(a).toInt & 0xffff) }
          ok = rd.forall { case (_, d, r) => r == d }
          rd.foreach { case (a, d, r) =>
            println(f"[chip rL=$readLatency] addr=0x$a%03x wrote=0x$d%04x read=0x$r%04x ${if (r == d) "ok" else "MISMATCH"}")
          }
        }
        ok
      }
      var pinned = -1
      for (rl <- 1 to 4) {
        val ok = rt(rl)
        println(f"[chip] readLatency=$rl round-trip ${if (ok) "PASS" else "fail"}")
        if (ok && pinned < 0) pinned = rl
      }
      println(s"[chip] pinned readLatency = $pinned")
      Predef.assert(pinned > 0, "no readLatency round-trips — chip model timing wrong")
    }
  }
}
