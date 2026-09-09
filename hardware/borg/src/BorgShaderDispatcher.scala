// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgShaderDispatcher — per-pixel shader chaining FSM.
  *
  * Owns the complete lifecycle of a single pixel:
  *   1. Receives `pixelReady` from BorgIterator.
  *   2. Triggers the rasterizer (edge) shader at PC=0.
  *   3. Snoops edge-sign results from pipeline write-back to determine inside/outside.
  *   4. If inside: chains to fragment shader at `fragPcReg`, which fetches
  *      texels inline via FTEX (Step 34.5) when texturing is enabled.
  *   5. Pushes snooped fragment RGBZ to the tile buffer.
  *   6. Releases the CPU stall.
  *
  * Phase FSM (Step 10.6.2 / Step 25.3d):
  *   sIdle → sRast → sFrag → sTileWrite → sIdle
  *   Outside pixels shortcut: sRast → sIdle (skip sFrag/sTileWrite).
  *
  * The `phase` output is exposed for debugability — when tracing in simulation,
  * you can directly observe which FSM state the dispatcher occupies without
  * instrumenting internal registers.
  *
  * @doc:dispatcher
  */
class BorgShaderDispatcherIO(val cfg: BorgConfig) extends Bundle {
  // --- Inputs from BorgIterator ---
  val pixelReady     = Input(Bool())            // one-cycle pulse: new quad ready
  val shaderTileIndex = Input(Vec(cfg.fragLanes, UInt(4.W)))  // per-lane pre-advance tile slots

  // --- Inputs from BorgCore (snooping), per lane ---
  val pipeWrite  = Flipped(Vec(cfg.fragLanes, new PipeWriteIO(cfg.totalBits)))
  val coreStatus = Flipped(new CoreStatusIO)

  // --- Inputs from MMIO registers ---
  val fragPcReg  = Input(UInt(6.W))             // fragment shader start PC

  // Step 50 item 11: depth-test state (DEPTH_CFG register). Vulkan requires
  // all 8 VkCompareOp values selectable and requires depthWriteEnable; the
  // hardware used to hardcode LESS with depth always written on pass. The
  // register's reset values (compare_op=1/LESS, write_en=1) reproduce that
  // exactly, so nothing changes until firmware writes it.
  val depthCompareOp = Input(UInt(3.W))
  val depthWriteEn   = Input(Bool())

  // Step 50 item 9: fixed-function blend state (BLEND_CFG/BLEND_CONST).
  // Present only when cfg.hasBlend, so a build without blending carries no
  // extra ports at all rather than tying them off.
  val blendCfg = if (cfg.hasBlend) Some(Input(new BlendConfig)) else None

  // Step 50 item 10: stencil state and the tile buffer's stencil plane.
  // The read arrives with the colour/Z read the depth test already waits
  // for, so stencil costs no extra FSM states.
  val stencilCfg     = if (cfg.hasStencil) Some(Input(new StencilConfig)) else None
  val stencilRead    = if (cfg.hasStencil) Some(Input(Vec(cfg.samples, UInt(8.W)))) else None
  val stencilWrite   = if (cfg.hasStencil) Some(Output(UInt(8.W))) else None
  val stencilWriteEn = if (cfg.hasStencil) Some(Output(Bool())) else None

  // Step 50: per-lane scissor result, computed in BorgRasterizer where the
  // screen coordinates live. Unconditional rather than config-gated: the
  // scissor test is core Vulkan state with no feature bit, and it costs one
  // AND per lane here (the comparators themselves are one shared rectangle
  // test in the rasterizer, not per lane).
  val scissorPass = Input(Vec(cfg.fragLanes, Bool()))

  // Step 50 item 9, second half: the tile buffer's destination-alpha plane.
  // Present with hasBlend, since destination alpha exists only to feed the
  // DST_ALPHA blend factors and the alpha channel's own blend equation.
  val alphaRead      = if (cfg.hasBlend) Some(Input(Vec(cfg.samples, UInt(8.W)))) else None
  val alphaWrite     = if (cfg.hasBlend) Some(Output(UInt(8.W))) else None
  val alphaWriteMask = if (cfg.hasBlend) Some(Output(Bool())) else None

  // --- Inputs from texture pipeline ---
  val texConfig  = new TexConfigIO              // mortonIndex, baseAddr, en
  val log2Dim    = Input(UInt(4.W))             // tex_config_log2_dim, see ClampTexCoord

  // --- Outputs to BorgCore ---
  val coreTrigger = new CoreTriggerIO           // shader start pulse + PC

