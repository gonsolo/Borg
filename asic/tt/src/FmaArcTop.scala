// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.tt

import chisel3._
import chisel3.util._
import borg.{BorgConfig, BorgFp16Fma}
import hardfloat._

/** Debug co-sim top for the arcilator custom-FMA investigation (see
  * docs/arcilator_custom_fma_bug.md).  Drives BorgFp16Fma and HardFloat
  * MulAddRecFN with identical a/b/c/negate and exposes both outputs, so the C++
  * harness simulation/arcilator/debug/fma_cosim.cpp can compare them inside
  * arcilator.  Result: 0 divergence over 400k cases — the FMA is correct in
  * arcilator standalone; only the full SoC miscompiles.  Not a build target.
  *
  * Emit FIRRTL: `mill asic.tt.runMain asic.tt.FmaArcMain`
  */
class FmaArcTopIO(cfg: BorgConfig) extends Bundle {
  val a = Input(UInt(cfg.totalBits.W))
  val b = Input(UInt(cfg.totalBits.W))
  val c = Input(UInt(cfg.totalBits.W))
  val negate = Input(Bool())
  val outCustom = Output(UInt(cfg.totalBits.W))
  val outHf     = Output(UInt(cfg.totalBits.W))
}

class FmaArcTop extends Module {
  val cfg = BorgConfig.Default
  val io = IO(new FmaArcTopIO(cfg))

  val cust = Module(new BorgFp16Fma(cfg))
  cust.io.a := io.a; cust.io.b := io.b; cust.io.c := io.c
  cust.io.negate := io.negate
  cust.io.pipeEn1 := true.B; cust.io.pipeEn2 := true.B
  io.outCustom := cust.io.out

  // HardFloat reference, delayed 3 cycles to match the free-running FMA latency.
  val hf = Module(new MulAddRecFN(cfg.exp, cfg.sig))
  hf.io.op := Mux(io.negate, 2.U, 0.U)
  hf.io.a  := recFNFromFN(cfg.exp, cfg.sig, io.a)
  hf.io.b  := recFNFromFN(cfg.exp, cfg.sig, io.b)
  hf.io.c  := recFNFromFN(cfg.exp, cfg.sig, io.c)
  hf.io.roundingMode := 0.U
  hf.io.detectTininess := 1.U
  hf.io.valid := true.B
  io.outHf := RegNext(RegNext(RegNext(fNFromRecFN(cfg.exp, cfg.sig, hf.io.out))))
}

object FmaArcMain extends App {
  soc.Emit.emitFIRRTL(new FmaArcTop, "out/hardware/borg/firrtl_fma")
}
