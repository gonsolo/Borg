// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._
import hardfloat._

/** BorgLane — the per-lane datapath of the shader core.
  *
  * Holds everything that differs between SIMT lanes (each lane = one pixel of a
  * 2×2 quad): the triplicated register file, coordinate expansion (r30/r31), the
  * FP16 ALU (FMA / FSTEP / FRCP), write-back, and the pipeline write-back snoop.
  *
  * Everything that is SHARED across lanes stays in [[BorgCore]] and is supplied
  * here as inputs: the decoded instruction (`regs`/`opFlags`), the pipeline
  * control (`busyCounter`/`running`/`isBusy`/`fmaStart`), the single uniform-RAM
  * read result (`uniformData`/`funct3Del`), the MMIO bus, LUT init, and the FTEX
  * write-back (`texWrite`) from the shared FTEX FSM.
  *
  * Write-back addr+enable are shared (same `rd`/MMIO address, same control); only
  * the data differs per lane, so the lane computes its own data and writes its own
  * register-file copies.  `recARaw`/`recBRaw` are exported so the shared FTEX FSM
  * can drive texU/texV from this lane's operands.
  *
  * At `fragLanes==1` a single instance reproduces the original monolithic BorgCore
  * behaviour bit-for-bit.
  */
class BorgLaneIO(val cfg: BorgConfig) extends Bundle {
  // --- Shared control (broadcast identically to every lane) ---
  val regs        = Input(new RegIndices())
  val opFlags     = Input(new FpuOpFlags())
  val busyCounter = Input(UInt(3.W))
  val running     = Input(Bool())
  val isBusy      = Input(Bool())
  val fmaStart    = Input(Bool())
  val seqBusy     = Input(Bool())

  // --- Per-lane pixel coordinate ---
  val iter        = Input(new Coord(cfg.coordWidth))

  // --- Shared uniform-RAM read (done once in BorgCore) ---
  val uniformData = Input(UInt(cfg.totalBits.W)) // op_en_del-gated read result
  val funct3Del   = Input(UInt(3.W))             // delayed funct3 selecting uniform operand

  // --- MMIO bus (broadcast; GPR read/write) ---
  val bus         = Flipped(new BorgBusIO())

  // --- RcpLut init (simulation) ---
  val lutInit     = Input(new LutInitIO(9, cfg.totalBits))

  // --- FTEX write-back from the shared FTEX FSM (en/addr/data) ---
  val texWrite    = Flipped(new MemWritePort(5, 16))

  // --- Outputs ---
  val pipeWrite   = new PipeWriteIO(cfg.totalBits) // write-back snoop
  val regReadData = Output(UInt(cfg.totalBits.W))  // MMIO GPR read (lane 0 consumed)
  val recARaw     = Output(UInt(cfg.totalBits.W))  // operands for shared FTEX FSM
  val recBRaw     = Output(UInt(cfg.totalBits.W))
}

