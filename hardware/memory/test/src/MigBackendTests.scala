// SPDX-FileCopyrightText: © 2026 Ziad Mubarak

package memory

import chisel3.{Bool, UInt}
import chisel3.{fromBooleanToLiteral, fromIntToWidth, fromIntToLiteral, fromBigIntToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

// MigBackend tests: the whole adapter, with both clocks running
// The test acts as MemoryController on one side and the MIG on the other.

// busy_until_calibrated: busy stays 1 until the MIG's calibration signal has passed through the two flip-flops

// write_reaches_mig: a write from Borg reaches the MIG with the right data, mask, command and address, Borg then gets done

// read_returns_word: a read of word 5 returns 0xA005 from the 128-bit block the MIG hands back

// burst_write: a 3 word write burst asks for words 2 and 3 with accept, then gives one done

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

        test("write_reaches_mig") {
            simulate(new MigBackend) { dut =>
                initBackend(dut)

                // MIG calibrated, but not ready yet 
                dut.io.mig.init_calib_complete.poke(true.B)
                tick(dut, 2)
                dut.io.backend.busy.expect(false.B)

                dut.io.mig.app_wdf_rdy.poke(false.B)
                dut.io.mig.app_rdy.poke(false.B)

                // Borg sends the write: 0xCDAB to word 3
                dut.io.backend.addrIn.poke(3.U)
                dut.io.backend.dataIn.poke(0xCDAB.U)
                dut.io.backend.byteEnIn.poke(3.U)
                dut.io.backend.lenIn.poke(1.U)

                dut.io.backend.startWrite.poke(true.B)
                tick(dut, 1)
                dut.io.backend.startWrite.poke(false.B)
                dut.io.backend.busy.expect(true.B)
                tick(dut, 2)

                // Driver waits in sData: check the write data and mask
                dut.io.mig.app_wdf_wren.expect(true.B)
                dut.io.mig.app_wdf_mask.expect(0xFF3F.U)
                dut.io.mig.app_wdf_data.expect(BigInt("CDAB" * 8, 16).U)

                // MIG takes the data, driver waits in sCmd: check the command
                dut.io.mig.app_wdf_rdy.poke(true.B)
                tick(dut, 2)
                dut.io.mig.app_en.expect(true.B)
                dut.io.mig.app_cmd.expect(0.U)
                dut.io.mig.app_addr.expect(0.U)

                // MIG takes the command, wait for done to come back to Borg
                dut.io.mig.app_rdy.poke(true.B)
                var gotDone = false
                for (_ <- 0 until 20 if !gotDone) {
                    tick(dut, 1)
                    if (dut.io.backend.done.peek().litValue == 1) gotDone = true
                }
                assert(gotDone)
                dut.io.backend.done.expect(true.B)
            }
        }

        test("read_returns_word") {
            simulate(new MigBackend) { dut =>
                initBackend(dut)

                // MIG calibrated, but not ready yet (holds the driver in sCmd)
                dut.io.mig.init_calib_complete.poke(true.B)
                tick(dut, 2)
                dut.io.backend.busy.expect(false.B)

                dut.io.mig.app_rdy.poke(false.B)

                // Borg sends the read: word 5
                dut.io.backend.addrIn.poke(5.U)
                dut.io.backend.startRead.poke(true.B)
                tick(dut, 1)
                dut.io.backend.startRead.poke(false.B)
                dut.io.backend.busy.expect(true.B)
                tick(dut, 2)

                // Driver waits in sCmd: check the command
                dut.io.mig.app_en.expect(true.B)
                dut.io.mig.app_cmd.expect(1.U)
                dut.io.mig.app_addr.expect(0.U)

                // MIG takes the command, driver waits in sRead
                dut.io.mig.app_rdy.poke(true.B)
                tick(dut, 1)

                // MIG hands back the 128-bit block (word k holds 0xA00k)
                dut.io.mig.app_rd_data.poke(BigInt("A007A006A005A004A003A002A001A000", 16).U)
                dut.io.mig.app_rd_data_valid.poke(true.B)
                tick(dut, 1)
                dut.io.mig.app_rd_data_valid.poke(false.B)

                // Wait for done to come back to Borg, then check the word
                var gotDone = false
                for (_ <- 0 until 20 if !gotDone) {
                    tick(dut, 1)
                    if (dut.io.backend.done.peek().litValue == 1) gotDone = true
                }
                assert(gotDone)
                dut.io.backend.dataOut.expect(0xA005.U)
            }
        }
        test("burst_write") {
            simulate(new MigBackend) { dut =>
                initBackend(dut)

                dut.io.mig.init_calib_complete.poke(true.B)
                tick(dut, 2)
                dut.io.backend.busy.expect(false.B)

                dut.io.mig.app_wdf_rdy.poke(true.B)
                dut.io.mig.app_rdy.poke(true.B)

                dut.io.backend.addrIn.poke(8.U)
                dut.io.backend.dataIn.poke(0x1111.U)
                dut.io.backend.byteEnIn.poke(3.U)
                dut.io.backend.lenIn.poke(3.U)
                dut.io.backend.startWrite.poke(true.B)
                tick(dut, 1)
                dut.io.backend.startWrite.poke(false.B)

                val nextWords = Seq(0x2222, 0x3333)   
                var acceptCount = 0
                var gotDone = false

                for (_ <- 0 until 100 if !gotDone) {
                    tick(dut, 1)
                    if (dut.io.backend.accept.peek().litValue == 1) {
                        dut.io.backend.dataIn.poke(nextWords(acceptCount).U)
                        acceptCount += 1
                    }
                    if (dut.io.backend.done.peek().litValue == 1) gotDone = true
                }

                assert(gotDone)
                assert(acceptCount == 2)
            }
        }
    }
}