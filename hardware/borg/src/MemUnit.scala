// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

class MemUnitIO(cfg: BorgConfig) extends CoreUnitIO(cfg) {
  val record     = Input(new CoreRecordIO)
  val laneHelper = Input(Vec(cfg.fragLanes, Bool()))
  val seqBusy    = Input(Bool())
  val mem  = new GpuMemIO
  val busy = Output(Bool())
}

/** Loads, stores and AMOs (byte address rs1 + imm), SOUT and FATTR (docs/B1_geometry_front_end.md),
  * one lane after another: each lane computes its own address, there is no coalescing. A masked
  * lane makes no access; a helper lane (no covered sample, discarded, failed ZTEST) makes no store
  * or AMO, but loads, and stores its own spill slots (SWS). Accesses complete in program order. */
class MemUnit(cfg: BorgConfig) extends Module {
  val io = IO(new MemUnitIO(cfg))
  private val N = cfg.fragLanes
  private val W = cfg.totalBits
  io.idle()

  private val isLoad = RegInit(false.B)
  // STORE and SOUT both write rs2 per lane; they differ only in the address.
  private val isStore = RegInit(false.B)
  private val isSout = RegInit(false.B); private val isSws = RegInit(false.B); private val isFattr = RegInit(false.B)
  private val rvMem = RegInit(false.B); private val rvWidth = Reg(UInt(3.W)); private val rvImm = Reg(UInt(32.W))
  private val isAmo = RegInit(false.B); private val amoOp = Reg(UInt(5.W)); private val amoNew = Reg(UInt(32.W))
  private val regs = Reg(new RegIndices)
  when(io.issue.valid) {
    val rv = RvInt(io.issue.bits.word)
    rvMem := rv.load || rv.store || rv.amo; rvWidth := Mux(rv.amo, 2.U, rv.width); rvImm := rv.memImm
    isAmo := rv.amo; amoOp := rv.amoOp
    isLoad := rv.load || rv.amo; isStore := rv.store || rv.sout; isSout := rv.sout
    isSws := rv.sws; isFattr := rv.fattr
    regs := io.issue.bits.regs
  }

  private val sIdle :: sReq :: sWB :: sAttrReq :: sAttrLook :: sAttrMem :: sAttrWB :: sAmoWr :: Nil = Enum(8)
  private val state = RegInit(sIdle)
  private val lane = RegInit(0.U(log2Up(N).W))
  private val rd   = RegInit(0.U(5.W))
  private val data = RegInit(0.U(W.W))

  // The lanes' operands and mask as the instruction starts, at its operands.
  private val start = io.atOps && state === sIdle && (isLoad || isStore || isFattr)
  private val (opA, opB, opC, mask) = (RegEnable(io.a, start), RegEnable(io.b, start), RegEnable(io.c, start), RegEnable(io.mask, start))
  private val curIndex = opA(lane); private val curData = opB(lane); private val curCmp = opC(lane)
  // A load whose active lanes share one address makes one access for all of them.
  private val uniform = RegEnable(isLoad && !isAmo && {
    val first = Mux1H(PriorityEncoderOH(io.mask), io.a)
    (0 until N).map(i => !io.mask(i) || io.a(i) === first).reduce(_ && _)
  }, start)

  // Word accesses are aligned: the low address bits select within the word.
  private val rvAddr = (curIndex.pad(32) + rvImm)(31, 0)
  private val lsAddr = Cat(rvAddr(GpuMemIO.AddrBits - 1, 2), 0.U(2.W))
  private def rvLoadData(w: UInt): UInt = {
    val sh = (w >> Cat(rvAddr(1, 0), 0.U(3.W)))(31, 0)
    riscv.Rv.loadExtend(rvWidth, sh, 32, full = Some(w))
  }

