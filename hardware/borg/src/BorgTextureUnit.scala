// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** IO bundle for BorgTextureUnit.
  *
  * Designed as a future insertion point for texture compression (e.g. BC1/ETC).
  * A decompressor would sit between [[gpuMem]] and [[fragColor]] without
  * touching the caller's interface.
  *
  * Texel memory layout (8 bytes per texel, stride = power-of-2):
  *   Word 0 [offset +0]: { G[15:0], R[15:0] }   — both R and G packed
  *   Word 1 [offset +4]: { pad[15:0], B[15:0] }  — B only
  *
  * Read order: B first (offset +4), then RG (offset +0).
  * The texture address (baseAddr + mortonIndex×8) is latched into tex_base
  * when io.start fires (sIdle state), so DRAM reads always use the stable
  * tex_base value — the B-first order is preserved for historical reasons
  * but is no longer required for address stability.
  */
class BorgTextureUnitIO extends Bundle {
  val start     = Input(Bool())           // one-cycle trigger from dispatcher
  val done      = Output(Bool())          // one-cycle completion pulse
  val texConfig = new TexConfigIO         // mortonIndex, baseAddr, en
  val gpuMem    = new GpuMemIO            // DRAM read port
  val fragColor = Output(new ColorZ(16))  // fetched R/G/B; Z is always zero here
}

/** Autonomous DRAM texel fetch unit (Step 25.3e).
  *
  * Issues two sequential read requests over [[GpuMemIO]], assembles the
  * 16-bit R, G, B channels, and pulses [[done]] for one cycle when finished.
  *
  * This module is a natural insertion point for texture compression:
  * add decompression logic between the raw [[gpuMem.data]] reads and
  * the [[fragColor]] outputs without changing the caller's interface.
  *
  * FSM:
  *   sIdle → sReadB (fetch B word, offset +4)
  *         → sReadRG (fetch RG word, offset +0)
  *         → sDone (pulse done, return to sIdle)
  */
class BorgTextureUnit extends Module {
  val io = IO(new BorgTextureUnitIO)

  // --- FSM ---
  val sIdle :: sReadB :: sReadRG :: sDone :: Nil = Enum(4)
  val state = RegInit(sIdle)

  // --- Result registers ---
  val frag_r = RegInit(0.U(16.W))
  val frag_g = RegInit(0.U(16.W))
  val frag_b = RegInit(0.U(16.W))

  // --- Base address: latched on start so the FTEX mortonIndex override ---
  // --- (valid for one cycle only) is captured for both DRAM reads.     ---
  val tex_base = RegInit(0.U(20.W))

  // --- Defaults ---
  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U
  io.gpuMem.wlen  := 1.U   // texture unit only reads
  io.done         := false.B

  io.fragColor.r := frag_r
  io.fragColor.g := frag_g
  io.fragColor.b := frag_b
  io.fragColor.z := 0.U  // Z is pass-through from shader snoop in dispatcher

  switch(state) {

    is(sIdle) {
      when(io.start) {
        val addr = io.texConfig.baseAddr +& (io.texConfig.mortonIndex << 3)
        tex_base := addr
        if (BorgDebug.trace) printf("[TEX] START baseAddr=0x%x morton=%d texAddr=0x%x\n",
          io.texConfig.baseAddr, io.texConfig.mortonIndex, addr)
        state := sReadB
      }
    }

    // Read 0: B word first (offset +4) — keeps Morton address stable
    is(sReadB) {
      io.gpuMem.req  := true.B
      io.gpuMem.addr := tex_base | 4.U
      when(io.gpuMem.ready) {
        frag_b := io.gpuMem.data(15, 0)
        if (BorgDebug.trace) printf("[TEX] READ-B addr=0x%x data=0x%x B=0x%x\n",
          tex_base | 4.U, io.gpuMem.data, io.gpuMem.data(15, 0))
        state  := sReadRG
      }
    }

    // Read 1: RG word (offset +0) — safe to overwrite R/G now
    is(sReadRG) {
      io.gpuMem.req  := true.B
      io.gpuMem.addr := tex_base
      when(io.gpuMem.ready) {
        frag_r := io.gpuMem.data(15, 0)
        frag_g := io.gpuMem.data(31, 16)
        if (BorgDebug.trace) printf("[TEX] READ-RG addr=0x%x data=0x%x R=0x%x G=0x%x\n",
          tex_base, io.gpuMem.data, io.gpuMem.data(15, 0), io.gpuMem.data(31, 16))
        state  := sDone
      }
    }

    is(sDone) {
      io.done := true.B
      if (BorgDebug.trace) printf("[TEX] DONE R=0x%x G=0x%x B=0x%x\n", frag_r, frag_g, frag_b)
      state   := sIdle
    }
  }
}
