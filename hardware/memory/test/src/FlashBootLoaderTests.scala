// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package memory

// D.2 gate test: FlashBootLoader elaborates and the FSM starts in sWaitSdram
// (boot_done=false, flash CS deasserted on reset, SPI clock low).

import chisel3.{fromBooleanToLiteral, fromIntToLiteral, fromIntToWidth}
import chisel3.simulator.EphemeralSimulator._
import utest._

object FlashBootLoaderTests extends TestSuite {
  val tests: Tests = Tests {

    test("D2_elaborates_and_resets_correctly") {
      simulate(new FlashBootLoader()) { dut =>
        // Drive required inputs
        dut.io.flash_miso.poke(false.B)
        dut.io.backend.dataOut.poke(0.U)
        dut.io.backend.dataReq.poke(false.B)
        dut.io.backend.dataReady.poke(false.B)
        dut.io.backend.busy.poke(false.B)

        // Explicit reset
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)

        // After reset: boot_done=false, CS high (deasserted), SPI clock low
        dut.clock.step(1)
        Predef.assert(dut.io.boot_done.peek().litValue == 0,    "boot_done must be false on reset")
        Predef.assert(dut.io.flash_csn.peek().litValue == 1,    "CS must be deasserted (high) on reset")
        Predef.assert(dut.io.spi_clk.peek().litValue == 0,      "SPI clock must be low on reset")
        Predef.assert(dut.io.backend.startWrite.peek().litValue == 0, "no write on reset")

        // After SDRAM_INIT_CYCLES + slack, CS should go low (SPI starts)
        dut.clock.step(13300)
        Predef.assert(dut.io.flash_csn.peek().litValue == 0, "CS must assert after SDRAM init wait")

        println("D2: FlashBootLoader elaborates and resets correctly ✓")
      }
    }
  }
}
