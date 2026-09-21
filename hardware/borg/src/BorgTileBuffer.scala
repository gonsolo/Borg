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
  * per sample plane by default, or a narrower one when `colorBits` requests
  * on-the-fly R/G/B quantization -- see the class-level doc below.
  * This avoids the ~256 FF cost of register-based Z storage.
  * Z comparison for Step 11.5 will use a 1-cycle BRAM read in the FSM.
  *
  * Clear writes FP16_MAX_DEPTH for Z and 0 for RGB sequentially (16 cycles).
  *
  * Tile index: tile_idx = iter_x[1:0] | (iter_y[1:0] << 2)
  *
  * Step 11 of the Borg GPU roadmap.
  */

/** Control for `msaaMultiPass`. The tile is rendered once per sample; this
  * bundle says which sample a pass is for, folds a finished pass's colour
  * into the accumulator, and switches the read port to the resolved average
  * for the flush. See BorgConfig.msaaMultiPass for the conformance argument.
  */
class TilePassIO(val samples: Int) extends Bundle {
  /** Sample this pass renders. Coverage, depth and stencil all act on it
    * alone; every other sample's bit in `write.coverage` is ignored. */
  val sampleIdx  = Input(UInt(log2Up(samples).W))
  /** One-cycle pulse: sweep the tile folding the working colour into the
    * accumulator. Runs after every pass EXCEPT the last. */
  val accumEn    = Input(Bool())
  /** True on the first accumulate of a tile: the sweep initialises the
    * accumulator instead of adding, so it needs no separate clear. */
  val accumFirst = Input(Bool())
  /** High while an accumulate sweep is in flight. */
  val accumBusy  = Output(Bool())
  /** Read port returns the resolved average instead of the working plane.
    * Asserted for the flush, after the final pass. */
  val resolve    = Input(Bool())
}

class BorgTileBufferIO(val dataBits: Int = 16, val samples: Int = 1,
                       val hasStencil: Boolean = false,
                       val hasAlpha: Boolean = false,
                       val multiPass: Boolean = false) extends Bundle {
  // Write port (from rasterizer auto-write or MMIO)
  val write = Flipped(new TileWriteIO(samples))

  // Read port (for tile flush - 2-cycle latency: BRAM + hold reg)
  val read  = Flipped(new TileReadIO(dataBits, samples))

  // Clear (resets all entries: Z to FP16_MAX_DEPTH, RGB to 0)
  val clear = Flipped(new TileClearIO)

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

  // --- Multi-pass MSAA control (BorgConfig.msaaMultiPass) ----------------
  val pass = if (multiPass) Some(new TilePassIO(samples)) else None
}

/** @param colorBits Stored R/G/B width, independent of `dataBits` (the port
  *                  width, always full FP16). Default `dataBits` (off) keeps
  *                  every existing target bit-identical; `colorBits = 8`
  *                  quantizes R/G/B through [[ColorQuantize]] on write and
  *                  dequantizes on read, entirely internally -- the write/
  *                  read/clear ports above are unchanged FP16 `ColorZ`
  *                  either way, so nothing outside this module needs to know.
  *                  Z is never narrowed this way -- see BorgConfig.tileColorBits's
  *                  own doc for why.
  */
