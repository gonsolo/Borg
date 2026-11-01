// SPDX-FileCopyrightText: © 2026 Ziad Mubarak

package memory

import chisel3.{Bool, UInt}
import chisel3.{fromBooleanToLiteral, fromIntToWidth, fromIntToLiteral, fromBigIntToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

object ToggleSyncTests extends TestSuite {

    def initSync(dut: ToggleSync): Unit = {
        dut.reset.poke(true.B)
        dut.io.in.poke(false.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
    }

    val tests: Tests = Tests {
        // T1: one flip of in gives exactly one one-cycle pulse
        //     flipping back is also an event
        test("T1_one_flip_one_pulse") {
            simulate(new ToggleSync) { dut =>
                initSync(dut)

                dut.io.pulse.expect(false.B)

                // change on in
                dut.io.in.poke(true.B)
                dut.clock.step(1)
                dut.io.pulse.expect(false.B)

                //s2 != s3 so there is a change
                dut.clock.step(1)
                dut.io.pulse.expect(true.B) 

                // s2 = s3 so there is no change
                dut.clock.step(1)
                dut.io.pulse.expect(false.B)
                dut.clock.step(1)

                // change on in
                dut.io.in.poke(false.B)
                
                // s2 = s3 so there is no change
                dut.clock.step(1)       
                dut.io.pulse.expect(false.B)

                // s2 != s3 so there is a change
                dut.clock.step(1)
                dut.io.pulse.expect(true.B)

                // s2 = s3 again, so the pulse lasted one cycle
                dut.clock.step(1)
                dut.io.pulse.expect(false.B)
            }
        }
    }
}