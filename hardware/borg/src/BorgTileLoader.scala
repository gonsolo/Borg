// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Which aspects of the attachments a tile load brings back (TILE_LOAD). */
class TileLoadAspects extends Bundle {
  val color   = Bool()
  val depth   = Bool()
  val stencil = Bool()
}

/** One whole tile-buffer entry, as the loader writes it. */
class TileLoadWrite(val zBits: Int = 16, val samples: Int = 1) extends Bundle {
  val en      = Bool()
  val idx     = UInt(4.W)
  val coverage = UInt(samples.W)        // samples written (all, or one per-sample)
  val data    = new ColorZ(16, zBits)
  val alpha   = UInt(8.W)
  val stencil = UInt(8.W)
  val ext     = UInt(32.W)                // RAW64's word 1 (the tile's extension plane)
}

class BorgTileLoaderIO(val hasDepth: Boolean = true, val hasStencil: Boolean = true,
                       val zBits: Int = 16, val samples: Int = 1) extends Bundle {
  val start = Input(Bool())    // one-cycle pulse, after the tile's clear finished
  val busy  = Output(Bool())

  val aspects     = Input(new TileLoadAspects)
  val format      = Input(UInt(FlushFormat.Bits.W))   // FlushFormat of the colour attachment
  val colorBase   = Input(UInt(GpuMemIO.AddrBits.W))       // this tile's colour data, as the flusher wrote it
  // Present only in a build that can store the aspect (hasDepthFlush /
  // hasStencil): there is nothing to load back otherwise.
  val depthBase   = if (hasDepth)   Some(Input(UInt(GpuMemIO.AddrBits.W))) else None  // 16 x D16_UNORM
  val stencilBase = if (hasStencil) Some(Input(UInt(GpuMemIO.AddrBits.W))) else None  // 16 x S8_UINT
  // D32_SFLOAT depth attachment (DEPTH_FORMAT), only with FP32 tile depth.
  val depthD32    = if (hasDepth && zBits == 32) Some(Input(Bool())) else None
  // Per-sample attachments (ATTACH_MS), the flusher's layout: each sample of
  // the tile in its own region, one tile size apart. msSample (msaaMultiPass)
  // names the one sample the tile buffer holds; otherwise every sample in
  // turn, each written to its own plane only.
  val msLoad   = if (samples > 1) Some(Input(Bool())) else None
  val msSample = if (samples > 1) Some(Input(Valid(UInt(log2Up(samples).W)))) else None

  // Values for aspects that are NOT loaded: the tile's clear values. Every
  // loaded entry is written whole (colour, Z, alpha and stencil together),
  // so an aspect that is cleared rather than loaded must be rewritten with
  // what the clear just put there.
  val clearColor   = Input(new ColorZ(16, zBits))
  val clearAlpha   = Input(UInt(8.W))
  val clearStencil = Input(UInt(8.W))
  val clearExt     = Input(UInt(32.W))

  val gpuMem = new GpuMemIO                 // reads only

  // Tile buffer write, every sample (a load has no per-sample coverage).
  val write = Output(new TileLoadWrite(zBits, samples))
}

/** Loads a tile's attachments back from DRAM into the tile buffer -- Vulkan's
  * `VK_ATTACHMENT_LOAD_OP_LOAD`, the reverse of [[BorgTileFlusher]].
  *
  * Without it a render pass could only start from a clear. That also blocked
  * everything that needs one render pass split into several renders (Borg's
  * pass 2 runs one fragment shader and one occlusion-query window), since the
  * second render had no way to continue from the first one's attachments.
  *
  * The data layout is exactly what the flusher writes, so a store followed by
  * a load is lossless for colour (RGB565 is expanded to UNORM8 by bit
  * replication, which quantizes back to the same 5/6/5 bits; quantize8
  * inverts dequantize8 exactly) and for stencil. Depth is D16_UNORM in memory
  * but FP16 in the tile buffer, so a loaded depth is the nearest FP16 -- the
  * same precision every depth test on Borg already runs at.
  *
  * Entries go two at a time: one colour word holds two RGB565 pixels (a
  * 32-bit format needs two words), one depth word holds two D16 values, and
  * one stencil word holds four. Each pair is read, then written as two whole
  * tile entries, so the staging is a few words, not a tile.
  *
  * Loaded content is written to every sample. That is exact for a
  * single-sample attachment. A multisampled attachment is stored resolved
  * (the flusher averages colour and takes depth/stencil from sample 0), so
  * loading it back gives every sample the resolved value -- storing and
  * loading all samples individually is not supported.
  */
