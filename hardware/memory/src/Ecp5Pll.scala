// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Chisel wrapper for the ECP5 EHXPLLL hard PLL primitive.
//
// PLL divider parameters are computed at Scala elaboration time by
// Ecp5PllParams.solve(), which is a direct port of EMARD's ecp5pll.sv
// parameter-solving functions.
//
// Usage:
//   val pll = Module(new Ecp5Pll(Ecp5PllParams(
//     inHz   = 25000000,
//     out0Hz = 125000000, out0Deg = 0,
//     out1Hz = 125000000, out1Deg = 90  // 90° phase for SDRAM clock
//   )))
//   val clk125     = pll.io.clk_o(0)
//   val clk125_90  = pll.io.clk_o(1)
//   val pllLocked  = pll.io.locked

package memory

import chisel3._
import chisel3.experimental.{fromIntToIntParam, fromStringToStringParam}

// ---------------------------------------------------------------------------
// Compile-time PLL parameter solver (port of EMARD's F_ecp5pll)
// ---------------------------------------------------------------------------

/** PLL divider solution for EHXPLLL. */
case class Ecp5PllSolution(
    refclkDiv: Int,
    feedbackDiv: Int,
    outputDiv: Int,
    fOut: Long,
    fVco: Long,
    primaryCPhase: Int,
    primaryFPhase: Int,
    sec1Div: Int,
    sec1CPhase: Int,
    sec1FPhase: Int,
    sec2Div: Int,
    sec2CPhase: Int,
    sec2FPhase: Int,
    sec3Div: Int,
    sec3CPhase: Int,
    sec3FPhase: Int
)

/** Request for PLL outputs.  Up to 4 clocks (out0 is primary). */
case class Ecp5PllParams(
    inHz: Long = 25000000L,
    out0Hz: Long = 25000000L,
    out0Deg: Int = 0,
    out1Hz: Long = 0L,
    out1Deg: Int = 0,
    out2Hz: Long = 0L,
    out2Deg: Int = 0,
    out3Hz: Long = 0L,
    out3Deg: Int = 0
)

object Ecp5PllParams {

  private val PFD_MIN: Long = 3125000L
  private val PFD_MAX: Long = 400000000L
  private val VCO_MIN: Long = 400000000L
  private val VCO_MAX: Long = 800000000L
  private val VCO_OPTIMAL: Long = (VCO_MIN + VCO_MAX) / 2

