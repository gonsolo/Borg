// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgSequencer IO — Step 29.1/29.2: vertex + triangle setup sequencing.
  *
  * The sequencer orchestrates autonomous vertex shading and triangle setup by:
  *   1. Loading the vertex shader binary from PSRAM into IMEM via DMA.
  *   2. For each of 3 vertices: loading vertex position data into the
  *      uniform buffer via DMA, then triggering BorgCore to run the
  *      vertex shader, and snooping clip-space outputs from PipeWriteIO.
  *   3. Writing snooped screen-space coordinates into the uniform buffer.
  *   4. Loading the setup shader from PSRAM into IMEM via DMA.
  *   5. Running the setup shader to compute edge vectors and inv_area.
  *
  * Step 29.3 adds uniform staging.
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

  /** PSRAM byte address of the setup shader binary (Step 29.2). */
  val setupShaderAddr = Input(UInt(20.W))

  /** Length of setup shader in 32-bit words (1–56) (Step 29.2). */
  val setupShaderLen  = Input(UInt(6.W))

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

  // --- Pipeline write-back snoop (to capture shader outputs) ---
  val pipeWrite = Flipped(new PipeWriteIO(cfg.totalBits))

  // --- Uniform write port (to load setup shader inputs from clipRegs) ---
  val uniformWrite = new MemWritePort(6, 16)

  // --- Snooped clip-space vertex outputs (3 vertices × 4 components) ---
  // Vertex shader writes clip-space results to r0(x), r1(y), r2(z), r3(w).
  // After all 3 runs, these 12 values are available for the setup shader.
  val clipOut = Output(Vec(3, Vec(4, UInt(16.W))))

  // --- Snooped setup shader outputs (Step 29.2) ---
  // Setup shader writes: r0=e0.dx, r1=e0.dy, r2=e1.dx, r3=e1.dy,
  //                      r4=e2.dx, r5=e2.dy, r6=area, r7=inv_area
  val setupOut = Output(Vec(8, UInt(16.W)))
}

/** BorgSequencer — vertex + triangle setup sequencer (Step 29).
  *
  * Step 29.1 FSM (vertex shader sequencing):
  *   sIdle → sLoadShader → sWaitDMA → sLoadVert → sWaitDMA →
  *   sRunVert → sWaitVert → (repeat for 3 vertices)
  *
  * Step 29.2 FSM (triangle setup):
  *   → sWriteSetupInputs → sLoadSetupShader → sWaitDMA →
  *   sRunSetup → sWaitSetup → sDone → sIdle
  *
  * `sWriteSetupInputs` writes 6 values from clipRegs (v0.x, v0.y, v1.x,
  * v1.y, v2.x, v2.y) into the uniform buffer (u0–u5) over 6 clock cycles
  * so the setup shader can read them as inputs.
  *
  * The setup shader computes edge vectors and signed area from screen-space
  * positions, then outputs inv_area via FRCP.  This replaces the CPU-side
  * `triangle_setup()` + `compute_edge_vectors()` functions.
  *
  * Descriptor layout in PSRAM (25 FP16 words = 50 bytes at descBase):
  *   [0..8]:  pos (3 vertices × 3 components = 9 FP16 words)
  *   [9..17]: color (3 vertices × 3 components = 9 FP16 words)
  *   [18..23]: uv (3 vertices × 2 components = 6 FP16 words)
  *   [24]: flags (1 FP16 word)
  */
class BorgSequencer(val cfg: BorgConfig = BorgConfig.Sim) extends Module {
  val io = IO(new BorgSequencerIO(cfg))

  // --- FSM states ---
  // Step 29.1:                       sIdle, sLoadShader, sWaitDMA, sLoadVert, sRunVert, sWaitVert
  // Step 29.2 (setup shader):        sWriteSetupInputs, sLoadSetupShader, sRunSetup, sWaitSetup
  // Shared terminal:                 sDone
  val (sIdle :: sLoadShader :: sWaitDMA :: sLoadVert :: sRunVert :: sWaitVert ::
       sWriteSetupInputs :: sLoadSetupShader :: sRunSetup :: sWaitSetup ::
       sDone :: Nil) = Enum(11)
  val state = RegInit(sIdle)

