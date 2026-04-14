// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgRasterizer — pixel iterator, inside-flag logic, and shader chaining.
  *
  * Manages the bounding-box traversal (iter_x / iter_y), snoops
  * FPU write-back to latch edge signs for the inside flag, and
  * (Step 10.6.2) chains rasterizer → fragment shader execution.
  *
  * Phase FSM (Step 10.6.2):
  *   IDLE → RAST (advance triggers edge shader at PC=0)
  *        → FRAG (if inside, auto-trigger fragment shader at frag_start_pc)
  *        → IDLE (release CPU stall)
  *   Outside pixels skip FRAG and go RAST → IDLE immediately.
  */

class BorgRasterizerIO(val config: FloatConfig) extends Bundle {
  // Command pop interface (Step 13.3)
  val cmdPop      = Flipped(Decoupled(new BorgCommand()))

  // Iterator advance (from MMIO write to BORG_ITER)
  val advance     = Input(Bool())

  // Pipeline write-back snoop (from BorgCore)
  val pipeWrite = Flipped(new PipeWriteIO(config.totalBits))

  // Core state feedback (needed for stall clearing)
  val coreStatus = Flipped(new CoreStatusIO)

  // Outputs
  val iter          = Output(new Coord())
  val shaderIter    = Output(new Coord())  // latched pre-advance position for coordLut
  val insideFlag    = Output(Bool())
  val iterValid     = Output(Bool())
  val uniformPage   = Output(UInt(1.W)) // Expose to BorgCore
  val autoRunStall  = Output(Bool())
  val coreTrigger   = new CoreTriggerIO  // pulse: tells BorgCore to auto-run

  // Tile Buffer auto-write interface (Step 11.3)
  val tileWrite = new TileWriteIO

  // GPU memory read port (Step 19.2: sTexFetch)
  val gpuRead    = new GpuReadIO
  val texConfig  = new TexConfigIO
}

class BorgRasterizer(val config: FloatConfig = FloatConfig.FP32) extends Module {
  val io = IO(new BorgRasterizerIO(config))

  // --- Phase FSM ---
  val sIdle :: sRast :: sFrag :: sTexFetch :: sTileWrite :: Nil = Enum(5)
  val phase = RegInit(sIdle)

  // --- State ---
  val iter_reg          = RegInit(0.U.asTypeOf(new Coord()))
  val shader_iter_reg   = RegInit(0.U.asTypeOf(new Coord()))  // pre-advance position for coordLut
  val bbox_reg          = RegInit(0.U.asTypeOf(new Bbox()))
  val frag_start_pc     = RegInit(0.U(6.W))
  val uniform_page_reg  = RegInit(0.U(1.W))

  val e0_outside = RegInit(false.B)
  val e1_outside = RegInit(false.B)
  val e2_outside = RegInit(false.B)
  val inside_flag = !e0_outside && !e1_outside && !e2_outside

  val auto_run_stall = RegInit(false.B)
  val read_word_count = RegInit(0.U(1.W))  // sTexFetch: 0 or 1 (2 packed reads)

  // Fragment output snooping registers (Hardware ABI: R=r26, G=r27, B=r28, Z=r29)
  // Always 16-bit to match Tile Buffer capacity (if core is FP32, it sends FP16 in low bits)
  val frag_r = RegInit(0.U(16.W))
  val frag_g = RegInit(0.U(16.W))
  val frag_b = RegInit(0.U(16.W))
  val frag_z = RegInit(0.U(16.W))

  // --- Command Popping ---
  val iter_valid = iter_reg.y < bbox_reg.max.y
  
  io.cmdPop.ready := false.B
  when(phase === sIdle && io.cmdPop.valid && !iter_valid) {
    io.cmdPop.ready := true.B
    bbox_reg := io.cmdPop.bits.bbox
    iter_reg := io.cmdPop.bits.bbox.min
    frag_start_pc := io.cmdPop.bits.fragPC
    uniform_page_reg := io.cmdPop.bits.uniformPage
  }

  // --- Trigger outputs (directly driven, no register delay) ---
  io.coreTrigger.valid := false.B
  io.coreTrigger.pc    := 0.U

  // Tile buffer write default (no write)
  io.tileWrite.en   := false.B
  io.tileWrite.idx  := iter_reg.x(1, 0) | (iter_reg.y(1, 0) << 2.U)
  io.tileWrite.data := 0.U.asTypeOf(new ColorZ(16))

