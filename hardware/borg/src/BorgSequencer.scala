// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgSequencer IO — Step 29.1: vertex shader sequencing.
  *
  * The sequencer orchestrates autonomous vertex shading by:
  *   1. Loading the vertex shader binary from PSRAM into IMEM via DMA.
  *   2. For each of 3 vertices: loading vertex position data into the
  *      uniform buffer via DMA, then triggering BorgCore to run the
  *      vertex shader, and snooping clip-space outputs from PipeWriteIO.
  *
  * Step 29.2 will extend this to triangle setup; Step 29.3 adds uniform staging.
  */
class BorgSequencerIO(val cfg: BorgConfig) extends Bundle {
  /** Single-pulse trigger from MMIO SEQ_TRIGGER write (bit 0). */
  val start    = Input(Bool())

  /** PSRAM byte address of the triangle descriptor, latched from SEQ_DESC_BASE. */
  val descBase = Input(UInt(20.W))

  /** PSRAM byte address of the vertex shader binary. */
  val vertShaderAddr = Input(UInt(20.W))

  /** Length of vertex shader in 32-bit words (1–56). */
  val vertShaderLen  = Input(UInt(6.W))

  /** True while the sequencer FSM is active.  Drives STATUS.seq_busy (bit 5). */
  val busy     = Output(Bool())

  /** One-cycle pulse on FSM completion. */
  val done     = Output(Bool())

  // --- DMA control (drives existing BorgDMA) ---
  val dmaStart = Output(Bool())
  val dmaDesc  = Output(new DMADescriptor)
  val dmaBusy  = Input(Bool())

  // --- Core trigger (shared with rasterizer — muxed in Borg.scala) ---
  val coreTrigger = new CoreTriggerIO

  // --- Core status feedback ---
  val coreStatus = Flipped(new CoreStatusIO)

  // --- Pipeline write-back snoop (to capture vertex shader outputs) ---
  val pipeWrite = Flipped(new PipeWriteIO(cfg.totalBits))

  // --- Snooped clip-space vertex outputs (3 vertices × 4 components) ---
  // Vertex shader writes clip-space results to r0(x), r1(y), r2(z), r3(w).
  // After all 3 runs, these 12 values are available for Step 29.2 (triangle setup).
  val clipOut = Output(Vec(3, Vec(4, UInt(16.W))))
}

/** BorgSequencer — vertex + triangle setup sequencer (Step 29).
  *
  * Orchestrates autonomous vertex shading, triangle setup, and uniform
  * staging by scheduling shader programs on BorgCore.  Replaces the CPU
  * `run_vertex_shader()` + `triangle_setup()` + `setup_tile_uniforms()` path.
  *
  * Step 29.1 FSM (vertex shader sequencing):
  *   sIdle → sLoadShader → sWaitDMA → sLoadVert → sWaitDMA →
  *   sRunVert → sWaitVert → (repeat for 3 vertices) → sDone → sIdle
  *
  * The `vertIdx` counter (0–2) tracks which vertex is being processed.
  * A `nextAfterDMA` register tracks the state to enter after DMA completes.
  *
  * Descriptor layout in PSRAM (25 FP16 words = 50 bytes at descBase):
  *   [0..8]:  pos (3 vertices × 3 components = 9 FP16 words)
  *   [9..17]: color (3 vertices × 3 components = 9 FP16 words)
  *   [18..23]: uv (3 vertices × 2 components = 6 FP16 words)
  *   [24]: flags (1 FP16 word)
  *
  * Each FP16 word occupies one 32-bit PSRAM word (low 16 bits used).
  * Vertex i position starts at descBase + i*12 (3 words × 4 bytes/word).
  *
  * The vertex shader reads position from uniforms u0/u1/u2 and writes
  * clip-space output to r0(x), r1(y), r2(z), r3(w).
  */
class BorgSequencer(val cfg: BorgConfig = BorgConfig.Sim) extends Module {
  val io = IO(new BorgSequencerIO(cfg))

  // --- FSM states ---
  val sIdle :: sLoadShader :: sWaitDMA :: sLoadVert :: sRunVert :: sWaitVert :: sDone :: Nil = Enum(7)
  val state = RegInit(sIdle)