  /** Port of F_ecp5pll — brute-force PLL parameter search. */
  def solve(p: Ecp5PllParams): Ecp5PllSolution = {
    val inHz = p.inHz
    val out0Hz = p.out0Hz

    var bestRefclkDiv = 1
    var bestFeedbackDiv = 1
    var bestOutputDiv = 1
    var bestFvco = 0L
    var bestError = Long.MaxValue

    val inputDivMin = math.max((inHz / PFD_MAX).toInt, 1)
    val inputDivMax = math.min((inHz / PFD_MIN).toInt, 128)

    for (inputDiv <- inputDivMin to inputDivMax) {
      // Avoid 32-bit overflow: pick multiplication order carefully
      val fbBase: Int = if (out0Hz / 1000000L * inputDiv < 2000L)
        (out0Hz * inputDiv / inHz).toInt
      else
        (out0Hz / inHz * inputDiv).toInt

      val fbMin = math.max(fbBase, 1)
      val fbMax = math.min(fbBase + 1, 80)

      for (feedbackDiv <- fbMin to fbMax) {
        val fPfd = inHz / inputDiv
        val fOut = inHz * feedbackDiv / inputDiv

        val odMin = math.max((VCO_MIN / feedbackDiv / fPfd).toInt, 1)
        val odMax = math.min((VCO_MAX / feedbackDiv / fPfd).toInt, 128)

        for (outputDiv <- odMin to odMax) {
          val fvco = fOut * outputDiv

          // Error: deviation from requested frequencies
          var error = math.abs(fOut - out0Hz)
          if (p.out1Hz > 0) {
            val d = if (fvco >= p.out1Hz) fvco / p.out1Hz else 1L
            error += math.abs(fvco / d - p.out1Hz)
          }
          if (p.out2Hz > 0) {
            val d = if (fvco >= p.out2Hz) fvco / p.out2Hz else 1L
            error += math.abs(fvco / d - p.out2Hz)
          }
          if (p.out3Hz > 0) {
            val d = if (fvco >= p.out3Hz) fvco / p.out3Hz else 1L
            error += math.abs(fvco / d - p.out3Hz)
          }

          if (error < bestError ||
              (error == bestError &&
               math.abs(fvco - VCO_OPTIMAL) < math.abs(bestFvco - VCO_OPTIMAL))) {
            bestError = error
            bestRefclkDiv = inputDiv
            bestFeedbackDiv = feedbackDiv
            bestOutputDiv = outputDiv
            bestFvco = fvco
          }
        }
      }
    }

    val fOut = inHz * bestFeedbackDiv / bestRefclkDiv
    val fVco = fOut * bestOutputDiv

    require(fVco >= VCO_MIN && fVco <= VCO_MAX,
      s"Ecp5Pll: fVco=$fVco out of range [$VCO_MIN, $VCO_MAX]")

    def primaryPhase(outDiv: Int, deg: Int): (Int, Int) = {
      val phaseComp = (outDiv + 1) / 2 * 8 - 8 + outDiv / 2 * 8
      var px8 = phaseComp + 8 * outDiv * deg / 360
      if (px8 > 1023) px8 = px8 % (outDiv * 8)
      (px8 / 8, px8 % 8)
    }

    def secondaryDivisor(sFreq: Long): Int = {
      if (sFreq > 0 && fVco >= sFreq) (fVco / sFreq).toInt else 1
    }

    def secondaryPhase(sFreq: Long, sDeg: Int): (Int, Int) = {
      if (sFreq <= 0) return (0, 0)
      val div = secondaryDivisor(sFreq)
      val phaseComp = div * 8 - 8
      var px8 = phaseComp + 8 * div * sDeg / 360
      if (px8 > 1023) px8 = px8 % (div * 8)
      (px8 / 8, px8 % 8)
    }

    val (pc, pf) = primaryPhase(bestOutputDiv, p.out0Deg)
    val (s1c, s1f) = secondaryPhase(p.out1Hz, p.out1Deg)
    val (s2c, s2f) = secondaryPhase(p.out2Hz, p.out2Deg)
    val (s3c, s3f) = secondaryPhase(p.out3Hz, p.out3Deg)

    Ecp5PllSolution(
      refclkDiv = bestRefclkDiv,
      feedbackDiv = bestFeedbackDiv,
      outputDiv = bestOutputDiv,
      fOut = fOut,
      fVco = fVco,
      primaryCPhase = pc,
      primaryFPhase = pf,
      sec1Div = secondaryDivisor(p.out1Hz),
      sec1CPhase = s1c,
      sec1FPhase = s1f,
      sec2Div = secondaryDivisor(p.out2Hz),
      sec2CPhase = s2c,
      sec2FPhase = s2f,
      sec3Div = secondaryDivisor(p.out3Hz),
      sec3CPhase = s3c,
      sec3FPhase = s3f
    )
  }

  /** Enriches [[Ecp5PllParams]] with a `solved` accessor that runs the
    * PLL parameter solver.
    */
  implicit class Ecp5PllParamsOps(private val p: Ecp5PllParams) extends AnyVal {
    def solved: Ecp5PllSolution = Ecp5PllParams.solve(p)
  }
}

// ---------------------------------------------------------------------------
// Chisel IO bundle for Ecp5Pll
// ---------------------------------------------------------------------------

class Ecp5PllIO extends Bundle {
  val clk_i  = Input(Clock())
  val clk_o  = Output(Vec(4, Clock()))
  val locked = Output(Bool())
}

// ---------------------------------------------------------------------------
// EHXPLLL ExtModule — instantiates the hard PLL primitive
// ---------------------------------------------------------------------------

/** ECP5 PLL wrapper.
  *
  * Wraps the `EHXPLLL` hard PLL primitive with parameters computed from
  * [[Ecp5PllParams]].  The module has no Chisel logic — just parameter
  * propagation to the ECP5 primitive.
  *
  * @param params PLL frequency/phase configuration
  */
