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
class TileLoadWrite(val zBits: Int = 16) extends Bundle {
  val en      = Bool()
  val idx     = UInt(4.W)
  val data    = new ColorZ(16, zBits)
  val alpha   = UInt(8.W)
  val stencil = UInt(8.W)
}

class BorgTileLoaderIO(val hasDepth: Boolean = true, val hasStencil: Boolean = true,
                       val zBits: Int = 16) extends Bundle {
  val start = Input(Bool())    // one-cycle pulse, after the tile's clear finished
  val busy  = Output(Bool())

  val aspects     = Input(new TileLoadAspects)
  val format      = Input(UInt(2.W))        // FlushFormat of the colour attachment
  val colorBase   = Input(UInt(25.W))       // this tile's colour data, as the flusher wrote it
  // Present only in a build that can store the aspect (hasDepthFlush /
  // hasStencil): there is nothing to load back otherwise.
  val depthBase   = if (hasDepth)   Some(Input(UInt(25.W))) else None  // 16 x D16_UNORM
  val stencilBase = if (hasStencil) Some(Input(UInt(25.W))) else None  // 16 x S8_UINT
  // D32_SFLOAT depth attachment (DEPTH_FORMAT), only with FP32 tile depth.
  val depthD32    = if (hasDepth && zBits == 32) Some(Input(Bool())) else None

  // Values for aspects that are NOT loaded: the tile's clear values. Every
  // loaded entry is written whole (colour, Z, alpha and stencil together),
  // so an aspect that is cleared rather than loaded must be rewritten with
  // what the clear just put there.
  val clearColor   = Input(new ColorZ(16, zBits))
  val clearAlpha   = Input(UInt(8.W))
  val clearStencil = Input(UInt(8.W))

  val gpuMem = new GpuMemIO                 // reads only

  // Tile buffer write, every sample (a load has no per-sample coverage).
  val write = Output(new TileLoadWrite(zBits))
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
                     val zBits: Int = 16) extends Module {
  val io = IO(new BorgTileLoaderIO(hasDepth, hasStencil, zBits))

  val sIdle :: sReadC0 :: sReadC1 :: sReadZ :: sReadZ1 :: sReadS :: sWrite0 :: sWrite1 :: Nil = Enum(8)
  val state = RegInit(sIdle)

  val aspects = Reg(new TileLoadAspects)
  val format  = Reg(UInt(2.W))
  val cBase   = Reg(UInt(25.W))
  val zBase   = Reg(UInt(25.W))
  val sBase   = Reg(UInt(25.W))
  val entry   = RegInit(0.U(4.W))            // first entry of the current pair (even)
  val cWord0  = Reg(UInt(32.W))
  val cWord1  = Reg(UInt(32.W))
  val zWord   = Reg(UInt(32.W))
  val zWord1  = Reg(UInt(32.W))            // D32: the pair's second entry
  val d32     = RegInit(false.B)
  val sWord   = Reg(UInt(32.W))

  val wide = FlushFormat.isWide(format)

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
    // Colour: RGB565 packs entries e, e+1 in one word at +2e; the 32-bit
    // formats take one word per entry at +4e. (The flusher writes a wide
    // tile as two 32-byte halves, which is the same contiguous layout.)
    is(sReadC0) {
      read(cBase + Mux(wide, entry << 2, entry << 1), cWord0,
           Mux(wide, sReadC1, afterColor()))
    }
    is(sReadC1) { read(cBase + ((entry + 1.U) << 2), cWord1, afterColor()) }
    // D16: one word holds the pair. D32: one word per entry.
    is(sReadZ)  { read(zBase + Mux(d32, entry << 2, entry << 1), zWord, afterZ0()) }
    is(sReadZ1) { read(zBase + ((entry + 1.U) << 2), zWord1, afterDepth()) }
    is(sReadS)  { read(sBase + entry, sWord, sWrite0) }   // entry is a multiple of 4 here
    is(sWrite0) { state := sWrite1 }
    is(sWrite1) {
      when(entry === 14.U) {
        state := sIdle
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
  val r8 = Mux(wide, Mux(bgra, word(23, 16), word(7, 0)),   expand5(half(15, 11)))
  val g8 = Mux(wide, word(15, 8),                            expand6(half(10, 5)))
  val b8 = Mux(wide, Mux(bgra, word(7, 0),   word(23, 16)), expand5(half(4, 0)))
  // RGB565 has no alpha: a format without it reads as opaque.
  val a8 = Mux(wide, word(31, 24), 255.U(8.W))

  val z16 = Mux(second, zWord(31, 16), zWord(15, 0))
  val zLoaded: UInt =
    if (zBits == 32) Mux(d32, Mux(second, zWord1, zWord), DepthQuantize.dequantize16Fp32(z16))
    else DepthQuantize.dequantize16(z16)
  // Stencil byte (entry mod 4) of the word read at the start of the quad.
  val sIdx = Cat(entry(1), second)
  val s8 = VecInit((0 until 4).map(i => sWord(8 * i + 7, 8 * i)))(sIdx)

  io.write.en  := state === sWrite0 || state === sWrite1
  io.write.idx := entry + second.asUInt
  io.write.data.r := Mux(aspects.color, ColorQuantize.dequantize8(r8), io.clearColor.r)
  io.write.data.g := Mux(aspects.color, ColorQuantize.dequantize8(g8), io.clearColor.g)
  io.write.data.b := Mux(aspects.color, ColorQuantize.dequantize8(b8), io.clearColor.b)
  io.write.data.z := Mux(aspects.depth, zLoaded, io.clearColor.z)
  io.write.alpha   := Mux(aspects.color, a8, io.clearAlpha)
  io.write.stencil := Mux(aspects.stencil, s8, io.clearStencil)

  if (BorgDebug.trace) {
    when(io.write.en) {
      printf("[LOAD] entry=%d rgb8=%x/%x/%x a=%x z16=%x s=%x\n",
        io.write.idx, r8, g8, b8, a8, z16, s8)
    }
  }
}
