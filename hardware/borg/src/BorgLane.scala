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

  // --- Storage: single-ported register file, time-multiplexed across rs1/rs2/
  // rs3/MMIO reads (was triplicated -- one physical copy per simultaneous read
  // port). A 32-entry x 16-bit SyncReadMem's read mux dominates its own area
  // (measured ~44,000 um^2 each; the 3x copy was ~132,000 um^2, the single
  // largest structural cost found in the whole ASIC area campaign). Serializing
  // the 3 reads over 3 extra pipeline cycles trades cycles -- free at the
  // ASIC's 4MHz -- for ~88,000 um^2. See wireGprReads() and BorgCore's
  // busy_counter doc comment (countdown widened 4->7 to make room).
  val regFile = Module(new RegFileCopy(config.totalBits, "regFile"))

  // --- FP16 special-function ROMs (purely combinational VecInit — zero FFs, zero clock load) ---
  private val rcpRom  = VecInit(BorgLutTables.rcpLut.map(_.U(10.W)))
  private val frsqRom = VecInit(BorgLutTables.frsqLut.map(_.U(10.W)))
  private val srgbRom = VecInit(BorgLutTables.srgbLut.map(_.U(16.W)))

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
  val (recA, recB, recC, mmioD) = wireGprReads()
  private val recA_raw = Mux(io.funct3Del === 1.U, io.uniformData, recA)
  private val recB_raw = Mux(io.funct3Del === 2.U, io.uniformData, recB)
  private val recC_raw = Mux(io.funct3Del === 3.U, io.uniformData, recC)
  io.recARaw := recA_raw
  io.recBRaw := recB_raw

  // --- ALU ---
  val (fma_result, is_fstep_reg, is_frcp_reg, is_frsq_reg, is_fsrgb_reg) = wireFma(recA_raw, recB_raw, recC_raw, io.fmaStart)
  val fstep_result = computeFstep(recA_raw)
  val special_result = computeFp16Special(recA_raw, is_frcp_reg, is_frsq_reg, is_fsrgb_reg)
  val (int_result, is_int_reg) = wireIntAlu(recA_raw, recB_raw, io.fmaStart)

  // --- Write-back (+ FTEX override) ---
  wireWriteBack(fma_result, fstep_result, special_result,
                is_fstep_reg, is_frcp_reg, is_frsq_reg, is_fsrgb_reg,
                int_result, is_int_reg, mmioD)

  io.regReadData := mmioD

  // =========================================================================
  // Helpers (moved verbatim from BorgCore; shared signals come from io.*)
  // =========================================================================

  /** Resolve a register-file read result, substituting the pixel-coordinate
    * pseudo-registers r30/r31 (same special-case for every port, GPR or MMIO). */
  private def resolveCoordReg(raw: UInt, idx: UInt): UInt = {
    val isCoordReg = idx === 30.U || idx === 31.U
    Mux(isCoordReg, Mux(!io.seqBusy, Mux(idx === 30.U, coordX, coordY), 0.U), raw)
  }

  /** Single read port, time-multiplexed across rs1/rs2/rs3/MMIO (was 3
    * physical register-file copies, one per simultaneously-needed operand --
    * see the `regFile` declaration above for the area rationale).
    *
    * Shader execution needs rs1/rs2/rs3 all valid by busy_counter==4 (when the
    * FMA/frcp/frsq/fsrgb/int-ALU pipeline captures them -- unchanged from the
    * original 3-port design). With one port, SyncReadMem's 1-cycle read
    * latency means the 3 reads must be issued on 3 *different* prior cycles:
    *   decode (rs1) -> busy_counter==7 (rs2, + rs1's result now ready) ->
    *   busy_counter==6 (rs3, + rs2's result now ready) -> busy_counter==5
    *   (rs3's result now ready) -> busy_counter==4 (all 3 held, pipeline
    *   starts, same trigger value as before the retiming).
    * Each result is captured into its own hold register the cycle it becomes
    * ready and stays stable from then on (regs.rs1/rs2/rs3 don't change
    * during a single instruction's execution, so re-reading was always
    * redundant -- BorgCore only advances the PC at busy_counter==1).
    *
    * MMIO reads (software register access while idle) reuse the same port;
    * mutually exclusive with the shader-execution window by construction
    * (`running` is false while MMIO can read GPRs).
    */
  private def wireGprReads(): (UInt, UInt, UInt, UInt) = {
    val mmioEn = !running && !is_busy && (io.bus.is_reading || io.bus.is_writing) &&
                 io.bus.address >= BorgGpuRegs.gpr_offset && io.bus.address < BorgGpuRegs.imem_offset
    val mmioAddr = (io.bus.address - BorgGpuRegs.gpr_offset) >> 2

    // Issue cycles: rs1 at decode, rs2 at busy==7, rs3 at busy==6.
    val atDecode = running && !is_busy
    val atC7 = is_busy && busy_counter === 7.U
    val atC6 = is_busy && busy_counter === 6.U

    regFile.io.rd.addr := Mux(mmioEn, mmioAddr, Mux(atDecode, regs.rs1, Mux(atC7, regs.rs2, regs.rs3)))
    regFile.io.rd.en   := mmioEn || atDecode || atC7 || atC6

    val mmioEnDel   = RegNext(mmioEn && io.bus.is_reading, false.B)
    val mmioAddrDel = RegEnable(mmioAddr, mmioEn)

    // Capture cycles: one cycle after each issue (SyncReadMem's read latency)
    // -- rs1's result lands at busy==7, rs2's at busy==6, rs3's at busy==5.
    val rs1IdxDel = RegEnable(regs.rs1, atDecode)
    val rs2IdxDel = RegEnable(regs.rs2, atC7)
    val rs3IdxDel = RegEnable(regs.rs3, atC6)
    val holdA = RegEnable(resolveCoordReg(regFile.io.rd.data, rs1IdxDel), 0.U(config.totalBits.W), atC7)
    val holdB = RegEnable(resolveCoordReg(regFile.io.rd.data, rs2IdxDel), 0.U(config.totalBits.W), atC6)
    val holdC = RegEnable(resolveCoordReg(regFile.io.rd.data, rs3IdxDel), 0.U(config.totalBits.W), is_busy && busy_counter === 5.U)
    val mmioD = Mux(mmioEnDel, resolveCoordReg(regFile.io.rd.data, mmioAddrDel), 0.U)

    (holdA, holdB, holdC, mmioD)
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

    // @doc:fma-muxing
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
    // @doc:end

    (fma_result, is_fstep_reg, is_frcp_reg, is_frsq_reg, is_fsrgb_reg)
  }

  // @doc:fstep
  private def computeFstep(recA_raw: UInt): UInt = {
    val one_fn = (((1 << (config.exp - 1)) - 1) << (config.sig - 1)).U(config.totalBits.W)
    val neg_or_zero = recA_raw(config.totalBits - 1) || (recA_raw === 0.U)
    Mux(neg_or_zero, 0.U(config.totalBits.W), one_fn)
  }
  // @doc:end

  /** Reciprocal / reciprocal-sqrt / linear->sRGB, sharing one Fp16Special
    * instance (was 3 separate modules, each with its own copy of the
    * LUT-interpolation datapath -- measured 6,967+7,273+11,189=25,429 um^2
    * standalone vs 15,363 um^2 consolidated, -39.6%). Each op's ROM lookup
    * (rcpRom/frsqRom/srgbRom, all purely combinational VecInits) still
    * happens independently -- cheap, and keeps each op's indexing formula
    * untouched -- only the downstream interpolation/edge-case hardware is
    * shared, muxed by which op is latched active this instruction. */
  // @doc:frcp
  private def computeFp16Special(recA_raw: UInt, isFrcp: Bool, isFrsq: Bool, isFsrgb: Bool): UInt = {
    val exp  = recA_raw(14, 10)
    val mant = recA_raw(9, 0)

    val rcpLutIdx = mant(9, 6)
    val rcpRawVal  = rcpRom(rcpLutIdx)
    val rcpRawNext = rcpRom((rcpLutIdx +& 1.U)(4, 0))

    val parity      = !exp(0)
    val rsqBaseAddr = Mux(parity, 17.U(6.W), 0.U(6.W)) + mant(9, 6)
    val rsqRawVal   = frsqRom(rsqBaseAddr)
    val rsqRawNext  = frsqRom((rsqBaseAddr +& 1.U)(5, 0))

    val srgbIdx     = Cat((exp - 1.U)(3, 0), mant(9, 6)) // 8-bit
    val srgbRawVal  = srgbRom(srgbIdx)
    val srgbRawNext = srgbRom((srgbIdx +& 1.U)(7, 0))

    // rcp/rsq LUT entries are 10-bit mantissa estimates; zero-extend to
    // Fp16Special's uniform 16-bit port (exact: delta=val-next is always
    // non-negative for these two monotonically-decreasing curves).
    val rawVal  = Mux(isFsrgb, srgbRawVal,  Mux(isFrsq, Cat(0.U(6.W), rsqRawVal),  Cat(0.U(6.W), rcpRawVal)))
    val rawNext = Mux(isFsrgb, srgbRawNext, Mux(isFrsq, Cat(0.U(6.W), rsqRawNext), Cat(0.U(6.W), rcpRawNext)))
    val valReg  = RegEnable(rawVal,  is_busy && busy_counter === 3.U)
    val nextReg = RegEnable(rawNext, is_busy && busy_counter === 3.U)

    val special = Module(new Fp16Special)
    special.io.in      := recA_raw(15, 0)
    special.io.lutVal  := valReg
    special.io.lutNext := nextReg
    special.io.op      := Mux(isFrcp, Fp16SpecialOp.Rcp, Mux(isFrsq, Fp16SpecialOp.Rsq, Fp16SpecialOp.Srgb))
    if (config.totalBits > 16) Cat(0.U((config.totalBits - 16).W), special.io.out)
    else special.io.out
  }
  // @doc:end

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
      fma_result: UInt, fstep_result: UInt, special_result: UInt,
      is_fstep_reg: Bool, is_frcp_reg: Bool, is_frsq_reg: Bool, is_fsrgb_reg: Bool,
      int_result: UInt, is_int_reg: Bool, mmio_reg_data: UInt
  ): Unit = {
    val mmio_write = io.bus.is_writing && io.bus.address >= BorgGpuRegs.gpr_offset && io.bus.address < BorgGpuRegs.imem_offset
    val pipe_write = running && is_busy && busy_counter === 1.U
    val w_en = mmio_write || pipe_write
    val w_addr = Mux(pipe_write, regs.rd, (io.bus.address - BorgGpuRegs.gpr_offset) >> 2)
    // is_frcp_reg/is_frsq_reg/is_fsrgb_reg are mutually exclusive (decoded
    // from distinct funct7 values); special_result already internally
    // selects the right op's result (see computeFp16Special's `op` mux).
    val is_special_reg = is_frcp_reg || is_frsq_reg || is_fsrgb_reg
    val w_data = Mux(pipe_write,
      Mux(is_int_reg, int_result,
        Mux(is_fstep_reg, fstep_result,
          Mux(is_special_reg, special_result, fma_result))),
      io.bus.data_in(config.totalBits - 1, 0))

    writeReg(w_addr, w_en, w_data)
    io.pipeWrite.en   := pipe_write
    io.pipeWrite.addr := w_addr
    io.pipeWrite.data := w_data

    // FTEX write-back override (shared FSM drives texWrite; last-connect wins,
    // matching the original wireTexStall-after-wireWriteBack ordering).
    when(io.texWrite.en) {
      writeReg(io.texWrite.addr, true.B, io.texWrite.data)
      io.pipeWrite.en   := true.B
      io.pipeWrite.addr := io.texWrite.addr
      io.pipeWrite.data := io.texWrite.data
    }
  }

  private def writeReg(addr: UInt, en: Bool, data: UInt): Unit = {
    regFile.io.wr.addr := addr
    regFile.io.wr.en := en
    regFile.io.wr.data := data
  }
}
