// SPDX-FileCopyrightText: © 2026 Ziad Mubarak

package memory

import chisel3.{Bool, UInt}
import chisel3.{fromBooleanToLiteral, fromIntToWidth, fromIntToLiteral, fromBigIntToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

// W1, R1–R2: MigUiDriver tests (the test acts as the MIG).
//
// These tests drive MigUiDriver's request side and verify that:
//   W1 — a write to word 3 sends the right data, mask (0xFF3F), command and address, then pulses done
//   R1 — a read of word 5 returns slot 5 of the 128-bit block on rdata
//   R2 — while the MIG is not ready, the driver holds app_en and waits, then finishes once app_rdy goes high

object MigUiDriverTests extends TestSuite {

  def initDriver(dut: MigUiDriver): Unit = {
    dut.reset.poke(true.B)

    // poke every input to 0 / false here
    dut.io.start.poke(false.B)
    dut.io.isWrite.poke(false.B)
    dut.io.addr.poke(0.U)
    dut.io.wdata.poke(0.U)

    dut.io.mig.app_rdy.poke(false.B)
    dut.io.mig.app_wdf_rdy.poke(false.B)
    dut.io.mig.app_rd_data.poke(0.U)
    dut.io.mig.app_rd_data_valid.poke(false.B)
    dut.io.mig.init_calib_complete.poke(false.B)

    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  val tests: Tests = Tests {
    test("W1_write_word") {
        simulate(new MigUiDriver) { dut =>
        initDriver(dut)
       
        dut.io.mig.app_wdf_rdy.poke(true.B)
        dut.io.mig.app_rdy.poke(true.B)

        dut.io.isWrite.poke(true.B)
        dut.io.addr.poke(3.U)
        dut.io.wdata.poke(0xCDAB.U)
        dut.io.start.poke(true.B)
        dut.clock.step(1)
        dut.io.start.poke(false.B)

        dut.io.mig.app_wdf_wren.expect(true.B)
        dut.io.mig.app_wdf_mask.expect(0xFF3F.U)
        dut.io.mig.app_wdf_data.expect(BigInt("CDAB" * 8, 16).U)

        dut.clock.step(1)
        dut.io.mig.app_en.expect(true.B)
        dut.io.mig.app_cmd.expect(0.U)
        dut.io.mig.app_addr.expect(0.U)

        dut.clock.step(1)
        dut.io.done.expect(true.B)
        
        dut.clock.step(1)
        dut.io.done.expect(false.B)

        }
    }
  }
}