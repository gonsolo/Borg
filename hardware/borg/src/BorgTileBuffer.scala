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
  * Storage: All 4 channels packed into a single 64-bit SyncReadMem (1 BRAM)
  * per sample plane.
  * This avoids the ~256 FF cost of register-based Z storage.
  * Z comparison for Step 11.5 will use a 1-cycle BRAM read in the FSM.
  *
  * Clear writes FP16_MAX_DEPTH for Z and 0 for RGB sequentially (16 cycles).
  *
  * Tile index: tile_idx = iter_x[1:0] | (iter_y[1:0] << 2)
  *
  * Step 11 of the Borg GPU roadmap.
  */

class BorgTileBufferIO(val dataBits: Int = 16, val samples: Int = 1,
                       val hasStencil: Boolean = false,
                       val hasAlpha: Boolean = false,
                       val zBits: Int = 16,
                       val hasExt: Boolean = false) extends Bundle {
  // Write port (from rasterizer auto-write or MMIO)
  val write = Flipped(new TileWriteIO(samples, zBits))

  // Read port (for tile flush - 2-cycle latency: BRAM + hold reg)
  val read  = Flipped(new TileReadIO(dataBits, samples, zBits))

  // Clear (resets all entries: Z to FP16_MAX_DEPTH, RGB to 0)
  val clear = Flipped(new TileClearIO(zBits))

  // --- Optional stencil plane (Step 50 item 10) --------------------------
  //
  // Deliberately NOT fields on TileWriteIO/TileReadIO/TileClearIO, even
  // though it is the same memory and the same index. Those three bundles are
  // shared by the flusher, the MMIO poke path and the dispatcher; adding an
  // optional field to them would force every one of those call sites to agree
  // on the flag just to stay type-compatible, for a plane only the dispatcher
  // touches. Piggybacking on `write.idx`/`read.idx`/`clear` keeps the shared
  // bundles untouched.
  //
  // `stencilWriteMask` is separate from `write.en`/`write.coverage` for a
  // real reason, not symmetry: the stencil buffer must be updated even when
  // the fragment is discarded by the stencil or depth test (see BorgStencil's
  // doc), so it cannot share the colour write's enable, and the samples it
  // updates are not the samples the colour write covers.
  val stencilRead      = if (hasStencil) Some(Output(Vec(samples, UInt(8.W)))) else None
  val stencilWrite     = if (hasStencil) Some(Input(UInt(8.W))) else None
  val stencilWriteMask = if (hasStencil) Some(Input(UInt(samples.W))) else None
  val stencilClear     = if (hasStencil) Some(Input(UInt(8.W))) else None

  // --- Optional destination-alpha plane (Step 50 item 9) -------------------
  //
  // UNORM8, matching the format blending is performed in, so no conversion
  // sits between the plane and the blend unit.
  //
  // Unlike stencil this has no write-enable of its own: alpha is written
  // exactly when colour is, on the same fragment and under the same
  // coverage. `alphaWriteMask` is colorWriteMask's A bit, which suppresses
  // the alpha store while leaving the colour store alone.
  val alphaRead      = if (hasAlpha) Some(Output(Vec(samples, UInt(8.W)))) else None
  val alphaWrite     = if (hasAlpha) Some(Input(UInt(8.W))) else None
  val alphaWriteMask = if (hasAlpha) Some(Input(Bool())) else None
  val alphaClear     = if (hasAlpha) Some(Input(UInt(8.W))) else None

  // --- Optional extension word (FlushFormat.RAW64) -----------------------
  //
  // A 64-bit colour format (RGBA16F, RG32F, ...) keeps both of its words in
  // the tile: its blending needs the whole pixel (DST_ALPHA factors read the
  // alpha while blending red), so it cannot be split into two passes. Word
  // 0 rides the colour and alpha planes like RAW32; word 1 lives here. Only
  // while `extActive` (the attachment is RAW64) do writes and clears reach
  // it. Same piggyback on the colour plane's index, enable and clear as
  // alpha and stencil.
  val extRead   = if (hasExt) Some(Output(Vec(samples, UInt(32.W)))) else None
  val extWrite  = if (hasExt) Some(Input(UInt(32.W))) else None
  val extActive = if (hasExt) Some(Input(Bool())) else None
  val extClear  = if (hasExt) Some(Input(UInt(32.W))) else None
}

