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
class BorgTextureUnitIO(val hasBilinear: Boolean = false) extends Bundle {
  val start     = Input(Bool())           // one-cycle trigger from dispatcher
  val done      = Output(Bool())          // one-cycle completion pulse
  val texConfig = new TexConfigIO         // mortonIndex, baseAddr, en
  val gpuMem    = new GpuMemIO            // DRAM read port
  val fragColor = Output(new ColorZ(16))  // fetched R/G/B; Z is always zero here
  // Bilinear operands. Absent unless the config asks for filtering, so a
  // nearest-only build carries no extra ports at all.
  val bilinear  = if (hasBilinear) Some(new BilinearIO) else None
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
class BorgTextureUnit(val hasBilinear: Boolean = false) extends Module {
  val io = IO(new BorgTextureUnitIO(hasBilinear))

  // --- FSM ---
  // sReadB/sReadRG are the two DRAM reads one texel costs (the layout packs
  // B in the second word). Bilinear walks them four times, once per tap, so
  // a filtered sample is 8 reads -- there is no coalescing, and the four taps
  // of a 2x2 footprint are adjacent in Morton order but still fetched
  // individually. That is the honest cost of the simplest correct version and
  // the obvious thing to optimize later.
  // sBlend exists only on the filtered path: it registers the bilinear result
  // instead of letting it fall out combinationally (see the fragColor comment
  // below). A nearest sample goes straight to sDone and is cycle-exact with
  // the pre-pipeline version.
  val sIdle :: sReadB :: sReadRG :: sBlend :: sDone :: Nil = Enum(5)
  val state = RegInit(sIdle)

  // Which of the four taps is in flight, and the UNORM8 tap store. Texels are
  // quantized as they arrive rather than kept as FP16: the weighting is
  // UNORM8 anyway, so this is 4x3x8 = 96 bits of storage instead of 192.
  val tap    = RegInit(0.U(2.W))
  val tapR   = Reg(Vec(4, UInt(8.W)))
  val tapG   = Reg(Vec(4, UInt(8.W)))
  val tapB   = Reg(Vec(4, UInt(8.W)))
  val filtering = RegInit(false.B)   // latched at start: this sample is filtered

  // --- Result registers ---
  val frag_r = RegInit(0.U(16.W))
  val frag_g = RegInit(0.U(16.W))
  val frag_b = RegInit(0.U(16.W))

  // --- Base address: latched on start so the FTEX mortonIndex override ---
  // --- (valid for one cycle only) is captured for both DRAM reads.     ---
  val tex_base = RegInit(0.U(20.W))

  // Bilinear operands, latched on start for the same reason. They come live off
  // the core's FTEX operands, so reading them on every tap put each tap's address
  // and border decision on a die-crossing path out of the core: on the 2026-09-23
  // wafer signoff (run holdscope) that was every setup violation, uniform read ->
  // frag_b/frag_g, 221 ns at max_ss_125C_3v00 of which 67 ns was wire repeaters.
  private val startNow = state === sIdle && io.start
  private case class Bil(u8: UInt, v8: UInt, fracU: UInt, fracV: UInt, log2Dim: UInt,
                         addrModeU: UInt, addrModeV: UInt, border: UInt)
  private val bil = io.bilinear.map { b =>
    Bil(RegEnable(b.u8, startNow), RegEnable(b.v8, startNow),
        RegEnable(b.fracU, startNow), RegEnable(b.fracV, startNow),
        RegEnable(b.log2Dim, startNow),
        RegEnable(b.addrModeU, startNow), RegEnable(b.addrModeV, startNow),
        RegEnable(b.border, startNow))
  }

  // Address of the tap currently in flight. For the nearest path this is
  // simply the latched tex_base; for a filtered sample each tap re-encodes
  // (u + dx, v + dy) through Morton, with the +1 neighbours clamped to the
  // last valid row/column via the shared helper -- the same clamp the
  // single-tap path needs, for the same reason (a UV of exactly 1.0 floors
  // one past the last texel, and Morton-addressing that reads unpopulated
  // memory as black).
  val (tapAddr, tapIsBorder) = if (hasBilinear) {
    val b  = bil.get
    val dx = tap(0)
    val dy = tap(1)
    // Each neighbour is wrapped by the sampler's own address mode, not just
    // clamped: under REPEAT the tap past the right edge must come from column
    // zero, which is what makes a tiling texture seamless instead of smearing
    // its last column.
    val (u, uBorder) = TexAddressMode(b.u8 +& dx, b.log2Dim, b.addrModeU)
    val (v, vBorder) = TexAddressMode(b.v8 +& dy, b.log2Dim, b.addrModeV)
    val morton = MortonEncode(u, v)
    (Mux(filtering, io.texConfig.baseAddr +& (morton << 3), tex_base),
     uBorder || vBorder)
  } else (tex_base, false.B)

  // --- Defaults ---
  io.gpuMem.req   := false.B
  io.gpuMem.addr  := 0.U
  io.gpuMem.wr    := false.B
  io.gpuMem.wdata := 0.U
  io.gpuMem.wlen  := 1.U   // texture unit only reads
  io.done         := false.B

