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
  *   4. If inside: chains to fragment shader at `fragPcReg`.
  *   5. If texturing enabled: performs autonomous PSRAM texel fetch (2-word read).
  *   6. Pushes snooped fragment RGBZ to the tile buffer.
  *   7. Releases the CPU stall.
  *
  * Phase FSM (Step 10.6.2 / Step 25.3d):
  *   sIdle → sRast → sFrag → sTexFetch → sTileWrite → sIdle
  *   Outside pixels shortcut: sRast → sIdle (skip sFrag/sTexFetch/sTileWrite).
  *
  * The `phase` output is exposed for debugability — when tracing in simulation,
  * you can directly observe which FSM state the dispatcher occupies without
  * instrumenting internal registers.
  *
  * @doc:dispatcher
  */
class BorgShaderDispatcherIO(val cfg: BorgConfig) extends Bundle {
  // --- Inputs from BorgIterator ---
  val pixelReady     = Input(Bool())            // one-cycle pulse: new pixel ready
  val shaderTileIndex = Input(UInt(4.W))        // pre-advance tile index for sTileWrite

  // --- Inputs from BorgCore (snooping) ---
  val pipeWrite  = Flipped(new PipeWriteIO(cfg.totalBits))
  val coreStatus = Flipped(new CoreStatusIO)

  // --- Inputs from MMIO registers ---
  val fragPcReg  = Input(UInt(6.W))             // fragment shader start PC

  // --- Inputs from texture pipeline ---
  val texConfig  = new TexConfigIO              // mortonIndex, baseAddr, en

  // --- Outputs to BorgCore ---
  val coreTrigger = new CoreTriggerIO           // shader start pulse + PC

  // --- Outputs to BorgTileBuffer ---
  val tileWrite  = new TileWriteIO              // tile buffer push
  val tileRead   = new TileReadIO(16)           // Step 25.5C: depth test read port

  // --- Outputs to MemoryController (PSRAM) ---
  val gpuMem     = new GpuMemIO                 // texel read port

  // --- Outputs (status / debug) ---
  val autoRunStall = Output(Bool())             // stalls CPU between advance and completion
  val insideFlag   = Output(Bool())             // true when all 3 edges are non-negative
  val fragU        = Output(UInt(16.W))         // snooped U (r26) for Morton encoder
  val fragV        = Output(UInt(16.W))         // snooped V (r27) for Morton encoder
  val phase        = Output(UInt(3.W))          // current FSM state (debug observable)
}

class BorgShaderDispatcher(val cfg: BorgConfig = BorgConfig.Sim) extends Module {
  val io = IO(new BorgShaderDispatcherIO(cfg))

  private val config = cfg.fp  // shorthand for FP arithmetic

  // --- Phase FSM ---
  // Step 25.5C: sZRead/sZWait1/sZWait2 added for depth test read-before-write.
  val sIdle :: sRast :: sFrag :: sTexFetch :: sZRead :: sZWait1 :: sZWait2 :: sTileWrite :: Nil = Enum(8)
  val phase = RegInit(sIdle)

  // --- Texture unit (Step 25.3e) ---
  val texUnit = Module(new BorgTextureUnit)

  // --- Edge-sign state ---
  val e0_outside = RegInit(false.B)
  val e1_outside = RegInit(false.B)
  val e2_outside = RegInit(false.B)
  val inside_flag = !e0_outside && !e1_outside && !e2_outside

  // --- Stall ---
  val auto_run_stall = RegInit(false.B)

  // --- Fragment output snooping registers (Hardware ABI: R=r26, G=r27, B=r28, Z=r29) ---
  // Always 16-bit to match Tile Buffer capacity (if core is FP32, it sends FP16 in low bits)
  // Note: frag_r/g/b are owned by BorgTextureUnit when texturing; we read them back
  // from texUnit.io.fragColor.  frag_r/g/b here are only used for the non-textured path.
  val frag_r = RegInit(0.U(16.W))
  val frag_g = RegInit(0.U(16.W))
  val frag_b = RegInit(0.U(16.W))
  val frag_z = RegInit(0.U(16.W))

  // --- Trigger outputs (directly driven, no register delay) ---
  io.coreTrigger.valid := false.B
  io.coreTrigger.pc    := 0.U