class Ecp5Pll(params: Ecp5PllParams) extends ExtModule(
  Map(
    // Computed by the Scala solver
    "CLKI_DIV"  -> params.solved.refclkDiv,
    "CLKFB_DIV" -> params.solved.feedbackDiv,

    "FEEDBK_PATH" -> "CLKOP",

    "OUTDIVIDER_MUXA" -> "DIVA",
    "CLKOP_ENABLE"    -> "ENABLED",
    "CLKOP_DIV"       -> params.solved.outputDiv,
    "CLKOP_CPHASE"    -> params.solved.primaryCPhase,
    "CLKOP_FPHASE"    -> params.solved.primaryFPhase,

    "OUTDIVIDER_MUXB" -> "DIVB",
    "CLKOS_ENABLE"    -> (if (params.out1Hz > 0) "ENABLED" else "DISABLED"),
    "CLKOS_DIV"       -> params.solved.sec1Div,
    "CLKOS_CPHASE"    -> params.solved.sec1CPhase,
    "CLKOS_FPHASE"    -> params.solved.sec1FPhase,

    "OUTDIVIDER_MUXC" -> "DIVC",
    "CLKOS2_ENABLE"   -> (if (params.out2Hz > 0) "ENABLED" else "DISABLED"),
    "CLKOS2_DIV"      -> params.solved.sec2Div,
    "CLKOS2_CPHASE"   -> params.solved.sec2CPhase,
    "CLKOS2_FPHASE"   -> params.solved.sec2FPhase,

    "OUTDIVIDER_MUXD" -> "DIVD",
    "CLKOS3_ENABLE"   -> (if (params.out3Hz > 0) "ENABLED" else "DISABLED"),
    "CLKOS3_DIV"      -> params.solved.sec3Div,
    "CLKOS3_CPHASE"   -> params.solved.sec3CPhase,
    "CLKOS3_FPHASE"   -> params.solved.sec3FPhase,

    "INTFB_WAKE"    -> "DISABLED",
    "STDBY_ENABLE"  -> "DISABLED",
    "PLLRST_ENA"    -> "DISABLED",
    "DPHASE_SOURCE" -> "DISABLED",
    "PLL_LOCK_MODE" -> 0
  )
) {
  override val desiredName = "EHXPLLL"

  // EHXPLLL port map — directly matches the ECP5 primitive interface.
  val CLKI         = IO(Input(Clock()))
  val CLKFB        = IO(Input(Clock()))
  val RST          = IO(Input(Bool()))
  val STDBY        = IO(Input(Bool()))
  val PLLWAKESYNC  = IO(Input(Bool()))
  val PHASESEL0    = IO(Input(Bool()))
  val PHASESEL1    = IO(Input(Bool()))
  val PHASEDIR     = IO(Input(Bool()))
  val PHASESTEP    = IO(Input(Bool()))
  val PHASELOADREG = IO(Input(Bool()))
  val ENCLKOP      = IO(Input(Bool()))
  val ENCLKOS      = IO(Input(Bool()))
  val ENCLKOS2     = IO(Input(Bool()))
  val ENCLKOS3     = IO(Input(Bool()))
  val CLKOP        = IO(Output(Clock()))
  val CLKOS        = IO(Output(Clock()))
  val CLKOS2       = IO(Output(Clock()))
  val CLKOS3       = IO(Output(Clock()))
  val LOCK         = IO(Output(Bool()))
  val CLKINTFB     = IO(Output(Clock()))

  /** Lazy solution — run the solver once on first access. */
  private lazy val _solved = Ecp5PllParams.solve(params)
}

/** Convenience wrapper: provides a simple IO and ties off unused EHXPLLL ports.
  *
  * Instantiate this instead of [[Ecp5Pll]] directly to avoid manual wiring
  * of the unused phase/standby/enable pins.
  */
class Ecp5PllWrapper(params: Ecp5PllParams) extends RawModule {
  val io = IO(new Ecp5PllIO)

  val pll = Module(new Ecp5Pll(params))

  // Clock input
  pll.CLKI := io.clk_i
  pll.CLKFB := pll.CLKOP  // Internal feedback (CLKOP → CLKFB)

  // Tie off unused control inputs
  pll.RST          := false.B
  pll.STDBY        := false.B
  pll.PLLWAKESYNC  := false.B
  pll.PHASESEL0    := false.B
  pll.PHASESEL1    := false.B
  pll.PHASEDIR     := false.B
  pll.PHASESTEP    := false.B
  pll.PHASELOADREG := false.B
  pll.ENCLKOP      := false.B
  pll.ENCLKOS      := false.B
  pll.ENCLKOS2     := false.B
  pll.ENCLKOS3     := false.B

  // Outputs
  io.clk_o(0) := pll.CLKOP
  io.clk_o(1) := pll.CLKOS
  io.clk_o(2) := pll.CLKOS2
  io.clk_o(3) := pll.CLKOS3
  io.locked    := pll.LOCK
}

