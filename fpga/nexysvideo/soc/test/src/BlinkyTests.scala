package nexysvideo

import chisel3._
import chisel3.simulator.EphemeralSimulator._

import utest._

object BlinkyTests extends TestSuite {
    val tests = Tests {
        utest.test("LED turns on exactly when bit 24 of the counter flips") {
            simulate(new Blinky) { dut =>
                dut.reset.poke(true.B)
                dut.clock.step(1)
                dut.reset.poke(false.B)
                dut.io.led.expect(false.B)
                dut.clock.step((1 << 24)-1)
                dut.io.led.expect(false.B)
                dut.clock.step(1)
                dut.io.led.expect(true.B)
                }
        }
    }
}