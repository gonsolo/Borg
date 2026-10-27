// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.util._

/** BorgLane — the per-lane datapath of the shader core.
  *
  * Holds everything that differs between SIMT lanes (each lane = one pixel of a
  * 2×2 quad): the triplicated register file, coordinate expansion (r30/r31), the
  * FP16 ALU (FMA / FRCP), write-back, and the pipeline write-back snoop.
  *
  * Everything that is SHARED across lanes stays in [[BorgCore]] and is supplied
  * here as inputs: the decoded instruction (`regs`/`opFlags`), the pipeline
  * control (`stages`/`running`/`fmaStart`), the single uniform-RAM
  * read result (`uniformData`/`funct3Del`), the MMIO bus, LUT init, and the
  * memory write-back (`memWrite`) from the shared LOAD/STORE and TEX FSMs.
  *
  * Write-back addr+enable are shared (same `rd`/MMIO address, same control); only
  * the data differs per lane, so the lane computes its own data and writes its own
  * register-file copies.  `recARaw`/`recBRaw`/`recCRaw` are exported so the
  * shared memory and TEX FSMs can read this lane's operands.
  */
class LaneIdsIO(val cfg: BorgConfig) extends Bundle {
  val mode = Bool()
  val r30  = UInt(cfg.totalBits.W)
  val r31  = UInt(cfg.totalBits.W)
}

class BorgLaneIO(val cfg: BorgConfig) extends Bundle {
  // --- Shared control (broadcast identically to every lane) ---
  val regs        = Input(new RegIndices())
  val opFlags     = Input(new FpuOpFlags())
  // This lane's bit of the execution mask. Low means the lane is inside the
  // not-taken arm of a divergent `if`: it still executes (the quad shares one
  // program counter, so it has no choice) but none of its writes may land.
  val execActive  = Input(Bool())
  // Broadcast, not per-lane: whether ANY lane's execMask bit is set right
  // now -- see Opcodes.MATCH_EXANY_V2.
  val execAny     = Input(Bool())
  // This lane's coverage mask, for SMASK.
  val covMask     = if (cfg.drawEnabled) Some(Input(UInt(cfg.samples.W))) else None
  // This lane's destination word, for TLD.
  val dstWord     = if (cfg.drawEnabled) Some(Input(Vec(2, UInt(32.W)))) else None
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
  val ids         = if (cfg.hasInvocationIds) Some(Input(new LaneIdsIO(cfg))) else None

  // --- Shared uniform-RAM read (done once in BorgCore) ---
  val uniformData = Input(UInt(cfg.totalBits.W)) // op_en_del-gated read result
  val funct3Del   = Input(UInt(3.W))             // delayed funct3 selecting uniform operand
  val ovA         = Input(Valid(UInt(cfg.totalBits.W))) // x0, LUI's zero, AUIPC's address
  val ovB         = Input(Valid(UInt(cfg.totalBits.W))) // x0 or an immediate

  // --- Cross-lane quad-derivative operands (broadcast by BorgCore for DDX/DDY) ---
  // crossA = neighbour lane's rs1 (lane1 for ddx, lane2 for ddy); crossC = -lane0's
  // rs1 (already fp16-negated). The lane's FMA then computes crossA + crossC.
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

class BorgLane(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgLaneIO(cfg))

  private val config = cfg.fp

  // --- Storage: three register-file copies, written together, so decode reads rs1, rs2 and rs3
  // at once (docs/B9_pipeline.md). regFile also serves MMIO.
  val regFile = Module(new RegFileCopy(config.totalBits, "regFile"))
  private val regFilesBC = Seq("regFileB", "regFileC").map(n => Module(new RegFileCopy(config.totalBits, n)))

