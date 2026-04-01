// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.*
import chisel3.util.*

/** BorgRasterizer — pixel iterator and inside-flag logic.
  *
  * Manages the bounding-box traversal (iter_x / iter_y) and snoops
  * FPU write-back to latch edge signs for the inside flag.
  * This module can be independently unit-tested without the FPU pipeline.
  */

class BorgRasterizerIO(val config: FloatConfig) extends Bundle {
  // Bbox write (directly from MMIO data_in bits)
  val setBbox     = Input(Bool())
  val bboxData    = Input(UInt(24.W))     // {bbox_y1[23:18], bbox_x1[17:12], bbox_y0[11:6], bbox_x0[5:0]}

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
  val triggerCore   = Output(Bool())    // pulse: tells BorgCore to set PC=0 and auto_run_pending
}

class BorgRasterizer(val config: FloatConfig = FloatConfig.FP32) extends Module {
  val io = IO(new BorgRasterizerIO(config))

  // --- State ---
  val iter_x  = RegInit(0.U(6.W))
  val iter_y  = RegInit(0.U(6.W))
  val shader_iter_x = RegInit(0.U(6.W))  // pre-advance position for coordLut
  val shader_iter_y = RegInit(0.U(6.W))  // pre-advance position for coordLut
  val bbox_x0 = RegInit(0.U(6.W))
  val bbox_y0 = RegInit(0.U(6.W))
  val bbox_x1 = RegInit(0.U(6.W))
  val bbox_y1 = RegInit(0.U(6.W))

  val e0_outside = RegInit(false.B)
  val e1_outside = RegInit(false.B)
  val e2_outside = RegInit(false.B)
  val inside_flag = !e0_outside && !e1_outside && !e2_outside

  val auto_run_stall = RegInit(false.B)

  // --- Bbox write ---
  when(io.setBbox) {
    bbox_x0 := io.bboxData(5, 0)
    bbox_y0 := io.bboxData(11, 6)
    bbox_x1 := io.bboxData(17, 12)
    bbox_y1 := io.bboxData(23, 18)
    iter_x  := io.bboxData(5, 0)
    iter_y  := io.bboxData(11, 6)
  }

  // --- Iterator advance ---
  io.triggerCore := false.B
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
    io.triggerCore := true.B
  }

  // --- Edge-sign snooping ---
  // @doc:inside-snoop
  when(io.pipeWriteEn) {
    val sign_bit = io.pipeWriteData(config.totalBits - 1).asBool
    val is_outside = sign_bit
    when(io.pipeWriteAddr === 0.U) { e0_outside := is_outside }
    when(io.pipeWriteAddr === 1.U) { e1_outside := is_outside }
    when(io.pipeWriteAddr === 2.U) { e2_outside := is_outside }
  }
  // @doc:end

  // --- Stall clearing ---
  when(auto_run_stall && !io.coreRunning && !io.coreAutoRunPending) {
    auto_run_stall := false.B
  }

  // --- Outputs ---
  io.iterX        := iter_x
  io.iterY        := iter_y
  io.shaderIterX  := shader_iter_x
  io.shaderIterY  := shader_iter_y
  io.insideFlag   := inside_flag
  io.iterValid    := iter_y < bbox_y1
  io.autoRunStall := auto_run_stall
}