class BorgTileBuffer(val dataBits: Int = 16, val samples: Int = 1, val colorBits: Int = 16,
                     val hasStencil: Boolean = false, val hasAlpha: Boolean = false,
                     val multiPass: Boolean = false) extends Module {
  require(colorBits == dataBits || colorBits == 8,
          s"colorBits must equal dataBits (off) or be 8 (ColorQuantize), got $colorBits")
  // Multi-pass averages STORED colour arithmetically. That is only meaningful
  // when the stored form is UNORM8 integers; averaging FP16 bit patterns is
  // not averaging the colours they denote.
  require(!multiPass || colorBits < dataBits,
          "msaaMultiPass requires quantized tile colour (tileColorBits = 8): " +
          "the accumulator averages stored integers, not FP16 bit patterns")
  require(!multiPass || samples > 1, "msaaMultiPass is meaningless at samples == 1")
  val io = IO(new BorgTileBufferIO(dataBits, samples, hasStencil, hasAlpha, multiPass))

  val FP16_MAX_DEPTH_VAL = 0x7BFF  // Scala constant
  val FP16_MAX_DEPTH = FP16_MAX_DEPTH_VAL.U(dataBits.W)
  val TILE_SIZE = 16  // 4×4
  val SAMPLE_BITS = new ColorZ(dataBits).getWidth   // 64 bits per sample, at the ports

  // Narrow storage encode/decode -- compile-time branch, so the colorBits ==
  // dataBits (default) path emits exactly the same hardware as before this
  // parameter existed, not merely equivalent hardware.
  val narrowColor  = colorBits < dataBits
  val STORED_BITS  = if (narrowColor) 3 * colorBits + dataBits else SAMPLE_BITS

  /** ColorZ(dataBits) -> the narrower stored bit pattern. */
  def encodeStored(cz: ColorZ): UInt =
    if (!narrowColor) cz.asUInt
    else Cat(ColorQuantize.quantize8(cz.r), ColorQuantize.quantize8(cz.g),
             ColorQuantize.quantize8(cz.b), cz.z)

  /** The stored colour channels as raw integers, for the multi-pass
    * accumulator: averaging must happen on the UNORM8 values, never on the
    * FP16 patterns they dequantize to. */
  def storedChannels(bits: UInt): (UInt, UInt, UInt) = {
    require(narrowColor, "storedChannels is only meaningful for quantized storage")
    (bits(3 * colorBits + dataBits - 1, 2 * colorBits + dataBits),
     bits(2 * colorBits + dataBits - 1, colorBits + dataBits),
     bits(colorBits + dataBits - 1, dataBits))
  }

  /** The stored bit pattern -> ColorZ(dataBits), reconstructed for every
    * reader outside this module (which only ever sees full-width FP16). */
  def decodeStored(bits: UInt): ColorZ = {
    val cz = Wire(new ColorZ(dataBits))
    if (!narrowColor) {
      cz := bits.asTypeOf(new ColorZ(dataBits))
    } else {
      val r8 = bits(3 * colorBits + dataBits - 1, 2 * colorBits + dataBits)
      val g8 = bits(2 * colorBits + dataBits - 1, colorBits + dataBits)
      val b8 = bits(colorBits + dataBits - 1, dataBits)
      cz.r := ColorQuantize.dequantize8(r8)
      cz.g := ColorQuantize.dequantize8(g8)
      cz.b := ColorQuantize.dequantize8(b8)
      cz.z := bits(dataBits - 1, 0)
    }
    cz
  }

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
  // At msaaMultiPass the tile is rendered once per sample, so only ONE plane
  // is ever live: the pass's own. The other samples live in the accumulator
  // as a running colour sum, which is 3 channels wide instead of a whole
  // ColorZ. 4x MSAA storage drops 3,584 -> 1,376 bits.
  val planes = if (multiPass) 1 else samples
  val rgbzMems = Seq.fill(planes)(SyncReadMem(TILE_SIZE, UInt(STORED_BITS.W)))

  // Per-channel accumulator width: enough headroom to sum `samples` values.
  val ACC_CH   = colorBits + log2Ceil(samples)
  val ACC_BITS = 3 * ACC_CH
  val accumMem = if (multiPass) Some(SyncReadMem(TILE_SIZE, UInt(ACC_BITS.W))) else None

  // --- Accumulate sweep -------------------------------------------------
  // One pass over the tile folding the working plane's colour into the
  // accumulator, run after every pass but the LAST. Sample 0 is rendered
  // last deliberately: its colour and Z are then still in the working plane
  // at flush time, which is what the sample-zero depth resolve needs, so the
  // accumulator never has to carry Z.
  val accRun     = RegInit(false.B)
  val accCtr     = RegInit(0.U(log2Ceil(TILE_SIZE + 1).W))
  val accFirstReg = RegInit(false.B)
  if (multiPass) {
    val p = io.pass.get
    when(p.accumEn && !accRun) {
      accRun := true.B; accCtr := 0.U; accFirstReg := p.accumFirst
    }.elsewhen(accRun) {
      accCtr := accCtr + 1.U
      when(accCtr === (TILE_SIZE - 1).U) { accRun := false.B }
    }
  }
  // The write lags the read by one cycle (SyncReadMem latency).
  val accRunDel = RegNext(accRun, false.B)
  val accCtrDel = RegNext(accCtr, 0.U)

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
  val clearWordReg = RegInit(encodeStored(clearInit))
  val clearWord = clearWordReg

  // --- Clear logic ---
  when(io.clear.en && !clearing) {
    clearCounter := 0.U
    clearWordReg := encodeStored(io.clear.color)
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

  // At multiPass the read port is shared with the accumulate sweep, which
  // walks the tile on its own counter.
  val rdAddr = if (multiPass) Mux(accRun, accCtr, io.read.idx) else io.read.idx
  val rdEn   = if (multiPass) (effectiveReadEn || accRun) else effectiveReadEn

  /** Coverage bit that gates a write into plane `s`. At multiPass there is
    * one plane and it belongs to the pass's own sample, so every other bit
    * of `write.coverage` is ignored rather than written elsewhere. */
  def coverageFor(s: Int): Bool =
    if (multiPass) io.write.coverage(io.pass.get.sampleIdx) else io.write.coverage(s)

  val rgbzRead = VecInit(rgbzMems.zipWithIndex.map { case (mem, s) =>
    when(clearing) {
      mem.write(clearCounter, clearWord)
    }.elsewhen(io.write.en && coverageFor(s)) {
      mem.write(io.write.idx, encodeStored(io.write.data))
    }
    mem.read(rdAddr, rdEn)
  })

  // Accumulator read rides the same address, so a resolving read returns the
  // running sum alongside the working plane in the same cycle.
  val accRead = accumMem.map(_.read(rdAddr, rdEn)).getOrElse(0.U)

  if (multiPass) {
    val (wr, wg, wb) = storedChannels(rgbzRead(0))
    val pr = accRead(3 * ACC_CH - 1, 2 * ACC_CH)
    val pg = accRead(2 * ACC_CH - 1, 1 * ACC_CH)
    val pb = accRead(1 * ACC_CH - 1, 0)
    when(accRunDel) {
      accumMem.get.write(accCtrDel, Cat(
        Mux(accFirstReg, wr.pad(ACC_CH), pr + wr),
        Mux(accFirstReg, wg.pad(ACC_CH), pg + wg),
        Mux(accFirstReg, wb.pad(ACC_CH), pb + wb)))
    }
    io.pass.get.accumBusy := accRun || accRunDel
  }

  when(effectiveReadEn) {
    if (BorgDebug.trace) printf("[TBUF] READ-REQ slot=%d\n", io.read.idx)
  }

  val readDataHeld = RegInit(0.U.asTypeOf(Vec(samples, new ColorZ(dataBits))))

  // Capture BRAM output one cycle after readEn pulse
  val readEnDel = RegNext(effectiveReadEn, false.B)
  when(readEnDel) {
    if (!multiPass) {
      readDataHeld := VecInit(rgbzRead.map(decodeStored))
    } else {
      // One plane, replicated across the read port so the dispatcher's
      // per-sample indexing (io.tileRead.data(dstIdx)) keeps working
      // unchanged -- during a pass every index names the same live sample.
      //
      // On `resolve` (the flush, after the final pass) the colour becomes
      // the MSAA average instead: accumulator + working plane, divided by
      // `samples`. Z is the working plane's, which is sample 0's because
      // sample 0 is rendered last -- the sample-zero depth resolve, for free.
      val work = decodeStored(rgbzRead(0))
      val (wr, wg, wb) = storedChannels(rgbzRead(0))
      val shift = log2Ceil(samples)
      val avgR = (accRead(3 * ACC_CH - 1, 2 * ACC_CH) +& wr) >> shift
      val avgG = (accRead(2 * ACC_CH - 1, 1 * ACC_CH) +& wg) >> shift
      val avgB = (accRead(1 * ACC_CH - 1, 0)           +& wb) >> shift
      val res = Wire(new ColorZ(dataBits))
      when(io.pass.get.resolve) {
        res.r := ColorQuantize.dequantize8(avgR(colorBits - 1, 0))
        res.g := ColorQuantize.dequantize8(avgG(colorBits - 1, 0))
        res.b := ColorQuantize.dequantize8(avgB(colorBits - 1, 0))
        res.z := work.z
      }.otherwise {
        res := work
      }
      readDataHeld := VecInit(Seq.fill(samples)(res))
    }
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
    // One plane per LIVE sample: at multiPass that is one, because stencil
    // never leaves the tile buffer (BorgTileFlusher reads colour and Z only),
    // so a pass's stencil is purely transient -- there is nothing to preserve
    // for the resolve.
    val stencilMems = Seq.fill(planes)(SyncReadMem(TILE_SIZE, UInt(8.W)))

    // Latched for the same reason as clearWordReg: io.stencilClear is a
    // one-cycle pulse from a mux, but the clear writes span 16 cycles.
    val stencilClearReg = RegInit(0.U(8.W))
    when(io.clear.en && !clearing) { stencilClearReg := io.stencilClear.get }

    val stencilRead = VecInit(stencilMems.zipWithIndex.map { case (mem, s) =>
      when(clearing) {
        mem.write(clearCounter, stencilClearReg)
      }.elsewhen(if (multiPass) io.stencilWriteMask.get(io.pass.get.sampleIdx)
                 else io.stencilWriteMask.get(s).asBool) {
        // The mask carries the coverage/discard decision per sample, which is
        // why it is a mask and not a single enable -- a fragment can pass the
        // stencil test for some samples and fail it for others. At multiPass
        // only the pass's own bit can select this plane.
        mem.write(io.write.idx, io.stencilWrite.get)
      }
      mem.read(rdAddr, rdEn)
    })

    val stencilHeld = RegInit(VecInit(Seq.fill(samples)(0.U(8.W))))
    when(readEnDel) {
      if (!multiPass) stencilHeld := stencilRead
      else stencilHeld := VecInit(Seq.fill(samples)(stencilRead(0)))
    }
    io.stencilRead.get := stencilHeld
  }

  // --- Optional destination-alpha plane ----------------------------------
  //
  // Same shape as the stencil plane and for the same reasons (shared index/
  // enable/clear, per-sample memories rather than a write mask). What makes
  // it worth having: without it every blend factor involving DST_ALPHA has
  // to assume an opaque destination, so an application compositing
  // translucent geometry into a translucent buffer gets the wrong answer
  // with no way to tell.
  if (hasAlpha) {
    // One plane per live sample, for the same reason as stencil: destination
    // alpha feeds blending inside the tile and is never flushed.
    val alphaMems = Seq.fill(planes)(SyncReadMem(TILE_SIZE, UInt(8.W)))

    // RegInit 0xFF, not 0: the reset auto-clear runs before firmware writes
    // anything, and an opaque destination is what the hardware behaved as
    // before this plane existed.
    val alphaClearReg = RegInit(0xFF.U(8.W))
    when(io.clear.en && !clearing) { alphaClearReg := io.alphaClear.get }

    val alphaRead = VecInit(alphaMems.zipWithIndex.map { case (mem, s) =>
      when(clearing) {
        mem.write(clearCounter, alphaClearReg)
      }.elsewhen(io.write.en && coverageFor(s) && io.alphaWriteMask.get) {
        mem.write(io.write.idx, io.alphaWrite.get)
      }
      mem.read(rdAddr, rdEn)
    })

    val alphaHeld = RegInit(VecInit(Seq.fill(samples)(0xFF.U(8.W))))
    when(readEnDel) {
      if (!multiPass) alphaHeld := alphaRead
      else alphaHeld := VecInit(Seq.fill(samples)(alphaRead(0)))
    }
    io.alphaRead.get := alphaHeld
  }
}
