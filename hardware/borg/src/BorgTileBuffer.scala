// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

/** BorgTileBuffer — 4×4 on-chip tile buffer for RGB + Z.
  *
  * Stores fragment results on-chip during rasterization of a 4×4 tile.
  * After all pixels in the tile are processed, the CPU flushes the buffer
  * to DRAM in a batch, eliminating per-pixel DRAM round-trips.
  *
  * Storage: All 4 channels packed into a single 64-bit SyncReadMem (1 BRAM).
  * This avoids the ~256 FF cost of register-based Z storage.
  * Z comparison for Step 11.5 will use a 1-cycle BRAM read in the FSM.
  *
  * Clear writes FP16_MAX_DEPTH for Z and 0 for RGB sequentially (16 cycles).
  *
  * Tile index: tile_idx = iter_x[1:0] | (iter_y[1:0] << 2)
  *
  * Step 11 of the Borg GPU roadmap.
  */

class BorgTileBufferIO(val dataBits: Int = 16, val samples: Int = 1) extends Bundle {
  // Write port (from rasterizer auto-write or MMIO)
  val write = Flipped(new TileWriteIO(samples))

  // Read port (for tile flush - 2-cycle latency: BRAM + hold reg)
  val read  = Flipped(new TileReadIO(dataBits, samples))

  // Clear (resets all entries: Z to FP16_MAX_DEPTH, RGB to 0)
  val clear = Flipped(new TileClearIO)
}

class BorgTileBuffer(val dataBits: Int = 16, val samples: Int = 1) extends Module {
  val io = IO(new BorgTileBufferIO(dataBits, samples))

  val FP16_MAX_DEPTH_VAL = 0x7BFF  // Scala constant
  val FP16_MAX_DEPTH = FP16_MAX_DEPTH_VAL.U(dataBits.W)
  val TILE_SIZE = 16  // 4×4
  val SAMPLE_BITS = new ColorZ(dataBits).getWidth   // 64 bits per sample

  // --- RGBZ buffer: ONE SyncReadMem PER SAMPLE, each 16 × 64 bits.
  //
  // All planes share the same 4-bit `idx`, so a pixel's samples are read
  // together in one cycle — the dispatcher's serialized read→compare→write
  // depth test stays 4 cycles per lane instead of becoming 4× that — and the
  // 4-bit index shared with TileWriteIO/TileReadIO/BorgIterator.tileIndex
  // stays valid.  Cost: 1024 bits (1×) → 4096 bits (4×).
  //
  // Deliberately NOT one wide Vec-typed SyncReadMem with a write mask: that
  // form compiles, and even emits correct-looking CHIRRTL
  // (`when writeMask[i] : connect MPORT[i], ...`), but CIRCT lowered it to a
  // memory macro with NO mask port at all — `if (W0_en) Memory[W0_addr] <=
  // W0_data`, a full-width write that clobbers uncovered samples.  That
  // silently breaks MSAA (caught by BorgTileBufferTests.msaa_partial_coverage_
  // _write, which writes coverage 0b0101 and checks samples 1 and 3 are
  // preserved).  Per-sample memories make the write-enable explicit and
  // structural, with no dependence on mask inference.
  //
  // At samples==1 this is exactly one 16×64 SyncReadMem — structurally
  // identical to the pre-MSAA design, which is what keeps the single-sample
  // path (and the ASIC config) bit-identical.
  val rgbzMems = Seq.fill(samples)(SyncReadMem(TILE_SIZE, UInt(SAMPLE_BITS.W)))

  // --- Clear state machine ---
  // BRAM needs sequential writes (1 entry per cycle).
  // clearCounter starts at 0 → clearing is true for the first 16 cycles after
  // reset, so the BRAM (which has no RegInit semantics) is auto-cleared.
  val clearCounter = RegInit(0.U(5.W))
  val clearing = clearCounter < TILE_SIZE.U

  io.clear.busy := clearing

  // Clear value: LATCH the color at clear-start.  The sequencer drives
  // io.clear.color through a mux gated on io.iter.clear, which is only a
  // 1-cycle pulse — but the BRAM clear writes span the following 16 cycles.
  // Sampling io.clear.color combinationally during those writes would read the
  // mux's fall-through default (black), painting the whole background black.
  // Latching at clear-start keeps the color stable across the entire sequence.
  //
  // RegInit defaults the latch to RGB=0, Z=FP16_MAX_DEPTH so the *reset*
  // auto-clear establishes the far-plane depth convention (Z=0x7BFF).  A reset
  // value of Z=0 would be the near plane and reject every fragment until the
  // first explicit clear.
  val clearInit = (new ColorZ(dataBits)).Lit(
    _.r -> 0.U, _.g -> 0.U, _.b -> 0.U, _.z -> FP16_MAX_DEPTH
  )
  // Replicated across samples: a clear has no per-sample coverage, every sample
  // of every pixel gets the same value (matches the software MSAA clear path).
  val clearWordReg = RegInit(clearInit.asUInt)
  val clearWord = clearWordReg

  // --- Clear logic ---
  when(io.clear.en && !clearing) {
    clearCounter := 0.U
    clearWordReg := io.clear.color.asUInt
    if (BorgDebug.trace) printf("[TBUF] CLEAR-START R=0x%x G=0x%x B=0x%x Z=0x%x\n",
      io.clear.color.r, io.clear.color.g, io.clear.color.b, io.clear.color.z)
  }

  // --- Clear / write logic ---
  // A clear writes every sample plane unconditionally (no per-sample coverage);
  // a rasterizer write goes only to the planes selected by `coverage`.  Both are
  // expressed as one guarded write per plane, so the enable is structural.
  when(clearing) {
    when(clearCounter === 0.U || clearCounter === 15.U) {
      if (BorgDebug.trace) printf("[TBUF] CLEAR slot=%d raw=0x%x\n", clearCounter, clearWord)
    }
    clearCounter := clearCounter + 1.U
  }

  when(io.write.en && !clearing) {
    if (BorgDebug.trace) printf("[TBUF] WRITE slot=%d cov=0x%x R=0x%x G=0x%x B=0x%x Z=0x%x\n",
      io.write.idx, io.write.coverage, io.write.data.r, io.write.data.g,
      io.write.data.b, io.write.data.z)
  }

  // --- Read port ---
  val effectiveReadEn = io.read.en && !clearing

  val rgbzRead = VecInit(rgbzMems.zipWithIndex.map { case (mem, s) =>
    when(clearing) {
      mem.write(clearCounter, clearWord)
    }.elsewhen(io.write.en && io.write.coverage(s).asBool) {
      mem.write(io.write.idx, io.write.data.asUInt)
    }
    mem.read(io.read.idx, effectiveReadEn)
  })

  when(effectiveReadEn) {
    if (BorgDebug.trace) printf("[TBUF] READ-REQ slot=%d\n", io.read.idx)
  }

  val readDataHeld = RegInit(0.U.asTypeOf(Vec(samples, new ColorZ(dataBits))))

  // Capture BRAM output one cycle after readEn pulse
  val readEnDel = RegNext(effectiveReadEn, false.B)
  when(readEnDel) {
    readDataHeld := VecInit(rgbzRead.map(_.asTypeOf(new ColorZ(dataBits))))
    val parsed = rgbzRead(0).asTypeOf(new ColorZ(dataBits))
    if (BorgDebug.trace) printf("[TBUF] READ-DATA s0 R=0x%x G=0x%x B=0x%x Z=0x%x\n",
      parsed.r, parsed.g, parsed.b, parsed.z)
  }

  io.read.data := readDataHeld
}
