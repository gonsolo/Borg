// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.wafer

import chisel3._
import chisel3.util._
import borg.BorgConfig
import borg.link.{BorgLinkSlave, LinkParams}

/** Borg-only ASIC core: [[borg.Borg]] behind [[borg.link.BorgLinkSlave]], no CPU.
  * The 1x0.5 lane map (1x1 differs, see BorgOnlyTop):
  *
  * {{{
  *   bidir[0:15]   dn_d[15:0]   in
  *   bidir[16]     dn_v         in
  *   bidir[17]     dn_p         in
  *   bidir[18]     dn_cred      out
  *   bidir[19:34]  up_d[15:0]   out
  *   bidir[35]     up_v         out
  *   bidir[36]     up_p         out
  *   bidir[37]     up_cred      in
  *   bidir[38]     link_up      out
  *   bidir[39]     link_err     out
  *   bidir[40:45]  dbg_o[5:0]   out (live: 4 views selected by dbg_sel)
  *
  *   input_in[0:1] dbg_sel      in  (live: selects the dbg_o view)
  *   input_in[2]   link_narrow  in  (live: halves the lanes at runtime)
  *   input_in[3]   link_fast    in
  * }}}
  *
  * link_narrow is a runtime switch (LinkParams.narrowCapable), the post-silicon
  * recovery path if the upper lanes fail.
  */
class BorgOnlyCoreIO(val p: LinkParams) extends Bundle {
  val dnD    = Input(UInt(p.w.W))
  val dnV    = Input(Bool())
  val dnP    = Input(Bool())
  val dnCred = Output(Bool())

  val upD    = Output(UInt(p.w.W))
  val upV    = Output(Bool())
  val upP    = Output(Bool())
  val upCred = Input(Bool())

  val linkUp     = Output(Bool())
  val linkErr    = Output(Bool())
  val dbgSel     = Input(UInt(2.W))
  val dbgO       = Output(UInt(6.W))
  val linkFast   = Input(Bool())
  val linkNarrow = Input(Bool())
}

class BorgOnlyCore(val cfg: BorgConfig, val p: LinkParams) extends Module {
  val io = IO(new BorgOnlyCoreIO(p))

  val slave    = Module(new BorgLinkSlave(p))
  val borgCore = Module(new borg.Borg(cfg))

  slave.io.mmio   <> borgCore.io.mmio
  slave.io.gpuMem <> borgCore.io.gpuMem

  slave.io.dnPins.d := io.dnD
  slave.io.dnPins.v := io.dnV
  slave.io.dnPins.p := io.dnP
  io.dnCred := slave.io.dnCred

  io.upD := slave.io.upPins.d
  io.upV := slave.io.upPins.v
  io.upP := slave.io.upPins.p
  slave.io.upCred := io.upCred

  slave.io.linkFast := io.linkFast
  slave.io.narrow   := io.linkNarrow
  io.linkUp  := slave.io.linkUp
  io.linkErr := slave.io.linkErr

  // Debug views to tell apart the bring-up faults that all look like
  // link_up never rising. A static heartbeat means no core clock.
  val heartbeat = RegInit(0.U(6.W))
  heartbeat := heartbeat + 1.U

  val dbgViews = VecInit(Seq(
    // 0: bring-up. trainGood distinguishes "no transitions arriving" (0) from
    //    "transitions arrive but the phase keeps relocking" (counts, resets).
    Cat(heartbeat(5), slave.io.linkUp, slave.io.dbgChanged,
        slave.io.dbgTrainGood(2, 0)),
    // 1: receiver health once trained -- is traffic arriving and is it clean?
    Cat(heartbeat(5), slave.io.linkErr, slave.io.dbgRxErr,
        slave.io.dbgRxParity, slave.io.dbgTxBusy, slave.io.linkUp),
    // 2: is Borg itself doing anything, or is the link fine and the GPU wedged?
    Cat(heartbeat(5), borgCore.io.mmio.req.ready, borgCore.io.mmio.resp.valid,
        borgCore.io.gpuMem.req, borgCore.io.gpuMem.wr, slave.io.linkUp),
    // 3: straps read back, to confirm the board is driving what it thinks.
    Cat(heartbeat(5), io.linkFast, io.linkNarrow, io.dbgSel, slave.io.linkUp)
  ))
  io.dbgO := dbgViews(io.dbgSel)
}

/** The wafer.space slot's padring. 1x1 trades six bidir pads for eight
  * input-only ones, so its lane map moves input lanes onto those. */
