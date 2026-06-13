// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

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

  // --- Cross-lane quad-derivative operands (broadcast by BorgCore for DDX/DDY) ---
  // crossA = neighbour lane's rs1 (lane1 for ddx, lane2 for ddy); crossC = -lane0's
  // rs1 (already fp16-negated). The lane's FMA then computes crossA + crossC.
  val crossA      = Input(UInt(cfg.totalBits.W))
  val crossC      = Input(UInt(cfg.totalBits.W))

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

  // --- Reciprocal-sqrt LUT (BRAM, 34 entries = 2 parity regions of 17) ---
  val frsqLutA = SyncReadMem(34, UInt(10.W))
  val frsqLutB = SyncReadMem(34, UInt(10.W))
  chisel3.util.experimental.loadMemoryFromFileInline(frsqLutA, "frsq_lut.hex")
  chisel3.util.experimental.loadMemoryFromFileInline(frsqLutB, "frsq_lut.hex")
  when(io.lutInit.en && io.lutInit.isFrsq) {
    frsqLutA.write(io.lutInit.addr(5, 0), io.lutInit.data(9, 0))
    frsqLutB.write(io.lutInit.addr(5, 0), io.lutInit.data(9, 0))
  }

  // --- Linear→sRGB LUT (BRAM, 256 fp16-output entries; direct function table) ---
  val srgbLutA = SyncReadMem(256, UInt(16.W))
  val srgbLutB = SyncReadMem(256, UInt(16.W))
  chisel3.util.experimental.loadMemoryFromFileInline(srgbLutA, "srgb_lut.hex")
  chisel3.util.experimental.loadMemoryFromFileInline(srgbLutB, "srgb_lut.hex")
  when(io.lutInit.en && io.lutInit.isFsrgb) {
    srgbLutA.write(io.lutInit.addr(7, 0), io.lutInit.data(15, 0))
    srgbLutB.write(io.lutInit.addr(7, 0), io.lutInit.data(15, 0))
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
  val (fma_result, is_fstep_reg, is_frcp_reg, is_frsq_reg, is_fsrgb_reg) = wireFma(recA_raw, recB_raw, recC_raw, io.fmaStart)
  val fstep_result = computeFstep(recA_raw)
  val frcp_result  = computeFrcp(recA_raw)
  val frsq_result  = computeFrsq(recA_raw)
  val fsrgb_result = computeFsrgb(recA_raw)
  val (int_result, is_int_reg) = wireIntAlu(recA_raw, recB_raw, io.fmaStart)

  // --- Write-back (+ FTEX override) ---
  wireWriteBack(fma_result, fstep_result, frcp_result, frsq_result, fsrgb_result,
                is_fstep_reg, is_frcp_reg, is_frsq_reg, is_fsrgb_reg,
                int_result, is_int_reg, mmioD)

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

  private def wireFma(recA_raw: UInt, recB_raw: UInt, recC_raw: UInt, start: Bool): (UInt, Bool, Bool, Bool, Bool) = {
    val one_fn = (((1 << (config.exp - 1)) - 1) << (config.sig - 1)).U(config.totalBits.W)

    val is_mul_reg = RegInit(false.B)
    val is_fma_reg = RegInit(false.B)
    val is_fneg_reg = RegInit(false.B)
    val is_fstep_reg = RegInit(false.B)
    val is_frcp_reg = RegInit(false.B)
    val is_frsq_reg = RegInit(false.B)
    val is_fsrgb_reg = RegInit(false.B)
    val is_deriv_reg = RegInit(false.B) // DDX/DDY: FMA computes crossA + crossC
    when(start) {
      is_mul_reg := opFlags.mul
      is_fma_reg := opFlags.fma
      is_fneg_reg := opFlags.fneg
      is_fstep_reg := opFlags.fstep
      is_frcp_reg := opFlags.frcp
      is_frsq_reg := opFlags.frsq
      is_fsrgb_reg := opFlags.fsrgb
      is_deriv_reg := opFlags.ddx || opFlags.ddy
    }

    val fma_result = {
        val fma = Module(new BorgFp16Fma(cfg))
        fma.io.a := Mux(is_deriv_reg, io.crossA, Mux(is_mul_reg || is_fma_reg, recA_raw, one_fn))
        fma.io.b := Mux(is_deriv_reg, one_fn,     Mux(is_mul_reg || is_fma_reg, recB_raw, recA_raw))
        fma.io.c := Mux(is_deriv_reg, io.crossC,
                        Mux(is_fma_reg, recC_raw,
                        Mux(is_mul_reg || is_fneg_reg, 0.U(config.totalBits.W), recB_raw)))
        fma.io.negate := is_fneg_reg
        // 4-stage custom FMA: regA@4, regB@3 (enabled → hold during non-busy, no X
        // churn); regC free-runs (no pipeEn3) to drop its high-fanout enable net.
        // Registered result ready for write-back/snoop at counter==1.
        fma.io.pipeEn1 := is_busy && busy_counter === 4.U
        fma.io.pipeEn2 := is_busy && busy_counter === 3.U
        fma.io.out
      }

    (fma_result, is_fstep_reg, is_frcp_reg, is_frsq_reg, is_fsrgb_reg)
  }

  /** Linear→sRGB via the 256-entry direct fp16→fp16 table (Fp16Srgb). Indexed by
    * (exp-1, mant[9:6]); same prefetch timing as the rcp/rsqrt LUTs. */
  private def computeFsrgb(recA_raw: UInt): UInt = {
    val exp  = recA_raw(14, 10)
    val mant = recA_raw(9, 0)
    val idx  = Cat((exp - 1.U)(3, 0), mant(9, 6)) // 8-bit
    val en   = is_busy && busy_counter >= 2.U
    val rawVal  = srgbLutA.read(idx, en)
    val rawNext = srgbLutB.read(idx +& 1.U, en)
    val valReg  = RegEnable(rawVal,  is_busy && busy_counter === 3.U)
    val nextReg = RegEnable(rawNext, is_busy && busy_counter === 3.U)
    val srgb = Module(new Fp16Srgb)
    srgb.io.in      := recA_raw(15, 0)
    srgb.io.lutVal  := valReg
    srgb.io.lutNext := nextReg
    if (config.totalBits > 16) Cat(0.U((config.totalBits - 16).W), srgb.io.out)
    else srgb.io.out
  }

  /** Reciprocal square root via the 34-entry parity-split LUT (companion of
    * computeFrcp). Parity (exp LSB) selects the LUT region; same prefetch timing. */
  private def computeFrsq(recA_raw: UInt): UInt = {
    val exp      = recA_raw(14, 10)
    val mant     = recA_raw(9, 0)
    val parity   = !exp(0)
    val baseAddr = Mux(parity, 17.U(6.W), 0.U(6.W)) + mant(9, 6)
    val rsqReadEn = is_busy && busy_counter >= 2.U
    val rsqLutRawVal  = frsqLutA.read(baseAddr, rsqReadEn)
    val rsqLutRawNext = frsqLutB.read(baseAddr +& 1.U, rsqReadEn)
    val rsqLutValReg  = RegEnable(rsqLutRawVal,  is_busy && busy_counter === 3.U)
    val rsqLutNextReg = RegEnable(rsqLutRawNext, is_busy && busy_counter === 3.U)
    val rsq = Module(new Fp16Rsq)
    rsq.io.in      := recA_raw(15, 0)
    rsq.io.lutVal  := rsqLutValReg
    rsq.io.lutNext := rsqLutNextReg
    if (config.totalBits > 16) Cat(0.U((config.totalBits - 16).W), rsq.io.out)
    else rsq.io.out
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

  /** Integer ALU (16-bit, on the raw register bits). Flags latch at `start` like
    * the FP ops; results are combinational on the operands, valid at write-back
    * (busy_counter==1), same as fstep/frcp. Returns (result, is_integer_op). */
  private def wireIntAlu(recA_raw: UInt, recB_raw: UInt, start: Bool): (UInt, Bool) = {
    val is_iadd_reg = RegInit(false.B)
    val is_ishl_reg = RegInit(false.B)
    val is_ishr_reg = RegInit(false.B)
    val is_imul_reg = RegInit(false.B)
    val is_i2f_reg  = RegInit(false.B)
    val is_f2i_reg  = RegInit(false.B)
    when(start) {
      is_iadd_reg := opFlags.iadd
      is_ishl_reg := opFlags.ishl
      is_ishr_reg := opFlags.ishr
      is_imul_reg := opFlags.imul
      is_i2f_reg  := opFlags.i2f
      is_f2i_reg  := opFlags.f2i
    }
    val w     = config.totalBits          // 16
    val mantN = config.sig - 1            // 10 stored mantissa bits
    val bias  = (1 << (config.exp - 1)) - 1   // 15

    val shamt = recB_raw(3, 0)
    val iadd = (recA_raw +& recB_raw)(w - 1, 0)
    val ishl = (recA_raw << shamt)(w - 1, 0)
    val ishr = (recA_raw.asSInt >> shamt).asUInt(w - 1, 0)
    val imul = (recA_raw * recB_raw)(w - 1, 0)

    // i2f: signed int16 → fp16. |a|, normalize: MSB → implicit 1, exp = msb+bias,
    // mantissa = the mantN bits below the MSB. Truncates for |a| >= 2^(mantN+1).
    val sgnI    = recA_raw(w - 1)
    val mag     = Mux(sgnI, (~recA_raw).asUInt + 1.U, recA_raw)(w - 1, 0)
    val msb     = Log2(mag)
    val expI    = (msb +& bias.U)(config.exp - 1, 0)
    val shifted = (mag << ((w - 1).U - msb))(w - 1, 0)   // MSB at bit w-1
    val mantI   = shifted(w - 2, w - 1 - mantN)          // mantN bits below MSB
    val i2f     = Mux(mag === 0.U, 0.U(w.W), Cat(sgnI, expI, mantI))

    // f2i: fp16 → signed int16, truncate toward zero. value = signif * 2^(e-mantN).
    val sgnF    = recA_raw(w - 1)
    val expF    = recA_raw(w - 2, mantN)                 // exponent field
    // 1.mant, zero-padded to w bits so the shifts index cleanly.
    val signif  = Cat(0.U((w - mantN - 1).W), 1.U(1.W), recA_raw(mantN - 1, 0))
    val e       = expF.zext - bias.S
    val magF    = Mux(e < 0.S, 0.U(w.W),
                  Mux(e >= mantN.S, (signif << (e - mantN.S).asUInt)(w - 1, 0),
                      (signif >> (mantN.S - e).asUInt)(w - 1, 0)))
    val f2i     = Mux(expF === 0.U, 0.U(w.W),
                      Mux(sgnF, (~magF).asUInt + 1.U, magF)(w - 1, 0))

    val result = Mux(is_iadd_reg, iadd,
                 Mux(is_ishl_reg, ishl,
                 Mux(is_ishr_reg, ishr,
                 Mux(is_imul_reg, imul,
                 Mux(is_i2f_reg,  i2f, f2i)))))
    val is_int = is_iadd_reg || is_ishl_reg || is_ishr_reg || is_imul_reg ||
                 is_i2f_reg || is_f2i_reg
    (result, is_int)
  }

  private def wireWriteBack(
      fma_result: UInt, fstep_result: UInt, frcp_result: UInt, frsq_result: UInt, fsrgb_result: UInt,
      is_fstep_reg: Bool, is_frcp_reg: Bool, is_frsq_reg: Bool, is_fsrgb_reg: Bool,
      int_result: UInt, is_int_reg: Bool, mmio_reg_data: UInt
  ): Unit = {
    val mmio_write = io.bus.is_writing && io.bus.address >= BorgGpuRegs.gpr_offset && io.bus.address < BorgGpuRegs.imem_offset
    val pipe_write = running && is_busy && busy_counter === 1.U
    val w_en = mmio_write || pipe_write
    val w_addr = Mux(pipe_write, regs.rd, (io.bus.address - BorgGpuRegs.gpr_offset) >> 2)
    val w_data = Mux(pipe_write,
      Mux(is_int_reg, int_result,
        Mux(is_fstep_reg, fstep_result,
          Mux(is_frcp_reg, frcp_result,
            Mux(is_frsq_reg, frsq_result, Mux(is_fsrgb_reg, fsrgb_result, fma_result))))),
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