  // Tile buffer write default (no write)
  io.tileWrite.en   := false.B
  io.tileWrite.idx  := io.shaderTileIndex
  io.tileWrite.data := 0.U.asTypeOf(new ColorZ(16))

  // GPU memory port: forwarded from BorgTextureUnit (Step 25.3e)
  texUnit.io.texConfig <> io.texConfig
  texUnit.io.gpuMem    <> io.gpuMem
  texUnit.io.start     := false.B  // overridden in sTexFetch transition below

  // Step 25.5C: tile read port defaults (no read)
  io.tileRead.en  := false.B
  io.tileRead.idx := io.shaderTileIndex

  // --- React to pixelReady from BorgIterator ---
  when(io.pixelReady) {
    e0_outside := false.B
    e1_outside := false.B
    e2_outside := false.B
    auto_run_stall := true.B
    phase := sRast
    io.coreTrigger.valid := true.B
    io.coreTrigger.pc    := 0.U
  }

  // --- Shader chaining FSM ---
  val core_was_active = RegNext(io.coreStatus.running || io.coreStatus.autoRunPending, false.B)
  val core_just_finished = core_was_active && !io.coreStatus.running && !io.coreStatus.autoRunPending

  when(phase === sRast && core_just_finished) {
    when(inside_flag && io.fragPcReg =/= 0.U) {
      phase := sFrag
      io.coreTrigger.valid := true.B
      io.coreTrigger.pc    := io.fragPcReg
    }.otherwise {
      phase := sIdle
      auto_run_stall := false.B
    }
  }

  when(phase === sFrag && core_just_finished) {
    when(io.texConfig.en) {
      phase := sTexFetch
      texUnit.io.start := true.B
    } .otherwise {
      phase := sZRead
    }
  }

  // --- sTexFetch: delegated to BorgTextureUnit (Step 25.3e) ---
  when(phase === sTexFetch && texUnit.io.done) {
    frag_r := texUnit.io.fragColor.r
    frag_g := texUnit.io.fragColor.g
    frag_b := texUnit.io.fragColor.b
    phase  := sZRead
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

  when(phase === sZRead) {
    io.tileRead.en  := true.B
    io.tileRead.idx := io.shaderTileIndex
    phase := sZWait1
  }

  when(phase === sZWait1) {
    phase := sZWait2
  }

  when(phase === sZWait2) {
    phase := sTileWrite
  }

  when(phase === sTileWrite) {
    io.tileWrite.idx := io.shaderTileIndex
    io.tileWrite.data.r := frag_r
    io.tileWrite.data.g := frag_g
    io.tileWrite.data.b := frag_b
    io.tileWrite.data.z := frag_z
    // Step 25.5C depth test: write only if inside AND closer.
    // FP16 Z is non-negative in NDC; unsigned < comparison is valid.
    // io.tileRead.data is held stable in BorgTileBuffer.readDataHeld.
    io.tileWrite.en := inside_flag && (frag_z < io.tileRead.data.z)

    phase := sIdle
    auto_run_stall := false.B
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

  when(io.pipeWrite.en && phase === sRast) {
    when(io.pipeWrite.addr === 0.U) {
      e0_outside := isOutside(io.pipeWrite.data)
    }
    when(io.pipeWrite.addr === 1.U) { e1_outside := isOutside(io.pipeWrite.data) }
    when(io.pipeWrite.addr === 2.U) { e2_outside := isOutside(io.pipeWrite.data) }
  }
  // @doc:end

  // Snoop fragment shader output (Hardware ABI: R=r26, G=r27, B=r28, Z=r29)
  when(io.pipeWrite.en && phase === sFrag) {
    when(io.pipeWrite.addr === 26.U) { frag_r := io.pipeWrite.data(15, 0) }
    when(io.pipeWrite.addr === 27.U) { frag_g := io.pipeWrite.data(15, 0) }
    when(io.pipeWrite.addr === 28.U) { frag_b := io.pipeWrite.data(15, 0) }
    when(io.pipeWrite.addr === 29.U) { frag_z := io.pipeWrite.data(15, 0) }
  }

  // --- Outputs ---
  io.autoRunStall := auto_run_stall
  io.insideFlag   := inside_flag
  io.fragU        := frag_r   // snooped U (mapped into r26 slot when texturing)
  io.fragV        := frag_g   // snooped V (mapped into r27 slot when texturing)
  io.phase        := phase    // debug: FSM state visible from parent
}