  // --- FP16 special-function ROMs (purely combinational VecInit — zero FFs, zero clock load) ---
  private val rcpRom  = VecInit(BorgLutTables.rcpLut.map(_.U(10.W)))
  private val frsqRom = VecInit(BorgLutTables.frsqLut.map(_.U(10.W)))

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
    coordX := io.ids.map(c => Mux(c.mode, c.r30, pixelCentre(gx))).getOrElse(pixelCentre(gx))
    coordY := io.ids.map(c => Mux(c.mode, c.r31, pixelCentre(gy))).getOrElse(pixelCentre(gy))
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
  val special_result = computeFp16Special(later(recA_raw, cfg.cOperands - lutStage),
    later(flagsR, cfg.cBusyLoad - lutStage).frsq)
  val (int_result, is_int_reg) = wireIntAlu(aW, bW)

  // --- Write-back (+ memory/TEX override) ---
  wireWriteBack(fma_result, special_result, int_result, is_int_reg, mmioD)

  io.regReadData := mmioD

  // =========================================================================
  // Helpers (moved verbatim from BorgCore; shared signals come from io.*)
  // =========================================================================

  /** Resolve a register-file read result, substituting the pixel-coordinate
    * pseudo-registers r30/r31 (same special-case for every port, GPR or MMIO). */
  private def resolveCoordReg(raw: UInt, idx: UInt): UInt = {
    val isCoordReg = idx === 30.U || idx === 31.U
    // A sequencer-run shader reads 0 here (legacy vertex/setup shaders use r31
    // as a zero), unless the sequencer supplies IDs -- a vertex shader's
    // VertexIndex/InstanceIndex.
    val idsMode = io.ids.map(_.mode).getOrElse(false.B)
    Mux(isCoordReg, Mux(!io.seqBusy || idsMode, Mux(idx === 30.U, coordX, coordY), 0.U), raw)
  }

  /** rs1, rs2 and rs3 read at decode from the three copies, held for the operand stage. MMIO
    * reads (software register access while idle) use regFile's port. */
  private def wireGprReads(): (UInt, UInt, UInt, UInt) = {
    val mmioEn = !running && !is_busy && (io.bus.is_reading || io.bus.is_writing) &&
                 io.bus.address >= BorgGpuRegs.gpr_offset && io.bus.address < BorgGpuRegs.imem_offset
    // Truncated to the register file's own log2Ceil(32) address width: mmioEn
    // (below) only ever selects this value when io.bus.address falls in
    // [gpr_offset, imem_offset), which spans exactly the 32-word GPR region --
    // so the word index is always in range and the high bits Chisel's generic
    // subtract/shift width-growth adds are provably always zero here.
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
    // opFlags.fma is the raw R4-type opcode bit, which TEX/TEXA also set --
    // this lane runs its real multiply-add datapath on their U/V/ctl
    // operands too, which is wasted power but harmless: TEX/TEXA's real
    // result always arrives via the memWrite override below (BorgCore's
    // wireSampler), last-connect-wins over whatever this path produces.
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
        // 4-stage custom FMA: regA@4, regB@3 (enabled → hold during non-busy, no X
        // churn); regC free-runs (no pipeEn3) to drop its high-fanout enable net.
        // Registered result ready for write-back/snoop at counter==1.
        fma.io.pipeEn1 := io.advance
        fma.io.pipeEn2 := io.advance
        fma.io.out
      }
    // @doc:end

