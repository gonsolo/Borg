// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package asic.wafer

import chisel3._
import chisel3.util._
import borg.BorgConfig
import borg.link.{BorgLinkSlave, LinkParams}

/** Borg-only ASIC top: [[borg.Borg]] behind [[borg.link.BorgLinkSlave]], with no
  * Hutt and no QSPI.  Flat pin layout, one bit per wafer.space bidir/input pad,
  * matching the wafer.space Borg-only tapeout plan's lane map exactly:
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
  * This is a `RawModule`: no `SoCLogic`, since that trait is entirely CPU glue and
  * there is no CPU here.  `BorgOnlyCore` holds the clocked logic so cocotb/Chisel
  * tests can instantiate it directly without the pin-flattening boilerplate.
  *
  * '''`link_narrow` is a real runtime switch.''' Built with
  * [[borg.link.LinkParams.narrowCapable]], so strapping input_in[2] high makes
  * the link drive d[7:0] only, tie d[15:8] low, take two beats per flit and
  * compute parity over the live lanes -- see LinkParams' doc. This is the
  * post-silicon recovery path: pins cannot be re-synthesized after tapeout, so
  * an elaboration-time `w = 8` would only have been a different build, not
  * something the fabricated part could be talked into.
  *
  * @param cfg Borg configuration.  `BorgConfig.Wafer`: Default's feature set
  *            (FP32, 4x MSAA, depth flush) at the slot's sizing, with
  *            debugPorts=false since there is no harness here to read them.
  * @param p   Link configuration.  Default width (16) matches the lane map above.
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
  val linkNarrow = Input(Bool()) // reserved, see class doc
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

  // -- Debug bus -------------------------------------------------------------
  // If the part comes back and link_up never rises, the only other evidence on
  // the package is link_err. That is two bits for a fault with many causes --
  // clock not arriving, reset stuck, a lane open in the padring, parity
  // polarity, the phase never locking -- which all present identically. These
  // four views are chosen to separate exactly those, and cost 6 output pads
  // that are otherwise tied to zero.
  //
  // The heartbeat is deliberately first and free-running: if it is static, the
  // core clock is not reaching the die and nothing else on this bus means
  // anything.
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

/** Pin-flattening `RawModule` wrapper: one bit per wafer.space pad, matching
  * [[chip_core]]'s `bidir_in`/`bidir_out`/`bidir_oe`/`input_in` convention exactly
  * (mirrors `tt_um_gonsolo_borg`'s flattening of Hutt's SoC ports for TT's pad
  * interface).  46 bidir bits, 4 input bits, dedicated `clk`/`rst_n` -- fills the
  * 1x0.5 slot's full 46 bidir + 4 input-only pad budget with no unused positions.
  */
class BorgOnlyTop(val cfg: BorgConfig, val p: LinkParams) extends RawModule {
  require(p.w == 16, "BorgOnlyTop's lane map assumes w=16 (dn_d/up_d each 16 bidir pads)")

  val clk      = IO(Input(Clock()))
  val rst_n    = IO(Input(Bool()))
  val bidirIn  = IO(Input(UInt(46.W)))
  val bidirOut = IO(Output(UInt(46.W)))
  val bidirOe  = IO(Output(UInt(46.W)))
  val inputIn  = IO(Input(UInt(4.W)))

  val core = withClockAndReset(clk, !rst_n) { Module(new BorgOnlyCore(cfg, p)) }

  core.io.dnD := bidirIn(15, 0)
  core.io.dnV := bidirIn(16)
  core.io.dnP := bidirIn(17)
  core.io.upCred := bidirIn(37)

  core.io.linkNarrow := inputIn(2)
  core.io.linkFast   := inputIn(3)
  core.io.dbgSel     := inputIn(1, 0)

  val dbgO = core.io.dbgO

  // Built per-bit rather than via Cat: the vector mixes in/out lanes at
  // non-contiguous positions, and bidirOe (below) is what actually decides
  // which of these bits reach a pad -- this only needs to get the *output*
  // lanes right.
  val outVec = Wire(Vec(46, Bool()))
  for (i <- 0 until 46) outVec(i) := false.B
  for (i <- 0 until 18) outVec(i) := false.B // dn_d/dn_v/dn_p lanes: no drive
  outVec(18) := core.io.dnCred
  for (i <- 0 until 16) outVec(19 + i) := core.io.upD(i)
  outVec(35) := core.io.upV
  outVec(36) := core.io.upP
  // bidir[37] = up_cred, an input lane -- no drive
  outVec(38) := core.io.linkUp
  outVec(39) := core.io.linkErr
  for (i <- 0 until 6) outVec(40 + i) := dbgO(i)
  bidirOut := outVec.asUInt

  val oeVec = Wire(Vec(46, Bool()))
  for (i <- 0 until 18) oeVec(i) := false.B // dn_d/dn_v/dn_p: input only
  oeVec(18) := true.B // dn_cred: output
  for (i <- 19 until 37) oeVec(i) := true.B // up_d/up_v/up_p: output
  oeVec(37) := false.B // up_cred: input only
  oeVec(38) := true.B
  oeVec(39) := true.B
  for (i <- 40 until 46) oeVec(i) := true.B
  bidirOe := oeVec.asUInt
}