  // --- Outputs to BorgTileBuffer ---
  val tileWrite  = new TileWriteIO(cfg.samples)         // tile buffer push
  val tileRead   = new TileReadIO(16, cfg.samples)      // Step 25.5C: depth test read port

  // --- Outputs to MemoryController (DRAM) ---
  val gpuMem     = new GpuMemIO                 // texel read port

  // --- Outputs (status / debug) ---
  val autoRunStall = Output(Bool())             // stalls CPU between advance and completion
  val insideFlag   = Output(Bool())             // true when all 3 edges are non-negative
  val phase        = Output(UInt(3.W))          // current FSM state (debug observable)

  // Step 34.5: FTEX inline texture fetch — core ↔ dispatcher ↔ texture unit
  val texReq  = Input(Bool())         // core requests texture fetch (FTEX instruction)
  val texU    = Input(UInt(16.W))     // U coordinate from core rs1
  val texV    = Input(UInt(16.W))     // V coordinate from core rs2
  val texDone = Output(Bool())        // texture unit completion pulse (to core)
  val texR    = Output(UInt(16.W))    // fetched texel R (to core)
  val texG    = Output(UInt(16.W))    // fetched texel G (to core)
  val texB    = Output(UInt(16.W))    // fetched texel B (to core)

  // MSAA coverage deltas (Step 50.2), per triangle, from the setup shader via
  // BorgSequencer.  Indexed [edge][k]: two base deltas per edge.  Absent at
  // cfg.samples == 1 so the single-sample build has no unused port to lint.
  //
  // Standard Vulkan/D3D 4× sample positions are ±symmetric about the pixel
  // centre — offsets (-.125,-.375), (.375,-.125), (-.375,.125), (.125,.375),
  // so s3 == -s0 and s2 == -s1 — and every offset is FP16-exact.  Since the
  // edge function is linear, sample s's edge value is e + δ_s where
  // δ_s = dx·δy_s + ndy·δx_s is a per-triangle constant, so only δ_s0 and
  // δ_s1 need computing; δ_s2 = -δ_s1 and δ_s3 = -δ_s0 are sign flips.
  val covDelta = if (cfg.samples > 1)
    Some(Input(Vec(3, Vec(2, UInt(cfg.totalBits.W))))) else None
}

