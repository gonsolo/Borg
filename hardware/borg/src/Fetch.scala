// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** An instruction-cache line's state, when it lives in a RAM. */
class ICacheLine(tagBits: Int) extends Bundle {
  val valid = Bool()
  val epoch = UInt(ICacheLine.EpochBits.W)
  val far   = Bool()
  val tag   = UInt(tagBits.W)
}
object ICacheLine { val EpochBits = 8 }

class ImemLoad extends Bundle {
  val addr = UInt(16.W)
  val data = UInt(32.W)
}

class FetchIO(cfg: BorgConfig) extends Bundle {
  val pc         = Input(UInt(cfg.pcBits.W))   // whose word `word` holds next cycle
  val fromRast   = Input(Bool())               // the raster ROM's program, not IMEM's
  val fromSetup  = Input(Bool())               // the setup ROM's
  val drawMode   = Input(Bool())
  val drawSingle = Input(Bool())
  val setupShort = Input(Bool())
  val word       = Output(UInt(32.W))
  val ready      = Output(Bool())              // `word` is the program's word at its PC
  val missOk     = Input(Bool())               // a miss may fill now: running, stages empty
  val codeBase   = Input(UInt(GpuMemIO.AddrBits.W))
  val flush      = Input(Bool())
  val load       = Input(Valid(new ImemLoad))  // DMA or MMIO
  val mem        = new GpuMemIO
  val filling    = Output(Bool())              // the cache owns the memory port
}

/** The next instruction word: IMEM, which is also the instruction cache's data array, or one of
  * the baked programs (BorgRasterRom, BorgSetupRom). */
class Fetch(cfg: BorgConfig) extends Module {
  val io = IO(new FetchIO(cfg))

  private val instructionMemory = SyncReadMem(cfg.maxInstructions, UInt(32.W))
  // The ROMs are read at the registered PC, matching IMEM's one-cycle read.
  private val rasterRom     = VecInit(BorgRasterRom.instructions.map(_.U(32.W)))
  private val setupRom      = VecInit(BorgSetupRom.instructions.map(_.U(32.W)))
  private val drawRasterRom = VecInit(BorgRasterRom.drawInstructions.map(_.U(32.W)))
  private val nextPC = io.pc
  private val rasterRomAddrReg = RegNext(nextPC)
  private val fetchRast = io.fromRast; private val fetchSetup = io.fromSetup
  private val rasterShort = io.drawSingle && rasterRomAddrReg === BorgRasterRom.drawSampleDepthPc.U
  private val rasterWord =
    Mux(io.drawMode, Mux(rasterShort, 0.U, drawRasterRom(rasterRomAddrReg)), rasterRom(rasterRomAddrReg))
  private val setupStop = io.setupShort && rasterRomAddrReg === BorgSetupRom.shortPc.U
  io.word := Mux(fetchRast, rasterWord,
    Mux(fetchSetup, Mux(setupStop, 0.U, setupRom(rasterRomAddrReg)), instructionMemory.read(imemIndex(nextPC))))

  io.mem.req := false.B; io.mem.addr := 0.U; io.mem.wr := false.B; io.mem.wdata := 0.U; io.mem.wlen := 1.U

  // One write port for the cache fill and DMA/MMIO loads, which never run at the same time. A load
  // past IMEM is dropped: the cache fetches the rest of the program.
  private val fillWriteEn   = WireDefault(false.B)
  private val fillWriteIdx  = WireDefault(0.U(log2Ceil(cfg.maxInstructions).W))
  private val fillWriteData = WireDefault(0.U(32.W))
  private val extWriteEn  = io.load.valid && io.load.bits.addr < cfg.maxInstructions.U
  private val extWriteIdx = io.load.bits.addr(log2Ceil(cfg.maxInstructions) - 1, 0)
  when(extWriteEn || fillWriteEn) {
    instructionMemory.write(Mux(fillWriteEn, fillWriteIdx, extWriteIdx), Mux(fillWriteEn, fillWriteData, io.load.bits.data))
  }
  wireICache()

  /** IMEM is direct-mapped over the program: word `pc` lives in line `pc mod L` (L = the largest
    * power of two within IMEM), except that every PC inside IMEM keeps its own word. */
  private def imemIndex(pc: UInt): UInt = {
    val n  = cfg.maxInstructions
    val lb = cfg.icacheLinesLog2
    if ((1 << lb) == n) pc(lb - 1, 0)
    else Mux(pc < n.U, pc(log2Ceil(n) - 1, 0), pc(lb - 1, 0))
  }

