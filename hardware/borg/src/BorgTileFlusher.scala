// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** Colour attachment formats the flusher can write (FLUSH_FORMAT.format).
  *
  * The first three are UNORM8, blended by BorgBlend and resolved by averaging. RAW formats hold
  * the shader's out_c0 word as bytes (byte k in channel k), never blended or resolved in hardware:
  * the shader blends through TLD and a 4x RAW attachment is stored per sample. */
object FlushFormat {
  val RGB565 = 0   // VK_FORMAT_R5G6B5_UNORM_PACK16: 2 bytes/pixel, 32 bytes/tile
  val RGBA8  = 1   // VK_FORMAT_R8G8B8A8_UNORM:      4 bytes/pixel, 64 bytes/tile
  val BGRA8  = 2   // VK_FORMAT_B8G8R8A8_UNORM:      4 bytes/pixel, 64 bytes/tile
  val RAW32  = 3   // bytes 0-3:                     4 bytes/pixel, 64 bytes/tile
  val RAW16  = 4   // bytes 0-1:                     2 bytes/pixel, 32 bytes/tile
  val RAW8   = 5   // byte 0:                        1 byte/pixel,  16 bytes/tile
  // out_c0, out_c1 (word 1 in the extension plane): 8 bytes/pixel, 128 bytes/tile
  val RAW64  = 6
  // 16 bytes/pixel in two RAW64 slices: bytes 0-7 of the 16 pixels, then 8-15.
  // Vulkan never blends a 128-bit format, which allows the split.
  val RAW128 = 7
  val Bits   = 3
  def isWide(f: UInt): Bool = f === RGBA8.U || f === BGRA8.U || f === RAW32.U
  def isByte(f: UInt): Bool = f === RAW8.U
  def is128(f: UInt): Bool = f === RAW128.U
  // RAW64, or one slice of RAW128
  def is64(f: UInt): Bool = f === RAW64.U || f === RAW128.U
  def isRaw(f: UInt): Bool = f >= RAW32.U
  // A tile offset in 32-byte (2 bytes/pixel) units, scaled to the format.
  def scaleTile(f: UInt, offset32: UInt): UInt =
    Mux(is128(f), offset32 << 3, Mux(is64(f), offset32 << 2,
      Mux(isWide(f), offset32 << 1, Mux(isByte(f), offset32 >> 1, offset32))))
}

class BorgTileFlusherIO(val dataBits: Int = 16, val samples: Int = 1,
                        val hasDepthFlush: Boolean = false,
                        val hasAlpha: Boolean = false,
                        val hasStencil: Boolean = false,
                        val zBits: Int = 16,
                        val hasExt: Boolean = false) extends Bundle {
  val start     = Input(Bool())    // one-cycle pulse
  val busy      = Output(Bool())

  val read      = new TileReadIO(dataBits, samples, zBits)

  val gpuMem    = new GpuMemIO

  // DRAM byte address of the tile: fbBase + ((ty >> 2) * tilesPerRow + (tx >> 2)) * tile size,
  // 16 pixels in Vulkan byte order.
  val tileBase  = Input(UInt(GpuMemIO.AddrBits.W))

  // Sampled at the start pulse.
  val format    = Input(UInt(FlushFormat.Bits.W))
  val srgb      = Input(Bool())          // encode R, G, B linear -> sRGB (formats 1 and 2)
  // Same timing as `read.data`; without an alpha plane the 32-bit formats write opaque.
  val alpha     = if (hasAlpha) Some(Input(Vec(samples, UInt(8.W)))) else None
  // RAW64's word 1, same timing as `read.data`.
  val ext       = if (hasExt) Some(Input(Vec(samples, UInt(32.W)))) else None

  // S8_UINT, 16 bytes per tile, after the depth burst; resolved to sample 0 like depth.
  val stencil     = if (hasStencil) Some(Input(Vec(samples, UInt(8.W)))) else None
  val stencilBase = if (hasStencil) Some(Input(UInt(GpuMemIO.AddrBits.W))) else None
  val stencilEn   = if (hasStencil) Some(Input(Bool())) else None

  // D32_SFLOAT (16 x 4 bytes, two bursts) instead of D16_UNORM; needs FP32 tile depth.
  val depthD32 = if (hasDepthFlush && zBits == 32) Some(Input(Bool())) else None

  // ATTACH_MS: store every sample instead of resolving, each `sample x tile size` past its base.
  val msStore  = if (samples > 1) Some(Input(Bool())) else None

  // Depth tile region, computed like tileBase from the depth-buffer base.
  val depthBase = if (hasDepthFlush) Some(Input(UInt(GpuMemIO.AddrBits.W))) else None
  // No depth attachment bound: skip the depth burst.
  val depthEn   = if (hasDepthFlush) Some(Input(Bool())) else None
  // No colour attachment: skip the colour burst (the fill still stages depth and stencil).
  val colorOff  = Input(Bool())
}

