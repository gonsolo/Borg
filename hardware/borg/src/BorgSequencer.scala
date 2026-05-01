// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgSequencer IO — Steps 29.1–29.3: vertex + setup + uniform staging.
  *
  * The sequencer orchestrates autonomous vertex shading, triangle setup, and
  * uniform staging by:
  *   1. Loading the vertex shader binary from PSRAM into IMEM via DMA.
  *   2. For each of 3 vertices: loading 8 vertex words (pos+color+uv) into
  *      the uniform buffer via DMA, then triggering BorgCore to run the
  *      vertex shader, snooping clip-space outputs from PipeWriteIO, and
  *      capturing color/z from the DMA write stream into colorRegs.
  *   3. Writing snooped screen-space coordinates into the uniform buffer.
  *   4. Loading the setup shader from PSRAM into IMEM via DMA.
  *   5. Running the setup shader to compute scaled edge vectors and inv_area.
  *   6. Staging all 31 uniform registers for the rasterizer and fragment
  *      shaders (sStageUniforms, replacing CPU's setup_tile_uniforms()).
  *
  * Descriptor layout in PSRAM (3 × borg_vertex_t, stride = 32 bytes):
  *   vertex i at descBase + i*32:
  *     offset  0: pos.x  (FP16 in bits[15:0] of 32-bit PSRAM word)
  *     offset  4: pos.y
  *     offset  8: pos.z
  *     offset 12: color.r
  *     offset 16: color.g
  *     offset 20: color.b
  *     offset 24: uv.u  (for future UV support)
  *     offset 28: uv.v
  *
  * Physical uniform register map (from SPIRB blob parse of shader_blobs.h):
  *   Rasterizer shader (uniform_regs[0..11] = [0..11]):
  *     u0  = e0.dx * inv_width     (scaled edge 0 x)
  *     u1  = e0.dy * inv_width     (scaled edge 0 y)
  *     u2  = e1.dx * inv_width     (scaled edge 1 x)
  *     u3  = e1.dy * inv_width     (scaled edge 1 y)
  *     u4  = e2.dx * inv_width     (scaled edge 2 x)
  *     u5  = e2.dy * inv_width     (scaled edge 2 y)
  *     u6  = -v0.x, u7 = -v0.y    (negated vertex 0 position)
  *     u8  = -v1.x, u9 = -v1.y    (negated vertex 1 position)
  *     u10 = -v2.x, u11 = -v2.y   (negated vertex 2 position)
  *   Fragment shader (uniform_regs[0..18] = [12..30]):
  *     u12 = inv_area
  *     u13 = colors[1].r, u14 = colors[0].r, u15 = colors[2].r
  *     u16 = colors[1].g, u17 = colors[0].g, u18 = colors[2].g
  *     u19 = colors[1].b, u20 = colors[0].b, u21 = colors[2].b
  *     u22 = z_vals[1], u23 = z_vals[0], u24 = z_vals[2]
  *     u25-u30 = 0 (UVs not yet implemented)
  *
  * The setup shader outputs pre-scaled edge constants to r0-r5 (already
  * multiplied by inv_width = 1/64 for a 64-wide framebuffer).
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

  // --- Uniform write port (to write setup inputs and staged uniforms) ---
  // Step 29.2: writes clipRegs to u0-u5 as setup shader inputs.
  // Step 29.3: writes 31 uniforms (u0-u30) after setup shader completes.
  val uniformWrite = new MemWritePort(6, 16)

  // --- DMA uniform write snoop (to capture color/z from DMA stream) ---
  // During vertex DMA, BorgCore's dmaUniformWrite is asserted by BorgDMA.
  // The sequencer snoops these writes to populate colorRegs.
  // Wired in Borg.scala from core.io.dmaUniformWrite (or DMA's uniformWrite).
  val dmaUniformSnoop = Flipped(new MemWritePort(6, 16))

  // --- Snooped clip-space vertex outputs (3 vertices × 4 components) ---
  val clipOut = Output(Vec(3, Vec(4, UInt(16.W))))

  // --- Snooped setup shader outputs (Step 29.2) ---
  // r0–r5 = scaled edge components (pre-multiplied by inv_width in setup shader)
  // r6 = area, r7 = inv_area
  val setupOut = Output(Vec(8, UInt(16.W)))

  // --- Staged uniform write page (for ping-pong) ---
  // The sequencer drives uniformWritePage during sStageUniforms to select
  // which uniform memory page the rasterizer will read from next frame.
  val uniformWritePage = Output(UInt(1.W))
}

