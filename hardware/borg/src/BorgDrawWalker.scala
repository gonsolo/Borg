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
  val indexBase     = UInt(25.W)
  val viewport      = Vec(4, UInt(32.W))   // sx, sy, ox, oy (FP32)
  val depthScale    = UInt(32.W)
  val depthOffset   = UInt(32.W)
}

class BorgDrawWalkerIO(val cfg: BorgConfig) extends Bundle {
  val start = Input(Bool())
  val done  = Output(Bool())
  val busy  = Output(Bool())

  val mmio  = new SeqMmioIO(cfg)
  val draw  = Input(new DrawMmioIO)
  val binner = new SeqBinnerIO(cfg)
  val store  = new SeqStoreIO
  val dma    = new SeqDmaIO(cfg)

  val coreTrigger = new CoreTriggerIO
  val coreStatus  = Flipped(new CoreStatusIO)
  val pipeWrite   = Flipped(Vec(cfg.fragLanes, new PipeWriteIO(cfg.totalBits)))
  val uniformWrite = new MemWritePort(6, cfg.totalBits)
  val seqShaderActive = Output(Bool())
  val ids    = Output(new InvocationIdsIO(cfg))
  val record = Output(new CoreRecordIO)
}

/** BorgDrawWalker -- Pass 1 of a draw (DRAW_CFG mode 1), in place of
  * BorgGeometrySequencer's per-triangle descriptors. See
  * docs/B1_geometry_front_end.md.
  *
  * For every instance and every primitive: assemble the primitive's three
  * vertex positions for the topology, turn them into VertexIndex values
  * (reading the index buffer if there is one, and restarting strips and fans
  * at a restart index), run the vertex shader once for all three corners --
  * one per lane, or three times on a single-lane build -- run the setup ROM,
  * cull, bin, and store the triangle's facing. The vertex shader and the
  * setup ROM write the rest of the triangle's record themselves (SOUT).
  *
  * The triangle index counts every primitive assembled, culled or not, so
  * record t always belongs to primitive t.
  */