class BorgShaderDispatcher(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgShaderDispatcherIO(cfg))

  // Blending reads the destination colour, which is per-sample, but writes
  // through TileWriteIO's single shared `data`. Blending sample 0's
  // destination and broadcasting the result to every covered sample would be
  // silently wrong on any partially-covered edge pixel, so the combination is
  // a build error rather than an approximation.
  require(!cfg.hasBlend || cfg.samples == 1,
          s"hasBlend requires samples==1 (got ${cfg.samples}): TileWriteIO broadcasts one " +
          "blended colour to all covered samples, which is wrong for per-sample destinations")

  // Same shared-write-port limitation: each sample's stencil update depends
  // on its own stored value, so one shared stencil write cannot express it.
  require(!cfg.hasStencil || cfg.samples == 1,
          s"hasStencil requires samples==1 (got ${cfg.samples}): each sample's stencil " +
          "update depends on its own stored value, which one shared write port cannot express")

  private val config = cfg.fp  // shorthand for FP arithmetic

  // --- Phase FSM ---
  // Step 25.5C: sZRead/sZWait1/sZWait2 added for depth test read-before-write.
  // sTexFetch (the legacy autonomous single-texel fetch state) removed:
  // texturing is exclusively FTEX-inline (Step 34.5) now, driven mid-sFrag by
  // the core's own FTEX instruction rather than a dispatcher-owned FSM state.
  val sIdle :: sRast :: sFrag :: sZRead :: sZWait1 :: sZWait2 :: sTileWrite :: Nil = Enum(7)
  val phase = RegInit(sIdle)

  // --- Texture unit (Step 25.3e) ---
  val texUnit = Module(new BorgTextureUnit)

  private val N = cfg.fragLanes

  // --- Per-lane edge-sign state (one 2×2 quad lane each) ---
  val e0_outside = RegInit(VecInit(Seq.fill(N)(false.B)))
  val e1_outside = RegInit(VecInit(Seq.fill(N)(false.B)))
  val e2_outside = RegInit(VecInit(Seq.fill(N)(false.B)))

  // --- MSAA per-sample coverage (Step 50.2) ---------------------------------
  // At samples == 1 coverage is the historical pixel-centre test: all three
  // edge signs non-negative.  At samples > 1 the centre is not itself a sample,
  // so coverage is evaluated per sample and `inside_flag` becomes "any sample
  // covered" — which is what decides whether the fragment gets shaded at all
  // (shade once per pixel, broadcast to covered samples).
  //
  // The per-sample test is `e + δ_s >= 0`, rewritten as `e >= -δ_s`.  Negating
  // an FP16 is exact (sign-bit flip), so this is an EXACT comparison of two
  // FP16 values — no rounding, and no FP16 adders.  Verified exhaustively:
  // `ordered` below is monotonic across all 63,488 finite FP16 bit patterns,
  // and over 400k random (e, δ) pairs the compare path matched exact float64
  // ground truth on every case (the add-then-test-sign path is the one that
  // rounds first).
  //
  // Known half-ULP corner: ordered(-0) < ordered(+0) whereas IEEE says they are
  // equal, so a sample landing exactly on an edge with a +0 threshold and a -0
  // edge value reads as outside.  That is one boundary sample at exactly zero;
  // the single-sample path has its own -0 convention (isOutside treats -0 as
  // inside via the magnitude test) and is unaffected.

  /** Bijective sign-magnitude → unsigned mapping that preserves FP16 order, so
    * a float compare becomes an unsigned integer compare. */
  def ordered(x: UInt): UInt = {
    val msb = 1.U << (cfg.totalBits - 1)
    Mux(x(cfg.totalBits - 1), ~x, x | msb)
  }
  def fnegBits(x: UInt): UInt = x ^ (1.U << (cfg.totalBits - 1))

  // Raw edge values per lane, needed only for the per-sample compare.
  val e_val = if (cfg.samples > 1)
    Some(RegInit(VecInit(Seq.fill(N)(VecInit(Seq.fill(3)(0.U(cfg.totalBits.W)))))))
  else None

  /** Per-lane, per-sample coverage. */
  val coverage: Vec[Vec[Bool]] = cfg.samples match {
    case 1 =>
      VecInit((0 until N).map(i =>
        VecInit(Seq(!e0_outside(i) && !e1_outside(i) && !e2_outside(i)))))
    case _ =>
      // thresholds per edge: {-d0, -d1, +d1, +d0}  (see covDelta's comment)
      val thresh = VecInit((0 until 3).map { e =>
        val d0 = io.covDelta.get(e)(0)
        val d1 = io.covDelta.get(e)(1)
        VecInit(Seq(fnegBits(d0), fnegBits(d1), d1, d0).map(ordered))
      })
      VecInit((0 until N).map { i =>
        VecInit((0 until cfg.samples).map { s =>
          (0 until 3).map(e => ordered(e_val.get(i)(e)) >= thresh(e)(s)).reduce(_ && _)
        })
      })
  }

  val inside_flag = VecInit((0 until N).map(i => coverage(i).reduce(_ || _)))
  val any_inside  = inside_flag.reduce(_ || _)

  // --- Stall ---
  val auto_run_stall = RegInit(false.B)

  // --- Per-lane fragment output snoop ---
  // Hardware ABI: A=r24, Kill=r25, R=r26, G=r27, B=r28, Z=r29.
  //
  // r24 (alpha) extends the ABI block downwards and exists only in a
  // cfg.hasBlend build -- an alpha output is meaningless without a blend
  // stage to consume it, and borgc's fragment allocator must reserve r24
  // in lockstep or it will hand the register to an unrelated temporary
  // (exactly the collision class that killed the r21-r24 read-back port).
  //
  // The same applies to the hand-written shaders in software/borg: any of
  // them that uses r24 as a scratch register would silently feed garbage
  // alpha to the blend unit. Harmless today only because blending is off by
  // default, so frag_a is never read -- not because the registers are
  // actually free. Check before enabling blending with a hand shader.
  val frag_r = RegInit(VecInit(Seq.fill(N)(0.U(16.W))))
  val frag_g = RegInit(VecInit(Seq.fill(N)(0.U(16.W))))
  val frag_b = RegInit(VecInit(Seq.fill(N)(0.U(16.W))))
  val frag_z = RegInit(VecInit(Seq.fill(N)(0.U(16.W))))
  // FP16 1.0: a shader that writes no alpha is opaque, which is what makes
  // adding the register backwards-compatible for existing shaders.
  val frag_a =
    if (cfg.hasBlend) Some(RegInit(VecInit(Seq.fill(N)(0x3C00.U(16.W))))) else None

  // discard: r25 is a hardware-ABI "kill" register, not a new ISA opcode. The
  // compiler lowers GLSL/SPIR-V `discard`/`discard_if(cond)` (already reduced
  // to a plain boolean value by NIR's own nir_lower_discard_if pass, no branch
  // needed) to any existing op that writes `cond` to r25. Sticky OR across the
  // whole fragment shader invocation, since a discarded fragment must stay
  // discarded even if later code in the same invocation writes r25 again with
  // a false condition — matches GLSL's "discard doesn't necessarily terminate
  // execution, but the fragment is never written" semantics. Reset once per
  // quad below (same lifecycle as e0/e1/e2_outside), gates tile write-back in
  // sTileWrite exactly like inside_flag already does.
  val killed = RegInit(VecInit(Seq.fill(N)(false.B)))

  // Lane counter for the serialized Z-read / tile-write loop (single-port tile buffer).
  // Ranges over [0, N-1] only (wraps at N-1, never reaches N) — log2Ceil(N) bits,
  // not N+1: the extra bit made this a 3-bit index into the 4-entry (2-bit) frag_*/
  // inside_flag Vecs, which triggered pathological blowup in Yosys/ABC9 synthesis
  // for the full ULX3S SoC (a 3-bit dynamic index into a 4-element Vec forces
  // hardware for 8 selector values instead of 4).
  // log2Up (not log2Ceil): at N=1 a single lane needs zero index bits, but
  // Chisel has no 0-width literal syntax, so `:= 0.U`/`+ 1.U` against a
  // genuinely 0-bit register trips the implicit-truncation warning; log2Up
  // floors at 1 bit instead.
  val laneCtr = RegInit(0.U(log2Up(N).W))
  // Dynamic Vec indices need a genuinely 0-width UInt at N=1 (Chisel's own
  // log2Ceil docs: "log2Ceil(1) // returns 0") to avoid a W004 "dynamic
  // index too wide" warning — but laneCtr itself must stay log2Up-width
  // (>=1 bit, see comment above) so its own `:= 0.U`/`+ 1.U` assignments
  // don't trip a *different* (truncation) warning against a literal 0-bit
  // register. laneIdx is laneCtr's value at the width Vec indexing actually
  // needs; use it (not laneCtr) at every `someVec(laneCtr)` call site below.
  private val laneIdx: UInt = if (N == 1) 0.U(0.W) else laneCtr

  // --- Trigger outputs (directly driven, no register delay) ---
  io.coreTrigger.valid  := false.B
  io.coreTrigger.pc     := 0.U
  io.coreTrigger.isRast := false.B

  // Tile buffer write default (no write)
  io.tileWrite.en       := false.B
  io.tileWrite.idx      := io.shaderTileIndex(0)
  io.tileWrite.data     := 0.U.asTypeOf(new ColorZ(16))
  io.tileWrite.coverage := 0.U
  io.stencilWrite.foreach(_ := 0.U)
  io.stencilWriteEn.foreach(_ := false.B)
  io.alphaWrite.foreach(_ := 0.U)
  // Defaults to masked-off outside sTileWrite, so the plane can never be
  // written by a stray write.en pulse from elsewhere in the FSM.
  io.alphaWriteMask.foreach(_ := false.B)

  // GPU memory port: forwarded from BorgTextureUnit (Step 25.3e)
  texUnit.io.texConfig <> io.texConfig
  texUnit.io.gpuMem    <> io.gpuMem
  texUnit.io.start     := false.B  // overridden by the FTEX start pulse below

  // Step 34.5: FTEX inline texture fetch — core drives texture unit directly
  //
  // When the core executes an FTEX instruction, it asserts texReq with U/V.
  // The dispatcher computes Morton coordinates inline and starts the texture
  // unit, forwarding results back to the core on completion. This is the
  // only texture-fetch path — the legacy autonomous single-texel fetch (a
  // dedicated FSM state that fired unconditionally once per fragment
  // whenever texturing was enabled, whether or not the shader asked for it)
  // was removed once no shader depended on it.
  //
  // Single-shader textured/non-textured support:
  // When tex_config.en=false, FTEX immediately returns (1.0, 1.0, 1.0).
  // This means: texel(1,1,1) × vertexColor = vertexColor — pure interpolated
  // color, no texture. The shader binary is identical for both paths.
  val FP16_ONE_U = 0x3C00.U(16.W)

  val ftexActive = RegInit(false.B)  // FTEX fetch in progress (tex enabled)
  val ftexMortonIndex = Wire(UInt(16.W))
  // Clamped to the last valid row/column -- see ClampTexCoord's comment. A
  // UV of exactly 1.0 at a triangle's far edge/vertex legitimately
  // interpolates to the texture's width in texel space (e.g. 64.0 for a
  // 64-wide texture) rather than 63.999..., which floors to one past the
  // last valid index; left unclamped that reads unpopulated texture memory
  // and returns black for an otherwise-correctly-covered pixel.
  val ftex_u8 = ClampTexCoord(Fp16ToUint8(io.texU), io.log2Dim)
  val ftex_v8 = ClampTexCoord(Fp16ToUint8(io.texV), io.log2Dim)
  ftexMortonIndex := MortonEncode(ftex_u8, ftex_v8)

  // Default FTEX response
  io.texDone := false.B
  io.texR    := 0.U
  io.texG    := 0.U
  io.texB    := 0.U

  // FTEX start: when texture is enabled, start the texture unit
  when(io.texReq && phase === sFrag) {
    when(io.texConfig.en) {
      // Texture enabled: start texture unit fetch
      texUnit.io.start := true.B
      texUnit.io.texConfig.mortonIndex := ftexMortonIndex
      ftexActive := true.B
    }.otherwise {
      // Texture disabled: immediately return white (1.0, 1.0, 1.0)
      // texel(1,1,1) × vertexColor = vertexColor (non-textured pass-through)
      io.texDone := true.B
      io.texR    := FP16_ONE_U
      io.texG    := FP16_ONE_U
      io.texB    := FP16_ONE_U
    }
  }

  // FTEX completion: forward texUnit results to core (texture-enabled path)
  when(ftexActive && texUnit.io.done) {
    io.texDone := true.B
    io.texR    := texUnit.io.fragColor.r
    io.texG    := texUnit.io.fragColor.g
    io.texB    := texUnit.io.fragColor.b
    // ftexActive gates this block to genuine FTEX completions -- texUnit.io.start
    // is only ever pulsed from the FTEX branch above, so texUnit.io.done can
    // only fire in response to one.
  }


  // Step 25.5C: tile read port defaults (no read)
  io.tileRead.en  := false.B
  io.tileRead.idx := io.shaderTileIndex(0)

  // --- React to pixelReady from BorgIterator (one quad) ---
  when(io.pixelReady) {
    for (i <- 0 until N) {
      e0_outside(i) := false.B
      e1_outside(i) := false.B
      e2_outside(i) := false.B
      killed(i) := false.B
      // Re-arm the opaque default every quad: a shader that writes r24 on one
      // quad and not the next must not inherit the previous quad's alpha.
      frag_a.foreach(_(i) := 0x3C00.U)
    }
    laneCtr := 0.U
    auto_run_stall := true.B
    phase := sRast
    io.coreTrigger.valid  := true.B
    io.coreTrigger.pc     := 0.U
    io.coreTrigger.isRast := true.B
    if (BorgDebug.trace) printf("[DISP] pixelReady tileIdx0=%d\n", io.shaderTileIndex(0))
  }

  // --- Shader chaining FSM ---
  val core_was_active = RegNext(io.coreStatus.running || io.coreStatus.autoRunPending, false.B)
  val core_just_finished = core_was_active && !io.coreStatus.running && !io.coreStatus.autoRunPending

  when(phase === sRast && core_just_finished) {
    // Run the fragment shader if ANY lane is inside (outside lanes run as helper
    // invocations and are masked off at tile-write time — required SIMT semantics).
    when(any_inside && io.fragPcReg =/= 0.U) {
      phase := sFrag
      io.coreTrigger.valid := true.B
      io.coreTrigger.pc    := io.fragPcReg
      if (BorgDebug.trace) printf("[DISP] -> sFrag pc=%d any_inside=%d\n", io.fragPcReg, any_inside)
    }.otherwise {
      phase := sIdle
      auto_run_stall := false.B
      if (BorgDebug.trace) printf("[DISP] -> sIdle (no lane inside or no frag)\n")
    }
  }

  when(phase === sFrag && core_just_finished) {
    laneCtr := 0.U
    phase   := sZRead
    // Clear ftexActive for next quad — FTEX was a one-shot for this frag invocation.
    ftexActive := false.B
  }

  // Step 25.5C: Depth test — read-before-write on tile SRAM
  // =========================================================================
  //
  // BorgTileBuffer uses SyncReadMem + readDataHeld register:
  //   Cycle 0 (sZRead):  assert read.en → SyncReadMem latches address
  //   Cycle 1 (sZWait1): SyncReadMem output valid; readEnDel fires
  //   Cycle 2 (sZWait2): readDataHeld captures → io.tileRead.data valid
  //   Cycle 3 (sTileWrite): compare frag_z vs io.tileRead.data.z
  //   (readDataHeld is held stable until next read.en pulse — no latch needed)

  // Serialized over laneCtr: each lane reads its tile slot's Z, then conditionally
  // writes.  The single-port tile buffer forces one lane per 4-cycle pass; at
  // fragLanes=1 this is exactly the original single sZRead→…→sTileWrite sequence.
  when(phase === sZRead) {
    io.tileRead.en  := true.B
    io.tileRead.idx := io.shaderTileIndex(laneIdx)
    phase := sZWait1
  }

  when(phase === sZWait1) {
    phase := sZWait2
  }

  when(phase === sZWait2) {
    phase := sTileWrite
  }

  when(phase === sTileWrite) {
    io.tileWrite.idx := io.shaderTileIndex(laneIdx)

    // Step 50 item 9: fixed-function blending.
    //
    // The destination colour is already in hand -- io.tileRead.data was
    // fetched three cycles ago for the depth test -- so blending needs no
    // extra tile-buffer traffic, only the equation.
    //
    // Quantize both operands to UNORM8, blend, and expand back to the FP16
    // the tile-write port speaks. That round trip is why the whole thing sits
    // behind `enable`: with blending off the fragment's original FP16 bits go
    // through untouched, so an enabled build still renders a non-blended
    // frame bit-identically to a build compiled without hasBlend at all.
    //
    // Destination alpha comes from the tile buffer's own alpha plane, read on
    // the same cycle as the colour/Z the depth test already fetched. Before
    // that plane existed this was hardwired to 1.0, which is correct only for
    // an opaque destination -- every DST_ALPHA-family factor gave the wrong
    // answer when compositing into a translucent buffer, with nothing to
    // indicate it.
    //
    // The plane is on-chip and tile-local, which is the full correctness
    // scope for Borg's render model: a tile is cleared, all triangles binned
    // to it blend against each other, then it is flushed. Alpha is not
    // written to DRAM -- exposing an alpha-carrying *attachment format* an
    // application can read back would additionally need the flusher to carry
    // it, the same shape as the item-14 depth burst.
    val (blendR, blendG, blendB) = if (cfg.hasBlend) {
      val cfgIn = io.blendCfg.get
      val src = Wire(new Rgba8)
      src.r := ColorQuantize.quantize8(frag_r(laneIdx))
      src.g := ColorQuantize.quantize8(frag_g(laneIdx))
      src.b := ColorQuantize.quantize8(frag_b(laneIdx))
      src.a := ColorQuantize.quantize8(frag_a.get(laneIdx))
      val dst = Wire(new Rgba8)
      dst.r := ColorQuantize.quantize8(io.tileRead.data(0).r)
      dst.g := ColorQuantize.quantize8(io.tileRead.data(0).g)
      dst.b := ColorQuantize.quantize8(io.tileRead.data(0).b)
      dst.a := io.alphaRead.get(0)
      val out = BorgBlend.blend(cfgIn, src, dst)
      // The alpha channel's own blend result, stored back to the plane. With
      // blending off the fragment's source alpha passes through, matching how
      // the colour channels behave.
      io.alphaWrite.get     := Mux(cfgIn.enable, out.a, src.a)
      io.alphaWriteMask.get := cfgIn.colorWriteMask(3)
      val blended = Seq(
        Mux(cfgIn.enable, ColorQuantize.dequantize8(out.r), frag_r(laneIdx)),
        Mux(cfgIn.enable, ColorQuantize.dequantize8(out.g), frag_g(laneIdx)),
        Mux(cfgIn.enable, ColorQuantize.dequantize8(out.b), frag_b(laneIdx)))
      // colorWriteMask: a masked-off channel keeps the destination value.
      // Applied outside the `enable` mux on purpose -- Vulkan's write mask is
      // independent of blendEnable, and the channel-isolating passes it
      // exists for typically run with blending off.
      //
      // Only the R/G/B bits are consumed here; the A bit gates the alpha
      // plane's write instead (io.alphaWriteMask above), so the colour and
      // alpha stores are maskable independently, as Vulkan requires.
      val dstRgb = Seq(io.tileRead.data(0).r, io.tileRead.data(0).g, io.tileRead.data(0).b)
      val masked = blended.zip(dstRgb).zipWithIndex.map { case ((b, d), i) =>
        Mux(cfgIn.colorWriteMask(i), b, d)
      }
      (masked(0), masked(1), masked(2))
    } else (frag_r(laneIdx), frag_g(laneIdx), frag_b(laneIdx))

    io.tileWrite.data.r := blendR
    io.tileWrite.data.g := blendG
    io.tileWrite.data.b := blendB
    // depthWriteEnable: on a passing fragment, write the new Z (historical
    // behaviour, write_en=1) or preserve the stored one (write_en=0, which
    // Vulkan requires for depth-read-only passes -- colour still updates).
    //
    // samples==1 only. At samples>1 each sample has its OWN stored Z but
    // TileWriteIO carries a single shared `data` for every covered sample
    // (see its doc comment -- shade once, broadcast), so preserving
    // per-sample depth would need a per-sample Z write mask on that port.
    // Rather than silently write sample 0's old Z to every sample, MSAA
    // keeps the historical always-write behaviour; making write_en correct
    // there is real port work, noted here and in DEPTH_CFG's own RDL desc.
    io.tileWrite.data.z := (if (cfg.samples == 1)
                              Mux(io.depthWriteEn, frag_z(laneIdx), io.tileRead.data(0).z)
                            else frag_z(laneIdx))
    // Depth test, per sample: a sample takes the fragment only if the lane is
    // inside (coverage), not discarded, AND this sample's own stored Z is
    // farther.  FP16 Z is non-negative in NDC; unsigned < comparison is valid.
    //
    // At cfg.samples==1 this is exactly the historical single `zPass`: one
    // coverage bit, `en` equal to it — the bit-identical regression anchor.
    // Per sample: covered by the triangle, not discarded, and passing that
    // sample's own depth test.  At samples == 1 `coverage(lane)(0)` is exactly
    // the historical inside_flag.
    // VkCompareOp depth test. FP16 Z is non-negative in NDC and positive
    // IEEE floats order identically as unsigned integers, so every ordering
    // op below is a plain unsigned compare on the raw bits -- the same
    // property the historical hardcoded `<` already relied on.
    def depthPasses(newZ: UInt, oldZ: UInt): Bool =
      CompareOp(io.depthCompareOp, newZ, oldZ)

    // Stencil (Step 50 item 10), folded into the same cycle.
    //
    // The order matters and is not symmetric: the stencil test runs BEFORE
    // the depth test but the stencil buffer is written AFTER the depth
    // result is known (the op chosen depends on it), so both are evaluated
    // here rather than split across states. The stencil buffer is written on
    // all three outcomes, including the two that kill the fragment -- hence
    // stencilWriteEn is its own signal, not io.tileWrite.en.
    //
    // frontFacing is hardwired true, so only the front face's state is
    // reachable. Cull mode is now configurable (CULL_CFG), so a build can
    // finally ask for VK_CULL_MODE_NONE and produce back-facing fragments --
    // but the facing bit itself still does not reach here. Pass 1 stores its
    // per-triangle setup state to DRAM and Pass 2 reloads it per tile, so
    // facing has to travel with it the way has_uvs does; that store/reload
    // change is the remaining piece for two-sided stencil. The back-face
    // registers and datapath are already carried through, so it lands as
    // wiring rather than a redesign.
    val stencilRes = if (cfg.hasStencil) {
      Some(BorgStencil.evaluate(io.stencilCfg.get, true.B, io.stencilRead.get(0),
                                depthPasses(frag_z(laneIdx), io.tileRead.data(0).z)))
    } else None

    val samplePass = (0 until cfg.samples).map { s =>
      val depthOk = stencilRes match {
        // evaluate() already folds the depth result in, and additionally
        // requires the stencil test to pass.
        case Some(r) if s == 0 => r.pass
        case _                 => depthPasses(frag_z(laneIdx), io.tileRead.data(s).z)
      }
      coverage(laneIdx)(s) && !killed(laneIdx) && io.scissorPass(laneIdx) && depthOk
    }
    io.tileWrite.coverage := Cat(samplePass.reverse)
    io.tileWrite.en       := samplePass.reduce(_ || _)

    stencilRes.foreach { r =>
      io.stencilWrite.get := r.newValue
      // Reached the stencil test at all: covered and not discarded. A
      // `discard`ed fragment performs no per-fragment operations, so it must
      // not advance the stencil buffer either.
      io.stencilWriteEn.get := coverage(laneIdx)(0) && !killed(laneIdx) && io.scissorPass(laneIdx)
    }
    if (BorgDebug.trace) printf("[DISP] tileWrite lane=%d idx=%d Z=0x%x zOld_s0=0x%x cov=0x%x\n",
      laneCtr, io.shaderTileIndex(laneIdx), frag_z(laneIdx), io.tileRead.data(0).z,
      Cat(samplePass.reverse))

    when(laneCtr === (N - 1).U) {
      laneCtr := 0.U
      phase := sIdle
      auto_run_stall := false.B
    }.otherwise {
      laneCtr := laneCtr + 1.U
      phase := sZRead   // next lane
    }
  }





  // =========================================================================
  // Fragment Output & Edge-sign snooping
  // =========================================================================
  //
  // The rasterizer shader (rasterize.s) evaluates edge functions for each
  // pixel and writes the results to registers r0 (e0), r1 (e1), r2 (e2).
  //
  //   POSITIVE edge value  →  pixel is INSIDE this edge  (not outside)
  //   NEGATIVE edge value  →  pixel is OUTSIDE this edge
  //   ZERO                 →  pixel is exactly ON the edge (counts as inside)
  //
  // This convention is verified by test_raster.c which asserts:
  //   assert(e_float > 0.0f)  // interior points are strictly positive
  //
  // A pixel is inside the triangle when ALL THREE edges are non-negative,
  // i.e., none of them are "outside" (negative and non-zero).
  //
  // In IEEE 754 / FP16:
  //   sign_bit = 1  →  value is negative
  //   sign_bit = 0  →  value is positive or zero
  //
  // Therefore:  is_outside = sign_bit AND magnitude_nonzero
  //             (negative zero has sign_bit=1 but magnitude=0 → not outside)
  //
  // ⚠️  DO NOT INVERT THIS LOGIC.  Getting it backwards produces a black
  //     screen because every pixel appears "outside" the triangle.
  //     This has caused regressions multiple times.  If in doubt, run
  //     `make triangle` in simulation/verilator and check the output image.
  //
  // @doc:inside-snoop
  def isOutside(data: UInt): Bool = {
    val sign_bit      = data(config.totalBits - 1).asBool
    val magn_non_zero = data(config.totalBits - 2, 0) =/= 0.U
    sign_bit && magn_non_zero
  }

  // Per-lane edge snoop: each lane runs the rast shader in lockstep and writes
  // its own r0/r1/r2 via its own pipeWrite port.
  for (i <- 0 until N) {
    when(io.pipeWrite(i).en && phase === sRast) {
      when(io.pipeWrite(i).addr === 0.U) { e0_outside(i) := isOutside(io.pipeWrite(i).data) }
      when(io.pipeWrite(i).addr === 1.U) { e1_outside(i) := isOutside(io.pipeWrite(i).data) }
      when(io.pipeWrite(i).addr === 2.U) { e2_outside(i) := isOutside(io.pipeWrite(i).data) }
      // MSAA also needs the raw edge magnitudes, not just their signs, to test
      // each sample's offset position.  Same write, same cycle, same registers'
      // lifecycle — just kept at full width.
      e_val.foreach { ev =>
        for (e <- 0 until 3) {
          when(io.pipeWrite(i).addr === e.U) { ev(i)(e) := io.pipeWrite(i).data(cfg.totalBits - 1, 0) }
        }
      }
    }
  }
  // @doc:end

  // Per-lane fragment output snoop (Hardware ABI: Kill=r25, R=r26, G=r27, B=r28, Z=r29).
  // R/G/B/Z feed the FP16-native tile buffer (frag_r/g/b/z below are 16-bit
  // registers, unchanged regardless of cfg.fp -- tile-buffer color/Z storage
  // is a deliberate FP16-native boundary, see the branch's plan doc). At
  // FP32, io.pipeWrite(i).data is a genuine 32-bit fragment-shader ALU
  // result -- narrow() rounds it to the nearest FP16 value; a raw (15,0)
  // slice (the pre-fix code) kept the wrong bits entirely, same class of
  // bug as clipRegs/setupRegs before commit 505bc139.
  def fragNarrow(d: UInt): UInt = if (config.totalBits > 16) Fp16Fp32.narrow(d) else d(15, 0)
  for (i <- 0 until N) {
    when(io.pipeWrite(i).en && phase === sFrag) {
      frag_a.foreach { a =>
        when(io.pipeWrite(i).addr === 24.U) { a(i) := fragNarrow(io.pipeWrite(i).data) }
      }
      when(io.pipeWrite(i).addr === 25.U) { killed(i) := killed(i) || (io.pipeWrite(i).data =/= 0.U) }
      when(io.pipeWrite(i).addr === 26.U) { frag_r(i) := fragNarrow(io.pipeWrite(i).data) }
      when(io.pipeWrite(i).addr === 27.U) { frag_g(i) := fragNarrow(io.pipeWrite(i).data) }
      when(io.pipeWrite(i).addr === 28.U) { frag_b(i) := fragNarrow(io.pipeWrite(i).data) }
      when(io.pipeWrite(i).addr === 29.U) { frag_z(i) := fragNarrow(io.pipeWrite(i).data) }
    }
  }

  // --- Outputs ---
  io.autoRunStall := auto_run_stall
  io.insideFlag   := any_inside
  io.phase        := phase    // debug: FSM state visible from parent
}
