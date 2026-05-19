// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// SoC address-decode regression tests.
//
// Demonstrates the bug that hangs the ULX3S full SoC on the second CPU
// write to the SoC debug UART (0x08000018):
//
//   (a) Address 0x08000018 matches BOTH SoCDecode.socRegion AND
//       SoCDecode.userRegion — the SoC inline-reg window is a strict
//       subset of the user-peripheral window (same addr[27:12]=0x8000).
//
//   (b) Peripherals never produces a response when the per-peripheral
//       select bits (addr[11:10]) resolve to USER_PERI_NONE — its resp.valid
//       mux only matches PERI_UART / PERI_BORG / PERI_GPIO.
//
// SoCLogic's routing prioritizes isUser → peripherals.resp.valid, so a CPU
// write to 0x08000018 lands in the conflict zone: the request fires (one
// UART byte transmits because the SoC UART decoder also catches the write),
// then the bus locks waiting for a response that can never come.
//
// The fix is to redefine isUser to exclude isSoc, making the two regions
// mutually exclusive.  See the third test below.

package soc

import chisel3.{Bool, Bundle, Input, IO, Module, Output, UInt}
import chisel3.{fromBooleanToLiteral, fromIntToLiteral, fromIntToWidth, fromLongToLiteral}
import chisel3.simulator.EphemeralSimulator._
import utest._

/** I/O for [[DecodeProbe]] — declared as a named Bundle so Scala 3 migration
  * doesn't infer a structural type. */
class DecodeProbeIO extends Bundle {
  val addr        = Input(UInt(28.W))
  val isMem       = Output(Bool())
  val isSoc       = Output(Bool())
  val isUserBuggy = Output(Bool())   // current SoCLogic definition
  val isUserFixed = Output(Bool())   // proposed: excludes isSoc
}

/** Probe module exposing SoCDecode predicates plus the proposed exclusion. */
class DecodeProbe extends Module {
  val io = IO(new DecodeProbeIO)

  io.isMem       := io.addr(27, 25) === 0.U
  io.isSoc       := SoCDecode.socRegion.matches(io.addr)
  io.isUserBuggy := SoCDecode.userRegion.matches(io.addr)
  io.isUserFixed := SoCDecode.userRegion.matches(io.addr) && !io.isSoc
}

object SoCRoutingTests extends TestSuite {

  val tests = Tests {

    // ── (a) Demonstrate the address overlap ────────────────────────────────
    test("debug UART address 0x08000018 falsely matches BOTH regions") {
      simulate(new DecodeProbe) { dut =>
        dut.io.addr.poke(0x08000018L.U)
        dut.clock.step(1)
        Predef.assert(!dut.io.isMem.peek().litToBoolean,        "isMem must be false")
        Predef.assert( dut.io.isSoc.peek().litToBoolean,         "isSoc must be true")
        Predef.assert( dut.io.isUserBuggy.peek().litToBoolean,   "bug: isUserBuggy is true too — region overlap")
        Predef.assert(!dut.io.isUserFixed.peek().litToBoolean,   "fix: isUserFixed must be false")
      }
    }

    test("user UART address 0x08000800 matches isUser only, both definitions") {
      simulate(new DecodeProbe) { dut =>
        dut.io.addr.poke(0x08000800L.U)
        dut.clock.step(1)
        Predef.assert(!dut.io.isMem.peek().litToBoolean)
        Predef.assert(!dut.io.isSoc.peek().litToBoolean,       "isSoc must be false here")
        Predef.assert( dut.io.isUserBuggy.peek().litToBoolean, "isUser must be true")
        Predef.assert( dut.io.isUserFixed.peek().litToBoolean, "fix must not regress true-user matches")
      }
    }

    test("SDRAM address 0x00085000 matches isMem only") {
      simulate(new DecodeProbe) { dut =>
        dut.io.addr.poke(0x00085000L.U)
        dut.clock.step(1)
        Predef.assert( dut.io.isMem.peek().litToBoolean)
        Predef.assert(!dut.io.isSoc.peek().litToBoolean)
        Predef.assert(!dut.io.isUserBuggy.peek().litToBoolean)
        Predef.assert(!dut.io.isUserFixed.peek().litToBoolean)
      }
    }

    // ── (b) Demonstrate Peripherals never acks a PERI_NONE request ─────────
    test("Peripherals never produces a resp.valid for addr[11:10]=NONE") {
      // sub_addr 0x18 has addr[11:10] = 0 → PERI_NONE inside Peripherals.
      simulate(new Peripherals(CLOCK_MHZ = 25)) { dut =>
        // Defaults
        dut.io.ui_in.poke(0.U)
        dut.io.mmio.req.valid.poke(false.B)
        dut.io.mmio.req.bits.addr.poke(0.U)
        dut.io.mmio.req.bits.data.poke(0.U)
        dut.io.mmio.req.bits.write.poke(false.B)
        dut.io.mmio.req.bits.size.poke(0.U)
        dut.io.mmio.resp.ready.poke(true.B)

        dut.reset.poke(true.B);  dut.clock.step(3);  dut.reset.poke(false.B)
        dut.clock.step(1)

        // Drive a single write to sub_addr 0x18 — the low-12 bits of
        // 0x08000018 as the CPU bus would present.
        dut.io.mmio.req.valid.poke(true.B)
        dut.io.mmio.req.bits.addr.poke(0x018.U)
        dut.io.mmio.req.bits.write.poke(true.B)
        dut.io.mmio.req.bits.data.poke(0x5A.U)   // 'Z'
        dut.io.mmio.req.bits.size.poke(2.U)      // word

        // Wait for req.fire (one cycle when ready=true).
        var fired = false
        var c = 0
        while (!fired && c < 10) {
          if (dut.io.mmio.req.valid.peek().litToBoolean &&
              dut.io.mmio.req.ready.peek().litToBoolean) fired = true
          dut.clock.step(1); c += 1
        }
        Predef.assert(fired, s"req never fired (waited $c cycles)")
        dut.io.mmio.req.valid.poke(false.B)

        // Now spin for many cycles and confirm resp.valid stays low —
        // proving the PERI_NONE request will never be acknowledged.
        var sawResp = false
        for (_ <- 0 until 200) {
          if (dut.io.mmio.resp.valid.peek().litToBoolean) sawResp = true
          dut.clock.step(1)
        }
        Predef.assert(!sawResp,
          "Peripherals produced an unexpected resp.valid for a PERI_NONE request — bug analysis is wrong.")
      }
    }
  }
}
