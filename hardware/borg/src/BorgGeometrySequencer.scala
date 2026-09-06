// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgGeometrySequencer — Pass 1 of BorgSequencer's two-pass triangle-batch
  * render (see BorgSequencer's own class doc for the split's rationale).
  *
  * Per triangle: loads the vertex shader, runs it for each of 3 vertices
  * (snooping clip-space + colour/UV outputs), runs the setup shader (screen-
  * space edge setup), bins the triangle, stages its 31 uniforms into the live
  * uniform buffer, and stores the same 31 values (plus has_uvs, plus covDelta
  * at samples>1) to DRAM for Pass 2 to reload per-tile. Never touches a pixel;
  * everything it produces either drives BorgCore directly (vertex/setup
  * shader execution) or is consumed once, later, by BorgTileSequencer reading
  * it back from DRAM -- the two passes share no live register state, only
  * `triCount` (an mmio input, not internal state) and the start/done
  * handshake.
  *
  * FSM (17 states, unchanged in shape from the original monolithic
  * BorgSequencer's Pass 1 half -- see git history for that version):
  *   sIdle → sLoadShader → sWaitDMA → sLoadMVP → sWaitDMA → sLoadVert →
  *   sWaitDMA → sRunVert → sWaitVert → (repeat sLoadVert..sWaitVert for
  *   3 vertices) → sWriteSetupInputs → sLoadSetupShader → sWaitDMA →
  *   sRunSetup → sWaitSetup → sLoadBBox → sWaitDMA → sBinTri → sWaitBinner →
  *   sStageUniforms → sStoreSetup → sNextTriangle → (loop to sLoadShader, or
  *   pulse `done` and return to sIdle once triCount triangles are processed)
  */
class BorgGeometrySequencerIO(val cfg: BorgConfig) extends Bundle {
  val start = Input(Bool())
  val done  = Output(Bool())
  val busy  = Output(Bool())

  val mmio   = new SeqMmioIO(cfg)
  val binner = new SeqBinnerIO(cfg)   // only start/triIndex/bbox/clearCounts/busy used
  val store  = new SeqStoreIO
  val dma    = new SeqDmaIO

  val coreTrigger = new CoreTriggerIO
  val coreStatus  = Flipped(new CoreStatusIO)
  val pipeWrite   = Flipped(new PipeWriteIO(cfg.totalBits))
  val uniformWrite     = new MemWritePort(6, 16)
  val uniformWritePage = Output(UInt(1.W))
  val seqShaderActive  = Output(Bool())

  // The just-computed setup shader's raw covDelta output (r8..r13), held
  // stable from sWaitSetup through this triangle's sNextTriangle regardless
  // of whether the triangle was subsequently culled/binned. Exists purely so
  // the top-level wrapper can reproduce the pre-split design's observable
  // behavior: `io.covDelta` there fell back to reading this register
  // whenever the sequencer was NOT actively in a Pass 2 state (including at
  // idle, after a full frame completes) -- see BorgSequencer's own doc for
  // why that fallback is load-bearing, not incidental.
  val covDeltaOut = if (cfg.samples > 1)
    Some(Output(Vec(6, UInt(16.W)))) else None
}

class BorgGeometrySequencer(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgGeometrySequencerIO(cfg))

  val nStates = 17
  val states = Enum(nStates)
  val (sIdle :: sLoadShader :: sWaitDMA :: sLoadMVP :: sLoadVert :: sRunVert :: sWaitVert ::
       sWriteSetupInputs :: sLoadSetupShader :: sRunSetup :: sWaitSetup ::
       sLoadBBox :: sBinTri :: sWaitBinner :: sStageUniforms :: sStoreSetup :: sNextTriangle :: Nil) = states
  val state = RegInit(sIdle)

  val nextAfterDMA = RegInit(sIdle)

  val vertIdx = RegInit(0.U(2.W))
  val triIdx  = RegInit(0.U(5.W))

  val bboxMinX = RegInit(0.U(cfg.coordWidth.W))
  val bboxMinY = RegInit(0.U(cfg.coordWidth.W))
  val bboxMaxX = RegInit(0.U(cfg.coordWidth.W))
  val bboxMaxY = RegInit(0.U(cfg.coordWidth.W))

  val storeWriteIdx = RegInit(0.U(6.W))
  // General-purpose sequential write counter: 0-5 in sWriteSetupInputs (6
  // uniform writes), 0-30 in sStageUniforms (31 uniform writes).
  val writeIdx = RegInit(0.U(5.W))

  // Shadow registers for clip-space outputs (3 vertices x 3 components: x,y,z).
  val clipRegs = RegInit(VecInit.fill(3, 3)(0.U(16.W)))
  // Shadow registers for color + z per vertex, populated by snooping the DMA
  // uniform write stream during vertex DMA. See computeUniformData's doc for
  // the offset layout.
  val colorRegs = RegInit(VecInit.fill(3, 4)(0.U(16.W)))  // [v][r,g,b,z]
  val uvRegs    = RegInit(VecInit.fill(3, 2)(0.U(16.W)))  // [v][u,v]

  // Shadow registers for setup shader outputs: r0-r5 = scaled edge
  // components, r6 = area, r7 = inv_area.
  val setupRegs = RegInit(VecInit.fill(8)(0.U(16.W)))

  // Step 50.2b: per-edge MSAA sample deltas produced by the setup shader in
  // r8..r13 as {d0[0], d1[0], d0[1], d1[1], d0[2], d1[2]}. Consumed by this
  // triangle's own handleStoreSetup DRAM write a few cycles later and never
  // read across a triangle boundary internally -- BorgTileSequencer reads
  // its own independent copy back from DRAM (or its own cache), not from
  // here. Also exposed read-only via io.covDeltaOut; see that port's doc.
  val covDeltaRegs = if (cfg.samples > 1)
    Some(RegInit(VecInit.fill(6)(0.U(16.W)))) else None

  /** Last uniform index written by sWriteSetupInputs: u0-u6 always, plus the
    * three sample-offset constants u7-u9 when MSAA is enabled. */
  private val lastSetupUniform = if (cfg.samples > 1) 9 else 6

  val dmaDescReg = RegInit(0.U.asTypeOf(new DMADescriptor))

  // Uniform page used while staging/storing this pass's uniforms. Always 0 in
  // the current design -- Pass 1 never writes page 1 (only BorgTileSequencer's
  // 2-entry setup cache uses page 1, on its own cache-miss victim selection).
  // Kept as a real register rather than a literal for structural parity with
  // the pre-split code, so synthesis (not this refactor) is what elides it.
  val uniformPage = RegInit(0.U(1.W))

  // Per-triangle has_uvs flag: read from descriptor metadata in sLoadBBox,
  // consumed by sStoreSetup a few cycles later for the SAME triangle. Purely
  // local; the next triangle's sLoadBBox overwrites it before anything else
  // could read a stale value. BorgTileSequencer keeps its own independent
  // copy, restored from DRAM (via this triangle's sStoreSetup write) or its
  // own cache -- the two are not the same register.
  val triHasUvs = RegInit(false.B)

  val core_was_active = RegNext(
    io.coreStatus.running || io.coreStatus.autoRunPending, false.B
  )
  val core_just_finished = core_was_active &&
    !io.coreStatus.running && !io.coreStatus.autoRunPending

  wireOutputDefaults()
  wireFsm()
  wireSnoops()

  private def wireOutputDefaults(): Unit = {
    io.busy := state =/= sIdle
    io.done := false.B
    io.seqShaderActive := state === sRunVert || state === sWaitVert ||
                          state === sRunSetup || state === sWaitSetup
    io.covDeltaOut.zip(covDeltaRegs).foreach { case (out, reg) => out := reg }

    io.dma.start := false.B
    io.dma.desc  := dmaDescReg

    io.coreTrigger.valid  := false.B
    io.coreTrigger.pc     := 0.U
    io.coreTrigger.isRast := false.B

    io.uniformWrite.en   := false.B
    io.uniformWrite.addr := 0.U
    io.uniformWrite.data := 0.U
    io.uniformWritePage  := uniformPage

    io.binner.start       := false.B
    io.binner.triIndex    := triIdx
    io.binner.bbox.min.x  := bboxMinX
    io.binner.bbox.min.y  := bboxMinY
    io.binner.bbox.max.x  := bboxMaxX
    io.binner.bbox.max.y  := bboxMaxY
    io.binner.clearCounts := false.B
    // Not used by this sub-FSM: BorgTileSequencer owns the count-read side of
    // SeqBinnerIO. Tied off rather than split into a bespoke bundle, to keep
    // this restructuring's diff mechanical and reviewable.
    io.binner.countReadAddr := 0.U
    io.binner.countReadEn   := false.B

    io.store.active := state === sStoreSetup
    io.store.req    := false.B
    io.store.addr   := 0.U
    io.store.wdata  := 0.U
  }

  private def wireFsm(): Unit = {
    switch(state) {
      is(sIdle)            { handleIdle() }
      is(sLoadShader)      { handleLoadShader() }
      is(sWaitDMA)         { handleWaitDMA() }
      is(sLoadMVP)         { handleLoadMVP() }
      is(sLoadVert)        { handleLoadVert() }
      is(sRunVert)         { handleRunVert() }
      is(sWaitVert)        { handleWaitVert() }
      is(sWriteSetupInputs){ handleWriteSetupInputs() }
      is(sLoadSetupShader) { handleLoadSetupShader() }
      is(sRunSetup)        { handleRunSetup() }
      is(sWaitSetup)       { handleWaitSetup() }
      is(sLoadBBox)        { handleLoadBBox() }
      is(sBinTri)          { handleBinTri() }
      is(sWaitBinner)      { handleWaitBinner() }
      is(sStageUniforms)   { handleStageUniforms() }
      is(sStoreSetup)      { handleStoreSetup() }
      is(sNextTriangle)    { handleNextTriangle() }
    }
  }

  private def handleIdle(): Unit = {
    when(io.start) {
      triIdx  := 0.U
      vertIdx := 0.U
      uniformPage := 0.U
      io.binner.clearCounts := true.B
      if (BorgDebug.trace) printf("[SEQ] Pass1 start triCount=%d\n", io.mmio.triCount)
      state := sLoadShader
    }
  }

  private def handleLoadShader(): Unit = {
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := io.mmio.vertShaderAddr
    desc.length   := io.mmio.vertShaderLen
    desc.dest     := 0.U  // dest=0 -> IMEM
    desc.offset   := 0.U

    dmaDescReg   := desc
    io.dma.desc  := desc
    io.dma.start := true.B
    nextAfterDMA := sLoadMVP   // load MVP uniforms before first vertex
    state        := sWaitDMA
  }

  private def handleLoadMVP(): Unit = {
    // DMA 16 TS-baked MVP words from descriptor+SEQ_MVP_OFFSET into uniform[8..23].
    // Descriptor stride is 256 bytes (SEQ_DESC_STRIDE). MVP is at offset 96.
    val desc = Wire(new DMADescriptor)
    val triOffset = triIdx * 256.U
    desc.baseAddr := io.mmio.descBase + triOffset + 96.U  // SEQ_MVP_OFFSET
    desc.length   := 16.U  // 16 FP16 words
    desc.dest     := 1.U   // uniformPage is always 0 -> page 0
    desc.offset   := 8.U   // write to u8..u23

    dmaDescReg   := desc
    io.dma.desc  := desc
    io.dma.start := true.B
    nextAfterDMA := sLoadVert
    state        := sWaitDMA
  }

  private def handleWaitDMA(): Unit = {
    when(!io.dma.busy) {
      state := nextAfterDMA
    }
  }

  private def handleLoadVert(): Unit = {
    val desc = Wire(new DMADescriptor)
    val triOffset = triIdx * 256.U  // SEQ_DESC_STRIDE = 256
    desc.baseAddr := io.mmio.descBase + triOffset + vertIdx * 32.U
    desc.length   := 8.U   // 8 x 32-bit words (x,y,z,r,g,b,u,v)
    // DMA dest encoding: 1=page0, 2=page1. Write to current uniformPage so
    // the vertex shader reads from the same page (Step 30.1c fix).
    desc.dest     := Mux(uniformPage === 0.U, 1.U, 2.U)
    desc.offset   := 0.U   // write to u0..u7

    dmaDescReg   := desc
    io.dma.desc  := desc
    io.dma.start := true.B
    nextAfterDMA := sRunVert
    state        := sWaitDMA
  }

  private def handleRunVert(): Unit = {
    io.coreTrigger.valid := true.B
    io.coreTrigger.pc    := 0.U
    state                := sWaitVert
  }

  // FP16 positive -> cfg.coordWidth-bit integer pixel coordinate. Unsigned
  // integer comparison on positive FP16 is monotone (IEEE 754 property).
  // Negative inputs (off-screen left/top) are clamped to 0.
  //
  // The shift amount and overflow threshold below MUST use the fixed FP16
  // mantissa width (10), not cfg.coordWidth: they extract the correct integer
  // value from the FP16 bit pattern, a property of the FP16 format itself,
  // independent of how many bits of that integer we ultimately want to keep.
  private def fp16ToPixelInt(fp: UInt): UInt = {
    val w    = cfg.coordWidth
    val e    = fp(14, 10)       // biased exponent (0..30)
    val m    = fp(9, 0)         // mantissa
    val norm = Cat(1.U(1.W), m) // 11-bit implicit-1 representation
    val raw10 = Mux(e < 15.U, 0.U(10.W),
                Mux(e >= 25.U, 1023.U(10.W),
                  (norm >> (10.U - (e - 15.U)))(9, 0)
                ))
    val maxW = ((1 << w) - 1).U(10.W)
    Mux(raw10 > maxW, ((1 << w) - 1).U(w.W), raw10(w - 1, 0))
  }

  private def handleWaitVert(): Unit = {
    when(core_just_finished) {
      when(vertIdx === 2.U) {
        // All 3 vertices done -- compute bbox from GPU clip-space outputs in
        // clipRegs. FP16 positive values compare correctly as unsigned
        // integers (IEEE 754). Clamp negatives (off-screen) to 0.
        def pos(fp: UInt): UInt = Mux(fp(15), 0.U(16.W), fp)
        val x0 = pos(clipRegs(0)(0)); val x1 = pos(clipRegs(1)(0)); val x2 = pos(clipRegs(2)(0))
        val y0 = pos(clipRegs(0)(1)); val y1 = pos(clipRegs(1)(1)); val y2 = pos(clipRegs(2)(1))
        def fp16Min(a: UInt, b: UInt): UInt = Mux(a <= b, a, b)
        def fp16Max(a: UInt, b: UInt): UInt = Mux(a >= b, a, b)
        val minXpix = fp16ToPixelInt(fp16Min(fp16Min(x0, x1), x2))
        val maxXpix = fp16ToPixelInt(fp16Max(fp16Max(x0, x1), x2))
        val minYpix = fp16ToPixelInt(fp16Min(fp16Min(y0, y1), y2))
        val maxYpix = fp16ToPixelInt(fp16Max(fp16Max(y0, y1), y2))
        bboxMinX := Cat(minXpix(cfg.coordWidth - 1, 2), 0.U(2.W))  // round down to 4-pixel tile boundary
        bboxMaxX := maxXpix
        bboxMinY := Cat(minYpix(cfg.coordWidth - 1, 2), 0.U(2.W))
        bboxMaxY := maxYpix
        if (BorgDebug.trace) printf("[SEQ] gpuBbox (%d,%d)-(%d,%d)\n", minXpix, minYpix, maxXpix, maxYpix)

        writeIdx := 0.U
        state    := sWriteSetupInputs
      }.otherwise {
        vertIdx := vertIdx + 1.U
        state   := sLoadVert
      }
    }
  }

  private def handleWriteSetupInputs(): Unit = {
    io.uniformWrite.en   := true.B
    io.uniformWrite.addr := (if (cfg.maxUniforms > 32) Cat(uniformPage, writeIdx(4, 0)) else writeIdx(4, 0))
    when(writeIdx < 6.U) {
      val v = writeIdx(2, 1)  // writeIdx / 2 -> vertex index (0, 1, 2)
      val c = writeIdx(0)     // writeIdx % 2 -> component (0=x, 1=y)
      io.uniformWrite.data := clipRegs(v)(Cat(0.U(1.W), c))
    }.elsewhen(writeIdx === 6.U) {
      // u6 = inv_width
      io.uniformWrite.data := io.mmio.seqInvWidth
    }.otherwise {
      // Step 50.2b: u7/u8/u9 = the MSAA sample-offset constants the setup
      // shader multiplies the edge coefficients by. Standard Vulkan/D3D 4x
      // offsets, all FP16-exact: -0.375 = 0xB600, -0.125 = 0xB000,
      // +0.375 = 0x3600. Constants rather than MMIO inputs because the
      // sample pattern is fixed by the spec, not a runtime choice.
      io.uniformWrite.data := MuxLookup(writeIdx, 0.U(16.W))(Seq(
        7.U -> "hB600".U(16.W),   // -0.375
        8.U -> "hB000".U(16.W),   // -0.125
        9.U -> "h3600".U(16.W)    // +0.375
      ))
    }
    when(writeIdx === lastSetupUniform.U) {
      state := sLoadSetupShader
    }.otherwise {
      writeIdx := writeIdx + 1.U
    }
  }

  private def handleLoadSetupShader(): Unit = {
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := io.mmio.setupShaderAddr
    desc.length   := io.mmio.setupShaderLen
    desc.dest     := 0.U  // dest=0 -> IMEM
    desc.offset   := 0.U

    dmaDescReg   := desc
    io.dma.desc  := desc
    io.dma.start := true.B
    nextAfterDMA := sRunSetup
    state        := sWaitDMA
  }

  private def handleRunSetup(): Unit = {
    io.coreTrigger.valid := true.B
    io.coreTrigger.pc    := 0.U
    state                := sWaitSetup
  }

  private def handleWaitSetup(): Unit = {
    when(core_just_finished) {
      writeIdx := 0.U
      // Screen y-down: front-facing (CW in screen) -> area < 0 -> r6 = -area/W
      // > 0 (sign 0). Back-facing -> r6 < 0 (sign 1) -> skip.
      when(setupRegs(6)(15)) {
        if (BorgDebug.trace) printf("[SEQ] cull triIdx=%d r6=0x%x\n", triIdx, setupRegs(6))
        state := sNextTriangle
      }.otherwise {
        state := sLoadBBox
      }
    }
  }

  private def handleLoadBBox(): Unit = {
    // Bbox is computed from GPU clipRegs above. Only read the 1-word has_uvs
    // flag from descriptor + SEQ_META_OFFSET + 8 = desc + 168.
    val desc = Wire(new DMADescriptor)
    desc.baseAddr := io.mmio.descBase + (triIdx * 256.U) + 168.U  // SEQ_META_OFFSET+8 = has_uvs
    desc.length   := 1.U  // 1 word: has_uvs flag
    desc.dest     := 2.U  // snoop only
    desc.offset   := 0.U

    dmaDescReg   := desc
    io.dma.desc  := desc
    io.dma.start := true.B
    writeIdx     := 0.U
    nextAfterDMA := sBinTri
    state        := sWaitDMA
  }

  private def handleBinTri(): Unit = {
    io.binner.start := true.B
    if (BorgDebug.trace) printf("[SEQ] sBinTri triIdx=%d bbox=(%d,%d)-(%d,%d) binnerBusy=%d\n",
      triIdx, bboxMinX, bboxMinY, bboxMaxX, bboxMaxY, io.binner.busy)
    state := sWaitBinner
  }

  private def handleWaitBinner(): Unit = {
    when(!io.binner.busy) {
      if (BorgDebug.trace) printf("[SEQ] sWaitBinner done\n")
      state := sStageUniforms
    }
  }

  // Combinational uniform-value lookup -- shared by sStageUniforms (writes
  // the live uniform buffer) and sStoreSetup (writes the same values to DRAM
  // for Pass 2 reload). Recomputed from index 'w' rather than latched into a
  // separate 31-entry register array, since the source shadow registers
  // (setupRegs/clipRegs/uvRegs/colorRegs) are stable across both states.
  //
  // FTEX uniform layout (matching frag.s SPIRB output with uniform_base=12):
  //   u0-u5:   scaled edge components (setupRegs[0..5])
  //   u6-u11:  negated vertex positions FNEG(clipRegs)
  //   u12:     inv_area (setupRegs[7])
  //   u13-u15: U texture coord  (v2, v1, v0) -- pre-scaled by tex_w
  //   u16-u18: V texture coord  (v2, v1, v0) -- pre-scaled by tex_h
  //   u19-u21: frag_pos.x       (v2, v1, v0) -- model position (borgc lighting)
  //   u22-u24: frag_pos.y       (v2, v1, v0)
  //   u25-u27: frag_pos.z       (v2, v1, v0)
  //   u28-u30: z value          (v2, v1, v0) -- projected depth for z-interp
  // Within each group of 3: slot 0->vertex2, slot 1->vertex1, slot 2->vertex0
  private def computeUniformData(w: UInt): UInt = {
    val uData = WireDefault(0.U(16.W))

    def vertOf(base: Int): UInt =
      Mux(w === base.U, 1.U(2.W), Mux(w === (base+1).U, 0.U(2.W), 2.U(2.W)))

    when(w < 6.U) {
      uData := setupRegs(w(2, 0))
    }.elsewhen(w < 12.U) {
      val vIdx = (w - 6.U)(2, 1)
      val cIdx = (w - 6.U)(0)
      val raw  = clipRegs(vIdx)(Cat(0.U(1.W), cIdx))
      uData := raw ^ "h8000".U(16.W)
    }.elsewhen(w === 12.U) {
      uData := setupRegs(7)
    }.elsewhen(w < 16.U) {
      uData := uvRegs(vertOf(13))(0)          // u13-u15: U-coord
    }.elsewhen(w < 19.U) {
      uData := uvRegs(vertOf(16))(1)          // u16-u18: V-coord
    }.elsewhen(w < 22.U) {
      uData := Mux(io.mmio.fragUsesFragPos, clipRegs(vertOf(19))(0), colorRegs(vertOf(19))(0))
    }.elsewhen(w < 25.U) {
      uData := Mux(io.mmio.fragUsesFragPos, clipRegs(vertOf(22))(1), colorRegs(vertOf(22))(1))
    }.elsewhen(w < 28.U) {
      uData := Mux(io.mmio.fragUsesFragPos, clipRegs(vertOf(25))(2), colorRegs(vertOf(25))(2))
    }.otherwise {
      uData := clipRegs(vertOf(28))(2)        // u28-u30: Z from vertex shader r2 (projected depth)
    }
    uData
  }

  private def handleStageUniforms(): Unit = {
    val uData = computeUniformData(writeIdx)

    io.uniformWrite.en   := true.B
    io.uniformWrite.addr := (if (cfg.maxUniforms > 32) Cat(uniformPage, writeIdx(4, 0)) else writeIdx(4, 0))
    io.uniformWrite.data := uData

    when(writeIdx === 0.U || writeIdx === 19.U || writeIdx === 22.U || writeIdx === 25.U) {
      if (BorgDebug.trace) printf("[SEQ] stageU tri=%d u%d=0x%x\n", triIdx, writeIdx, uData)
    }

    when(writeIdx === 30.U) {
      storeWriteIdx := 0.U
      state := sStoreSetup
    }.otherwise {
      writeIdx := writeIdx + 1.U
    }
  }

  // Per-triangle setup-store stride: 128B (32 words) at samples==1, unchanged
  // from the original layout. At samples>1 the store grows to 38 words (adds
  // covDelta), so it needs the wider 256B stride to have room -- see
  // software/borg/borg_layout.h's TBR_SETUP_ENTRY_BYTES (must match).
  private def setupStrideShift = if (cfg.samples > 1) 8 else 7
  private def lastStoreWord    = if (cfg.samples > 1) 37 else 31

  private def handleStoreSetup(): Unit = {
    val dramAddr = io.mmio.setupBase + (triIdx << setupStrideShift) + (storeWriteIdx << 2)
    io.store.req   := true.B
    io.store.addr  := dramAddr
    // Word 31 = has_uvs flag; words 32-37 (samples>1 only) = covDelta.
    val storeData = if (cfg.samples > 1)
      MuxCase(computeUniformData(storeWriteIdx(4, 0)), Seq(
        (storeWriteIdx === 31.U) -> triHasUvs.asUInt,
        (storeWriteIdx >= 32.U)  -> covDeltaRegs.get((storeWriteIdx - 32.U)(2, 0))
      ))
    else
      Mux(storeWriteIdx === 31.U, triHasUvs.asUInt, computeUniformData(storeWriteIdx(4, 0)))
    io.store.wdata := storeData
    when(io.store.ready) {
      when(storeWriteIdx < 2.U || storeWriteIdx === 19.U || storeWriteIdx === 22.U || storeWriteIdx === 25.U || storeWriteIdx === 31.U) {
        if (BorgDebug.trace) printf("[SEQ] storeSetup triIdx=%d [%d] addr=0x%x data=0x%x\n",
          triIdx, storeWriteIdx, dramAddr, storeData)
      }
      when(storeWriteIdx === lastStoreWord.U) {
        state := sNextTriangle
      }.otherwise {
        storeWriteIdx := storeWriteIdx + 1.U
      }
    }
  }

  private def handleNextTriangle(): Unit = {
    val nextIdx = triIdx + 1.U
    when(nextIdx < io.mmio.triCount) {
      triIdx  := nextIdx
      vertIdx := 0.U
      state   := sLoadShader
    }.otherwise {
      // All triangles processed. Pulses `done` combinationally in the same
      // cycle this branch is taken (matches the original design's 1-cycle
      // sNextTriangle -> Pass2 handoff timing exactly), then returns to
      // sIdle to wait for the next frame.
      io.done := true.B
      state   := sIdle
    }
  }

  private def wireSnoops(): Unit = {
    // --- Clip-space output snooping ---
    // Vertex shader writes results to r0(x), r1(y) (passthrough of u0, u1).
    when(io.pipeWrite.en && state === sWaitVert) {
      for (comp <- 0 until 3) {
        when(io.pipeWrite.addr === comp.U) {
          clipRegs(vertIdx)(comp) := io.pipeWrite.data(15, 0)
        }
      }
    }

    // --- Color/z capture from DMA uniform write stream ---
    // During vertex DMA (sWaitDMA with nextAfterDMA=sRunVert), BorgDMA writes
    // 8 words to uniform[0..7]:
    //   uniform[0]=x, [1]=y, [2]=z, [3]=r, [4]=g, [5]=b, [6]=u_tex, [7]=v_tex
    // We snoop the DMA write stream to capture z(index=2), r(3), g(4), b(5).
    when(io.dma.uniformSnoop.en && state === sWaitDMA &&
         nextAfterDMA === sRunVert) {
      val addr = io.dma.uniformSnoop.addr(2, 0)  // low 3 bits = offset 0-7
      val data = io.dma.uniformSnoop.data
      when(addr === 2.U) {
        colorRegs(vertIdx)(3) := data                       // z (projected depth source)
      }
      when(addr === 3.U) {
        colorRegs(vertIdx)(0) := data   // r
        if (BorgDebug.trace) printf("[SEQ] colorSnoop vert=%d R=0x%x\n", vertIdx, data)
      }
      when(addr === 4.U) {
        colorRegs(vertIdx)(1) := data   // g
        if (BorgDebug.trace) printf("[SEQ] colorSnoop vert=%d G=0x%x\n", vertIdx, data)
      }
      when(addr === 5.U) {
        colorRegs(vertIdx)(2) := data   // b
        if (BorgDebug.trace) printf("[SEQ] colorSnoop vert=%d B=0x%x\n", vertIdx, data)
      }
      when(addr === 6.U) { uvRegs(vertIdx)(0) := data }  // u (scaled)
      when(addr === 7.U) { uvRegs(vertIdx)(1) := data }  // v (scaled)
    }

    // --- Setup shader output snooping ---
    // Setup shader writes: r0-r7 = scaled edge components + area + inv_area.
    when(io.pipeWrite.en && state === sWaitSetup) {
      for (i <- 0 until 8) {
        when(io.pipeWrite.addr === i.U) {
          setupRegs(i) := io.pipeWrite.data(15, 0)
        }
      }
      // Step 50.2b: r8..r13 carry the per-edge MSAA sample deltas.
      covDeltaRegs.foreach { cd =>
        for (i <- 0 until 6) {
          when(io.pipeWrite.addr === (8 + i).U) {
            cd(i) := io.pipeWrite.data(15, 0)
            if (BorgDebug.trace) printf("[SEQ] covDelta r%d = 0x%x\n", (8 + i).U, io.pipeWrite.data(15, 0))
          }
        }
      }
    }

    // --- DMA snoop for has_uvs flag ---
    when(io.dma.snoop.valid && state === sWaitDMA && nextAfterDMA === sBinTri) {
      triHasUvs := io.dma.snoop.bits(0)
      if (BorgDebug.trace) printf("[SEQ] hasUvs triIdx=%d flag=%d\n", triIdx, io.dma.snoop.bits(0))
    }
  }
}
