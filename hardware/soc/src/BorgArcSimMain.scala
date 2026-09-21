// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package soc

/** FIRRTL-only emission for the arcilator simulation target.
  *
  * Run via: CLOCK_MHZ=4 mill hardware.soc.runMain soc.BorgArcSimMain
  *
  * [[BorgSimMain]] also emits the whole verilator Verilog tree, which the
  * arcilator flow never reads; this writes just the .fir that
  * simulation/arcilator's Makefile consumes, into the same directory.
  */
object BorgArcSimMain extends App {
  val clockMhz = sys.env.getOrElse("CLOCK_MHZ", "4").toInt
  println(s"Generating arcilator sim FIRRTL with CLOCK_MHZ = $clockMhz")
  Emit.emitFIRRTL(new BorgArcSimTop(clockMhz), "out/hardware/borg/firrtl_sim")
}