class BorgLane(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgLaneIO(cfg))

  private val config = cfg.fp

  // --- Storage: triplicated register file (rs1/rs2/rs3+MMIO read ports) ---
  val regFileA = Module(new RegFileCopy(config.totalBits, "regFileA"))
  val regFileB = Module(new RegFileCopy(config.totalBits, "regFileB"))
  val regFileC = Module(new RegFileCopy(config.totalBits, "regFileC"))

  // --- Reciprocal LUT (BRAM, 2 copies for parallel lutIdx / lutIdx+1) ---
  val rcpLutA = SyncReadMem(17, UInt(10.W))
  val rcpLutB = SyncReadMem(17, UInt(10.W))
  chisel3.util.experimental.loadMemoryFromFileInline(rcpLutA, "rcp_lut.hex")
  chisel3.util.experimental.loadMemoryFromFileInline(rcpLutB, "rcp_lut.hex")
  when(io.lutInit.en && io.lutInit.isRcp) {
    rcpLutA.write(io.lutInit.addr(4, 0), io.lutInit.data(9, 0))
    rcpLutB.write(io.lutInit.addr(4, 0), io.lutInit.data(9, 0))
  }

  private val busy_counter = io.busyCounter
  private val is_busy      = io.isBusy
  private val running      = io.running
  private val regs         = io.regs
  private val opFlags      = io.opFlags

  // --- Coordinate expansion (r30/r31 = pixel center i+0.5) ---
  def pixelToFP16Half(i: UInt): UInt = {
    val x    = Cat(i, 1.U(1.W))
    val n    = Log2(x)
    val exp  = (n +& 14.U)(4, 0)
    val frac = (x << (10.U - n))(9, 0)
    Cat(0.U(1.W), exp, frac)
  }
  private val coordReadEn = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
  private val coordX = Reg(UInt(config.totalBits.W))
  private val coordY = Reg(UInt(config.totalBits.W))
  when(coordReadEn) {
    coordX := pixelToFP16Half(io.iter.x)
    coordY := pixelToFP16Half(io.iter.y)
  }

  // --- Register reads + uniform operand mux ---
  val (recA, _)        = wirePortA()
  val (recB, _)        = wirePortB()
  val (recC, _, mmioD) = wirePortC()
  private val recA_raw = Mux(io.funct3Del === 1.U, io.uniformData, recA)
  private val recB_raw = Mux(io.funct3Del === 2.U, io.uniformData, recB)
  private val recC_raw = Mux(io.funct3Del === 3.U, io.uniformData, recC)
  io.recARaw := recA_raw
  io.recBRaw := recB_raw

  // --- ALU ---
  val (fma_result, is_fstep_reg, is_frcp_reg) = wireFma(recA_raw, recB_raw, recC_raw, io.fmaStart)
  val fstep_result = computeFstep(recA_raw)
  val frcp_result  = computeFrcp(recA_raw)

  // --- Write-back (+ FTEX override) ---
  wireWriteBack(fma_result, fstep_result, frcp_result, is_fstep_reg, is_frcp_reg, mmioD)

  io.regReadData := mmioD

  // =========================================================================
  // Helpers (moved verbatim from BorgCore; shared signals come from io.*)
  // =========================================================================

  private def wirePortA(): (UInt, UInt) = {
    val en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
    val en_del = RegNext(en, false.B)
    val rs1_idx_del = RegEnable(regs.rs1, en)
    regFileA.io.rd.addr := regs.rs1
    regFileA.io.rd.en := en
    val is_coord_reg_A = rs1_idx_del === 30.U || rs1_idx_del === 31.U
    val resolved_data = Mux(is_coord_reg_A,
                        Mux(!io.seqBusy, Mux(rs1_idx_del === 30.U, coordX, coordY), 0.U),
                        regFileA.io.rd.data)
    (Mux(en_del, resolved_data, 0.U), rs1_idx_del)
  }

  private def wirePortB(): (UInt, UInt) = {
    val en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
    val en_del = RegNext(en, false.B)
    val rs2_idx_del = RegEnable(regs.rs2, en)
    regFileB.io.rd.addr := regs.rs2
    regFileB.io.rd.en := en
    val is_coord_reg_B = rs2_idx_del === 30.U || rs2_idx_del === 31.U
    val resolved_data = Mux(is_coord_reg_B,
                        Mux(!io.seqBusy, Mux(rs2_idx_del === 30.U, coordX, coordY), 0.U),
                        regFileB.io.rd.data)
    (Mux(en_del, resolved_data, 0.U), rs2_idx_del)
  }

  private def wirePortC(): (UInt, UInt, UInt) = {
    val mmio_en = !running && !is_busy && (io.bus.is_reading || io.bus.is_writing) &&
                  io.bus.address >= BorgGpuRegs.gpr_offset && io.bus.address < BorgGpuRegs.imem_offset
    val rs3_en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
    val addr = Mux(running || is_busy, regs.rs3, (io.bus.address - BorgGpuRegs.gpr_offset) >> 2)
    val en = mmio_en || rs3_en
    val mmio_en_del = RegNext(mmio_en && io.bus.is_reading, false.B)
    val rs3_en_del = RegNext(rs3_en, false.B)
    val addr_del = RegEnable(addr, en)
    regFileC.io.rd.addr := addr
    regFileC.io.rd.en := en
    val is_coord_reg_C = addr_del === 30.U || addr_del === 31.U
    val resolved_data = Mux(is_coord_reg_C,
                        Mux(!io.seqBusy, Mux(addr_del === 30.U, coordX, coordY), 0.U),
                        regFileC.io.rd.data)
    (Mux(rs3_en_del, resolved_data, 0.U), addr_del, Mux(mmio_en_del, resolved_data, 0.U))
  }

  private def wireFma(recA_raw: UInt, recB_raw: UInt, recC_raw: UInt, start: Bool): (UInt, Bool, Bool) = {
    val one_fn = (((1 << (config.exp - 1)) - 1) << (config.sig - 1)).U(config.totalBits.W)

    val is_mul_reg = RegInit(false.B)
    val is_fma_reg = RegInit(false.B)
    val is_fneg_reg = RegInit(false.B)
    val is_fstep_reg = RegInit(false.B)
    val is_frcp_reg = RegInit(false.B)
    when(start) {
      is_mul_reg := opFlags.mul
      is_fma_reg := opFlags.fma
      is_fneg_reg := opFlags.fneg
      is_fstep_reg := opFlags.fstep
      is_frcp_reg := opFlags.frcp
    }

    val pipe_stage = is_busy && busy_counter === 2.U

    val fma_result =
      if (cfg.useCustomFma) {
        val fma = Module(new BorgFp16Fma(cfg))
        fma.io.a := Mux(is_mul_reg || is_fma_reg, recA_raw, one_fn)
        fma.io.b := Mux(is_mul_reg || is_fma_reg, recB_raw, recA_raw)
        fma.io.c := Mux(is_fma_reg, recC_raw,
                        Mux(is_mul_reg || is_fneg_reg, 0.U(config.totalBits.W), recB_raw))
        fma.io.negate := is_fneg_reg
        fma.io.pipeEn := pipe_stage
        fma.io.out
      } else {
        val recA = recFNFromFN(config.exp, config.sig, recA_raw)
        val recB = recFNFromFN(config.exp, config.sig, recB_raw)
        val recC = recFNFromFN(config.exp, config.sig, recC_raw)
        val recOne = recFNFromFN(config.exp, config.sig, one_fn)
        val recZero = recFNFromFN(config.exp, config.sig, 0.U(config.totalBits.W))

        val preMul = Module(new MulAddRecFNToRaw_preMul(config.exp, config.sig))
        preMul.io.op := Mux(is_fneg_reg, 2.U, 0.U)
        preMul.io.a  := Mux(is_mul_reg || is_fma_reg, recA, recOne)
        preMul.io.b  := Mux(is_mul_reg || is_fma_reg, recB, recA)
        preMul.io.c  := Mux(is_fma_reg, recC, Mux(is_mul_reg || is_fneg_reg, recZero, recB))

        val mulAddResult = (preMul.io.mulAddA * preMul.io.mulAddB) +& preMul.io.mulAddC
        val toPostMul_reg    = RegEnable(preMul.io.toPostMul,  pipe_stage)
        val mulAddResult_reg = RegEnable(mulAddResult,          pipe_stage)

        val postMul = Module(new MulAddRecFNToRaw_postMul(config.exp, config.sig))
        postMul.io.fromPreMul   := toPostMul_reg
        postMul.io.mulAddResult := mulAddResult_reg
        postMul.io.roundingMode := 0.U

        val round = Module(new RoundRawFNToRecFN(config.exp, config.sig, 0))
        round.io.invalidExc    := postMul.io.invalidExc
        round.io.infiniteExc   := false.B
        round.io.in            := postMul.io.rawOut
        round.io.roundingMode  := 0.U
        round.io.detectTininess := 1.U

        fNFromRecFN(config.exp, config.sig, round.io.out)
      }

    (fma_result, is_fstep_reg, is_frcp_reg)
  }

  private def computeFstep(recA_raw: UInt): UInt = {
    val one_fn = (((1 << (config.exp - 1)) - 1) << (config.sig - 1)).U(config.totalBits.W)
    val neg_or_zero = recA_raw(config.totalBits - 1) || (recA_raw === 0.U)
    Mux(neg_or_zero, 0.U(config.totalBits.W), one_fn)
  }

  private def computeFrcp(recA_raw: UInt): UInt = {
    val rcpMant = recA_raw(9, 0)
    val rcpLutIdx = rcpMant(9, 6)
    val rcpReadEn = is_busy && busy_counter >= 2.U
    val rcpLutRawVal  = rcpLutA.read(rcpLutIdx, rcpReadEn)
    val rcpLutRawNext = rcpLutB.read(rcpLutIdx +& 1.U, rcpReadEn)
    val rcpLutValReg  = RegEnable(rcpLutRawVal,  is_busy && busy_counter === 3.U)
    val rcpLutNextReg = RegEnable(rcpLutRawNext, is_busy && busy_counter === 3.U)
    val rcp = Module(new Fp16Rcp)
    rcp.io.in      := recA_raw(15, 0)
    rcp.io.lutVal  := rcpLutValReg
    rcp.io.lutNext := rcpLutNextReg
    if (config.totalBits > 16) Cat(0.U((config.totalBits - 16).W), rcp.io.out)
    else rcp.io.out
  }

  private def wireWriteBack(
      fma_result: UInt, fstep_result: UInt, frcp_result: UInt,
      is_fstep_reg: Bool, is_frcp_reg: Bool, mmio_reg_data: UInt
  ): Unit = {
    val mmio_write = io.bus.is_writing && io.bus.address >= BorgGpuRegs.gpr_offset && io.bus.address < BorgGpuRegs.imem_offset
    val pipe_write = running && is_busy && busy_counter === 1.U
    val w_en = mmio_write || pipe_write
    val w_addr = Mux(pipe_write, regs.rd, (io.bus.address - BorgGpuRegs.gpr_offset) >> 2)
    val w_data = Mux(pipe_write,
      Mux(is_fstep_reg, fstep_result, Mux(is_frcp_reg, frcp_result, fma_result)),
      io.bus.data_in(config.totalBits - 1, 0))

    writeAllCopies(w_addr, w_en, w_data)
    io.pipeWrite.en   := pipe_write
    io.pipeWrite.addr := w_addr
    io.pipeWrite.data := w_data

    // FTEX write-back override (shared FSM drives texWrite; last-connect wins,
    // matching the original wireTexStall-after-wireWriteBack ordering).
    when(io.texWrite.en) {
      writeAllCopies(io.texWrite.addr, true.B, io.texWrite.data)
      io.pipeWrite.en   := true.B
      io.pipeWrite.addr := io.texWrite.addr
      io.pipeWrite.data := io.texWrite.data
    }
  }

  private def writeAllCopies(addr: UInt, en: Bool, data: UInt): Unit = {
    for (rf <- Seq(regFileA, regFileB, regFileC)) {
      rf.io.wr.addr := addr
      rf.io.wr.en := en
      rf.io.wr.data := data
    }
  }
}
