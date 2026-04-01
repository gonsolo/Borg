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
  // Bbox write (directly from MMIO data_in bits)
  val setBbox     = Input(Bool())
  val bboxData    = Input(UInt(24.W))     // {bbox_y1[23:18], bbox_x1[17:12], bbox_y0[11:6], bbox_x0[5:0]}

  // Fragment shader PC write (from MMIO write to BORG_FRAG_PC)
  val setFragPC   = Input(Bool())
  val fragPCData  = Input(UInt(6.W))

  // Iterator advance (from MMIO write to BORG_ITER)
  val advance     = Input(Bool())

  // Pipeline write-back snoop (from BorgCore)
  val pipeWriteEn   = Input(Bool())
  val pipeWriteAddr = Input(UInt(log2Ceil(MmioMap.BORG_NUM_REGS).W))
  val pipeWriteData = Input(UInt(config.totalBits.W))

  // Core state feedback (needed for stall clearing)
  val coreRunning        = Input(Bool())
  val coreAutoRunPending = Input(Bool())

  // Outputs
  val iterX         = Output(UInt(6.W))
  val iterY         = Output(UInt(6.W))
  val shaderIterX   = Output(UInt(6.W))  // latched pre-advance position for coordLut
  val shaderIterY   = Output(UInt(6.W))  // latched pre-advance position for coordLut
  val insideFlag    = Output(Bool())
  val iterValid     = Output(Bool())
  val autoRunStall  = Output(Bool())
  val triggerCoreValid = Output(Bool())  // pulse: tells BorgCore to auto-run
  val triggerCorePC    = Output(UInt(6.W))  // PC to start at
}

class BorgRasterizer(val config: FloatConfig = FloatConfig.FP32) extends Module {
  val io = IO(new BorgRasterizerIO(config))

  // --- Phase FSM ---
  val sIdle :: sRast :: sFrag :: Nil = Enum(3)
  val phase = RegInit(sIdle)

  // --- State ---
  val iter_x  = RegInit(0.U(6.W))
  val iter_y  = RegInit(0.U(6.W))
  val shader_iter_x = RegInit(0.U(6.W))  // pre-advance position for coordLut
  val shader_iter_y = RegInit(0.U(6.W))  // pre-advance position for coordLut
  val bbox_x0 = RegInit(0.U(6.W))
  val bbox_y0 = RegInit(0.U(6.W))
  val bbox_x1 = RegInit(0.U(6.W))
  val bbox_y1 = RegInit(0.U(6.W))

  val frag_start_pc = RegInit(0.U(6.W))

  val e0_outside = RegInit(false.B)
  val e1_outside = RegInit(false.B)
  val e2_outside = RegInit(false.B)
  val inside_flag = !e0_outside && !e1_outside && !e2_outside

  val auto_run_stall = RegInit(false.B)

  // --- Fragment PC write ---
  when(io.setFragPC) {
    frag_start_pc := io.fragPCData
  }

  // --- Bbox write ---
  when(io.setBbox) {
    bbox_x0 := io.bboxData(5, 0)
    bbox_y0 := io.bboxData(11, 6)
    bbox_x1 := io.bboxData(17, 12)
    bbox_y1 := io.bboxData(23, 18)
    iter_x  := io.bboxData(5, 0)
    iter_y  := io.bboxData(11, 6)
  }

  // --- Trigger outputs (directly driven, no register delay) ---
  io.triggerCoreValid := false.B
  io.triggerCorePC    := 0.U

  // --- Iterator advance ---
  when(io.advance) {
    // Latch current position BEFORE advancing — shader r30/r31 read these
    shader_iter_x := iter_x
    shader_iter_y := iter_y
    when(iter_x + 1.U >= bbox_x1) {
      iter_x := bbox_x0
      iter_y := iter_y + 1.U
    }.otherwise {
      iter_x := iter_x + 1.U
    }
    auto_run_stall := true.B
    phase := sRast
    io.triggerCoreValid := true.B
    io.triggerCorePC    := 0.U  // rasterizer shader always at PC=0
  }

  // --- Shader chaining FSM ---
  // When rast shader finishes: chain to frag shader if inside, else release CPU
  val core_just_finished = !io.coreRunning && !io.coreAutoRunPending

  when(phase === sRast && core_just_finished) {
    when(inside_flag && frag_start_pc =/= 0.U) {
      // Inside pixel and chaining enabled: trigger fragment shader
      phase := sFrag
      io.triggerCoreValid := true.B
      io.triggerCorePC    := frag_start_pc
    }.otherwise {
      // Outside pixel or chaining disabled: release CPU immediately
      phase := sIdle
      auto_run_stall := false.B
    }
  }

  when(phase === sFrag && core_just_finished) {
    // Fragment shader finished: release CPU
    phase := sIdle
    auto_run_stall := false.B
  }

  // =========================================================================
  // Edge-sign snooping — SIGN CONVENTION (read this before changing!)
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
  when(io.pipeWriteEn) {
    val sign_bit      = io.pipeWriteData(config.totalBits - 1).asBool
    val magn_non_zero = io.pipeWriteData(config.totalBits - 2, 0) =/= 0.U
    // Negative non-zero → outside.  Positive or zero → inside.
    val is_negative_nonzero = sign_bit && magn_non_zero
    when(io.pipeWriteAddr === 0.U) { e0_outside := is_negative_nonzero }
    when(io.pipeWriteAddr === 1.U) { e1_outside := is_negative_nonzero }
    when(io.pipeWriteAddr === 2.U) { e2_outside := is_negative_nonzero }
  }
  // @doc:end

  // --- Outputs ---
  io.iterX        := iter_x
  io.iterY        := iter_y
  io.shaderIterX  := shader_iter_x
  io.shaderIterY  := shader_iter_y
  io.insideFlag   := inside_flag
  io.iterValid    := iter_y < bbox_y1
  io.autoRunStall := auto_run_stall
}