/** BorgSequencer — vertex + setup + uniform staging sequencer (Step 29).
  *
  * Step 29.1 FSM (vertex shader sequencing):
  *   sIdle → sLoadShader → sWaitDMA → sLoadVert → sWaitDMA →
  *   sRunVert → sWaitVert → (repeat for 3 vertices)
  *
  * Step 29.2 FSM (triangle setup):
  *   → sWriteSetupInputs → sLoadSetupShader → sWaitDMA →
  *   sRunSetup → sWaitSetup
  *
  * Step 29.3 FSM (uniform staging, replaces CPU's setup_tile_uniforms()):
  *   → sStageUniforms → sDone → sIdle
  *
  * `sStageUniforms` cycles writeIdx from 0 to 30, writing each uniform in
  * turn.  Data sources are determined by writeIdx:
  *   0-5:   setupRegs[writeIdx]               (scaled edge components)
  *   6-11:  FNEG(clipRegs[v][c])              (negated vertex positions)
  *   12:    setupRegs[7]                       (inv_area)
  *   13-21: colorRegs[v][c] (r,g,b per vertex in barycentric order)
  *   22-24: colorRegs[v][3]                   (z_vals per vertex)
  *   25-30: 0                                 (UVs — future work)
  */
class BorgSequencer(val cfg: BorgConfig = BorgConfig.Sim) extends Module {
  val io = IO(new BorgSequencerIO(cfg))

  // --- FSM states ---
  // Step 29.1: sIdle, sLoadShader, sWaitDMA, sLoadVert, sRunVert, sWaitVert
  // Step 29.2: sWriteSetupInputs, sLoadSetupShader, sRunSetup, sWaitSetup
  // Step 29.3: sStageUniforms
  // Shared terminal: sDone
  val (sIdle :: sLoadShader :: sWaitDMA :: sLoadVert :: sRunVert :: sWaitVert ::
       sWriteSetupInputs :: sLoadSetupShader :: sRunSetup :: sWaitSetup ::
       sStageUniforms ::
       sDone :: Nil) = Enum(12)
  val state = RegInit(sIdle)

  // Which state to enter after DMA completes
  val nextAfterDMA = RegInit(sIdle)

  // Current vertex index (0, 1, 2)
  val vertIdx = RegInit(0.U(2.W))

  // General-purpose sequential write counter
  // - sWriteSetupInputs: 0-5 (6 uniform writes)
  // - sStageUniforms:    0-30 (31 uniform writes)
  val writeIdx = RegInit(0.U(5.W))

  // Shadow registers for clip-space outputs (3 vertices × 4 components: x,y,z,w)
  val clipRegs = RegInit(VecInit.fill(3, 4)(0.U(16.W)))

  // Shadow registers for color + z per vertex (3 vertices × 4 components: r,g,b,z)
  // Populated by snooping the DMA uniform write stream during vertex DMA.
  // DMA loads 8 words per vertex at descBase + v*32:
  //   uniform offset 0 = pos.x, 1 = pos.y, 2 = pos.z,
  //   uniform offset 3 = color.r, 4 = color.g, 5 = color.b,
  //   uniform offset 6 = uv.u, 7 = uv.v  (not captured)
  // We capture offsets 3,4,5 as r,g,b and offset 2 as z.
  val colorRegs = RegInit(VecInit.fill(3, 4)(0.U(16.W)))  // [v][r,g,b,z]

  // Shadow registers for setup shader outputs (8 values)
  // r0-r5 = scaled edge components, r6 = area, r7 = inv_area
  val setupRegs = RegInit(VecInit.fill(8)(0.U(16.W)))