  // SOUT and FATTR take a component index from the instruction's register fields.
  private val compIdx = RegInit(0.U(10.W))
  private val attrK   = RegInit(0.U(2.W))          // FATTR: which of the three words
  // A vertex shader's SOUT index: bits 7:0 the component, bits 9:8 = k + 1 to store corner k's
  // lane only (0 = every corner), so a varying can go out as a0, a1 - a0, a2 - a0.
  private val soutSel = compIdx(9, 8)
  private val rec = io.record
  private val soutLaneOk =
    !(isSout && rec.outInterleave && soutSel =/= 0.U && (rec.outCorner +& lane) =/= soutSel - 1.U)
  // FATTR: the component's first word.
  private val attrHead = (rec.attrBase +& (((compIdx << 1) +& compIdx) << 2))(GpuMemIO.AddrBits - 1, 0)
  private val byteAddr = {
    val comp   = compIdx(7, 0)
    val corner = rec.outCorner +& lane
    val word   = Mux(rec.outInterleave, ((comp << 1) +& comp) +& corner, compIdx)
    val soutAddr = (rec.outBase +& (word << 2))(GpuMemIO.AddrBits - 1, 0)
    val attrAddr = (attrHead +& (attrK << 2))(GpuMemIO.AddrBits - 1, 0)
    Mux(isFattr, attrAddr, Mux(isSout, soutAddr, lsAddr))
  }

  io.mem.req   := false.B
  io.mem.addr  := 0.U
  io.mem.wr    := false.B
  io.mem.wdata := 0.U
  io.mem.wlen  := 1.U
  io.busy      := state =/= sIdle
  io.wbWant    := state === sWB || state === sAttrWB

  when(io.atOps && state === sIdle && (isLoad || isStore)) {
    rd := regs.rd; lane := Mux(isLoad && !isAmo, PriorityEncoder(io.mask), 0.U)
    state := sReq
    compIdx := Cat(regs.rs1, regs.rd)          // SOUT: index in rs1:rd
  }
  // FATTR is one access for the whole quad: the three per-vertex values are the same for every
  // fragment of the triangle.
  when(io.atOps && state === sIdle && isFattr) {
    rd := regs.rd; attrK := 0.U
    state := sAttrReq
    compIdx := Cat(regs.rs2, regs.rs1)         // FATTR: index in rs2:rs1
  }

  private def finishInstr(): Unit = {
    state := sIdle
    isLoad := false.B; isStore := false.B; isSout := false.B; isSws := false.B; isFattr := false.B
    io.finish := true.B
  }
  private def finishLane(): Unit = {
    when(lane === (N - 1).U) { finishInstr() }
      .otherwise { lane := lane + 1.U; state := sReq }
  }

  // The helper bits describe the quad the dispatcher shades, never a sequencer's program.
  private val laneActive = mask(lane) && soutLaneOk &&
    !((isStore && !isSws || isAmo) && !io.seqBusy && io.laneHelper(lane))

  when(state === sReq && !laneActive) {
    finishLane()
  }
  when(state === sReq && laneActive) {
    io.mem.addr  := byteAddr
    io.mem.req   := isLoad
    io.mem.wr    := isStore
    io.mem.wdata := curData
    when(io.mem.ready) {
      if (BorgDebug.trace) printf("[MEM] load=%d lane=%d addr=0x%x data=0x%x\n",
        isLoad, lane, byteAddr, Mux(isLoad, io.mem.data, curData))
      when(isLoad) {
        data   := Mux(rvMem, rvLoadData(io.mem.data.pad(32)), io.mem.data)(W - 1, 0)
        state  := Mux(isAmo, sAmoWr, sWB)
        amoNew := riscv.Rv.amoResult(amoOp, io.mem.data.pad(32)(31, 0), curData.pad(32),
                                     other = curData.pad(32), cmp = Some(curCmp.pad(32)))
      }.otherwise {
        finishLane()
      }
    }
  }
  // AMO: the new value goes to the same word, then rd gets the old one.
  when(state === sAmoWr) {
    io.mem.addr  := byteAddr
    io.mem.wr    := true.B
    io.mem.wdata := amoNew
    when(io.mem.ready) { state := sWB }
  }
  when(state === sWB) {
    io.wb.zipWithIndex.foreach { case (w, i) =>
      w.en := io.wbReady && Mux(uniform, mask(i), i.U === lane) && !(rvMem && rd === 0.U); w.addr := rd; w.data := data
    }
    when(io.wbReady) { when(uniform) { finishInstr() }.otherwise { finishLane() } }
  }