class BorgTileLoader(val hasDepth: Boolean = true, val hasStencil: Boolean = true,
                     val zBits: Int = 16, val samples: Int = 1) extends Module {
  val io = IO(new BorgTileLoaderIO(hasDepth, hasStencil, zBits, samples))

  val sIdle :: sReadC0 :: sReadC1 :: sReadC2 :: sReadC3 :: sReadZ :: sReadZ1 :: sReadS :: sWrite0 :: sWrite1 :: Nil = Enum(10)
  val state = RegInit(sIdle)

  val aspects = Reg(new TileLoadAspects)
  val format  = Reg(UInt(FlushFormat.Bits.W))
  val cBase   = Reg(UInt(GpuMemIO.AddrBits.W))
  val zBase   = Reg(UInt(GpuMemIO.AddrBits.W))
  val sBase   = Reg(UInt(GpuMemIO.AddrBits.W))
  val entry   = RegInit(0.U(4.W))            // first entry of the current pair (even)
  val cWord0  = Reg(UInt(32.W))
  val cWord1  = Reg(UInt(32.W))
  val cWord2  = Reg(UInt(32.W))            // RAW64: word 1 of entries e, e+1
  val cWord3  = Reg(UInt(32.W))
  val zWord   = Reg(UInt(32.W))
  val zWord1  = Reg(UInt(32.W))            // D32: the pair's second entry
  val d32     = RegInit(false.B)
  val msReg      = RegInit(false.B)
  val msGiven    = RegInit(false.B)        // msaaMultiPass: one given sample
  val sampleSel  = RegInit(0.U(math.max(1, log2Up(samples)).W))
  val lastSample = RegInit(true.B)
  val sampleIdx: UInt = if (samples > 1) sampleSel(log2Up(samples) - 1, 0) else 0.U
  val sWord   = Reg(UInt(32.W))

  val wide = FlushFormat.isWide(format)
  val byteFmt = FlushFormat.isByte(format)
  val raw16 = format === FlushFormat.RAW16.U
  val fmt64 = FlushFormat.is64(format)
  // This sample's regions: one tile size apart (colour 16/32/64/128, depth
  // 32/64, stencil 16 bytes), matching the flusher.
  val cSample = cBase + sampleIdx * Mux(FlushFormat.is128(format), 256.U,
                                    Mux(fmt64, 128.U, Mux(wide, 64.U, Mux(byteFmt, 16.U, 32.U))))
  val zSample = zBase + sampleIdx * Mux(d32, 64.U, 32.U)
  val sSample = sBase + (sampleIdx << 4)

  io.busy := state =/= sIdle
  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U
  io.gpuMem.wlen  := 1.U

  /** The first read the current pair needs, starting from `from`. */
  def firstRead(e: UInt, a: TileLoadAspects): UInt =
    Mux(a.color, sReadC0,
    Mux(a.depth, sReadZ,
    Mux(a.stencil && e(1, 0) === 0.U, sReadS, sWrite0)))
  def afterColor(): UInt =
    Mux(aspects.depth, sReadZ, Mux(aspects.stencil && entry(1, 0) === 0.U, sReadS, sWrite0))
  def afterDepth(): UInt = Mux(aspects.stencil && entry(1, 0) === 0.U, sReadS, sWrite0)
  def afterZ0(): UInt = Mux(d32, sReadZ1, afterDepth())

  /** One read: present the address, and on `ready` capture and move on. */
  def read(addr: UInt, into: UInt, next: UInt): Unit = {
    io.gpuMem.req  := true.B
    io.gpuMem.addr := addr
    when(io.gpuMem.ready) { into := io.gpuMem.data; state := next }
  }

  switch(state) {
    is(sIdle) {
      when(io.start) {
        aspects := io.aspects
        // An aspect this build cannot store is never loaded.
        if (!hasDepth)   aspects.depth   := false.B
        if (!hasStencil) aspects.stencil := false.B
        format  := io.format
        d32     := io.depthD32.getOrElse(false.B)
        val ms = io.msLoad.getOrElse(false.B)
        msReg := ms
        io.msSample.foreach { m =>
          msGiven    := ms && m.valid
          sampleSel  := Mux(ms && m.valid, m.bits, 0.U)
          lastSample := !ms || m.valid
        }
        cBase   := io.colorBase
        io.depthBase.foreach(zBase := _)
        io.stencilBase.foreach(sBase := _)
        entry   := 0.U
        val a = WireDefault(io.aspects)
        if (!hasDepth)   a.depth   := false.B
        if (!hasStencil) a.stencil := false.B
        state   := firstRead(0.U, a)
      }
    }
    // Colour: the 2-byte formats pack entries e, e+1 in one word at +2e; the
    // 32-bit formats take one word per entry at +4e (the flusher writes a
    // wide tile as two 32-byte halves, which is the same contiguous
    // layout); RAW8 holds entries e..e+3 in the word at +e, read aligned.
    // RAW64 reads word 0 of e and e+1 like a 4-byte format, then their word
    // 1s: entry e is at +8e.
    is(sReadC0) {
      read(cSample + Mux(fmt64, entry << 3, Mux(wide, entry << 2, Mux(byteFmt, Cat(entry(3, 2), 0.U(2.W)), entry << 1))), cWord0,
           Mux(wide || fmt64, sReadC1, afterColor()))
    }
    is(sReadC1) { read(cSample + Mux(fmt64, (entry + 1.U) << 3, (entry + 1.U) << 2), cWord1, Mux(fmt64, sReadC2, afterColor())) }
    is(sReadC2) { read(cSample + (entry << 3) + 4.U, cWord2, sReadC3) }
    is(sReadC3) { read(cSample + ((entry + 1.U) << 3) + 4.U, cWord3, afterColor()) }
    // D16: one word holds the pair. D32: one word per entry.
    is(sReadZ)  { read(zSample + Mux(d32, entry << 2, entry << 1), zWord, afterZ0()) }
    is(sReadZ1) { read(zSample + ((entry + 1.U) << 2), zWord1, afterDepth()) }
    is(sReadS)  { read(sSample + entry, sWord, sWrite0) }   // entry is a multiple of 4 here
    is(sWrite0) { state := sWrite1 }
    is(sWrite1) {
      when(entry === 14.U) {
        when(msReg && !lastSample) {
          // Next sample: the same tile from its own regions.
          sampleSel  := sampleSel + 1.U
          lastSample := (if (samples > 1) sampleSel === (samples - 2).U else true.B)
          entry      := 0.U
          state      := firstRead(0.U, aspects)
        }.otherwise {
          state := sIdle
        }
      }.otherwise {
        entry := entry + 2.U
        state := firstRead(entry + 2.U, aspects)
      }
    }
  }

  // --- The entry being written this cycle (sWrite0: `entry`, sWrite1: +1) --
  val second = state === sWrite1
  val half   = Mux(second, cWord0(31, 16), cWord0(15, 0))    // RGB565 pixel
  val word   = Mux(second, cWord1, cWord0)                   // 32-bit pixel
  val bgra   = format === FlushFormat.BGRA8.U

  def expand5(v: UInt): UInt = Cat(v, v(4, 2))
  def expand6(v: UInt): UInt = Cat(v, v(5, 4))
  // RAW8: the entry's byte of the aligned word (entry mod 4).
  val byte8 = VecInit((0 until 4).map(i => cWord0(8 * i + 7, 8 * i)))(Cat(entry(1), second))
  val wide4 = wide || fmt64                                   // word 0 is RGBA8's bytes
  val r8 = Mux(wide4, Mux(bgra, word(23, 16), word(7, 0)),
           Mux(raw16, half(7, 0), Mux(byteFmt, byte8, expand5(half(15, 11)))))
  val g8 = Mux(wide4, word(15, 8), Mux(raw16, half(15, 8), Mux(byteFmt, 0.U, expand6(half(10, 5)))))
  val b8 = Mux(wide4, Mux(bgra, word(7, 0), word(23, 16)), Mux(raw16 || byteFmt, 0.U, expand5(half(4, 0))))
  // RGB565 has no alpha: a format without it reads as opaque. The narrow
  // RAW formats' missing bytes read as 0.
  val a8 = Mux(wide4, word(31, 24), Mux(raw16 || byteFmt, 0.U, 255.U(8.W)))

  val z16 = Mux(second, zWord(31, 16), zWord(15, 0))
  val zLoaded: UInt =
    if (zBits == 32) Mux(d32, Mux(second, zWord1, zWord), DepthQuantize.dequantize16Fp32(z16))
    else DepthQuantize.dequantize16(z16)
  // Stencil byte (entry mod 4) of the word read at the start of the quad.
  val sIdx = Cat(entry(1), second)
  val s8 = VecInit((0 until 4).map(i => sWord(8 * i + 7, 8 * i)))(sIdx)

  io.write.en  := state === sWrite0 || state === sWrite1
  // Every sample (a resolved attachment), or only the sample being loaded.
  // msaaMultiPass holds just that sample in its one plane, which takes the
  // pass's own coverage bit -- all bits cover it.
  io.write.coverage := Mux(msReg && !msGiven, UIntToOH(sampleIdx, samples), Fill(samples, 1.U(1.W)))
  io.write.idx := entry + second.asUInt
  io.write.data.r := Mux(aspects.color, ColorQuantize.dequantize8(r8), io.clearColor.r)
  io.write.data.g := Mux(aspects.color, ColorQuantize.dequantize8(g8), io.clearColor.g)
  io.write.data.b := Mux(aspects.color, ColorQuantize.dequantize8(b8), io.clearColor.b)
  io.write.data.z := Mux(aspects.depth, zLoaded, io.clearColor.z)
  io.write.alpha   := Mux(aspects.color, a8, io.clearAlpha)
  io.write.stencil := Mux(aspects.stencil, s8, io.clearStencil)
  io.write.ext     := Mux(aspects.color && fmt64, Mux(second, cWord3, cWord2), io.clearExt)

  if (BorgDebug.trace) {
    when(io.write.en) {
      printf("[LOAD] entry=%d rgb8=%x/%x/%x a=%x z16=%x s=%x\n",
        io.write.idx, r8, g8, b8, a8, z16, s8)
    }
  }
}