  // GPU read port defaults (Step 19.2)
  io.gpuRead.req  := false.B
  io.gpuRead.addr := 0.U

  // --- Iterator advance ---
  when(io.advance) {
    // Latch current position BEFORE advancing — shader r30/r31 read these
    shader_iter_reg := iter_reg
    when(iter_reg.x + 1.U >= bbox_reg.max.x) {
      iter_reg.x := bbox_reg.min.x
      iter_reg.y := iter_reg.y + 1.U
    }.otherwise {
      iter_reg.x := iter_reg.x + 1.U
    }
    auto_run_stall := true.B
    phase := sRast
    io.coreTrigger.valid := true.B
    io.coreTrigger.pc    := 0.U  // rasterizer shader always at PC=0
  }

  // --- Shader chaining FSM ---
  // When rast shader finishes: chain to frag shader if inside, else release CPU
  val core_was_active = RegNext(io.coreStatus.running || io.coreStatus.autoRunPending, false.B)
  val core_just_finished = core_was_active && !io.coreStatus.running && !io.coreStatus.autoRunPending

  when(phase === sRast && core_just_finished) {
    when(inside_flag && frag_start_pc =/= 0.U) {
      // Inside pixel and chaining enabled: trigger fragment shader
      phase := sFrag
      io.coreTrigger.valid := true.B
      io.coreTrigger.pc    := frag_start_pc
    }.otherwise {
      // Outside pixel or chaining disabled: release CPU immediately
      phase := sIdle
      auto_run_stall := false.B
    }
  }

  when(phase === sFrag && core_just_finished) {
    // Fragment shader finished: texel fetch or tile write
    when(io.texConfig.en) {
      phase := sTexFetch
      read_word_count := 0.U
    } .otherwise {
      phase := sTileWrite
    }
  }

  // --- sTexFetch: autonomous PSRAM texel read (Step 19.2) ---
  //
  // Texel memory layout (8 bytes per texel, stride = power-of-2):
  //   Word 0 [offset +0]: { G[15:0], R[15:0] }   — both R and G packed
  //   Word 1 [offset +4]: { pad[15:0], B[15:0] }  — B only
  //
  // Address is computed combinationally (no register needed: inputs are
  // stable while FSM is in sTexFetch). Word 1 = base | 4 (bit 2 set,
  // zero-cost since base is 8-byte aligned from <<3).
  val tex_base = io.texConfig.baseAddr +& (io.texConfig.mortonIndex << 3)
  when(phase === sTexFetch) {
    io.gpuRead.req  := true.B
    io.gpuRead.addr := Mux(read_word_count === 0.U, tex_base, tex_base | 4.U)

    when(io.gpuRead.ready) {
      when(read_word_count === 0.U) {
        frag_r := io.gpuRead.data(15, 0)
        frag_g := io.gpuRead.data(31, 16)
        read_word_count := 1.U
      } .otherwise {
        frag_b := io.gpuRead.data(15, 0)
        phase := sTileWrite
        io.gpuRead.req := false.B
      }
    }
  }

  when(phase === sTileWrite) {
    // Push the snooped values to the tile buffer (Step 11.3 auto-write)
    // We use the pre-advanced coordinates for index!
    io.tileWrite.idx := shader_iter_reg.x(1, 0) | (shader_iter_reg.y(1, 0) << 2.U)
    io.tileWrite.data.r := frag_r
    io.tileWrite.data.g := frag_g
    io.tileWrite.data.b := frag_b
    io.tileWrite.data.z := frag_z
    io.tileWrite.en := true.B

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
  when(io.pipeWrite.en && phase === sRast) {
    val sign_bit      = io.pipeWrite.data(config.totalBits - 1).asBool
    val magn_non_zero = io.pipeWrite.data(config.totalBits - 2, 0) =/= 0.U
    // Negative non-zero → outside.  Positive or zero → inside.
    val is_negative_nonzero = sign_bit && magn_non_zero
    when(io.pipeWrite.addr === 0.U) { e0_outside := is_negative_nonzero }
    when(io.pipeWrite.addr === 1.U) { e1_outside := is_negative_nonzero }
    when(io.pipeWrite.addr === 2.U) { e2_outside := is_negative_nonzero }
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
  io.iter         := iter_reg
  io.shaderIter   := shader_iter_reg
  io.insideFlag   := inside_flag
  io.iterValid    := iter_valid
  io.autoRunStall := auto_run_stall
  io.uniformPage  := uniform_page_reg
}
