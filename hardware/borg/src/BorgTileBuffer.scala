// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

/** The 4x4 on-chip tile of RGB + Z, one SyncReadMem per sample, plus optional stencil, alpha and
  * extension planes on the same index. A clear takes 16 cycles. Index = x[1:0] | y[1:0] << 2.
  */
class BorgTileBufferIO(val dataBits: Int = 16, val samples: Int = 1,
                       val hasStencil: Boolean = false,
                       val hasAlpha: Boolean = false,
                       val zBits: Int = 16,
                       val hasExt: Boolean = false) extends Bundle {
  val write = Flipped(new TileWriteIO(samples, zBits))
  val read  = Flipped(new TileReadIO(dataBits, samples, zBits))   // 2-cycle latency
  val clear = Flipped(new TileClearIO(zBits))

  // The extra planes stay off the shared Tile*IO bundles and reuse their index and clear.
  // Stencil has its own mask: it updates even for fragments the stencil or depth test discards.
  val stencilRead      = if (hasStencil) Some(Output(Vec(samples, UInt(8.W)))) else None
  val stencilWrite     = if (hasStencil) Some(Input(UInt(8.W))) else None
  val stencilWriteMask = if (hasStencil) Some(Input(UInt(samples.W))) else None
  val stencilClear     = if (hasStencil) Some(Input(UInt(8.W))) else None

  // UNORM8 destination alpha, written with colour; alphaWriteMask is colorWriteMask's A bit.
  val alphaRead      = if (hasAlpha) Some(Output(Vec(samples, UInt(8.W)))) else None
  val alphaWrite     = if (hasAlpha) Some(Input(UInt(8.W))) else None
  val alphaWriteMask = if (hasAlpha) Some(Input(Bool())) else None
  val alphaClear     = if (hasAlpha) Some(Input(UInt(8.W))) else None

  // RAW64's word 1 (word 0 rides colour and alpha): blending needs the whole pixel, so it cannot
  // split into two passes. Writes and clears reach it only while extActive.
  val extRead   = if (hasExt) Some(Output(Vec(samples, UInt(32.W)))) else None
  val extWrite  = if (hasExt) Some(Input(UInt(32.W))) else None
  val extActive = if (hasExt) Some(Input(Bool())) else None
  val extClear  = if (hasExt) Some(Input(UInt(32.W))) else None
}

class BorgTileBuffer(val dataBits: Int = 16, val samples: Int = 1,
                     val hasStencil: Boolean = false, val hasAlpha: Boolean = false,
                     val zBits: Int = 16, val hasExt: Boolean = false) extends Module {
  val io = IO(new BorgTileBufferIO(dataBits, samples, hasStencil, hasAlpha, zBits, hasExt))

  // The far-plane clear value, 65504 (FP16 max) in either depth width.
  val FP16_MAX_DEPTH_VAL = 0x7BFF
  val FP16_MAX_DEPTH = (if (zBits == 32) 0x477FE000L else FP16_MAX_DEPTH_VAL.toLong).U(zBits.W)
  val TILE_SIZE = 16  // 4×4
  val SAMPLE_BITS = new ColorZ(dataBits, zBits).getWidth   // per sample, at the ports

  def decodeStored(bits: UInt): ColorZ = bits.asTypeOf(new ColorZ(dataBits, zBits))

  // One memory per sample, read together in one cycle. Not one Vec memory with a write mask:
  // CIRCT lowers that to an unmasked full-width write that clobbers uncovered samples.
  val rgbzMems = Seq.fill(samples)(SyncReadMem(TILE_SIZE, UInt(SAMPLE_BITS.W)))

  // Starts at 0, so the memories are cleared in the 16 cycles after reset.
  val clearCounter = RegInit(0.U(5.W))
  val clearing = clearCounter < TILE_SIZE.U

  io.clear.busy := clearing

  // Latched at clear start: io.clear.color is valid only during the one-cycle request.
  // Reset to the far plane, so the reset clear does not reject every fragment.
  val clearInit = (new ColorZ(dataBits, zBits)).Lit(
    _.r -> 0.U, _.g -> 0.U, _.b -> 0.U, _.z -> FP16_MAX_DEPTH
  )
  val clearWordReg = RegInit(clearInit.asUInt)
  val clearWord = clearWordReg

  when(io.clear.en && !clearing) {
    clearCounter := 0.U
    clearWordReg := io.clear.color.asUInt
    if (BorgDebug.trace) printf("[TBUF] CLEAR-START R=0x%x G=0x%x B=0x%x Z=0x%x\n",
      io.clear.color.r, io.clear.color.g, io.clear.color.b, io.clear.color.z)
  }

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

  // Destination alpha for DST_ALPHA blend factors; RGBA8/BGRA8 flush it as the A channel.
  val alphaReadRaw: Option[Vec[UInt]] = if (!hasAlpha) None else {
    val alphaMems = Seq.fill(samples)(SyncReadMem(TILE_SIZE, UInt(8.W)))

    // The reset clear leaves an opaque destination.
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

  val extClearReg = RegInit(0.U(32.W))
  val extClearOn  = RegInit(false.B)
  if (hasExt) when(io.clear.en && !clearing) {
    extClearReg := io.extClear.get
    extClearOn  := io.extActive.get
  }
  val extWriteNow = if (hasExt) io.write.en && !clearing && io.extActive.get else false.B

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

  val readEnDel = RegNext(effectiveReadEn, false.B)
  when(readEnDel) {
    readDataHeld := VecInit(rgbzRead.map(decodeStored))
    val parsed = decodeStored(rgbzRead(0))
    if (BorgDebug.trace) printf("[TBUF] READ-DATA s0 R=0x%x G=0x%x B=0x%x Z=0x%x\n",
      parsed.r, parsed.g, parsed.b, parsed.z)
  }

  io.read.data := readDataHeld

  // Stencil arrives with the colour/Z read the depth test already waits for.
  if (hasStencil) {
    val stencilMems = Seq.fill(samples)(SyncReadMem(TILE_SIZE, UInt(8.W)))

    val stencilClearReg = RegInit(0.U(8.W))
    when(io.clear.en && !clearing) { stencilClearReg := io.stencilClear.get }

    val stencilRead = VecInit(stencilMems.zipWithIndex.map { case (mem, s) =>
      when(clearing) {
        mem.write(clearCounter, stencilClearReg)
      }.elsewhen(io.stencilWriteMask.get(s).asBool) {
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

  alphaReadRaw.foreach { alphaRead =>
    val alphaHeld = RegInit(VecInit(Seq.fill(samples)(0xFF.U(8.W))))
    when(readEnDel) { alphaHeld := alphaRead }
    io.alphaRead.get := alphaHeld
  }
}