  // The filtered result is REGISTERED (state sBlend), not combinational on the
  // tap store. It used to be combinational -- "a state would add a cycle and
  // buy nothing" -- but that made the whole blend part of the consumer's path:
  // tapB -> lerp8 -> lerp8 -> dequantize8 -> Fp16Fp32.widen -> BorgCore's
  // texResultB, in one cycle. On the 2026-09-17 wafer signoff that was THE
  // critical path: 162 ns of a 125 ns budget at max_ss_125C_3v00, 112 ns of it
  // logic (two serial lerps) and the rest repeaters over 6.65 mm of wire.
  //
  // Registering it costs one cycle per FILTERED sample only; a nearest sample
  // never enters sBlend and keeps its exact old cycle count. With filtering
  // off these are still the raw FP16 texels, bit-for-bit -- no quantize/
  // dequantize round trip is paid by a nearest sample.
  io.fragColor.r := frag_r
  io.fragColor.g := frag_g
  io.fragColor.b := frag_b
  io.fragColor.z := 0.U  // Z is pass-through from shader snoop in dispatcher

  switch(state) {

    is(sIdle) {
      when(io.start) {
        val addr = io.texConfig.baseAddr +& (io.texConfig.mortonIndex << 3)
        tex_base := addr
        tap      := 0.U
        io.bilinear.foreach { b => filtering := b.enable }
        if (BorgDebug.trace) printf("[TEX] START baseAddr=0x%x morton=%d texAddr=0x%x\n",
          io.texConfig.baseAddr, io.texConfig.mortonIndex, addr)
        state := sReadB
      }
    }

    // Read 0: B word first (offset +4) — keeps Morton address stable.
    //
    // A tap that lands on the border has no texel to read and is short
    // circuited here. Skipping the access is not merely an optimization: the
    // address would be outside the texture's allocation, so the read would
    // return whatever else happens to live there.
    is(sReadB) {
      when(tapIsBorder) {
        if (hasBilinear) {
          val bc = BorderColor.rgb8(bil.get.border)
          tapR(tap) := bc; tapG(tap) := bc; tapB(tap) := bc
          frag_r := ColorQuantize.dequantize8(bc)
          frag_g := ColorQuantize.dequantize8(bc)
          frag_b := ColorQuantize.dequantize8(bc)
        }
        when(filtering && tap =/= 3.U) {
          tap   := tap + 1.U       // stay in sReadB for the next tap
        }.otherwise {
          state := Mux(filtering, sBlend, sDone)
        }
      }.otherwise {
        io.gpuMem.req  := true.B
        io.gpuMem.addr := tapAddr | 4.U
        when(io.gpuMem.ready) {
          frag_b := io.gpuMem.data(15, 0)
          if (hasBilinear) tapB(tap) := ColorQuantize.quantize8(io.gpuMem.data(15, 0))
          if (BorgDebug.trace) printf("[TEX] READ-B addr=0x%x data=0x%x B=0x%x\n",
            tapAddr | 4.U, io.gpuMem.data, io.gpuMem.data(15, 0))
          state  := sReadRG
        }
      }
    }

    // Read 1: RG word (offset +0) — safe to overwrite R/G now
    is(sReadRG) {
      io.gpuMem.req  := true.B
      io.gpuMem.addr := tapAddr
      when(io.gpuMem.ready) {
        frag_r := io.gpuMem.data(15, 0)
        frag_g := io.gpuMem.data(31, 16)
        if (BorgDebug.trace) printf("[TEX] READ-RG addr=0x%x data=0x%x R=0x%x G=0x%x\n",
          tex_base, io.gpuMem.data, io.gpuMem.data(15, 0), io.gpuMem.data(31, 16))
        if (hasBilinear) {
          tapR(tap) := ColorQuantize.quantize8(io.gpuMem.data(15, 0))
          tapG(tap) := ColorQuantize.quantize8(io.gpuMem.data(31, 16))
          // Loop over the remaining taps; a nearest sample takes the same
          // single pass it always did, so its cycle count is unchanged.
          when(filtering && tap =/= 3.U) {
            tap   := tap + 1.U
            state := sReadB
          }.otherwise {
            state := Mux(filtering, sBlend, sDone)
          }
        } else {
          state := sDone
        }
      }
    }

    // Filtered path only: collapse the four taps into frag_* so the blend
    // terminates at a register here instead of in the consumer's timing path.
    // Reached only when `filtering`, so a nearest sample never pays this cycle.
    is(sBlend) {
      if (hasBilinear) {
        val b = bil.get
        def filtered(taps: Vec[UInt]): UInt =
          ColorQuantize.dequantize8(TexFilter.bilinear(taps, b.fracU, b.fracV))
        frag_r := filtered(tapR)
        frag_g := filtered(tapG)
        frag_b := filtered(tapB)
      }
      state := sDone
    }

    is(sDone) {
      io.done := true.B
      if (BorgDebug.trace) printf("[TEX] DONE R=0x%x G=0x%x B=0x%x\n", frag_r, frag_g, frag_b)
      state   := sIdle
    }
  }
}