  /** Shader instruction cache with IMEM as its data array. A line is invalid, near (the word of the
    * PC equal to its index, where DMA and MMIO loads put it) or far (a PC past IMEM, tagged); only
    * the low L lines go far. Any PC can miss, so the whole program must be in DRAM at codeBase.
    * A flush invalidates every line and the loads that follow prefill it; lines reset valid. An
    * MMIO-loaded program longer than IMEM must write CODE_BASE before its IMEM words.
    */
  private def wireICache(): Unit = {
    val pc      = rasterRomAddrReg
    val inImem  = pc < cfg.maxInstructions.U

    val sIdle :: sFetch :: sSettle :: Nil = Enum(3)
    val state  = RegInit(sIdle)
    val missPc = RegInit(0.U(cfg.pcBits.W))
    val g = io.mem
    val fill = state === sFetch && g.ready
    val lineHit =
      if (cfg.icacheMetaInRam) icacheLinesRam(pc, inImem, fill, missPc)
      else icacheLinesFlops(pc, inImem, fill, missPc)
    val hit = fetchRast || fetchSetup || lineHit
    io.ready := hit && state === sIdle
    io.filling := state === sFetch

    when(state === sIdle && io.missOk && !hit) {
      missPc := pc
      state  := sFetch
      if (BorgDebug.trace) printf("[ICACHE] miss pc=%d\n", pc)
    }
    when(state === sFetch) {
      g.req  := true.B
      g.wr   := false.B
      g.addr := (io.codeBase + (missPc << 2))(GpuMemIO.AddrBits - 1, 0)
      when(g.ready) {
        fillWriteEn   := true.B
        fillWriteIdx  := imemIndex(missPc)
        fillWriteData := g.data
        state := sSettle
      }
    }
    // IMEM returns the filled word one read later; hold issue for that cycle.
    when(state === sSettle) { state := sIdle }
  }

  /** Line state in flops, for a small IMEM. True if the line holds pc's word. */
  private def icacheLinesFlops(pc: UInt, inImem: Bool, fill: Bool, missPc: UInt): Bool = {
    val n       = cfg.maxInstructions
    val lb      = cfg.icacheLinesLog2
    val L       = 1 << lb
    val valid    = RegInit(VecInit(Seq.fill(n)(true.B)))
    val isFar    = RegInit(VecInit(Seq.fill(L)(false.B)))
    val farTag   = Reg(Vec(L, UInt((cfg.pcBits - lb).W)))
    val line   = pc(lb - 1, 0)
    val nearOk = if (L < n) Mux(pc >= L.U, true.B, !isFar(line)) else !isFar(line)
    val hit = valid(imemIndex(pc)) && Mux(inImem, nearOk,
      isFar(line) && farTag(line) === pc(cfg.pcBits - 1, lb))
    when(fill) {
      val l = missPc(lb - 1, 0)
      valid(imemIndex(missPc)) := true.B
      when(missPc < n.U) {
        when(missPc < L.U) { isFar(l) := false.B }   // a near word (re)fetched
      }.otherwise {
        isFar(l)  := true.B
        farTag(l) := missPc(cfg.pcBits - 1, lb)
      }
    }
    // A load in the same cycle as a flush wins.
    when(io.flush) {
      valid.foreach(_ := false.B)
      isFar.foreach(_ := false.B)
    }
    when(extWriteEn) {
      valid(extWriteIdx) := true.B
      when(extWriteIdx < L.U) { isFar(extWriteIdx(lb - 1, 0)) := false.B }
    }
    hit
  }

  /** Line state in a RAM, for a large IMEM. A line is valid only in the epoch it was written in, so
    * a flush bumps the epoch; a sweep rewrites every line after reset and when the epoch wraps. */
  private def icacheLinesRam(pc: UInt, inImem: Bool, fill: Bool, missPc: UInt): Bool = {
    val n     = cfg.maxInstructions
    val lb    = cfg.icacheLinesLog2
    val lines = SyncReadMem(n, new ICacheLine(cfg.pcBits - lb))
    val epoch      = RegInit(0.U(ICacheLine.EpochBits.W))
    val sweeping   = RegInit(true.B)
    val sweepValid = RegInit(true.B)
    val sweepIdx   = RegInit(0.U(log2Ceil(n).W))
    val flush = io.flush

    val cur = lines.read(imemIndex(nextPC))
    val unswept = RegNext(sweeping && imemIndex(nextPC) >= sweepIdx)
    val unsweptValid = RegNext(sweepValid && !flush)
    val hit = Mux(unswept, unsweptValid && inImem,
      cur.valid && cur.epoch === epoch && Mux(inImem, !cur.far, cur.far && cur.tag === pc(cfg.pcBits - 1, lb)))

    val w = Wire(new ICacheLine(cfg.pcBits - lb))
    w.valid := true.B
    w.epoch := Mux(flush, epoch + 1.U, epoch)
    w.far   := fill && !(missPc < n.U)
    w.tag   := missPc(cfg.pcBits - 1, lb)
    val step = sweeping && !fill && !extWriteEn
    when(step) { w.valid := sweepValid && !flush; w.far := false.B }
    when(fill || extWriteEn || step) {
      lines.write(Mux(fill, imemIndex(missPc), Mux(extWriteEn, extWriteIdx, sweepIdx)), w)
    }
    when(step) {
      sweepIdx := sweepIdx + 1.U
      when(sweepIdx === (n - 1).U) { sweeping := false.B }
    }
    when(flush) {
      epoch := epoch + 1.U
      when(sweeping) { sweepValid := false.B }   // the rest is stale now too
      when(epoch.andR) { sweeping := true.B; sweepValid := false.B; sweepIdx := 0.U }
    }
    hit
  }
}