  // Which state to enter after DMA completes
  val nextAfterDMA = RegInit(sIdle)

  // Current vertex index (0, 1, 2)
  val vertIdx = RegInit(0.U(2.W))

  // Write counter for sWriteSetupInputs (0–5: write 6 uniform values)
  val writeIdx = RegInit(0.U(3.W))

  // Shadow registers for clip-space outputs (3 vertices × 4 components)
  val clipRegs = RegInit(VecInit.fill(3, 4)(0.U(16.W)))

  // Shadow registers for setup shader outputs (8 values: 6 edge + area + inv_area)
  val setupRegs = RegInit(VecInit.fill(8)(0.U(16.W)))

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

  io.uniformWrite.en   := false.B
  io.uniformWrite.addr := 0.U
  io.uniformWrite.data := 0.U

  io.clipOut  := clipRegs
  io.setupOut := setupRegs

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
          // All 3 vertices done → proceed to triangle setup (Step 29.2)
          writeIdx := 0.U
          state    := sWriteSetupInputs
        }.otherwise {
          vertIdx := vertIdx + 1.U
          state   := sLoadVert
        }
      }
    }

    // --- Step 29.2: Triangle setup ---

    // Write 6 screen-space coordinates from clipRegs into uniform buffer.
    // u0=v0.x, u1=v0.y, u2=v1.x, u3=v1.y, u4=v2.x, u5=v2.y
    // clipRegs layout: clipRegs(v)(c) where v=0..2, c=0..3 (x,y,z,w)
    // We write: u[2*v+0] = clipRegs(v)(0), u[2*v+1] = clipRegs(v)(1)
    is(sWriteSetupInputs) {
      val v = writeIdx(2, 1)  // writeIdx / 2 → vertex index
      val c = writeIdx(0)     // writeIdx % 2 → component (0=x, 1=y)
      io.uniformWrite.en   := true.B
      io.uniformWrite.addr := writeIdx  // u0..u5 on uniform page 0
      io.uniformWrite.data := clipRegs(v)(c)
      when(writeIdx === 5.U) {
        state := sLoadSetupShader
      }.otherwise {
        writeIdx := writeIdx + 1.U
      }
    }

    // Load setup shader from PSRAM into IMEM via DMA
    is(sLoadSetupShader) {
      val desc = Wire(new DMADescriptor)
      desc.baseAddr := io.setupShaderAddr
      desc.length   := io.setupShaderLen
      desc.dest     := 0.U  // dest=0 → IMEM
      desc.offset   := 0.U

      dmaDescReg   := desc
      io.dmaDesc   := desc
      io.dmaStart  := true.B
      nextAfterDMA := sRunSetup
      state        := sWaitDMA
    }

    // Trigger setup shader execution on BorgCore at PC=0
    is(sRunSetup) {
      io.coreTrigger.valid := true.B
      io.coreTrigger.pc    := 0.U
      state                := sWaitSetup
    }

    // Wait for setup shader to finish; snoop outputs
    is(sWaitSetup) {
      when(core_just_finished) {
        state := sDone
      }
    }

    // Sequencer complete — pulse done for one cycle, return to idle
    is(sDone) {
      io.done := true.B
      state   := sIdle
    }
  }

  // --- Clip-space output snooping (Step 29.1) ---
  // Vertex shader writes results to r0(x), r1(y), r2(z), r3(w).
  when(io.pipeWrite.en && state === sWaitVert) {
    for (comp <- 0 until 4) {
      when(io.pipeWrite.addr === comp.U) {
        clipRegs(vertIdx)(comp) := io.pipeWrite.data(15, 0)
      }
    }
  }

  // --- Setup shader output snooping (Step 29.2) ---
  // Setup shader writes: r0–r7 = edge vectors + area + inv_area.
  when(io.pipeWrite.en && state === sWaitSetup) {
    for (i <- 0 until 8) {
      when(io.pipeWrite.addr === i.U) {
        setupRegs(i) := io.pipeWrite.data(15, 0)
      }
    }
  }
}