class BorgDrawWalker(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  require(cfg.drawEnabled, "the draw walker needs the draw front end (BorgConfig.drawEnabled)")
  val io = IO(new BorgDrawWalkerIO(cfg))

  private val (sIdle :: sLoadVS :: sLoadConst :: sWaitDMA :: sAssemble :: sFetchIdx :: sIdxDone :: sRunVS ::
               sWaitVS :: sStage :: sRunSetup :: sWaitSetup :: sBin :: sWaitBin :: sStoreMeta :: sNext :: Nil) = Enum(16)
  private val state = RegInit(sIdle)
  private val nextAfterDMA = RegInit(sIdle)
  private val simt = cfg.fragLanes >= 3   // all three corners in one run

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
  private val screen = Reg(Vec(6, UInt(32.W)))          // setup ROM r0..r5
  private val det   = Reg(UInt(32.W))                   // setup ROM r6
  private val isBack = RegInit(false.B)
  private val stageIdx = RegInit(0.U(5.W))
  private val dmaDescReg = RegInit(0.U.asTypeOf(new DMADescriptor))

  private val bboxMinX = RegInit(0.U(cfg.coordWidth.W))
  private val bboxMinY = RegInit(0.U(cfg.coordWidth.W))
  private val bboxMaxX = RegInit(0.U(cfg.coordWidth.W))
  private val bboxMaxY = RegInit(0.U(cfg.coordWidth.W))

  private val recordBase = (io.mmio.setupBase +& (tri << d.recordShift))(24, 0)
  /** Where the vertex shader's SOUT component 0 goes: record word 48. */
  private val attrOffset = 48 * 4

  private val coreWasActive = RegNext(io.coreStatus.running || io.coreStatus.autoRunPending, false.B)
  private val coreFinished  = coreWasActive && !io.coreStatus.running && !io.coreStatus.autoRunPending

  // --- Defaults ---------------------------------------------------------
  io.busy := state =/= sIdle
  io.done := false.B
  io.seqShaderActive := state === sRunVS || state === sWaitVS || state === sRunSetup || state === sWaitSetup
  io.dma.start := false.B
  io.dma.desc  := dmaDescReg
  io.coreTrigger.valid   := false.B
  io.coreTrigger.pc      := 0.U
  io.coreTrigger.isRast  := false.B
  io.coreTrigger.isSetup := false.B
  io.uniformWrite.en   := false.B
  io.uniformWrite.addr := 0.U
  io.uniformWrite.data := 0.U
  io.binner.start       := false.B
  io.binner.triIndex    := tri
  io.binner.bbox.min.x  := bboxMinX
  io.binner.bbox.min.y  := bboxMinY
  io.binner.bbox.max.x  := bboxMaxX
  io.binner.bbox.max.y  := bboxMaxY
  io.binner.clearCounts := false.B
  io.binner.countReadAddr := 0.U
  io.binner.countReadEn   := false.B
  io.store.active := state === sStoreMeta
  io.store.req    := false.B
  io.store.addr   := recordBase + (BorgSetupRom.Record.Meta * 4).U
  io.store.wdata  := Cat(isBack, 0.U(1.W))    // bit 1 = back-facing (bit 0, has_uvs, is legacy)

  // Vertex shader: VertexIndex/InstanceIndex, one corner per lane; setup ROM: none.
  private val inVS = state === sRunVS || state === sWaitVS
  io.ids.mode     := inVS
  io.ids.laneMask := (if (simt) "b0111".U(cfg.fragLanes.W) else 1.U(cfg.fragLanes.W))
  for (i <- 0 until cfg.fragLanes) {
    io.ids.r30(i) := (if (simt) (if (i < 3) vidx(i) else 0.U) else vidx(k))
    io.ids.r31(i) := d.firstInstance + inst
  }
  io.record.outBase       := Mux(inVS, recordBase + attrOffset.U, recordBase)
  io.record.outInterleave := inVS
  io.record.outCorner     := (if (simt) 0.U else k)
  io.record.attrBase      := recordBase + attrOffset.U

  private def dma(desc: DMADescriptor, next: UInt): Unit = {
    dmaDescReg   := desc
    io.dma.desc  := desc
    io.dma.start := true.B
    nextAfterDMA := next
    state        := sWaitDMA
  }

  // --- Primitive assembly ------------------------------------------------
  private val isList = d.topology === 0.U
  private val isFan  = d.topology === 2.U
  private val odd    = prim(0)
  private val relPos = VecInit(
    Mux(isList, prim * 3.U,       Mux(isFan, prim + 1.U, Mux(odd, prim + 1.U, prim))),
    Mux(isList, prim * 3.U + 1.U, Mux(isFan, prim + 2.U, Mux(odd, prim, prim + 1.U))),
    Mux(isList, prim * 3.U + 2.U, Mux(isFan, 0.U,        prim + 2.U)))
  private val lastPos  = Mux(isList, prim * 3.U + 2.U, base + prim + 2.U)
  private val indexed  = d.indexType =/= 0.U
  private val idx16    = d.indexType === 1.U
  private val restartOn = d.restart && indexed && !isList

  switch(state) {
    is(sIdle) {
      when(io.start) {
        inst := 0.U; prim := 0.U; base := 0.U; tri := 0.U
        io.binner.clearCounts := true.B
        // BorgSequencer routes Pass 1's DMA port from the next cycle on.
        state := sLoadVS
      }
    }
    is(sLoadVS) {
      {
        val desc = Wire(new DMADescriptor)
        desc.baseAddr := io.mmio.vertShaderAddr
        desc.length   := io.mmio.vertShaderLen
        desc.dest     := 0.U                      // IMEM (and the I-cache's codeBase)
        desc.offset   := 0.U
        dma(desc, sLoadConst)
      }
    }
    is(sLoadConst) {
      // The vertex shader's constant window, once per draw: the setup ROM
      // only ever writes the uniforms below it.
      val first = BorgSetupRom.Record.VsConstFirst
      val desc = Wire(new DMADescriptor)
      desc.baseAddr := io.mmio.vsConstBase
      desc.length   := (32 - first).U
      desc.dest     := 1.U
      desc.offset   := first.U
      when(io.mmio.vsConstBase =/= 0.U) { dma(desc, sAssemble) }.otherwise { state := sAssemble }
    }
    is(sWaitDMA) {
      when(!io.dma.busy) { state := nextAfterDMA }
    }
    is(sAssemble) {
      when(inst >= d.instanceCount) {
        io.done := true.B
        state   := sIdle
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
      val byteAddr = d.indexBase +& Mux(idx16, pos(k) << 1, pos(k) << 2)
      val desc = Wire(new DMADescriptor)
      desc.baseAddr := Cat(byteAddr(24, 2), 0.U(2.W))
      desc.length   := 1.U
      desc.dest     := 2.U                        // snoop only
      desc.offset   := 0.U
      dma(desc, Mux(k === 2.U, sIdxDone, sFetchIdx))
      k := k + 1.U
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
      io.coreTrigger.valid := true.B
      state := sWaitVS
    }
    is(sWaitVS) {
      when(coreFinished) {
        if (simt) state := sStage
        else when(k === 2.U) { state := sStage }.otherwise { k := k + 1.U; state := sRunVS }
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
        io.uniformWrite.addr := stageIdx
        io.uniformWrite.data := MuxLookup(stageIdx, 0.U)(
          (for (c <- 0 until 3; j <- 0 until 4) yield (4 * c + j).U -> clip(c)(j)) ++ Seq(
            12.U -> d.viewport(0), 13.U -> d.viewport(1), 14.U -> d.viewport(2), 15.U -> d.viewport(3),
            16.U -> d.depthScale, 17.U -> d.depthOffset,
            18.U -> "h3F800000".U,       //  1.0
            19.U -> "hBE000000".U,       // -0.125
            20.U -> "hBEC00000".U,       // -0.375
            21.U -> "h3EC00000".U))      // +0.375
        when(stageIdx === (BorgSetupRom.Uniforms - 1).U) { state := sRunSetup }
        stageIdx := stageIdx + 1.U
      }
    }
    is(sRunSetup) {
      io.coreTrigger.valid   := true.B
      io.coreTrigger.isSetup := true.B
      state := sWaitSetup
    }
    is(sWaitSetup) {
      when(coreFinished) {
        // det M: zero is a degenerate triangle; its sign is the facing.
        // With frontFaceInvert clear, front is Vulkan's counter-clockwise
        // (positive area in framebuffer coordinates), where det < 0.
        val degenerate = det(30, 0) === 0.U
        val back = !det(31) ^ io.mmio.frontFaceInvert
        isBack := back
        val culled = degenerate || Mux(back, io.mmio.cullMode(1), io.mmio.cullMode(0))
        // A corner at or behind the eye has no screen position: bin the
        // whole grid (the binner clamps it) and let the planes decide.
        def front(w: UInt) = !w(31) && w(30, 0) =/= 0.U
        val allFront = clip.map(c => front(c(3))).reduce(_ && _)
        def minOf(a: UInt, b: UInt) = Mux(ordered(a) <= ordered(b), a, b)
        def maxOf(a: UInt, b: UInt) = Mux(ordered(a) >= ordered(b), a, b)
        val xs = Seq(screen(0), screen(2), screen(4)); val ys = Seq(screen(1), screen(3), screen(5))
        val loX = PixelBox.toPixel(cfg, xs.reduce(minOf)); val hiX = PixelBox.toPixel(cfg, xs.reduce(maxOf))
        val loY = PixelBox.toPixel(cfg, ys.reduce(minOf)); val hiY = PixelBox.toPixel(cfg, ys.reduce(maxOf))
        val full = ((1 << cfg.coordWidth) - 1).U
        bboxMinX := Mux(allFront, Cat(loX(cfg.coordWidth - 1, 2), 0.U(2.W)), 0.U)
        bboxMinY := Mux(allFront, Cat(loY(cfg.coordWidth - 1, 2), 0.U(2.W)), 0.U)
        // One pixel of slack: the corners are ~22-bit reciprocals, and a
        // pixel whose centre sits a rounding error inside must not be lost.
        bboxMaxX := Mux(allFront, Mux(hiX === full, full, hiX + 1.U), full)
        bboxMaxY := Mux(allFront, Mux(hiY === full, full, hiY + 1.U), full)
        state := Mux(culled, sNext, sBin)
      }
    }
    is(sBin) {
      io.binner.start := true.B
      state := sWaitBin
    }
    is(sWaitBin) {
      when(!io.binner.busy) { state := sStoreMeta }
    }
    is(sStoreMeta) {
      io.store.req := true.B
      when(io.store.ready) { state := sNext }
    }
    is(sNext) {
      tri  := tri + 1.U
      prim := prim + 1.U
      state := sAssemble
    }
  }

  if (BorgDebug.trace) {
    val last = RegNext(state)
    when(state =/= last) {
      printf("[DRAW] state %d -> %d inst=%d prim=%d base=%d tri=%d k=%d vidx=(%d,%d,%d)\n",
        last, state, inst, prim, base, tri, k, vidx(0), vidx(1), vidx(2))
    }
    when(state === sWaitSetup && coreFinished) {
      printf("[DRAW] setup tri=%d det=0x%x screen=(0x%x,0x%x) (0x%x,0x%x) (0x%x,0x%x) W=(0x%x,0x%x,0x%x)\n",
        tri, det, screen(0), screen(1), screen(2), screen(3), screen(4), screen(5), clip(0)(3), clip(1)(3), clip(2)(3))
    }
  }

  // --- Snoops -------------------------------------------------------------
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

  // Clip-space position: r0..r3 of each corner's lane (or run).
  when(state === sWaitVS) {
    for (lane <- 0 until cfg.fragLanes; comp <- 0 until 4) {
      val pw = io.pipeWrite(lane)
      when(pw.en && pw.addr === comp.U) {
        if (simt) { if (lane < 3) clip(lane)(comp) := pw.data(31, 0) }
        else clip(k)(comp) := pw.data(31, 0)
      }
    }
  }
  // Setup ROM: screen corners r0..r5 and det M in r6.
  when(state === sWaitSetup && io.pipeWrite(0).en) {
    for (r <- 0 until 6) when(io.pipeWrite(0).addr === r.U) { screen(r) := io.pipeWrite(0).data(31, 0) }
    when(io.pipeWrite(0).addr === 6.U) { det := io.pipeWrite(0).data(31, 0) }
  }
}

/** Float screen coordinate -> integer pixel, for bounding boxes. */
object PixelBox {
  /** Truncates positive values to their integer part, clamps negatives to 0
    * and anything past the coordinate range to its maximum. */
  def toPixel(cfg: BorgConfig, fp: UInt): UInt = {
    val w        = cfg.coordWidth
    val mantBits = cfg.sig - 1
    val bias     = (1 << (cfg.exp - 1)) - 1
    val e        = fp(cfg.totalBits - 2, mantBits)
    val norm     = Cat(1.U(1.W), fp(mantBits - 1, 0))
    val big      = e >= (bias + w).U
    val raw = Mux(e < bias.U, 0.U, (norm >> ((bias + mantBits).U - e))(w - 1, 0))
    Mux(fp(cfg.totalBits - 1), 0.U(w.W), Mux(big, ((1 << w) - 1).U(w.W), raw))
  }
}