class BorgTileBuffer(val dataBits: Int = 16, val samples: Int = 1,
                     val hasStencil: Boolean = false, val hasAlpha: Boolean = false,
                     val zBits: Int = 16, val hasExt: Boolean = false) extends Module {
  val io = IO(new BorgTileBufferIO(dataBits, samples, hasStencil, hasAlpha, zBits, hasExt))

  // The far-plane clear value, 65504 in either width (FP16 max): 0x7BFF, or
  // the same number as FP32 when depth is FP32 (see ColorZ).
  val FP16_MAX_DEPTH_VAL = 0x7BFF  // Scala constant
  val FP16_MAX_DEPTH = (if (zBits == 32) 0x477FE000L else FP16_MAX_DEPTH_VAL.toLong).U(zBits.W)
  val TILE_SIZE = 16  // 4×4
  val SAMPLE_BITS = new ColorZ(dataBits, zBits).getWidth   // per sample, at the ports

  def decodeStored(bits: UInt): ColorZ = bits.asTypeOf(new ColorZ(dataBits, zBits))

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
  val clearInit = (new ColorZ(dataBits, zBits)).Lit(
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

  val rdAddr = io.read.idx
  val rdEn   = effectiveReadEn
  def coverageFor(s: Int): Bool = io.write.coverage(s)

  val rgbzRead = VecInit(rgbzMems.zipWithIndex.map { case (mem, s) =>
    when(clearing) {
      mem.write(clearCounter, clearWord)
    }.elsewhen(io.write.en && coverageFor(s)) {
      mem.write(io.write.idx, io.write.data.asUInt)
    }
    mem.read(rdAddr, rdEn)
  })

  // --- Optional destination-alpha plane: storage --------------------------
  //
  // Same shape as the stencil plane and for the same reasons (shared index/
  // enable/clear, per-sample memories rather than a write mask). What makes
  // it worth having: without it every blend factor involving DST_ALPHA has
  // to assume an opaque destination, so an application compositing
  // translucent geometry into a translucent buffer gets the wrong answer
  // with no way to tell. It is also flushed: the 32-bit colour formats
  // (FlushFormat.RGBA8/BGRA8) write it out as the attachment's A channel.
  val alphaReadRaw: Option[Vec[UInt]] = if (!hasAlpha) None else {
    // One plane per sample, for the same reason as stencil.
    val alphaMems = Seq.fill(samples)(SyncReadMem(TILE_SIZE, UInt(8.W)))

    // RegInit 0xFF, not 0: the reset auto-clear runs before firmware writes
    // anything, and an opaque destination is what the hardware behaved as
    // before this plane existed.
    val alphaClearReg = RegInit(0xFF.U(8.W))
    when(io.clear.en && !clearing) { alphaClearReg := io.alphaClear.get }

    Some(VecInit(alphaMems.zipWithIndex.map { case (mem, s) =>
      when(clearing) {
        mem.write(clearCounter, alphaClearReg)
      }.elsewhen(io.write.en && coverageFor(s) && io.alphaWriteMask.get) {
        mem.write(io.write.idx, io.alphaWrite.get)
      }
      mem.read(rdAddr, rdEn)
    }))
  }

  // Extension word: its clear value and whether this clear reaches it,
  // latched at clear start like the other planes' clear values.
  val extClearReg = RegInit(0.U(32.W))
  val extClearOn  = RegInit(false.B)
  if (hasExt) when(io.clear.en && !clearing) {
    extClearReg := io.extClear.get
    extClearOn  := io.extActive.get
  }
  val extWriteNow = if (hasExt) io.write.en && !clearing && io.extActive.get else false.B

  // The extension word's storage: one 16 x 32 plane per sample.
  val extReadRaw: Option[Vec[UInt]] = if (!hasExt) None else {
    val extMems = Seq.fill(samples)(SyncReadMem(TILE_SIZE, UInt(32.W)))
    Some(VecInit(extMems.zipWithIndex.map { case (mem, s) =>
      when(clearing && extClearOn) {
        mem.write(clearCounter, extClearReg)
      }.elsewhen(extWriteNow && coverageFor(s)) {
        mem.write(io.write.idx, io.extWrite.get)
      }
      mem.read(rdAddr, rdEn)
    }))
  }

  when(effectiveReadEn) {
    if (BorgDebug.trace) printf("[TBUF] READ-REQ slot=%d\n", io.read.idx)
  }

  val readDataHeld = RegInit(0.U.asTypeOf(Vec(samples, new ColorZ(dataBits, zBits))))

  // Capture BRAM output one cycle after readEn pulse
  val readEnDel = RegNext(effectiveReadEn, false.B)
  when(readEnDel) {
    readDataHeld := VecInit(rgbzRead.map(decodeStored))
    val parsed = decodeStored(rgbzRead(0))
    if (BorgDebug.trace) printf("[TBUF] READ-DATA s0 R=0x%x G=0x%x B=0x%x Z=0x%x\n",
      parsed.r, parsed.g, parsed.b, parsed.z)
  }

  io.read.data := readDataHeld

  // --- Optional stencil plane -------------------------------------------
  //
  // One 16x8-bit SyncReadMem per sample, sharing the colour plane's index,
  // enable and clear sequence -- so a stencil read arrives on exactly the
  // same cycle as the colour/Z read the depth test already waits for, and
  // costs the dispatcher no extra states.
  //
  // Per-sample memories for the same structural reason as the colour planes
  // (see the long note above): a Vec-typed memory with a write mask lowers to
  // an unmasked full-width write in CIRCT, which would silently clobber
  // uncovered samples.
  if (hasStencil) {
    val stencilMems = Seq.fill(samples)(SyncReadMem(TILE_SIZE, UInt(8.W)))

    // Latched for the same reason as clearWordReg: io.stencilClear is a
    // one-cycle pulse from a mux, but the clear writes span 16 cycles.
    val stencilClearReg = RegInit(0.U(8.W))
    when(io.clear.en && !clearing) { stencilClearReg := io.stencilClear.get }

    val stencilRead = VecInit(stencilMems.zipWithIndex.map { case (mem, s) =>
      when(clearing) {
        mem.write(clearCounter, stencilClearReg)
      }.elsewhen(io.stencilWriteMask.get(s).asBool) {
        // The mask carries the coverage/discard decision per sample, which is
        // why it is a mask and not a single enable -- a fragment can pass the
        // stencil test for some samples and fail it for others.
        mem.write(io.write.idx, io.stencilWrite.get)
      }
      mem.read(rdAddr, rdEn)
    })

    val stencilHeld = RegInit(VecInit(Seq.fill(samples)(0.U(8.W))))
    when(readEnDel) { stencilHeld := stencilRead }
    io.stencilRead.get := stencilHeld
  }

  extReadRaw.foreach { extRead =>
    val extHeld = RegInit(VecInit(Seq.fill(samples)(0.U(32.W))))
    when(readEnDel) { extHeld := extRead }
    io.extRead.get := extHeld
  }

  // --- Optional destination-alpha plane: read port -----------------------
  //
  // Held with the same timing as the colour read.
  alphaReadRaw.foreach { alphaRead =>
    val alphaHeld = RegInit(VecInit(Seq.fill(samples)(0xFF.U(8.W))))
    when(readEnDel) { alphaHeld := alphaRead }
    io.alphaRead.get := alphaHeld
  }
}