/** Bulk DMA from tile SRAM to DRAM: colour, then depth and stencil bursts of at most 16 halfwords
  * (LinkParams.maxBurst), so wide formats take one fill/burst round per 32-byte part.
  * Depth and stencil resolve to sample 0 (VK_RESOLVE_MODE_SAMPLE_ZERO_BIT, as v3dv). */
class BorgTileFlusher(val dataBits: Int = 16, val samples: Int = 1,
                      val hasDepthFlush: Boolean = false,
                      val hasAlpha: Boolean = false,
                      val hasStencil: Boolean = false,
                      val zBits: Int = 16,
                      val hasExt: Boolean = false) extends Module {
  val io = IO(new BorgTileFlusherIO(dataBits, samples, hasDepthFlush, hasAlpha, hasStencil, zBits, hasExt))

  val sIdle :: sFill :: sBurst :: sBurstZ :: sBurstS :: sNextSample :: Nil = Enum(6)
  val state = RegInit(sIdle)

  // 16 RGB565 pixels, or 8 RGBA8 pixels as low/high halfword pairs.
  val rgbVec   = Reg(Vec(16, UInt(16.W)))
  // Staged raw from the same read pass, converted per burst beat: one converter, not 16.
  val zVec     = if (hasDepthFlush) Some(Reg(Vec(16, UInt(zBits.W)))) else None
  val d32Reg   = RegInit(false.B)
  val zHalf    = RegInit(false.B)      // D32: which 8-entry half is bursting
  // Addresses are latched: the sequencer's inputs are only valid at the start pulse.
  val baseReg  = RegInit(0.U(GpuMemIO.AddrBits.W))
  val depthBaseReg = if (hasDepthFlush) Some(RegInit(0.U(GpuMemIO.AddrBits.W))) else None
  val sVec           = if (hasStencil) Some(Reg(Vec(16, UInt(8.W)))) else None
  val stencilBaseReg = if (hasStencil) Some(RegInit(0.U(GpuMemIO.AddrBits.W))) else None
  val formatReg = RegInit(FlushFormat.RGB565.U(FlushFormat.Bits.W))
  val srgbReg   = RegInit(false.B)
  // Per-sample store: the sample being flushed and whether more follow.
  val msReg      = RegInit(false.B)
  val sampleSel  = RegInit(0.U(math.max(1, log2Up(samples)).W))
  val lastSample = RegInit(true.B)
  val sampleIdx: UInt = if (samples > 1) sampleSel(log2Up(samples) - 1, 0) else 0.U
  val wide      = FlushFormat.isWide(formatReg)
  val byteFmt   = FlushFormat.isByte(formatReg)
  val fmt64     = FlushFormat.is64(formatReg)
  val tileBytes = Mux(FlushFormat.is128(formatReg), 256.U, Mux(fmt64, 128.U, Mux(wide, 64.U, Mux(byteFmt, 16.U, 32.U))))
  // Which 32-byte part of the colour tile is being staged/burst: the two
  // 8-pixel halves of a 4-byte format, the four 4-pixel quarters of RAW64.
  val part      = RegInit(0.U(2.W))
  val lastPart  = Mux(fmt64, part === 3.U, !wide || part === 1.U)
  val issueIdx = RegInit(0.U(5.W))  // next entry to issue a read for (0..16)
  val capIdx   = RegInit(0.U(5.W))  // next entry to capture into rgbVec (0..16)
  val burstIdx = RegInit(0.U(5.W))  // entry currently being streamed (0..15)

  // Registered so arcilator sees no combinational loop through the tile buffer.
  val readEnReg  = RegInit(false.B)
  val readIdxReg = RegInit(0.U(4.W))

  io.busy := (state =/= sIdle) || io.start

  io.read.en  := readEnReg
  io.read.idx := readIdxReg

  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U
  io.gpuMem.wlen  := 1.U

  readEnReg := false.B

  // Top N bits of round(v * 255), Vulkan's float-to-UNORM conversion. Not cheap: the MSAA resolve
  // time-shares one per channel across samples.
  def fp16ToUnorm(fp16: UInt, bits: Int): UInt = ColorQuantize.quantize8(fp16)(7, 8 - bits)

  def alphaOf(s: Int): UInt = io.alpha.map(_(s)).getOrElse(255.U(8.W))

  /** Stage one pixel, as UNORM8 channels, for tile entry `idx`, little-endian in Vulkan byte order.
    * A wide tile is staged 8 pixels at a time, so only idx(2, 0) picks its slot pair; RAW8 packs
    * two pixels per halfword. */
  def stagePixel(idx: UInt, r8: UInt, g8: UInt, b8: UInt, a8: UInt, ext: UInt): Unit = {
    when(fmt64) {
      val slot = Cat(idx(1, 0), 0.U(2.W))
      rgbVec(slot)       := Cat(g8, r8)            // word 0, bytes 1:0
      rgbVec(slot | 1.U) := Cat(a8, b8)            // word 0, bytes 3:2
      rgbVec(slot | 2.U) := ext(15, 0)             // word 1
      rgbVec(slot | 3.U) := ext(31, 16)
    }.elsewhen(wide) {
      val bgra = formatReg === FlushFormat.BGRA8.U
      val slot = Cat(idx(2, 0), 0.U(1.W))
      rgbVec(slot)        := Cat(g8, Mux(bgra, b8, r8))   // bytes 1:0
      rgbVec(slot | 1.U)  := Cat(a8, Mux(bgra, r8, b8))   // bytes 3:2
    }.elsewhen(formatReg === FlushFormat.RAW16.U) {
      rgbVec(idx) := Cat(g8, r8)
    }.elsewhen(byteFmt) {
      val h = idx(3, 1)
      rgbVec(h) := Mux(idx(0), Cat(r8, rgbVec(h)(7, 0)), Cat(rgbVec(h)(15, 8), r8))
    }.otherwise {
      rgbVec(idx) := Cat(r8(7, 3), g8(7, 2), b8(7, 3))
    }
  }

  // A read issued this cycle returns data 3 cycles later, at v3.
  val issueValid = WireDefault(false.B)
  val v1 = RegNext(issueValid, false.B)
  val v2 = RegNext(v1, false.B)
  val v3 = RegNext(v2, false.B)

  // The MSAA resolve must drain before the next read issues.
  val issueGate = WireDefault(true.B)

  // Stencil rides the colour read; capIdx still names that entry at vCap on both paths.
  // The single-sample path captures two cycles after v3, behind the sRGB encoder's tables.
  val capLat = if (samples == 1) 2 else 0
  def late[T <: Data](x: T): T = ShiftRegister(x, capLat)
  val vCap = ShiftRegister(v3, capLat, false.B, true.B)
  sVec.foreach { v =>
    val s = late(io.stencil.get(Mux(msReg, sampleIdx, 0.U)))
    when(vCap) { v(capIdx(3, 0)) := s }
  }

  def afterDepth: UInt =
    if (hasStencil) Mux(io.stencilEn.get, sBurstS, sNextSample) else sNextSample

  if (samples == 1) {
    val e = io.read.data(0)
    def enc(h: UInt): UInt = Mux(srgbReg, SrgbCodec.encode(h, piped = true), fp16ToUnorm(late(h), 8))
    val (r8, g8, b8) = (enc(e.r), enc(e.g), enc(e.b))
    val (a8, ext, z) = (late(alphaOf(0)), late(io.ext.map(_(0)).getOrElse(0.U(32.W))), late(e.z))
    when(vCap) {
      stagePixel(capIdx(3, 0), r8, g8, b8, a8, ext)
      zVec.foreach(_(capIdx(3, 0)) := z)
      capIdx := capIdx + 1.U
    }
  } else {
    // MSAA resolve, one sample per cycle through one shared converter per channel, averaged in the
    // UNORM integer domain. The resolve outlasts the 3-cycle read latency, so pipelineBusy holds
    // the next read until the pixel is staged.
    val sampleBits = log2Ceil(samples)
    val accBits    = 16 + sampleBits         // UNORM8 << 8, or an sRGB attachment's linear UNORM16

    val pipelineBusy   = RegInit(false.B)
    val resolveBusy    = RegInit(false.B)
    val resolveSample  = RegInit(0.U(sampleBits.W))
    val pendingSamples = Reg(Vec(samples, new ColorZ(dataBits, zBits)))
    val pendingAlpha   = io.alpha.map(_ => Reg(Vec(samples, UInt(8.W))))
    // RAW64's word 1: raw formats are never averaged, so only the stored sample is kept.
    val pendingExt     = io.ext.map(_ => Reg(UInt(32.W)))
    val accR = RegInit(0.U(accBits.W))
    val accG = RegInit(0.U(accBits.W))
    val accB = RegInit(0.U(accBits.W))
    val accA = io.alpha.map(_ => RegInit(0.U(accBits.W)))

    issueGate := !pipelineBusy
    when(issueValid) { pipelineBusy := true.B }
    when(state === sIdle && io.start) {
      pipelineBusy := false.B
      resolveBusy  := false.B
    }

    when(v3) {
      pendingSamples := io.read.data
      pendingAlpha.foreach(_ := io.alpha.get)
      pendingExt.foreach(_ := io.ext.get(Mux(msReg, sampleIdx, 0.U)))
      resolveBusy    := true.B
      resolveSample  := 0.U
      accR := 0.U
      accG := 0.U
      accB := 0.U
      accA.foreach(_ := 0.U)
    }

    when(resolveBusy) {
      // Per sample (msReg): one sample, stored as is. Resolved: the average.
      val s     = pendingSamples(Mux(msReg, sampleIdx, resolveSample))
      // An sRGB attachment averages in linear (16 bits) and encodes the result.
      def term(h: UInt): UInt = Mux(srgbReg, DepthQuantize.quantize16(h), Cat(fp16ToUnorm(h, 8), 0.U(8.W)))
      val rTerm = term(s.r)
      val gTerm = term(s.g)
      val bTerm = term(s.b)
      val rSum  = accR + rTerm
      val gSum  = accG + gTerm
      val bSum  = accB + bTerm
      val aOne  = pendingAlpha.map(_(Mux(msReg, sampleIdx, resolveSample)))
      val aSum  = accA.map(a => a + Cat(aOne.get, 0.U(8.W)))
      when(msReg || resolveSample === (samples - 1).U) {
        def pick(sum: UInt, one: UInt): UInt = Mux(msReg, one, (sum >> sampleBits)(15, 0))(15, 8)
        def colour(sum: UInt, one: UInt, h: UInt): UInt = {
          val avg = ((sum +& (if (sampleBits > 0) (1 << (sampleBits - 1)) else 0).U) >> sampleBits)(15, 0)   // rounded: samples * 65535 + half still fits
          Mux(srgbReg, SrgbCodec.encode(Mux(msReg, h, DepthQuantize.dequantize16(avg))), pick(sum, one))
        }
        stagePixel(capIdx(3, 0),
          colour(rSum, rTerm, s.r),
          colour(gSum, gTerm, s.g),
          colour(bSum, bTerm, s.b),
          aSum.map(a => pick(a, Cat(aOne.get, 0.U(8.W)))).getOrElse(255.U(8.W)),
          pendingExt.getOrElse(0.U(32.W)))
        zVec.foreach(_(capIdx(3, 0)) := pendingSamples(Mux(msReg, sampleIdx, 0.U)).z)
        if (BorgDebug.trace) printf("[FLUSH] entry=%d resolved from %d samples\n",
          capIdx, samples.U)
        capIdx       := capIdx + 1.U
        resolveBusy  := false.B
        pipelineBusy := false.B
      }.otherwise {
        accR := rSum
        accG := gSum
        accB := bSum
        accA.zip(aSum).foreach { case (a, sum) => a := sum }
        resolveSample := resolveSample + 1.U
      }
    }
  }

  // Entries staged per burst: the whole tile, half for 4-byte formats, a quarter for RAW64.
  val fillEnd = Mux(fmt64, Cat(part +& 1.U, 0.U(2.W)), Mux(wide && part === 0.U, 8.U, 16.U))

  val colorOffReg = RegInit(false.B)
  def afterColor: UInt = if (hasDepthFlush) Mux(io.depthEn.get, sBurstZ, afterDepth) else afterDepth

  switch(state) {
    is(sIdle) {
      when(io.start) {
        baseReg   := io.tileBase
        depthBaseReg.foreach(_ := io.depthBase.get)
        stencilBaseReg.foreach(_ := io.stencilBase.get)
        d32Reg    := io.depthD32.getOrElse(false.B)
        zHalf     := false.B
        formatReg := io.format
        srgbReg   := io.srgb
        colorOffReg := io.colorOff
        val ms = io.msStore.getOrElse(false.B)
        msReg      := ms
        if (samples > 1) { sampleSel := 0.U; lastSample := !ms }
        part      := 0.U
        issueIdx  := 0.U
        capIdx    := 0.U
        burstIdx  := 0.U
        state     := sFill
      }
    }
    is(sFill) {
      when(issueIdx < fillEnd && issueGate) {
        readEnReg  := true.B
        readIdxReg := issueIdx(3, 0)
        issueValid := true.B
        issueIdx   := issueIdx + 1.U
      }
      when(capIdx === fillEnd) {
        state := sBurst
        when(colorOffReg) {
          when(!lastPart) { part := part + 1.U; state := sFill }
            .otherwise { state := afterColor }
        }
      }
    }
    is(sBurst) {
      io.gpuMem.wr    := true.B
      io.gpuMem.addr  := baseReg + (part << 5) + sampleIdx * tileBytes
      io.gpuMem.wdata := rgbVec(burstIdx(3, 0))
      io.gpuMem.wlen  := Mux(byteFmt, 8.U, 16.U)
      when(io.gpuMem.waccept) {
        if (BorgDebug.trace) printf("[FLUSH] beat=%d colour=0x%x\n",
          burstIdx, rgbVec(burstIdx(3, 0)))
        burstIdx := burstIdx + 1.U
      }
      when(io.gpuMem.ready) {
        burstIdx := 0.U
        when(!lastPart) {
          // issueIdx/capIdx carry on into the next part.
          part  := part + 1.U
          state := sFill
        }.otherwise {
          state := afterColor
        }
      }
    }
    // Chisel's switch needs every arm unconditionally; without hasDepthFlush this one is unreachable.
    is(sBurstZ) {
      if (hasDepthFlush) {
        // D32_SFLOAT: the raw FP32, low halfword first, 8 entries per burst.
        val z = zVec.get
        val d16 = if (zBits == 32) DepthQuantize.quantize16Fp32(z(burstIdx(3, 0)))
                  else DepthQuantize.quantize16(z(burstIdx(3, 0)))
        val d32Entry = z(Cat(zHalf, burstIdx(3, 1)))
        val d32Half  = if (zBits == 32) Mux(burstIdx(0), d32Entry(31, 16), d32Entry(15, 0)) else 0.U
        io.gpuMem.wr    := true.B
        io.gpuMem.addr  := depthBaseReg.get + Mux(zHalf, 32.U, 0.U) + sampleIdx * Mux(d32Reg, 64.U, 32.U)
        io.gpuMem.wdata := Mux(d32Reg, d32Half, d16)
        io.gpuMem.wlen  := 16.U
        when(io.gpuMem.waccept) {
          if (BorgDebug.trace) printf("[FLUSH] depth beat=%d data=0x%x\n",
            burstIdx, io.gpuMem.wdata)
          burstIdx := burstIdx + 1.U
        }
        when(io.gpuMem.ready) {
          burstIdx := 0.U
          when(d32Reg && !zHalf) {
            zHalf := true.B                 // second 32 bytes of a D32 tile
          }.otherwise {
            state := afterDepth
          }
        }
      } else {
        state := sIdle
      }
    }
    // Two S8 values per halfword beat.
    is(sBurstS) {
      if (hasStencil) {
        io.gpuMem.wr    := true.B
        io.gpuMem.addr  := stencilBaseReg.get + (sampleIdx << 4)
        io.gpuMem.wdata := Cat(sVec.get(Cat(burstIdx(2, 0), 1.U(1.W))),
                               sVec.get(Cat(burstIdx(2, 0), 0.U(1.W))))
        io.gpuMem.wlen  := 8.U
        when(io.gpuMem.waccept) { burstIdx := burstIdx + 1.U }
        when(io.gpuMem.ready) { state := sNextSample }
      } else {
        state := sIdle
      }
    }
    // Per sample: the same tile again for the next sample.
    is(sNextSample) {
      when(msReg && !lastSample) {
        sampleSel  := sampleSel + 1.U
        lastSample := (if (samples > 1) sampleSel === (samples - 2).U else true.B)
        part      := 0.U
        zHalf     := false.B
        issueIdx  := 0.U
        capIdx    := 0.U
        burstIdx  := 0.U
        state     := sFill
      }.otherwise {
        state := sIdle
      }
    }
  }
}
