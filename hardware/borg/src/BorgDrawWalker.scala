// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** The draw registers (DRAW_*, VIEWPORT_*, DEPTH_*). */
class DrawMmioIO extends Bundle {
  val topology      = UInt(2.W)    // 0 list, 1 strip, 2 fan
  val indexType     = UInt(2.W)    // 0 none, 1 u16, 2 u32
  val restart       = Bool()
  val recordShift   = UInt(4.W)
  val vertexCount   = UInt(32.W)
  val instanceCount = UInt(32.W)
  val firstVertex   = UInt(32.W)
  val firstInstance = UInt(32.W)
  val vertexOffset  = UInt(32.W)   // signed
  val indexBase     = UInt(GpuMemIO.AddrBits.W)
  val viewport      = Vec(4, UInt(32.W))   // sx, sy, ox, oy (FP32)
  val depthScale    = UInt(32.W)
  val depthOffset   = UInt(32.W)
  val singleSample  = Bool()       // rasterizationSamples = 1: every sample at the centre
  val depthBiasConst = UInt(32.W)  // FP32 depthBiasConstantFactor
  val depthBiasSlope = UInt(32.W)  // FP32 depthBiasSlopeFactor
  val depthD32      = Bool()       // D32_SFLOAT: r per triangle, else D16's 2^-16
  val depthD24      = Bool()       // D24_UNORM kept as FP32
}

class BorgDrawWalkerIO(val cfg: BorgConfig) extends Bundle {
  val start = Input(Bool())
  val done  = Output(Bool())
  val busy  = Output(Bool())
  // Render lists: the first draw clears the bins and numbers triangles from 0, later ones continue.
  val first     = Input(Bool())
  val stateAddr = Input(UInt(GpuMemIO.AddrBits.W))
  // A full bin pauses the walker on its triangle until the sequencer has rendered
  // the bins (docs/B11_partial_render.md); resume bins the triangle again.
  val paused = Output(Bool())
  val resume = Input(Bool())

  val mmio  = new SeqMmioIO(cfg)
  val draw  = Input(new DrawMmioIO)
  val binner = new SeqBinnerIO(cfg)
  val store  = new SeqStoreIO
  val dma    = new SeqDmaIO(cfg)

  val coreTrigger = new CoreTriggerIO
  val coreStatus  = Flipped(new CoreStatusIO)
  val csrWrite    = Flipped(Vec(cfg.fragLanes, new CsrWriteIO(cfg.totalBits)))
  val csrSlot     = Input(UInt(cfg.slotBits.W))
  val slotBusy    = Input(Vec(cfg.quadSlots, Bool()))
  val triggerSlot = Output(UInt(cfg.slotBits.W))
  val uniformPage = Output(UInt(1.W))
  val uniformWrite = new MemWritePort(6, cfg.totalBits)
  val seqShaderActive = Output(Bool())
  val ids    = Output(new InvocationIdsIO(cfg))
  val record = Output(new CoreRecordIO)
}

/** BorgDrawWalker: Pass 1 of a draw (docs/B1_geometry_front_end.md).
  *
  * Per instance and primitive: assemble the corners, fetch indices (with restart), run the
  * vertex shader once for all three corners, run the setup ROM, cull, bin, store the facing.
  * The front (up to the setup run) works on one context, the back (setup result, cull, bin,
  * store) finishes the one before: with two uniform pages and slots, context c on slot and
  * page c, so a triangle's setup overlaps the next one's vertex shader. The back keeps order.
  * The vertex shader and the setup ROM write the rest of the record themselves (SOUT).
  * The triangle index counts every primitive, culled or not, so record t is primitive t.
  */
