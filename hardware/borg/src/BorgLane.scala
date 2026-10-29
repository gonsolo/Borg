// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

class LaneIdsIO(val cfg: BorgConfig) extends Bundle {
  val mode = Bool()
  val r30  = UInt(cfg.totalBits.W)
  val r31  = UInt(cfg.totalBits.W)
}

class BorgLaneIO(val cfg: BorgConfig) extends Bundle {
  // --- Shared control (broadcast identically to every lane) ---
  val regs        = Input(new RegIndices())
  val opFlags     = Input(new FpuOpFlags())
  // The lane's bit of the execution mask: low, it executes but none of its writes land.
  val execActive  = Input(Bool())
  // Whether any lane's bit is set: EXANY's answer.
  val execAny     = Input(Bool())
  // This lane's coverage mask, for SMASK.
  val covMask     = Input(UInt(cfg.samples.W))
  // This lane's destination word, for TLD.
  val dstWord     = Input(Vec(2, UInt(32.W)))
  val stages      = Input(UInt(cfg.cBusyLoad.W))   // BorgCore.stages
  val running     = Input(Bool())
  val fmaStart    = Input(Bool())
  val decode      = Input(Bool())   // operand reads: the instruction issuing
  val advance     = Input(Bool())   // the stages move on (not held by a stall FSM)
  val seqBusy     = Input(Bool())

  // --- Per-lane pixel coordinate ---
  val iter        = Input(new Coord(cfg.coordWidth))
  val pixelOrigin = Input(new Coord(14))   // the render window's origin
  // --- Compute or vertex stage: r30/r31 read these raw integers instead ---
  val ids         = Input(new LaneIdsIO(cfg))

  // --- Shared uniform-RAM read (done once in BorgCore) ---
  val uniformData = Input(UInt(cfg.totalBits.W)) // op_en_del-gated read result
  val funct3Del   = Input(UInt(3.W))             // delayed funct3 selecting uniform operand
  val ovA         = Input(Valid(UInt(cfg.totalBits.W))) // x0, LUI's zero, AUIPC's address
  val ovB         = Input(Valid(UInt(cfg.totalBits.W))) // x0 or an immediate

  // --- Cross-lane quad-derivative operands (broadcast by BorgCore for DDX/DDY) ---
  // The FMA adds them: a neighbour's rs1 and minus the other one's.
  val crossA      = Input(UInt(cfg.totalBits.W))
  val crossC      = Input(UInt(cfg.totalBits.W))

  // --- MMIO bus (broadcast; GPR read/write) ---
  val bus         = Flipped(new BorgBusIO())

  // --- Write-back from the shared memory/TEX FSMs (en/addr/data) ---
  val memWrite    = Flipped(new MemWritePort(5, cfg.totalBits))

  // --- Outputs ---
  val csrWrite    = new CsrWriteIO(cfg.totalBits)
  val regReadData = Output(UInt(cfg.totalBits.W))  // MMIO GPR read (lane 0 consumed)
  val recARaw     = Output(UInt(cfg.totalBits.W))  // operands for the shared FSMs
  val recBRaw     = Output(UInt(cfg.totalBits.W))
  val recCRaw     = Output(UInt(cfg.totalBits.W))  // TEX's rs3: the control word
}

object BorgLane {
  /** `x` as the instruction `n` stages on sees it: a register per stage. */
  def later[T <: Data](advance: Bool)(x: T, n: Int): T =
    (0 until n).foldLeft(x)((v, _) => RegEnable(v, advance))
}

/** One lane of the quad: its register files (three copies, for rs1, rs2 and rs3), r30/r31, the
  * FMA, the integer ALU, FRCP/FRSQ and write-back. Decode, issue and the units are shared, in
  * [[BorgCore]]; the units read the lane's operands (recARaw..recCRaw) and write through memWrite. */