  // FATTR: word k to rd+k of every active lane, three times, through a direct-mapped cache of
  // whole components: one lookup, three write-backs.
  // Nothing writes records during Pass 2, so instead of snooping writes each Pass 2 start moves to
  // a new epoch and an entry hits only in its own. Epoch 0 is never current; on wrap and out of
  // reset a sweep zeroes every entry's epoch, and nothing hits until it ends.
  private val fcLog2    = cfg.fattrCacheLog2
  private val fcTagBits = GpuMemIO.AddrBits - 2
  private val fcWidth   = fcTagBits + 8 + 3 * W              // tag, epoch, words 2..0
  private val fcMem     = SyncReadMem(1 << fcLog2, UInt(fcWidth.W))
  private val fcEpoch    = RegInit(1.U(8.W))
  private val fcSweeping = RegInit(true.B)
  private val fcSweepIdx = RegInit(0.U(math.max(fcLog2, 1).W))
  private val fcAddr     = RegInit(0.U(fcTagBits.W))
  private val attrWords  = Reg(Vec(3, UInt(W.W)))
  /** The entry of a byte address: the word address XOR-folded, so records a power of two apart
    * do not all collide. */
  private def fcIndex(addr: UInt): UInt = {
    val w = addr(GpuMemIO.AddrBits - 1, 2)
    (0 until (fcTagBits + fcLog2 - 1) / fcLog2)
      .map(i => w(math.min((i + 1) * fcLog2, fcTagBits) - 1, i * fcLog2).pad(fcLog2))
      .reduce(_ ^ _)
  }
  private val fcRead    = fcMem.read(fcIndex(attrHead), state === sAttrReq)
  private val fcFill    = WireDefault(false.B)
  private val memWord   = io.mem.data(W - 1, 0)
  when(fcSweeping) {
    fcSweepIdx := fcSweepIdx + 1.U
    when(fcSweepIdx === ((1 << fcLog2) - 1).U) { fcSweeping := false.B }
  }
  when(io.record.attrFlush) {
    when(fcEpoch === 255.U) { fcEpoch := 1.U; fcSweeping := true.B; fcSweepIdx := 0.U }
      .otherwise { fcEpoch := fcEpoch + 1.U }
  }
  // One write port: the sweep wins over a fill, which only costs a hit.
  when(fcSweeping) { fcMem.write(fcSweepIdx, 0.U(fcWidth.W)) }
    .elsewhen(fcFill) { fcMem.write(fcIndex(attrHead), Cat(fcAddr, fcEpoch, memWord, attrWords(1), attrWords(0))) }

  when(state === sAttrReq) {
    fcAddr := attrHead(GpuMemIO.AddrBits - 1, 2)
    state  := sAttrLook
  }
  when(state === sAttrLook) {
    val eTag   = fcRead(fcWidth - 1, 8 + 3 * W)
    val eEpoch = fcRead(8 + 3 * W - 1, 3 * W)
    when(!fcSweeping && eEpoch === fcEpoch && eTag === fcAddr) {
      attrWords := VecInit.tabulate(3)(k => fcRead((k + 1) * W - 1, k * W)); state := sAttrWB
    }.otherwise { state := sAttrMem }
  }
  when(state === sAttrMem) {
    io.mem.addr := byteAddr
    io.mem.req  := true.B
    when(io.mem.ready) {
      attrWords(attrK) := memWord
      when(attrK === 2.U) { fcFill := true.B; attrK := 0.U; state := sAttrWB }.otherwise { attrK := attrK + 1.U }
    }
  }
  when(state === sAttrWB) {
    io.wb.zipWithIndex.foreach { case (w, i) =>
      w.en := io.wbReady && mask(i); w.addr := rd + attrK; w.data := attrWords(attrK)
    }
    when(io.wbReady) {
      when(attrK === 2.U) { finishInstr() }.otherwise { attrK := attrK + 1.U }
    }
  }
}