    fma_result
  }

  /** Reciprocal / reciprocal-sqrt / linear->sRGB, sharing one Fp16Special
    * instance (was 3 separate modules, each with its own copy of the
    * LUT-interpolation datapath -- measured 6,967+7,273+11,189=25,429 um^2
    * standalone vs 15,363 um^2 consolidated, -39.6%). Each op's ROM lookup
    * (rcpRom/frsqRom, both purely combinational VecInits) still
    * happens independently -- cheap, and keeps each op's indexing formula
    * untouched -- only the downstream interpolation/edge-case hardware is
    * shared, muxed by which op is latched active this instruction. */
  // @doc:frcp
  private def computeFp16Special(aLut: UInt, frsqLut: Bool): UInt = {
    val isFrcp = flagsW.frcp; val isFrsq = flagsW.frsq
    // Narrow once, up front: every LUT index below and special.io.in itself
    // must all read the SAME FP16 bit pattern. Reading exp/mant straight off
    // recA_raw's low bits would silently misindex the ROMs at FP32 (bits
    // 9:0/14:10 of a 32-bit FP32 pattern are not this value's FP16 mantissa/
    // exponent -- they're arbitrary low mantissa bits of the FP32 pattern).
    //
    // FP32 rcp/rsq keep FP32's exponent range: the LUT core only ever sees the
    // MANTISSA, scaled into [1, 2) -- or [2, 4) for an rsq of an odd exponent,
    // so the halved exponent stays an integer -- and the input's own exponent
    // is applied to the result afterwards. Narrowing the whole value instead
    // overflowed FP16 from 65504 up (1/x = 0) and underflowed below 6e-5
    // (1/x = inf): a triangle's setup determinant is routinely outside both.
    def narrowed(a: UInt, frsq: Bool) = {
      val e32 = a(30, 23)
      val eUnb = e32.zext - 127.S                       // input exponent, unbiased
      val special32 = e32 === 0.U || e32 === 255.U      // zero/denormal, inf/NaN
      val oddExp = eUnb(0) && frsq
      val scaled = Cat(a(31), Mux(oddExp, 128.U(8.W), 127.U(8.W)), a(22, 0))
      val in = Mux(special32, Fp16Fp32.narrow(a), Fp16Fp32.narrow(scaled))
      (in, eUnb, special32, oddExp)
    }
    val lutIn = narrowed(aLut, frsqLut)._1
    val (fp16In, eUnb, special32, oddExp) = narrowed(aW, isFrsq)
    val exp  = lutIn(14, 10)
    val mant = lutIn(9, 0)

    // rcp's LUT is 33 entries on mant(9,5) (rsq/srgb stay on mant(9,6)); idx is
    // 0..31 so only idx+1 can reach the last entry. A 33-entry Vec requires a
    // 6-bit dynamic index.
    val rcpLutIdx = mant(9, 5)
    val rcpRawVal  = rcpRom(rcpLutIdx.pad(6))
    val rcpRawNext = rcpRom((rcpLutIdx +& 1.U)(5, 0))

    val parity      = !exp(0)
    val rsqBaseAddr = Mux(parity, 17.U(6.W), 0.U(6.W)) + mant(9, 6)
    val rsqRawVal   = frsqRom(rsqBaseAddr)
    val rsqRawNext  = frsqRom((rsqBaseAddr +& 1.U)(5, 0))


    // rcp/rsq LUT entries are 10-bit mantissa estimates; zero-extend to
    // Fp16Special's uniform 16-bit port (exact: delta=val-next is always
    // non-negative for these two monotonically-decreasing curves).
    val rawVal  = Mux(frsqLut, Cat(0.U(6.W), rsqRawVal),  Cat(0.U(6.W), rcpRawVal))
    val rawNext = Mux(frsqLut, Cat(0.U(6.W), rsqRawNext), Cat(0.U(6.W), rcpRawNext))
    val lutEn   = io.advance
    val valReg  = RegEnable(rawVal,  lutEn)
    val nextReg = RegEnable(rawNext, lutEn)

    val special = Module(new Fp16Special)
    // Fp16Special stays FP16-internal (existing LUT hardware) regardless of
    // cfg.fp -- feed it the same narrowed fp16In used for the LUT indices
    // above, widen its FP16 result back up at the output below.
    special.io.in      := fp16In
    special.io.lutVal  := valReg
    special.io.lutNext := nextReg
    special.io.op      := Mux(isFrcp, Fp16SpecialOp.Rcp, Fp16SpecialOp.Rsq)
    locally {
      val r = Fp16Fp32.widen(special.io.out)
      // 1/(m*2^E) = (1/m)*2^-E;  1/sqrt(m*2^E) = (1/sqrt(m'))*2^-(E'/2).
      val shift = Mux(isFrsq, (eUnb - oddExp.asUInt.zext) >> 1, eUnb)
      val eOut  = r(30, 23).zext - shift
      val scaledOut = MuxCase(Cat(r(31), eOut.asUInt(7, 0), r(22, 0)), Seq(
        (eOut <= 0.S)   -> Cat(r(31), 0.U(31.W)),                      // flush to zero
        (eOut >= 255.S) -> Cat(r(31), "hFF".U(8.W), 0.U(23.W))         // overflow to inf
      ))
      Mux(special32 || r(30, 23) === 255.U, r, scaledOut)
    }
  }
  // @doc:end

  /** Integer ALU (16-bit, on the raw register bits). Flags latch at `start` like
    * the FP ops; results are combinational on the operands, valid at write-back
    * (stage 1), same as frcp. Returns (result, is_integer_op). */
  private def wireIntAlu(recA_raw: UInt, recB_raw: UInt): (UInt, Bool) = {
    val f = flagsW
    val is_iadd_reg = f.iadd; val is_ishl_reg = f.ishl; val is_ishr_reg = f.ishr
    val is_imul_reg = f.imul; val is_isub_reg = f.isub; val is_iand_reg = f.iand
    val is_ior_reg  = f.ior;  val is_ixor_reg = f.ixor; val is_islt_reg = f.islt
    val is_f2h_reg  = f.f2h;  val is_h2f_reg  = f.h2f;  val is_exany_reg = f.exany
    val is_smask_reg = f.smask; val is_tld_reg = f.tld
    val tld_sel_reg = f.funct3(0)            // TLD's word: funct3 bit 0
    val is_isrl_reg = f.isrl; val is_isltu_reg = f.isltu
    val is_rvx_reg = f.rvx; val rvx_op_reg = f.rvxOp
    val w     = config.totalBits          // 16
    val mantN = config.sig - 1            // 10 stored mantissa bits
    val bias  = (1 << (config.exp - 1)) - 1   // 15

    // Shift amount: covers the full 0..w-1 range (4 bits sufficed only for
    // w=16; w=32 needs 5).
    val shamt = recB_raw(log2Ceil(w) - 1, 0)
    val iadd = (recA_raw +& recB_raw)(w - 1, 0)
    val ishl = (recA_raw << shamt)(w - 1, 0)
    val ishr = (recA_raw.asSInt >> shamt).asUInt(w - 1, 0)
    val imul = (recA_raw * recB_raw)(w - 1, 0)
    val isub = (recA_raw -& recB_raw)(w - 1, 0)
    val iand = recA_raw & recB_raw
    val ior  = recA_raw | recB_raw
    val ixor = recA_raw ^ recB_raw
    val islt = Mux(recA_raw.asSInt < recB_raw.asSInt, 1.U(w.W), 0.U(w.W))
    val isrl = (recA_raw >> shamt)(w - 1, 0)
    val isltu = Mux(recA_raw < recB_raw, 1.U(w.W), 0.U(w.W))
    val exany = Mux(anyW, 1.U(w.W), 0.U(w.W))

    // F2H/H2F: the boundary conversions of Fp16Fp32 (one narrow is already shared with the special functions).
    val f2h = Fp16Fp32.narrow(recA_raw).pad(w)
    val h2f = Fp16Fp32.widen(recA_raw(15, 0))

    val result = Mux(is_rvx_reg, RvInt.exec(rvx_op_reg, recA_raw, recB_raw, w),
                 Mux(is_iadd_reg, iadd,
                 Mux(is_ishl_reg, ishl,
                 Mux(is_ishr_reg, ishr,
                 Mux(is_imul_reg, imul,
                 Mux(is_isub_reg, isub,
                 Mux(is_iand_reg, iand,
                 Mux(is_ior_reg,  ior,
                 Mux(is_ixor_reg, ixor,
                 Mux(is_islt_reg, islt,
                 Mux(is_isrl_reg, isrl,
                 Mux(is_isltu_reg, isltu,
                 Mux(is_exany_reg, exany,
                 Mux(is_smask_reg, io.covMask.map(_.pad(w)).getOrElse(0.U(w.W)),
                 Mux(is_tld_reg, io.dstWord.map(d => Mux(tld_sel_reg, d(1), d(0)).pad(w)).getOrElse(0.U(w.W)),
                 Mux(is_f2h_reg,  f2h, h2f))))))))))))))))
    val is_int = is_rvx_reg || is_iadd_reg || is_ishl_reg || is_ishr_reg || is_imul_reg ||
                 is_isub_reg || is_iand_reg || is_ior_reg || is_ixor_reg ||
                 is_islt_reg || is_isrl_reg || is_isltu_reg || is_exany_reg || is_smask_reg || is_tld_reg || is_f2h_reg || is_h2f_reg
    (result, is_int)
  }

  private def wireWriteBack(
      fma_result: UInt, special_result: UInt,
      int_result: UInt, is_int_reg: Bool, mmio_reg_data: UInt
  ): Unit = {
    val mmio_write = io.bus.is_writing && io.bus.address >= BorgGpuRegs.gpr_offset && io.bus.address < BorgGpuRegs.imem_offset
    // A branch has no destination: its rd field carries the low bits of the
    // target address, so writing back would corrupt an unrelated register.
    // The exec mask gates the same point, which is what makes predicated
    // execution correct for everything the ALU produces; the CSR writes
    // (fragment outputs, rast_*) take the same gate.
    //
    // EXANY is the one deliberate exception to the exec-mask gate: its whole
    // purpose is to hand every lane -- INCLUDING ones the current mask has
    // masked off -- a quad-uniform "is any lane still active" value, so that
    // a lane which has already dropped out of a divergent loop still gets a
    // fresh answer every iteration. BRZ/BRNZ only ever reads lane 0's
    // operand; gating EXANY's write-back on execActive like everything else
    // would freeze lane 0's copy the moment lane 0 itself masks off, and the
    // loop could never observe every OTHER lane finishing after that.
    val wbWrites = !flagsW.branch && !flagsW.execOp && !flagsW.rvNoWrite
    val pipe_write = running && at(1) && wbWrites && (flagsW.exany || activeW)
    val w_en = mmio_write || pipe_write
    // Truncated to log2Ceil(32) for the same reason as wireGprReads' mmioAddr:
    // this branch is only selected when mmio_write is true, which bounds
    // io.bus.address to the 32-word GPR region.
    val w_addr = Mux(pipe_write, rdW, ((io.bus.address - BorgGpuRegs.gpr_offset) >> 2)(log2Ceil(32) - 1, 0))
    // is_frcp_reg/is_frsq_reg are mutually exclusive (decoded
    // from distinct funct7 values); special_result already internally
    // selects the right op's result (see computeFp16Special's `op` mux).
    val is_special_reg = flagsW.frcp || flagsW.frsq
    val w_data = Mux(pipe_write,
      Mux(is_int_reg, int_result,
        Mux(is_special_reg, special_result, fma_result)),
      io.bus.data_in(config.totalBits - 1, 0))

    writeReg(w_addr, w_en, w_data)
    io.csrWrite.en   := running && at(1) && flagsW.csrw && activeW
    io.csrWrite.addr := flagsW.csr
    io.csrWrite.data := aW

    // Memory-FSM write-back override (TEX's texel, or LOAD's word).
    // Last-connect wins over the ALU write-back above.
    when(io.memWrite.en && io.execActive) {
      writeReg(io.memWrite.addr, true.B, io.memWrite.data)
    }
  }

  private def writeReg(addr: UInt, en: Bool, data: UInt): Unit =
    (regFile +: regFilesBC).foreach { f => f.io.wr.addr := addr; f.io.wr.en := en; f.io.wr.data := data }
}