class BorgLane(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgLaneIO(cfg))

  private val config = cfg.fp

  // --- Storage: three register-file copies, written together, so decode reads rs1, rs2 and rs3
  // at once (docs/B9_pipeline.md). regFile also serves MMIO.
  val regFile = Module(new RegFileCopy(config.totalBits, "regFile"))
  private val regFilesBC = Seq("regFileB", "regFileC").map(n => Module(new RegFileCopy(config.totalBits, n)))


  private def at(k: Int): Bool = io.stages(k - 1)
  private val is_busy      = io.stages.orR
  private val running      = io.running
  private val regs         = io.regs
  private val opFlags      = io.opFlags
  private def later[T <: Data](x: T, n: Int): T = BorgLane.later(io.advance)(x, n)
  // The instruction's flags and rd as latched at issue, at the operands and at write-back.
  private val flagsR = RegEnable(opFlags, 0.U.asTypeOf(opFlags), io.fmaStart)
  private val flagsX = later(flagsR, cfg.cBusyLoad - cfg.cOperands)
  private val flagsW = later(flagsR, cfg.cBusyLoad - 1)
  private val rdW    = later(RegEnable(regs.rd, 0.U, io.fmaStart), cfg.cBusyLoad - 1)
  // The execution mask as the instruction saw it at its operands.
  private val activeW = later(io.execActive, cfg.cOperands - 1)
  private val anyW    = later(io.execAny, cfg.cOperands - 1)

  // i + 0.5 as FP32: FP16 holds it exactly only up to 1023.5, and a Vulkan framebuffer is 4096 wide.
  def pixelCentre(i: UInt): UInt = {
    val x    = Cat(i, 1.U(1.W))                 // 2i + 1
    val n    = Log2(x)
    val exp  = (n +& 126.U)(7, 0)               // (2i + 1) / 2 = 1.f * 2^(n - 1)
    val frac = (x << (23.U - n))(22, 0)
    Cat(0.U(1.W), exp, frac)
  }
  private val coordReadEn = io.decode || io.stages(cfg.cBusyLoad - 1, 1).orR
  private val coordX = Reg(UInt(config.totalBits.W))
  private val coordY = Reg(UInt(config.totalBits.W))
  // Muxed ahead of the registers, not on the read path, so compute mode adds
  // nothing to the register-file read timing.
  when(coordReadEn) {
    val gx = io.iter.x +& io.pixelOrigin.x; val gy = io.iter.y +& io.pixelOrigin.y
    coordX := Mux(io.ids.mode, io.ids.r30, pixelCentre(gx))
    coordY := Mux(io.ids.mode, io.ids.r31, pixelCentre(gy))
  }

  // --- Register reads + uniform operand mux ---
  val (recA, recB, recC, mmioD) = wireGprReads()
  private val toOperands = cfg.cBusyLoad - cfg.cOperands
  private val ovA = later(io.ovA, toOperands); private val ovB = later(io.ovB, toOperands)
  private val recA_raw = Mux(ovA.valid, ovA.bits,
    Mux(later(io.funct3Del, toOperands) === 1.U, later(io.uniformData, toOperands), recA))
  private val recB_raw = Mux(ovB.valid, ovB.bits, recB)
  private val recC_raw = recC
  io.recARaw := recA_raw
  io.recBRaw := recB_raw
  io.recCRaw := recC_raw
  private val aW = later(recA_raw, cfg.cOperands - 1)
  private val bW = later(recB_raw, cfg.cOperands - 1)

  // --- ALU ---
  val fma_result = wireFma(recA_raw, recB_raw, recC_raw)
  // The special functions' table read: a stage before write-back.
  private val lutStage = 2
  private val special = Module(new SpecialFn(cfg))
  special.io.lutIn   := later(recA_raw, cfg.cOperands - lutStage)
  special.io.lutFrsq := later(flagsR, cfg.cBusyLoad - lutStage).frsq
  special.io.in      := aW
  special.io.frcp    := flagsW.frcp
  special.io.frsq    := flagsW.frsq
  special.io.advance := io.advance
  val special_result = special.io.out
  val (int_result, is_int_reg) = LaneIntAlu(flagsW, aW, bW, anyW, io.covMask, io.dstWord, config.totalBits)

  // --- Write-back (+ memory/TEX override) ---
  wireWriteBack(fma_result, special_result, int_result, is_int_reg, mmioD)

  io.regReadData := mmioD


  /** Resolve a register-file read result, substituting the pixel-coordinate
    * pseudo-registers r30/r31 (same special-case for every port, GPR or MMIO). */
  private def resolveCoordReg(raw: UInt, idx: UInt): UInt = {
    val isCoordReg = idx === 30.U || idx === 31.U
    // A sequencer's program reads 0 (the setup ROM uses r31 as zero) unless it supplies ids.
    val idsMode = io.ids.mode
    Mux(isCoordReg, Mux(!io.seqBusy || idsMode, Mux(idx === 30.U, coordX, coordY), 0.U), raw)
  }

  /** rs1, rs2 and rs3 read at decode from the three copies, held for the operand stage. MMIO
    * reads (software register access while idle) use regFile's port. */
  private def wireGprReads(): (UInt, UInt, UInt, UInt) = {
    val mmioEn = !running && !is_busy && (io.bus.is_reading || io.bus.is_writing) &&
                 io.bus.address >= BorgGpuRegs.gpr_offset && io.bus.address < BorgGpuRegs.imem_offset
    // mmioEn bounds the address to the 32-word register region.
    val mmioAddr = ((io.bus.address - BorgGpuRegs.gpr_offset) >> 2)(log2Ceil(32) - 1, 0)

    val atDecode = io.decode
    regFile.io.rd.addr := Mux(mmioEn, mmioAddr, regs.rs1)
    regFile.io.rd.en   := mmioEn || atDecode
    regFilesBC.zip(Seq(regs.rs2, regs.rs3)).foreach { case (f, r) => f.io.rd.addr := r; f.io.rd.en := atDecode }
    val (dataB, dataC) = (regFilesBC(0).io.rd.data, regFilesBC(1).io.rd.data)

    val mmioEnDel   = RegNext(mmioEn && io.bus.is_reading, false.B)
    val mmioAddrDel = RegEnable(mmioAddr, mmioEn)

    val rs1IdxDel = RegEnable(regs.rs1, atDecode)
    val rs2IdxDel = RegEnable(regs.rs2, atDecode)
    val rs3IdxDel = RegEnable(regs.rs3, atDecode)
    // A read in the cycle its register is written takes the written word.
    val wr = regFile.io.wr
    val wrDataDel = RegEnable(wr.data, atDecode)
    def bypass(data: UInt, rs: UInt): UInt =
      Mux(RegEnable(wr.en && wr.addr === rs, false.B, atDecode), wrDataDel, data)
    def hold(data: UInt, rs: UInt, rsDel: UInt): UInt =
      RegEnable(resolveCoordReg(bypass(data, rs), rsDel), 0.U(config.totalBits.W), io.advance)
    val holdA = hold(regFile.io.rd.data, regs.rs1, rs1IdxDel)
    val holdB = hold(dataB, regs.rs2, rs2IdxDel)
    val holdC = hold(dataC, regs.rs3, rs3IdxDel)
    val mmioD = Mux(mmioEnDel, resolveCoordReg(regFile.io.rd.data, mmioAddrDel), 0.U)

    (holdA, holdB, holdC, mmioD)
  }

  private def wireFma(recA_raw: UInt, recB_raw: UInt, recC_raw: UInt): UInt = {
    val one_fn = (((1 << (config.exp - 1)) - 1) << (config.sig - 1)).U(config.totalBits.W)

    val is_mul_reg = flagsX.mul
    // The R4-type opcode bit, which TEX and TEXA set too: their result comes from the unit instead.
    val is_fma_reg = flagsX.fma
    val is_deriv_reg = flagsX.ddx || flagsX.ddy // DDX/DDY: FMA computes crossA + crossC
    val negC_reg = flagsX.negC; val negProd_reg = flagsX.negProd

    // @doc:fma-muxing
    val fma_result = {
        val fma = Module(new BorgFma(cfg))
        fma.io.a := Mux(is_deriv_reg, io.crossA, Mux(is_mul_reg || is_fma_reg, recA_raw, one_fn))
        fma.io.b := Mux(is_deriv_reg, one_fn,     Mux(is_mul_reg || is_fma_reg, recB_raw, recA_raw))
        val c = Mux(is_deriv_reg, io.crossC,
                        Mux(is_fma_reg, recC_raw,
                        Mux(is_mul_reg, 0.U(config.totalBits.W), recB_raw)))
        fma.io.c := c ^ Cat(negC_reg, 0.U((config.totalBits - 1).W))
        fma.io.negate := negProd_reg
        fma.io.pipeEn1 := io.advance
        fma.io.pipeEn2 := io.advance
        fma.io.out
      }
    // @doc:end

    fma_result
  }

  private def wireWriteBack(
      fma_result: UInt, special_result: UInt,
      int_result: UInt, is_int_reg: Bool, mmio_reg_data: UInt
  ): Unit = {
    val mmio_write = io.bus.is_writing && io.bus.address >= BorgGpuRegs.gpr_offset && io.bus.address < BorgGpuRegs.imem_offset
    // A branch's rd field is part of its target. The exec mask gates every write, the CSR writes
    // too, except EXANY's: a lane already out of a divergent loop must still see the others finish.
    val wbWrites = !flagsW.branch && !flagsW.execOp && !flagsW.rvNoWrite
    val pipe_write = running && at(1) && wbWrites && (flagsW.exany || activeW)
    val w_en = mmio_write || pipe_write
    val w_addr = Mux(pipe_write, rdW, ((io.bus.address - BorgGpuRegs.gpr_offset) >> 2)(log2Ceil(32) - 1, 0))
    val is_special_reg = flagsW.frcp || flagsW.frsq
    val w_data = Mux(pipe_write,
      Mux(is_int_reg, int_result,
        Mux(is_special_reg, special_result, fma_result)),
      io.bus.data_in(config.totalBits - 1, 0))

    writeReg(w_addr, w_en, w_data)
    io.csrWrite.en   := running && at(1) && flagsW.csrw && activeW
    io.csrWrite.addr := flagsW.csr
    io.csrWrite.data := aW

    // A unit's write (a load's word, TEX's texel) wins over the pipeline's.
    when(io.memWrite.en && io.execActive) {
      writeReg(io.memWrite.addr, true.B, io.memWrite.data)
    }
  }

  private def writeReg(addr: UInt, en: Bool, data: UInt): Unit =
    (regFile +: regFilesBC).foreach { f => f.io.wr.addr := addr; f.io.wr.en := en; f.io.wr.data := data }
}
