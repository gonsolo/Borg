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

  // --- Per-lane fragment output snoop (Hardware ABI: Kill=r25, R=r26, G=r27, B=r28, Z=r29) ---
  val frag_r = RegInit(VecInit(Seq.fill(N)(0.U(16.W))))
  val frag_g = RegInit(VecInit(Seq.fill(N)(0.U(16.W))))
  val frag_b = RegInit(VecInit(Seq.fill(N)(0.U(16.W))))
  val frag_z = RegInit(VecInit(Seq.fill(N)(0.U(16.W))))

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
    io.tileWrite.data.r := frag_r(laneIdx)
    io.tileWrite.data.g := frag_g(laneIdx)
    io.tileWrite.data.b := frag_b(laneIdx)
    io.tileWrite.data.z := frag_z(laneIdx)
    // Depth test, per sample: a sample takes the fragment only if the lane is
    // inside (coverage), not discarded, AND this sample's own stored Z is
    // farther.  FP16 Z is non-negative in NDC; unsigned < comparison is valid.
    //
    // At cfg.samples==1 this is exactly the historical single `zPass`: one
    // coverage bit, `en` equal to it — the bit-identical regression anchor.
    // Per sample: covered by the triangle, not discarded, and passing that
    // sample's own depth test.  At samples == 1 `coverage(lane)(0)` is exactly
    // the historical inside_flag.
    val samplePass = (0 until cfg.samples).map { s =>
      coverage(laneIdx)(s) && !killed(laneIdx) && (frag_z(laneIdx) < io.tileRead.data(s).z)
    }
    io.tileWrite.coverage := Cat(samplePass.reverse)
    io.tileWrite.en       := samplePass.reduce(_ || _)
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

  // Per-lane fragment output snoop (Hardware ABI: Kill=r25, R=r26, G=r27, B=r28, Z=r29)
  for (i <- 0 until N) {
    when(io.pipeWrite(i).en && phase === sFrag) {
      when(io.pipeWrite(i).addr === 25.U) { killed(i) := killed(i) || (io.pipeWrite(i).data =/= 0.U) }
      when(io.pipeWrite(i).addr === 26.U) { frag_r(i) := io.pipeWrite(i).data(15, 0) }
      when(io.pipeWrite(i).addr === 27.U) { frag_g(i) := io.pipeWrite(i).data(15, 0) }
      when(io.pipeWrite(i).addr === 28.U) { frag_b(i) := io.pipeWrite(i).data(15, 0) }
      when(io.pipeWrite(i).addr === 29.U) { frag_z(i) := io.pipeWrite(i).data(15, 0) }
    }
  }

  // --- Outputs ---
  io.autoRunStall := auto_run_stall
  io.insideFlag   := any_inside
  io.phase        := phase    // debug: FSM state visible from parent
}