sealed trait WaferSlot {
  def numBidir: Int
  def numInput: Int
  def name: String
}
case object Slot1x0p5 extends WaferSlot {
  val numBidir = 46; val numInput = 4; val name = "1x0p5"
}
case object Slot1x1 extends WaferSlot {
  val numBidir = 40; val numInput = 12; val name = "1x1"
}

/** One bit per wafer.space pad, in chip_core's bidir_in/out/oe and input_in
  * convention. 27 output and 23 input lanes: 1x0.5 puts every link lane on
  * bidir; 1x1 moves the straps and dn_d[7:0] to the input-only pads to keep w=16.
  */
class BorgOnlyTop(
    val cfg: BorgConfig,
    val p: LinkParams,
    val slot: WaferSlot = Slot1x0p5
) extends RawModule {
  require(p.w == 16, "BorgOnlyTop's lane map assumes w=16 (dn_d/up_d each 16 lanes)")

  // A name per slot: on a slot mismatch yosys only warns and resizes the ports.
  override def desiredName: String = slot match {
    case Slot1x0p5 => "BorgOnlyTop"
    case Slot1x1   => "BorgOnlyTop1x1"
  }

  val clk      = IO(Input(Clock()))
  val rst_n    = IO(Input(Bool()))
  val bidirIn  = IO(Input(UInt(slot.numBidir.W)))
  val bidirOut = IO(Output(UInt(slot.numBidir.W)))
  val bidirOe  = IO(Output(UInt(slot.numBidir.W)))
  val inputIn  = IO(Input(UInt(slot.numInput.W)))

  // rst_n is asynchronous to clk; only the first flop sees it (a false path in
  // chip_top.sdc), so the core's reset is a timed path.
  val rstSync = withClockAndReset(clk, false.B) { ShiftRegister(!rst_n, 2) }
  rstSync.suggestName("rstSync")

  val core = withClockAndReset(clk, rstSync) { Module(new BorgOnlyCore(cfg, p)) }

  val dbgO = core.io.dbgO

  // bidirOe decides which of these bits reach a pad.
  val outVec = Wire(Vec(slot.numBidir, Bool()))
  val oeVec  = Wire(Vec(slot.numBidir, Bool()))
  for (i <- 0 until slot.numBidir) { outVec(i) := false.B; oeVec(i) := false.B }

  slot match {
    case Slot1x0p5 =>
      // Validated by the pad-level cocotb test (chip_link_tb.py); do not renumber.
      core.io.dnD    := bidirIn(15, 0)
      core.io.dnV    := bidirIn(16)
      core.io.dnP    := bidirIn(17)
      core.io.upCred := bidirIn(37)

      core.io.linkNarrow := inputIn(2)
      core.io.linkFast   := inputIn(3)
      core.io.dbgSel     := inputIn(1, 0)

      outVec(18) := core.io.dnCred
      for (i <- 0 until 16) outVec(19 + i) := core.io.upD(i)
      outVec(35) := core.io.upV
      outVec(36) := core.io.upP
      outVec(38) := core.io.linkUp
      outVec(39) := core.io.linkErr
      for (i <- 0 until 6) outVec(40 + i) := dbgO(i)

      oeVec(18) := true.B // dn_cred
      for (i <- 19 until 37) oeVec(i) := true.B // up_d/up_v/up_p
      oeVec(38) := true.B
      oeVec(39) := true.B
      for (i <- 40 until 46) oeVec(i) := true.B

    case Slot1x1 =>
      // dn_d is split: low half on the input-only pads, high half on bidir.
      // 38 of 40 bidir used; bidir[38..39] are spare and tied off.
      core.io.dnD    := Cat(bidirIn(7, 0), inputIn(11, 4))
      core.io.dnV    := bidirIn(8)
      core.io.dnP    := bidirIn(9)
      core.io.upCred := bidirIn(29)

      core.io.dbgSel     := inputIn(1, 0)
      core.io.linkNarrow := inputIn(2)
      core.io.linkFast   := inputIn(3)

      outVec(10) := core.io.dnCred
      for (i <- 0 until 16) outVec(11 + i) := core.io.upD(i)
      outVec(27) := core.io.upV
      outVec(28) := core.io.upP
      outVec(30) := core.io.linkUp
      outVec(31) := core.io.linkErr
      for (i <- 0 until 6) outVec(32 + i) := dbgO(i)

      oeVec(10) := true.B // dn_cred
      for (i <- 11 until 29) oeVec(i) := true.B // up_d/up_v/up_p
      oeVec(30) := true.B
      oeVec(31) := true.B
      for (i <- 32 until 38) oeVec(i) := true.B
  }

  bidirOut := outVec.asUInt
  bidirOe  := oeVec.asUInt
}
