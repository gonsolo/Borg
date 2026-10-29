// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

class TexUnitIO(cfg: BorgConfig) extends CoreUnitIO(cfg) {
  val descWritten = Input(Bool())
  val mem  = new GpuMemIO
  val busy = Output(Bool())
}

/** TEX, TEXA and TEXS (docs/B2_texture_unit.md). TEXA keeps each lane's w or layer, LOD or bias
  * and depth reference, TEXS the sampler descriptor and control word, for the TEXs after them.
  * TEX hands the whole quad to [[BorgSampler]], which takes the LOD from the quad's derivatives,
  * and writes each lane's four words to rd..rd+3 as the sampler answers. */
class TexUnit(cfg: BorgConfig) extends Module {
  val io = IO(new TexUnitIO(cfg))
  private val N = cfg.fragLanes
  io.idle()

  private val sampler = Module(new BorgSampler(cfg))
  sampler.io.invalidate := io.descWritten

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
  sampler.io.req.valid := false.B
  sampler.io.req.bits.ctl := sCtl(q)
  sampler.io.req.bits.texAddr := lead(io.c)(GpuMemIO.AddrBits - 1, 0)
  sampler.io.req.bits.sampAddr := sAddr(q)
  for (i <- 0 until N) {
    sampler.io.req.bits.active(i) := io.mask(i)
    val a = sampler.io.req.bits.lane(i)
    a.u := io.a(i); a.v := io.b(i); a.w := auxW(q)(i); a.lod := auxLod(q)(i); a.dref := auxRef(q)(i)
  }
  // Taken into respData at once: the sampler works on the next lane during the write-back.
  sampler.io.respReady := st === sWait

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
    sampler.io.req.valid := true.B
    st := sWait
  }
  when(st === sWait) {
    when(sampler.io.resp.valid) {
      respLane := sampler.io.resp.bits.lane; respData := sampler.io.resp.bits.data
      lastResp := sampler.io.done
      wbK := 0.U; st := sWB
    }
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

  io.busy := sampler.io.busy
  io.wbWant := st === sWB
  sampler.io.gpuMem.data    := io.mem.data
  sampler.io.gpuMem.ready   := io.mem.ready && sampler.io.busy
  sampler.io.gpuMem.waccept := false.B
  io.mem.req   := sampler.io.gpuMem.req
  io.mem.addr  := sampler.io.gpuMem.addr
  io.mem.wr    := false.B
  io.mem.wdata := 0.U
  io.mem.wlen  := 1.U
}
