// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

class TexUnitIO(cfg: BorgConfig) extends CoreUnitIO(cfg) {
  val descWritten = Input(Bool())
  val mem  = new GpuMemIO
  val busy = Output(Bool())
  val prof = Output(UInt(5.W))
}

/** TEX, TEXA and TEXS (docs/B2_texture_unit.md). TEXA keeps each lane's w or layer, LOD or bias
  * and depth reference, TEXS the sampler descriptor and control word, for the TEXs after them.
  * TEX hands the quad to two [[BorgSampler]]s, each sampling half of its lanes with the LOD from
  * the quad's derivatives, and writes each lane's four words to rd..rd+3 as they answer. */
class TexUnit(cfg: BorgConfig) extends Module {
  val io = IO(new TexUnitIO(cfg))
  private val N = cfg.fragLanes
  io.idle()

  // Two samplers, each a half of the quad's lanes; both take the LOD from all four.
  private val samplers = Seq.fill(2)(Module(new BorgSampler(cfg)))
  samplers.foreach(_.io.invalidate := io.descWritten)
  io.prof := samplers.head.io.prof

  // TEXA's and TEXS's state, per quad slot.
  private val S = cfg.quadSlots
  private val auxW = Reg(Vec(S, Vec(N, UInt(32.W)))); private val auxLod = Reg(Vec(S, Vec(N, UInt(32.W))))
  private val auxRef = Reg(Vec(S, Vec(N, UInt(32.W))))
  private val sAddr = RegInit(VecInit(Seq.fill(S)(0.U(GpuMemIO.AddrBits.W))))
  private val sCtl  = RegInit(VecInit(Seq.fill(S)(0.U(32.W))))
  private val q = io.slot
  private val isTex = RegInit(false.B); private val isTexa = RegInit(false.B); private val isTexs = RegInit(false.B)
  private val rd = RegInit(0.U(5.W))
  when(io.issue.valid) {
    val rv = RvInt(io.issue.bits.word)
    isTex := rv.texd; isTexa := rv.texad; isTexs := rv.texs; rd := io.issue.bits.regs.rd
  }

  private val sIdle :: sWait :: sWB :: Nil = Enum(3)
  private val st = RegInit(sIdle)
  private val lastResp = RegInit(false.B)
  private val wbK = RegInit(0.U(2.W))
  private val respLane = RegInit(0.U(log2Up(N).W))
  private val respData = Reg(Vec(4, UInt(32.W)))

  private def finish(): Unit = {
    st := sIdle
    isTex := false.B; isTexa := false.B; isTexs := false.B
    io.finish := true.B
  }

  private def lead(ops: Vec[UInt]): UInt = PriorityMux(io.mask.asBools, ops)
  private val req = Wire(new SamplerReq(N))
  req.ctl := sCtl(q)
  req.texAddr := lead(io.c)(GpuMemIO.AddrBits - 1, 0)
  req.sampAddr := sAddr(q)
  for (i <- 0 until N) {
    req.active(i) := io.mask(i)
    val a = req.lane(i)
    a.u := io.a(i); a.v := io.b(i); a.w := auxW(q)(i); a.lod := auxLod(q)(i); a.dref := auxRef(q)(i)
  }
  // Taken into respData at once: the sampler works on the next lane during the write-back.
  private val answering = VecInit(samplers.map(_.io.resp.valid))
  private val take = PriorityEncoderOH(answering.asUInt)
  private val left = RegInit(0.U(2.W))                      // samplers not done yet
  samplers.zipWithIndex.foreach { case (s, h) =>
    s.io.req.valid := false.B
    s.io.req.bits := req
    for (i <- 0 until N) s.io.req.bits.active(i) := io.mask(i) && (i * 2 / N == h).B
    s.io.respReady := st === sWait && take(h)
  }

  when(io.atOps && isTexs) {
    sAddr(q) := lead(io.a); sCtl(q) := lead(io.b)
    finish()
  }
  when(io.atOps && isTexa) {
    for (i <- 0 until N) { auxW(q)(i) := io.a(i); auxLod(q)(i) := io.b(i); auxRef(q)(i) := io.c(i) }
    finish()
  }
  private val start = io.atOps && isTex && st === sIdle
  private val mask = RegEnable(io.mask, start)   // the sampler latches the rest
  when(start) {
    samplers.foreach(_.io.req.valid := true.B)
    left := 3.U
    st := sWait
  }
  when(st === sWait && answering.asUInt.orR) {
    val r = Mux1H(take, samplers.map(_.io.resp.bits))
    val last = (left & ~VecInit(samplers.map(_.io.done)).asUInt) === 0.U
    left := left & ~VecInit(samplers.map(_.io.done)).asUInt
    respLane := r.lane; respData := r.data
    lastResp := last
    wbK := 0.U
    // A lane the other sampler or no pixel has needs no write-back.
    when(mask(r.lane) && VecInit((0 until N).map(i => (i * 2 / N).U(1.W)))(r.lane) === OHToUInt(take)) { st := sWB }
      .elsewhen(last) { finish() }
  }
  when(st === sWB) {
    io.wb.zipWithIndex.foreach { case (w, i) =>
      w.en := io.wbReady && i.U === respLane && mask(i); w.addr := rd + wbK; w.data := respData(wbK)
    }
    when(io.wbReady) {
      when(wbK === 3.U) {
        when(lastResp) { finish() }.otherwise { st := sWait }
      }.otherwise { wbK := wbK + 1.U }
    }
  }

  io.busy := samplers.map(_.io.busy).reduce(_ || _)
  io.wbWant := st === sWB
  GpuMemIO.arbitrate(samplers.map(_.io.gpuMem), io.mem)
}
