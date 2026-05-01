// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgSequencer IO — Step 29 scaffolding.
  *
  * Full interface will be wired in Step 29.1 (DMA control, CoreTriggerIO,
  * PipeWriteIO, uniformWrite).  For Step 29.0 only `start`, `descBase`,
  * `busy`, and `done` are needed to wire the status bit and MMIO decode.
  */
class BorgSequencerIO extends Bundle {
  /** Singlepulse trigger from MMIO SEQ_TRIGGER write (bit 0). */
  val start    = Input(Bool())

  /** PSRAM byte address of the triangle descriptor, latched from SEQ_DESC_BASE. */
  val descBase = Input(UInt(20.W))

  /** True while the sequencer FSM is active.  Drives STATUS.seq_busy (bit 5). */
  val busy     = Output(Bool())

  /** One-cycle pulse on FSM completion (unused externally in Step 29.0). */
  val done     = Output(Bool())
}

/** BorgSequencer — vertex + triangle setup sequencer (Step 29).
  *
  * Orchestrates autonomous vertex shading, triangle setup, and uniform
  * staging by scheduling shader programs on BorgCore.  Replaces the CPU
  * `run_vertex_shader()` + `triangle_setup()` + `setup_tile_uniforms()` path.
  *
  * Step 29.0: stub only — FSM returns immediately, busy=false always.
  *            IO will be expanded in Steps 29.1–29.3.
  *
  * Full FSM (Steps 29.1–29.3):
  *   sIdle → sLoadVertShader → sRunVert0 → sRunVert1 → sRunVert2
  *         → sLoadSetupShader → sRunSetup → sStageUniforms → sDone
  */
class BorgSequencer extends Module {
  val io = IO(new BorgSequencerIO)

  // --- Step 29.0 stub: always idle ---
  io.busy := false.B
  io.done := false.B

  // Suppress unused-input warnings; will be used from Step 29.1.
  val _ = io.start
  val __ = io.descBase
}
