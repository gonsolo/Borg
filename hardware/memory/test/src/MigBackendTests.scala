// SPDX-FileCopyrightText: © 2026 Ziad Mubarak

package memory

import chisel3.{Bool, UInt}
import chisel3.{fromBooleanToLiteral, fromIntToWidth, fromIntToLiteral, fromBigIntToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

// MigBackend tests: the whole adapter, with both clocks running
// The test acts as MemoryController on one side and the MIG on the other.
// Tests:
// busy_until_calibrated: busy stays 1 until the MIG's calibration signal has passed through the two flip-flops
//
//

object MigBackendTests extends TestSuite {

    def tick(dut: MigBackend, n: Int): Unit = {
        for (_ <- 0 until n) {
            dut.clock.step(1)
            for (_ <- 0 until 4) {
                dut.io.ui_clk.step(1)
            }
        }
    }

    def initBackend(dut: MigBackend): Unit = {
        dut.reset.poke(true.B)
        dut.io.ui_rst.poke(true.B)

        dut.io.backend.startRead.poke(false.B)
        dut.io.backend.startWrite.poke(false.B)
        dut.io.backend.addrIn.poke(0.U)
        dut.io.backend.dataIn.poke(0.U)
        dut.io.backend.byteEnIn.poke(0.U)
        dut.io.backend.lenIn.poke(0.U)

        dut.io.mig.app_rdy.poke(false.B)
        dut.io.mig.app_wdf_rdy.poke(false.B)
        dut.io.mig.app_rd_data_valid.poke(false.B)
        dut.io.mig.init_calib_complete.poke(false.B)
        dut.io.mig.app_rd_data.poke(0.U)

        tick(dut, 2)

        dut.reset.poke(false.B)
        dut.io.ui_rst.poke(false.B)
    }

    val tests: Tests = Tests {
        test("busy_until_calibrated") {
            simulate(new MigBackend) { dut =>
                initBackend(dut)

                dut.io.backend.busy.expect(true.B)

                dut.io.mig.init_calib_complete.poke(true.B)
                tick(dut, 1)
                dut.io.backend.busy.expect(true.B)
                tick(dut, 1)
                dut.io.backend.busy.expect(false.B)
                
            }
        }
    }

    test("write_reaches_mig") {
        simulate(new MigBackend) { dut =>
            initBackend(dut)

            dut.io.mig.init_calib_complete.poke(true.B)
            tick(dut, 2)
            dut.io.backend.busy.expect(false.B)

            dut.io.mig.app_wdf_rdy.poke(false.B)
            dut.io.mig.app_rdy.poke(false.B)

            dut.io.backend.addrIn.poke(3.U)
            dut.io.backend.dataIn.poke(0xCDAB.U)
            dut.io.backend.byteEnIn.poke(3.U)
            dut.io.backend.lenIn.poke(1.U)
            dut.io.backend.startWrite.poke(true.B)
            tick(dut, 1)
            dut.io.backend.startWrite.poke(false.B)

            dut.io.backend.busy.expect(true.B)

            tick(dut, 2)
                
        }
    }
}