  // Latched DMA descriptor — BorgDMA (Step 26.3) reads io.desc fields
  // directly every cycle in sRead, so the sequencer must hold them stable
  // from start until busy deasserts.
  val dmaDescReg = RegInit(0.U.asTypeOf(new DMADescriptor))

  // Ping-pong page register (toggles each time sStageUniforms runs)
  val uniformPage = RegInit(0.U(1.W))

  // Core completion detection
  val core_was_active = RegNext(
    io.coreStatus.running || io.coreStatus.autoRunPending, false.B
  )
  val core_just_finished = core_was_active &&
    !io.coreStatus.running && !io.coreStatus.autoRunPending

  // --- Output defaults ---
  io.busy := state =/= sIdle
  io.done := false.B

  io.dmaStart := false.B
  io.dmaDesc  := dmaDescReg

  io.coreTrigger.valid := false.B
  io.coreTrigger.pc    := 0.U

  io.uniformWrite.en   := false.B
  io.uniformWrite.addr := 0.U
  io.uniformWrite.data := 0.U

  io.uniformWritePage := uniformPage

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
      io.dmaDesc   := desc
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

    // Load full vertex data (8 FP16 words: x,y,z,r,g,b,u,v) from descriptor.
    // Vertex i is at descBase + i*32 bytes (8 words × 4 bytes = 32 bytes).
    // DMA writes all 8 words to uniform[0..7] in uniform page 0.
    // During the wait, sWaitDMA snoops uniform writes to colorRegs (see below).
    is(sLoadVert) {
      val desc = Wire(new DMADescriptor)
      desc.baseAddr := io.descBase + vertIdx * 32.U
      desc.length   := 8.U   // 8 × 32-bit words (x,y,z,r,g,b,u,v)
      desc.dest     := 1.U   // dest=1 → uniform buffer page 0
      desc.offset   := 0.U   // write to u0..u7

      dmaDescReg   := desc
      io.dmaDesc   := desc
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

    // Wait for vertex shader to finish; snoop clip-space outputs (x,y into clipRegs)
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
    is(sWriteSetupInputs) {
      val v = writeIdx(2, 1)  // writeIdx / 2 → vertex index (0, 1, 2)
      val c = writeIdx(0)     // writeIdx % 2 → component (0=x, 1=y)
      io.uniformWrite.en   := true.B
      io.uniformWrite.addr := writeIdx(4, 0)
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

    // Wait for setup shader to finish; snoop outputs into setupRegs
    is(sWaitSetup) {
      when(core_just_finished) {
        // Toggle uniform write page for ping-pong (Step 13.4 pattern)
        uniformPage := ~uniformPage
        writeIdx    := 0.U
        state       := sStageUniforms
      }
    }

    // --- Step 29.3: Uniform staging ---
    // Write all 31 uniform registers (u0-u30) to replace setup_tile_uniforms().
    // Physical uniform indices match the fixed SPIRB layout:
    //   u0-u5:  scaled edge components from setupRegs[0..5]
    //   u6-u11: negated vertex positions from FNEG(clipRegs[v][c])
    //   u12:    inv_area from setupRegs[7]
    //   u13-u21: colors in barycentric order (v1,v0,v2) × RGB
    //   u22-u24: z_vals (z of v1, v0, v2)
    //   u25-u30: 0 (UVs — not yet implemented)
    is(sStageUniforms) {
      val w = writeIdx

      // Barycentric order: w0→v2, w1→v0, w2→v1
      // color[1].r = v1.r, color[0].r = v0.r, color[2].r = v2.r
      // Fragment uniforms 1-3: (v1, v0, v2) = colorRegs(1), (0), (2)
      val baryV: Vec[UInt] = VecInit(1.U(2.W), 0.U(2.W), 2.U(2.W))  // bary index → vertex index

      // Default: write zero (for UV slots 25-30)
      val uData = WireDefault(0.U(16.W))

      when(w < 6.U) {
        // u0-u5: scaled edge components from setup shader outputs
        uData := setupRegs(w(2, 0))
      }.elsewhen(w < 12.U) {
        // u6-u11: negated vertex positions (FNEG = flip bit 15)
        // u6,u7 = -v0.x, -v0.y; u8,u9 = -v1.x, -v1.y; u10,u11 = -v2.x, -v2.y
        val vIdx = (w - 6.U)(2, 1)  // vertex index 0,1,2
        val cIdx = (w - 6.U)(0)     // component: 0=x, 1=y
        val raw  = clipRegs(vIdx)(cIdx)
        uData := raw ^ (1.U(16.W) << 15)  // flip sign bit = FNEG
      }.elsewhen(w === 12.U) {
        // u12: inv_area
        uData := setupRegs(7)
      }.elsewhen(w < 22.U) {
        // u13-u21: 9 color values = 3 components (R,G,B) × 3 vertices (bary order)
        // u13-u15 = R(v1,v0,v2), u16-u18 = G(v1,v0,v2), u19-u21 = B(v1,v0,v2)
        val colorOff = w - 13.U  // 0-8
        val comp     = colorOff / 3.U   // 0=R, 1=G, 2=B
        val baryIdx  = colorOff % 3.U   // 0,1,2 → vertex baryV[baryIdx]
        val vIdx     = baryV(baryIdx)
        uData := colorRegs(vIdx)(comp)
      }.elsewhen(w < 25.U) {
        // u22-u24: z values — z_vals[1], z_vals[0], z_vals[2] (bary order)
        val zOff = w - 22.U  // 0,1,2
        val vIdx = baryV(zOff)
        uData := colorRegs(vIdx)(3)  // index 3 = z component
      }
      // u25-u30: uData stays 0

      io.uniformWrite.en   := true.B
      io.uniformWrite.addr := writeIdx(4, 0)
      io.uniformWrite.data := uData

      when(writeIdx === 30.U) {
        state := sDone
      }.otherwise {
        writeIdx := writeIdx + 1.U
      }
    }

    // Sequencer complete — pulse done for one cycle, return to idle
    is(sDone) {
      io.done := true.B
      state   := sIdle
    }
  }

  // --- Clip-space output snooping (Step 29.1) ---
  // Vertex shader writes results to r0(x), r1(y) (passthrough of u0, u1).
  when(io.pipeWrite.en && state === sWaitVert) {
    for (comp <- 0 until 4) {
      when(io.pipeWrite.addr === comp.U) {
        clipRegs(vertIdx)(comp) := io.pipeWrite.data(15, 0)
      }
    }
  }

  // --- Color/z capture from DMA uniform write stream (Step 29.3) ---
  // During vertex DMA (sWaitDMA with nextAfterDMA=sRunVert), BorgDMA writes
  // 8 words to uniform[0..7]:
  //   uniform[0]=x, [1]=y, [2]=z, [3]=r, [4]=g, [5]=b, [6]=u_tex, [7]=v_tex
  // We snoop the DMA write stream to capture z(index=2), r(3), g(4), b(5).
  when(io.dmaUniformSnoop.en && state === sWaitDMA &&
       nextAfterDMA === sRunVert) {
    val addr = io.dmaUniformSnoop.addr(2, 0)  // low 3 bits = offset 0-7
    val data = io.dmaUniformSnoop.data
    when(addr === 2.U) { colorRegs(vertIdx)(3) := data }  // z
    when(addr === 3.U) { colorRegs(vertIdx)(0) := data }  // r
    when(addr === 4.U) { colorRegs(vertIdx)(1) := data }  // g
    when(addr === 5.U) { colorRegs(vertIdx)(2) := data }  // b
  }

  // --- Setup shader output snooping (Step 29.2) ---
  // Setup shader writes: r0–r7 = scaled edge components + area + inv_area.
  when(io.pipeWrite.en && state === sWaitSetup) {
    for (i <- 0 until 8) {
      when(io.pipeWrite.addr === i.U) {
        setupRegs(i) := io.pipeWrite.data(15, 0)
      }
    }
  }
}