  // Which state to enter after DMA completes
  val nextAfterDMA = RegInit(sIdle)

  // Current vertex index (0, 1, 2)
  val vertIdx = RegInit(0.U(2.W))

  // Shadow registers for clip-space outputs (3 vertices × 4 components)
  val clipRegs = RegInit(VecInit.fill(3, 4)(0.U(16.W)))

  // Latched DMA descriptor — BorgDMA (Step 26.3) reads io.desc fields
  // directly every cycle in sRead, so the sequencer must hold them stable
  // from start until busy deasserts.
  val dmaDescReg = RegInit(0.U.asTypeOf(new DMADescriptor))

  // Core completion detection (same pattern as BorgShaderDispatcher)
  val core_was_active = RegNext(
    io.coreStatus.running || io.coreStatus.autoRunPending, false.B
  )
  val core_just_finished = core_was_active &&
    !io.coreStatus.running && !io.coreStatus.autoRunPending

  // --- Output defaults ---
  io.busy := state =/= sIdle
  io.done := false.B

  io.dmaStart := false.B
  // Drive latched desc so DMA sees stable values throughout its sRead phase
  io.dmaDesc  := dmaDescReg

  io.coreTrigger.valid := false.B
  io.coreTrigger.pc    := 0.U

  io.clipOut := clipRegs

  // --- FSM ---
  switch(state) {

    is(sIdle) {
      when(io.start) {
        vertIdx := 0.U
        state   := sLoadShader
      }
    }

    // Load vertex shader binary from PSRAM into IMEM via DMA
    is(sLoadShader) {
      val desc = Wire(new DMADescriptor)
      desc.baseAddr := io.vertShaderAddr
      desc.length   := io.vertShaderLen
      desc.dest     := 0.U  // dest=0 → IMEM
      desc.offset   := 0.U

      dmaDescReg   := desc
      io.dmaDesc   := desc   // combinational override for this cycle
      io.dmaStart  := true.B
      nextAfterDMA := sLoadVert
      state        := sWaitDMA
    }

    // Wait for DMA transfer to complete
    is(sWaitDMA) {
      when(!io.dmaBusy) {
        state := nextAfterDMA
      }
    }

    // Load vertex position data (3 FP16 words) into uniform buffer via DMA.
    // Vertex i position is at descBase + i*12 bytes (3 words × 4 bytes/word).
    is(sLoadVert) {
      val desc = Wire(new DMADescriptor)
      desc.baseAddr := io.descBase + vertIdx * 12.U
      desc.length   := 3.U  // 3 × 32-bit words (each contains one FP16)
      desc.dest     := 1.U  // dest=1 → uniform buffer page 0
      desc.offset   := 0.U  // write to u0, u1, u2

      dmaDescReg   := desc
      io.dmaDesc   := desc   // combinational override for this cycle
      io.dmaStart  := true.B
      nextAfterDMA := sRunVert
      state        := sWaitDMA
    }

    // Trigger vertex shader execution on BorgCore at PC=0
    is(sRunVert) {
      io.coreTrigger.valid := true.B
      io.coreTrigger.pc    := 0.U
      state                := sWaitVert
    }

    // Wait for vertex shader to finish; snoop clip-space outputs
    is(sWaitVert) {
      when(core_just_finished) {
        when(vertIdx === 2.U) {
          state := sDone
        }.otherwise {
          vertIdx := vertIdx + 1.U
          state   := sLoadVert
        }
      }
    }

    // Sequencer complete — pulse done for one cycle, return to idle
    is(sDone) {
      io.done := true.B
      state   := sIdle
    }
  }

  // --- Clip-space output snooping ---
  // Vertex shader writes results to r0(x), r1(y), r2(z), r3(w).
  // We snoop these during sWaitVert via PipeWriteIO.
  when(io.pipeWrite.en && state === sWaitVert) {
    for (comp <- 0 until 4) {
      when(io.pipeWrite.addr === comp.U) {
        clipRegs(vertIdx)(comp) := io.pipeWrite.data(15, 0)
      }
    }
  }
}