class BorgDrawWalker(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgDrawWalkerIO(cfg))

  private val (sIdle :: sLoadVS :: sLoadConst :: sWaitDMA :: sAssemble :: sFetchIdx :: sIdxDone :: sRunVS ::
               sWaitVS :: sStage :: sRunSetup :: sNext :: sHold :: Nil) = Enum(13)
  private val (bIdle :: bWaitSetup :: bBin :: bWaitBin :: bStore :: bPaused :: Nil) = Enum(6)
  private val state = RegInit(sIdle)
  private val bState = RegInit(bIdle)
  private val ctxs = if (cfg.maxUniforms > 32 && cfg.quadSlots >= 2) 2 else 1
  private val fc = RegInit(0.U(1.W))            // the front's context: its slot and uniform page
  private val cPage = RegInit(0.U(1.W))         // the page the constants load into
  private val constLoad = RegInit(false.B)
  private val resumeTo = RegInit(sIdle)         // where the front goes on after a full bin
  private val bResume = RegInit(false.B)
  private val nextAfterDMA = RegInit(sIdle)
  private val resuming = RegInit(false.B)

  private val d = io.draw
  private val inst  = RegInit(0.U(32.W))
  private val prim  = RegInit(0.U(32.W))  // primitive within the current strip/fan/list
  private val base  = RegInit(0.U(32.W))  // first position of the current strip/fan (restart)
  private val tri   = RegInit(0.U(16.W))
  private val k     = RegInit(0.U(2.W))   // corner being fetched / shaded
  private val pos   = Reg(Vec(3, UInt(32.W)))
  private val vidx  = Reg(Vec(3, UInt(32.W)))
  private val restartAt = RegInit(0.U(32.W))
  private val sawRestart = RegInit(false.B)
  private val clip  = Reg(Vec(3, Vec(4, UInt(32.W))))   // X, Y, Z, W per corner
  // Per context, the triangle handed to the back: its index, the corners' W, then its setup's
  // screen corners (setup_screen0..5) and det M; done once its setup has finished.
  private val eValid = RegInit(VecInit(Seq.fill(ctxs)(false.B)))
  private val eDone  = RegInit(VecInit(Seq.fill(ctxs)(false.B)))
  private val eTri   = Reg(Vec(ctxs, UInt(16.W)))
  private val eClipW = Reg(Vec(ctxs, Vec(3, UInt(32.W))))
  private val eScreen = Reg(Vec(ctxs, Vec(6, UInt(32.W))))
  private val eDet   = Reg(Vec(ctxs, UInt(32.W)))
  private val head   = RegInit(0.U(1.W))           // the context the back finishes next: triangle order
  private val bTri   = eTri(head)
  private val isBack = RegInit(false.B)
  private val stageIdx = RegInit(0.U(5.W))
  private val dmaDescReg = RegInit(0.U.asTypeOf(new DMADescriptor))

  private val bboxMinX = RegInit(0.U(cfg.coordWidth.W))
  private val bboxMinY = RegInit(0.U(cfg.coordWidth.W))
  private val bboxMaxX = RegInit(0.U(cfg.coordWidth.W))
  private val bboxMaxY = RegInit(0.U(cfg.coordWidth.W))

  private val recordBase = (io.mmio.setupBase +& (tri << d.recordShift))(GpuMemIO.AddrBits - 1, 0)
  /** Where the vertex shader's SOUT component 0 goes: record word 48. */
  private val attrOffset = 48 * 4

  private val bRecordBase = (io.mmio.setupBase +& (bTri << d.recordShift))(GpuMemIO.AddrBits - 1, 0)
  private val slotWasBusy = RegNext(io.slotBusy, VecInit(Seq.fill(cfg.quadSlots)(false.B)))
  private def finished(s: UInt): Bool = slotWasBusy(s) && !io.slotBusy(s)
  private def slotFree(c: UInt): Bool = !eValid(c) || eDone(c)
  // The front's DMAs and the back's bins and store never overlap (a snoop read beside them returns 0).
  private val frontDma = state === sWaitDMA || state === sLoadVS || state === sLoadConst || state === sFetchIdx
  private val backMem  = bState === bBin || bState === bWaitBin || bState === bStore
  private def retire(): Unit = { eValid(head) := false.B; if (ctxs > 1) head := ~head; bState := bIdle }
  private val hold = bState === bPaused && !bResume   // a full bin: the front stops where it can

  io.busy := state =/= sIdle
  io.done := false.B
  // Pass 2 takes the core and the pages: no setup may still run.
  io.paused := hold && state === sHold && (0 until ctxs).map(c => slotFree(c.U)).reduce(_ && _)
  io.seqShaderActive := state === sRunVS || state === sWaitVS || state === sRunSetup ||
    (0 until ctxs).map(c => eValid(c) && !eDone(c)).reduce(_ || _)
  io.triggerSlot := (if (ctxs > 1) fc else 0.U)
  io.uniformPage := Mux(constLoad, cPage, fc)
  io.dma.start := false.B
  io.dma.desc  := dmaDescReg
  io.coreTrigger.valid   := false.B
  io.coreTrigger.pc      := 0.U
  io.coreTrigger.isRast  := false.B
  io.coreTrigger.isSetup := false.B
  io.coreTrigger.fragment := false.B
  io.uniformWrite.en   := false.B
  io.uniformWrite.addr := 0.U
  io.uniformWrite.data := 0.U
  io.binner.start       := false.B
  io.binner.triIndex    := bTri
  io.binner.bbox.min.x  := bboxMinX
  io.binner.bbox.min.y  := bboxMinY
  io.binner.bbox.max.x  := bboxMaxX
  io.binner.bbox.max.y  := bboxMaxY
  io.binner.clearCounts := false.B
  io.binner.countReadAddr := 0.U
  io.binner.countReadEn   := false.B
  io.store.active := bState === bStore
  io.store.req    := false.B
  io.store.addr   := bRecordBase + (BorgSetupRom.Record.Meta * 4).U
  // Meta word: bits 31:2 the draw's state block (a word address, 0 outside a
  // render list), bit 1 = back-facing; bit 0 is unused.
  io.store.wdata  := Cat(io.stateAddr(GpuMemIO.AddrBits - 1, 2), isBack, 0.U(1.W))

  // Vertex shader: VertexIndex/InstanceIndex, one corner per lane; setup ROM: none.
  private val inVS = state === sRunVS || state === sWaitVS
  io.ids.mode     := inVS
  io.ids.laneMask := "b0111".U(cfg.fragLanes.W)   // all three corners in one run
  io.ids.depth    := 0.U
  for (i <- 0 until cfg.fragLanes) {
    io.ids.r30(i) := (if (i < 3) vidx(i) else 0.U)
    io.ids.r31(i) := d.firstInstance + inst
  }
  io.record.outBase       := Mux(inVS, recordBase + attrOffset.U, recordBase)
  io.record.outInterleave := inVS
  io.record.outCorner     := 0.U
  io.record.attrBase      := recordBase + attrOffset.U
  io.record.attrFlush     := false.B       // the sequencer drives it (Pass 2)

  private def dma(desc: DMADescriptor, next: UInt): Unit = {
    dmaDescReg   := desc
    io.dma.desc  := desc
    io.dma.start := true.B
    nextAfterDMA := next
    state        := sWaitDMA
  }

  private val isList = d.topology === 0.U
  private val isFan  = d.topology === 2.U
  private val odd    = prim(0)
  // Vulkan's orders, corner 0 being the provoking vertex (flat shading):
  // list {3p, 3p+1, 3p+2}; strip {p, p+1, p+2}, odd p {p, p+2, p+1} (the
  // winding flips back); fan {p+1, p+2, 0}.
  private val relPos = VecInit(
    Mux(isList, prim * 3.U,       Mux(isFan, prim + 1.U, prim)),
    Mux(isList, prim * 3.U + 1.U, Mux(isFan, prim + 2.U, Mux(odd, prim + 2.U, prim + 1.U))),
    Mux(isList, prim * 3.U + 2.U, Mux(isFan, 0.U,        Mux(odd, prim + 1.U, prim + 2.U))))
  private val lastPos  = Mux(isList, prim * 3.U + 2.U, base + prim + 2.U)
  private val indexed  = d.indexType =/= 0.U
  private val idx16    = d.indexType === 1.U
  private val restartOn = d.restart && indexed && !isList

  switch(state) {
    is(sIdle) {
      when(io.start) {
        inst := 0.U; prim := 0.U; base := 0.U
        when(io.first) {
          tri := 0.U
          io.binner.clearCounts := true.B
        }
        // BorgSequencer routes Pass 1's DMA port from the next cycle on.
        state := sLoadVS
      }
    }
    is(sLoadVS) {
      when(!backMem) {
        val desc = Wire(new DMADescriptor)
        desc.baseAddr := io.mmio.vertShaderAddr
        desc.length   := io.mmio.vertShaderLen
        desc.dest     := 0.U                      // IMEM (and the I-cache's codeBase)
        desc.offset   := 0.U
        dma(desc, sLoadConst)
        cPage := 0.U
      }
    }
    is(sLoadConst) {
      when(!backMem) {
        // The vertex shader's constants, once per draw; the setup ROM writes only below them.
        val first = BorgSetupRom.Record.VsConstFirst
        val desc = Wire(new DMADescriptor)
        desc.baseAddr := io.mmio.vsConstBase
        desc.length   := (32 - first).U
        desc.dest     := 1.U
        desc.offset   := first.U
        // Into every context's page; after a full bin the front goes on where it stopped.
        val next = Mux(resuming, resumeTo, sAssemble)
        when(io.mmio.vsConstBase =/= 0.U) { dma(desc, next); constLoad := true.B }
          .otherwise { state := next; resuming := false.B }
      }
    }
    is(sWaitDMA) {
      when(!io.dma.busy) {
        state := nextAfterDMA
        when(constLoad) {
          constLoad := false.B
          when(cPage =/= (ctxs - 1).U) { cPage := cPage + 1.U; state := sLoadConst }
            .otherwise { resuming := false.B }
        }
      }
    }
    is(sAssemble) {
      when(inst >= d.instanceCount) {
        when(hold) { resumeTo := sAssemble; state := sHold }
          .elsewhen(!eValid.reduce(_ || _)) { io.done := true.B; state := sIdle }
      }.elsewhen(lastPos >= d.vertexCount) {
        inst := inst + 1.U; prim := 0.U; base := 0.U   // next instance
      }.otherwise {
        for (c <- 0 until 3) pos(c) := Mux(isList, relPos(c), base + relPos(c))
        k := 0.U
        sawRestart := false.B
        state := Mux(indexed, sFetchIdx, sIdxDone)
      }
    }
    is(sFetchIdx) {
      when(!backMem) {
        val byteAddr = d.indexBase +& Mux(idx16, pos(k) << 1, pos(k) << 2)
        val desc = Wire(new DMADescriptor)
        desc.baseAddr := Cat(byteAddr(GpuMemIO.AddrBits - 1, 2), 0.U(2.W))
        desc.length   := 1.U
        desc.dest     := 2.U                        // snoop only
        desc.offset   := 0.U
        dma(desc, Mux(k === 2.U, sIdxDone, sFetchIdx))
        k := k + 1.U
      }
    }
    is(sIdxDone) {
      when(sawRestart) {
        // Restart after the latest restart index this primitive touched.
        base := restartAt + 1.U; prim := 0.U
        state := sAssemble
      }.otherwise {
        when(!indexed) { for (c <- 0 until 3) vidx(c) := d.firstVertex + pos(c) }
        k := 0.U
        state := sRunVS
      }
    }
    is(sRunVS) {
      when(hold) { resumeTo := sRunVS; state := sHold }
        .elsewhen(slotFree(fc)) {
          io.coreTrigger.valid := true.B
          state := sWaitVS
        }
    }
    is(sWaitVS) {
      when(finished(fc)) {
        state := sStage
        stageIdx := 0.U
      }
    }
    is(sStage) {
      // Every W <= 0: the triangle is entirely behind the eye.
      def notFront(w: UInt) = w(31) || w(30, 0) === 0.U
      when(clip.map(c => notFront(c(3))).reduce(_ && _)) {
        state := sNext
      }.otherwise {
        io.uniformWrite.en   := true.B
        io.uniformWrite.addr := Cat(fc, stageIdx)   // the front's page
        io.uniformWrite.data := MuxLookup(stageIdx, 0.U)(
          (for (c <- 0 until 3; j <- 0 until 4) yield (4 * c + j).U -> clip(c)(j)) ++ Seq(
            12.U -> d.viewport(0), 13.U -> d.viewport(1), 14.U -> d.viewport(2), 15.U -> d.viewport(3),
            16.U -> d.depthScale, 17.U -> d.depthOffset,
            18.U -> "h3F800000".U,       //  1.0
            // 4x sample offsets, or all at the centre for single-sample so samples resolve exactly.
            19.U -> Mux(d.singleSample, 0.U, "hBE000000".U),   // -0.125
            20.U -> Mux(d.singleSample, 0.U, "hBEC00000".U),   // -0.375
            21.U -> Mux(d.singleSample, 0.U, "h3EC00000".U),   // +0.375
            22.U -> d.depthBiasSlope, 23.U -> d.depthBiasConst,
            // r is the spec's 2 x 2^-n: 2^-15 for D16, 2^-23 for D24 (one step is under it).
            24.U -> Mux(d.depthD24, "h34000000".U, Mux(d.depthD32, 0.U, "h38000000".U))))
        when(stageIdx === (BorgSetupRom.Uniforms - 1).U) { state := sRunSetup }
        stageIdx := stageIdx + 1.U
      }
    }
    is(sRunSetup) {
      // The staged page is gone after a full bin: stage it again.
      when(hold) { resumeTo := sStage; stageIdx := 0.U; state := sHold }
        .elsewhen(!eValid(fc)) {
          io.coreTrigger.valid   := true.B
          io.coreTrigger.isSetup := true.B
          eValid(fc) := true.B; eDone(fc) := false.B; eTri(fc) := tri
          for (c <- 0 until 3) eClipW(fc)(c) := clip(c)(3)
          if (ctxs > 1) fc := ~fc
          state := sNext
        }
    }
    is(sHold) {
      // Pass 2 ran in between: its fragment shader and constants replaced ours.
      when(io.resume) { resuming := true.B; state := sLoadVS }
    }
    is(sNext) {
      tri  := tri + 1.U
      prim := prim + 1.U
      state := sAssemble
    }
  }

  for (c <- 0 until ctxs) when(eValid(c) && finished(c.U)) { eDone(c) := true.B }
  private val screen = eScreen(head); private val det = eDet(head)
  switch(bState) {
    is(bIdle) {
      when(eValid(head)) { bState := bWaitSetup }
    }
    is(bWaitSetup) {
      when(eDone(head) && !frontDma) {
        // det M: zero is degenerate, the sign is the facing; Vulkan's CCW front has det < 0.
        val degenerate = det(30, 0) === 0.U
        val back = !det(31) ^ io.mmio.frontFaceInvert
        isBack := back
        val culled = degenerate || Mux(back, io.mmio.cullMode(1), io.mmio.cullMode(0))
        // A corner at or behind the eye has no screen position: bin the whole grid.
        def front(w: UInt) = !w(31) && w(30, 0) =/= 0.U
        val allFront = eClipW(head).map(front).reduce(_ && _)
        def minOf(a: UInt, b: UInt) = Mux(ordered(a) <= ordered(b), a, b)
        def maxOf(a: UInt, b: UInt) = Mux(ordered(a) >= ordered(b), a, b)
        val xs = Seq(screen(0), screen(2), screen(4)); val ys = Seq(screen(1), screen(3), screen(5))
        // Framebuffer pixels (up to 8191), then relative to the render window.
        val G = 13
        val loX = PixelBox.toPixel(cfg, xs.reduce(minOf), G); val hiX = PixelBox.toPixel(cfg, xs.reduce(maxOf), G)
        val loY = PixelBox.toPixel(cfg, ys.reduce(minOf), G); val hiY = PixelBox.toPixel(cfg, ys.reduce(maxOf), G)
        val ox = Cat(io.mmio.fbOriginX, 0.U(2.W)); val oy = Cat(io.mmio.fbOriginY, 0.U(2.W))
        val wx = Cat(io.mmio.fbWidthTiles, 0.U(2.W)); val wy = Cat(io.mmio.fbHeightTiles, 0.U(2.W))
        // One pixel of slack on the far side for the ~22-bit reciprocal corners.
        val outside = allFront && (hiX +& 1.U < ox || hiY +& 1.U < oy || loX >= ox +& wx || loY >= oy +& wy)
        def local(v: UInt, o: UInt, w: UInt) = Mux(v < o, 0.U, Mux(v - o >= w, w - 1.U, v - o))
        val full = ((1 << cfg.coordWidth) - 1).U
        val lx = local(loX, ox, wx); val ly = local(loY, oy, wy)
        val hx = local(hiX +& 1.U, ox, wx); val hy = local(hiY +& 1.U, oy, wy)
        bboxMinX := Mux(allFront, Cat(lx(cfg.coordWidth - 1, 2), 0.U(2.W)), 0.U)
        bboxMinY := Mux(allFront, Cat(ly(cfg.coordWidth - 1, 2), 0.U(2.W)), 0.U)
        bboxMaxX := Mux(allFront, hx(cfg.coordWidth - 1, 0), full)
        bboxMaxY := Mux(allFront, hy(cfg.coordWidth - 1, 0), full)
        when(culled || outside) { retire() }.otherwise { bState := bBin }
      }
    }
    is(bBin) {
      io.binner.start := true.B
      bState := bWaitBin
    }
    is(bWaitBin) {
      when(!io.binner.busy) { bState := Mux(io.binner.full, bPaused, bStore) }
    }
    is(bPaused) {
      // Bins again once the front has reloaded the vertex shader and its constants.
      when(io.resume) { bResume := true.B }
      when(bResume && !resuming && !frontDma) { bResume := false.B; bState := bBin }
    }
    is(bStore) {
      io.store.req := true.B
      when(io.store.ready) { retire() }
    }
  }

  if (BorgDebug.trace) {
    val last = RegNext(state)
    when(state =/= last) {
      printf("[DRAW] state %d -> %d inst=%d prim=%d base=%d tri=%d k=%d vidx=(%d,%d,%d)\n",
        last, state, inst, prim, base, tri, k, vidx(0), vidx(1), vidx(2))
    }
    when(bState === bWaitSetup && eDone(head)) {
      printf("[DRAW] setup tri=%d det=0x%x screen=(0x%x,0x%x) (0x%x,0x%x) (0x%x,0x%x)\n",
        bTri, eDet(head), eScreen(head)(0), eScreen(head)(1), eScreen(head)(2), eScreen(head)(3), eScreen(head)(4), eScreen(head)(5))
    }
  }

  /** Sign-magnitude float -> an unsigned key with the same order. */
  private def ordered(x: UInt): UInt = Mux(x(31), ~x, x | (1.U << 31))

  // Index buffer words (snoop-only DMA of the aligned word holding pos(k)).
  when(io.dma.snoop.valid && state === sWaitDMA && (nextAfterDMA === sFetchIdx || nextAfterDMA === sIdxDone)) {
    val c = k - 1.U                                  // k already advanced
    val byteAddr = d.indexBase +& Mux(idx16, pos(c) << 1, pos(c) << 2)
    val word = io.dma.snoop.bits
    val index = Mux(idx16, Mux(byteAddr(1), word(31, 16), word(15, 0)), word)
    val isRestart = restartOn && Mux(idx16, index(15, 0) === "hFFFF".U, index === "hFFFFFFFF".U)
    vidx(c) := index + d.vertexOffset
    when(isRestart && (!sawRestart || pos(c) > restartAt)) {
      sawRestart := true.B
      restartAt  := pos(c)
    }
  }

  // Clip-space position: out_pos0..3 of each corner's lane (or run).
  when(state === sWaitVS && io.csrSlot === fc) {
    for (lane <- 0 until cfg.fragLanes; comp <- 0 until 4) {
      val pw = io.csrWrite(lane)
      when(pw.en && pw.addr === (Csr.OutPos0 + comp).U) {
        if (lane < 3) clip(lane)(comp) := pw.data(31, 0)
      }
    }
  }
  // Setup ROM: screen corners and det M.
  for (c <- 0 until ctxs) when(eValid(c) && !eDone(c) && io.csrSlot === c.U && io.csrWrite(0).en) {
    for (r <- 0 until 6) when(io.csrWrite(0).addr === (Csr.SetupScreen0 + r).U) { eScreen(c)(r) := io.csrWrite(0).data(31, 0) }
    when(io.csrWrite(0).addr === Csr.SetupDet.U) { eDet(c) := io.csrWrite(0).data(31, 0) }
  }
}

/** Float screen coordinate -> integer pixel, for bounding boxes. */
object PixelBox {
  /** Truncates to the integer part; negatives clamp to 0, overflow to the maximum. */
  def toPixel(cfg: BorgConfig, fp: UInt, width: Int = 0): UInt = {
    val w        = if (width > 0) width else cfg.coordWidth
    val mantBits = cfg.sig - 1
    val bias     = (1 << (cfg.exp - 1)) - 1
    val e        = fp(cfg.totalBits - 2, mantBits)
    val norm     = Cat(1.U(1.W), fp(mantBits - 1, 0))
    val big      = e >= (bias + w).U
    val raw = Mux(e < bias.U, 0.U, (norm >> ((bias + mantBits).U - e))(w - 1, 0))
    Mux(fp(cfg.totalBits - 1), 0.U(w.W), Mux(big, ((1 << w) - 1).U(w.W), raw))
  }
}
