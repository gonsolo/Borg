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
  * write-back (`memWrite`) from the shared FTEX FSM.
  *
  * Write-back addr+enable are shared (same `rd`/MMIO address, same control); only
  * the data differs per lane, so the lane computes its own data and writes its own
  * register-file copies.  `recARaw`/`recBRaw` are exported so the shared FTEX FSM
  * can drive texU/texV from this lane's operands.
  *
  * At `fragLanes==1` a single instance reproduces the original monolithic BorgCore
  * behaviour bit-for-bit.
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
  // now -- see Instructions.FUNCT7_EXANY.
  val execAny     = Input(Bool())
  // This lane's coverage mask, for SMASK.
  val covMask     = if (cfg.drawEnabled) Some(Input(UInt(cfg.samples.W))) else None
  val attIndex    = if (cfg.drawEnabled) Some(Input(UInt(2.W))) else None
  val busyCounter = Input(UInt(cfg.busyCounterWidth.W))
  val running     = Input(Bool())
  val isBusy      = Input(Bool())
  val fmaStart    = Input(Bool())
  val seqBusy     = Input(Bool())

  // --- Per-lane pixel coordinate ---
  val iter        = Input(new Coord(cfg.coordWidth))
  val pixelOrigin = Input(new Coord(14))   // the render window's origin
  // --- Compute or vertex stage: r30/r31 read these raw integers instead ---
  val ids         = if (cfg.hasInvocationIds) Some(Input(new LaneIdsIO(cfg))) else None

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
  val memWrite    = Flipped(new MemWritePort(5, cfg.totalBits))

  // --- Outputs ---
  val pipeWrite   = new PipeWriteIO(cfg.totalBits) // write-back snoop
  val regReadData = Output(UInt(cfg.totalBits.W))  // MMIO GPR read (lane 0 consumed)
  val recARaw     = Output(UInt(cfg.totalBits.W))  // operands for shared FTEX FSM
  val recBRaw     = Output(UInt(cfg.totalBits.W))
  val recCRaw     = Output(UInt(cfg.totalBits.W))  // FTEX's rs3: texture-slot select
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
  // Deliberately stays FP16-native (rasterizer coordinate generation, per
  // the branch's plan doc) regardless of cfg.fp -- widened into coordX/
  // coordY's full cfg.totalBits width via Fp16Fp32.widen below, rather than
  // relying on plain zero-extension, which is only a valid FP32 value when
  // cfg.fp is already FP16 (totalBits==16, i.e. a no-op).
  def pixelToFP16Half(i: UInt): UInt = {
    val x    = Cat(i, 1.U(1.W))
    val n    = Log2(x)
    val exp  = (n +& 14.U)(4, 0)
    val frac = (x << (10.U - n))(9, 0)
    Cat(0.U(1.W), exp, frac)
  }
  // An FP32 build builds i + 0.5 as FP32 directly: FP16 holds it exactly
  // only up to 1023.5, and a Vulkan framebuffer is 4096 wide.
  def pixelToFP32Half(i: UInt): UInt = {
    val x    = Cat(i, 1.U(1.W))                 // 2i + 1
    val n    = Log2(x)
    val exp  = (n +& 126.U)(7, 0)               // (2i + 1) / 2 = 1.f * 2^(n - 1)
    val frac = (x << (23.U - n))(22, 0)
    Cat(0.U(1.W), exp, frac)
  }
  private def coordToRegWidth(h: UInt): UInt =
    if (config.totalBits == 16) h else Fp16Fp32.widen(h)
  private def pixelCentre(i: UInt): UInt =
    if (config.totalBits == 32) pixelToFP32Half(i) else coordToRegWidth(pixelToFP16Half(i))
  private val coordReadEn = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
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
  private val recA_raw = Mux(io.funct3Del === 1.U, io.uniformData, recA)
  private val recB_raw = Mux(io.funct3Del === 2.U, io.uniformData, recB)
  private val recC_raw = Mux(io.funct3Del === 3.U, io.uniformData, recC)
  io.recARaw := recA_raw
  io.recBRaw := recB_raw
  io.recCRaw := recC_raw

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
    // A sequencer-run shader reads 0 here (legacy vertex/setup shaders use r31
    // as a zero), unless the sequencer supplies IDs -- a vertex shader's
    // VertexIndex/InstanceIndex.
    val idsMode = io.ids.map(_.mode).getOrElse(false.B)
    Mux(isCoordReg, Mux(!io.seqBusy || idsMode, Mux(idx === 30.U, coordX, coordY), 0.U), raw)
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
    // Truncated to the register file's own log2Ceil(32) address width: mmioEn
    // (below) only ever selects this value when io.bus.address falls in
    // [gpr_offset, imem_offset), which spans exactly the 32-word GPR region --
    // so the word index is always in range and the high bits Chisel's generic
    // subtract/shift width-growth adds are provably always zero here.
    val mmioAddr = ((io.bus.address - BorgGpuRegs.gpr_offset) >> 2)(log2Ceil(32) - 1, 0)

    // Issue cycles: rs1 at decode, rs2 at busy==7, rs3 at busy==6.
    val atDecode = running && !is_busy
    val atRs2 = is_busy && busy_counter === cfg.cRs2.U
    val atRs3 = is_busy && busy_counter === cfg.cRs3.U

    regFile.io.rd.addr := Mux(mmioEn, mmioAddr, Mux(atDecode, regs.rs1, Mux(atRs2, regs.rs2, regs.rs3)))
    regFile.io.rd.en   := mmioEn || atDecode || atRs2 || atRs3

    val mmioEnDel   = RegNext(mmioEn && io.bus.is_reading, false.B)
    val mmioAddrDel = RegEnable(mmioAddr, mmioEn)

    // Capture cycles: one cycle after each issue (SyncReadMem's read latency)
    // -- rs1's result lands at busy==7, rs2's at busy==6, rs3's at busy==5.
    val rs1IdxDel = RegEnable(regs.rs1, atDecode)
    val rs2IdxDel = RegEnable(regs.rs2, atRs2)
    val rs3IdxDel = RegEnable(regs.rs3, atRs3)
    val holdA = RegEnable(resolveCoordReg(regFile.io.rd.data, rs1IdxDel), 0.U(config.totalBits.W), atRs2)
    val holdB = RegEnable(resolveCoordReg(regFile.io.rd.data, rs2IdxDel), 0.U(config.totalBits.W), atRs3)
    val holdC = RegEnable(resolveCoordReg(regFile.io.rd.data, rs3IdxDel), 0.U(config.totalBits.W), is_busy && busy_counter === cfg.cHoldC.U)
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
      // opFlags.fma is the raw R4-type opcode bit, which FTEX now also sets
      // (see Instructions.FUNCT2_FTEX) -- exclude it here so a texture
      // sample doesn't needlessly run its U/V/texSelect operands through the
      // real multiply-add datapath. Harmless either way (FTEX's own result
      // always arrives via the memWrite override below, last-connect-wins
      // over whatever this path would have produced), but wasteful power and
      // muddies what "is_fma_reg" means.
      is_fma_reg := opFlags.fma && !opFlags.ftex
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
        fma.io.pipeEn1 := is_busy && busy_counter === cfg.cOperands.U
        fma.io.pipeEn2 := is_busy && busy_counter === cfg.cPipeEn2.U
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
    val wide = config.totalBits > 16
    val e32 = if (wide) recA_raw(30, 23) else 0.U(8.W)
    val eUnb = e32.zext - 127.S                         // input exponent, unbiased
    val special32 = e32 === 0.U || e32 === 255.U        // zero/denormal, inf/NaN
    val oddExp = eUnb(0) && isFrsq
    val fp16In = if (wide) {
      val scaled = Cat(recA_raw(31), Mux(oddExp, 128.U(8.W), 127.U(8.W)), recA_raw(22, 0))
      Mux(isFsrgb || special32, Fp16Fp32.narrow(recA_raw), Fp16Fp32.narrow(scaled))
    } else recA_raw(15, 0)
    val exp  = fp16In(14, 10)
    val mant = fp16In(9, 0)

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

    val srgbIdx     = Cat((exp - 1.U)(3, 0), mant(9, 6)) // 8-bit
    val srgbRawVal  = srgbRom(srgbIdx)
    val srgbRawNext = srgbRom((srgbIdx +& 1.U)(7, 0))

    // rcp/rsq LUT entries are 10-bit mantissa estimates; zero-extend to
    // Fp16Special's uniform 16-bit port (exact: delta=val-next is always
    // non-negative for these two monotonically-decreasing curves).
    val rawVal  = Mux(isFsrgb, srgbRawVal,  Mux(isFrsq, Cat(0.U(6.W), rsqRawVal),  Cat(0.U(6.W), rcpRawVal)))
    val rawNext = Mux(isFsrgb, srgbRawNext, Mux(isFrsq, Cat(0.U(6.W), rsqRawNext), Cat(0.U(6.W), rcpRawNext)))
    // Fixed at 3 regardless of cfg.fmaStages: this is the special-function
    // path, not the FMA path. Its operands come from holdA, which an extra FMA
    // stage makes available EARLIER (cRs2 moves up), and its result still has
    // to land at write-back (counter==1), which never moves.
    val valReg  = RegEnable(rawVal,  is_busy && busy_counter === 3.U)
    val nextReg = RegEnable(rawNext, is_busy && busy_counter === 3.U)

    val special = Module(new Fp16Special)
    // Fp16Special stays FP16-internal (existing LUT hardware) regardless of
    // cfg.fp -- feed it the same narrowed fp16In used for the LUT indices
    // above, widen its FP16 result back up at the output below.
    special.io.in      := fp16In
    special.io.lutVal  := valReg
    special.io.lutNext := nextReg
    special.io.op      := Mux(isFrcp, Fp16SpecialOp.Rcp, Mux(isFrsq, Fp16SpecialOp.Rsq, Fp16SpecialOp.Srgb))
    if (wide) {
      val r = Fp16Fp32.widen(special.io.out)
      // 1/(m*2^E) = (1/m)*2^-E;  1/sqrt(m*2^E) = (1/sqrt(m'))*2^-(E'/2).
      val shift = Mux(isFrsq, (eUnb - oddExp.asUInt.zext) >> 1, eUnb)
      val eOut  = r(30, 23).zext - shift
      val scaledOut = MuxCase(Cat(r(31), eOut.asUInt(7, 0), r(22, 0)), Seq(
        (eOut <= 0.S)   -> Cat(r(31), 0.U(31.W)),                      // flush to zero
        (eOut >= 255.S) -> Cat(r(31), "hFF".U(8.W), 0.U(23.W))         // overflow to inf
      ))
      Mux(isFsrgb || special32 || r(30, 23) === 255.U, r, scaledOut)
    } else special.io.out
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
    val is_isub_reg = RegInit(false.B)
    val is_iand_reg = RegInit(false.B)
    val is_ior_reg  = RegInit(false.B)
    val is_ixor_reg = RegInit(false.B)
    val is_islt_reg = RegInit(false.B)
    val is_iseq_reg = RegInit(false.B)
    val is_i2f_reg  = RegInit(false.B)
    val is_f2i_reg  = RegInit(false.B)
    val is_exany_reg = RegInit(false.B)
    val is_smask_reg = RegInit(false.B)
    val is_attidx_reg = RegInit(false.B)
    val is_isrl_reg = RegInit(false.B); val is_isltu_reg = RegInit(false.B)
    when(start) {
      is_isrl_reg := opFlags.isrl; is_isltu_reg := opFlags.isltu
      is_smask_reg := opFlags.smask
      is_attidx_reg := opFlags.attidx
      is_iadd_reg := opFlags.iadd
      is_ishl_reg := opFlags.ishl
      is_ishr_reg := opFlags.ishr
      is_imul_reg := opFlags.imul
      is_isub_reg := opFlags.isub
      is_iand_reg := opFlags.iand
      is_ior_reg  := opFlags.ior
      is_ixor_reg := opFlags.ixor
      is_islt_reg := opFlags.islt
      is_iseq_reg := opFlags.iseq
      is_i2f_reg  := opFlags.i2f
      is_f2i_reg  := opFlags.f2i
      is_exany_reg := opFlags.exany
    }
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
    val iseq = Mux(recA_raw === recB_raw, 1.U(w.W), 0.U(w.W))
    val exany = Mux(io.execAny, 1.U(w.W), 0.U(w.W))

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
                 Mux(is_isub_reg, isub,
                 Mux(is_iand_reg, iand,
                 Mux(is_ior_reg,  ior,
                 Mux(is_ixor_reg, ixor,
                 Mux(is_islt_reg, islt,
                 Mux(is_isrl_reg, isrl,
                 Mux(is_isltu_reg, isltu,
                 Mux(is_iseq_reg, iseq,
                 Mux(is_exany_reg, exany,
                 Mux(is_smask_reg, io.covMask.map(_.pad(w)).getOrElse(0.U(w.W)),
                 Mux(is_attidx_reg, io.attIndex.map(_.pad(w)).getOrElse(0.U(w.W)),
                 Mux(is_i2f_reg,  i2f, f2i))))))))))))))))
    val is_int = is_iadd_reg || is_ishl_reg || is_ishr_reg || is_imul_reg ||
                 is_isub_reg || is_iand_reg || is_ior_reg || is_ixor_reg ||
                 is_islt_reg || is_isrl_reg || is_isltu_reg || is_iseq_reg || is_exany_reg || is_smask_reg || is_attidx_reg || is_i2f_reg || is_f2i_reg
    (result, is_int)
  }

  private def wireWriteBack(
      fma_result: UInt, fstep_result: UInt, special_result: UInt,
      is_fstep_reg: Bool, is_frcp_reg: Bool, is_frsq_reg: Bool, is_fsrgb_reg: Bool,
      int_result: UInt, is_int_reg: Bool, mmio_reg_data: UInt
  ): Unit = {
    val mmio_write = io.bus.is_writing && io.bus.address >= BorgGpuRegs.gpr_offset && io.bus.address < BorgGpuRegs.imem_offset
    // A branch has no destination: its rd field carries the low bits of the
    // target address, so writing back would corrupt an unrelated register.
    // The exec mask gates the same point, which is what makes predicated
    // execution correct for everything the ALU produces -- including the
    // fragment outputs r24..r29, since the dispatcher snoops them through
    // this very port.
    //
    // EXANY is the one deliberate exception to the exec-mask gate: its whole
    // purpose is to hand every lane -- INCLUDING ones the current mask has
    // masked off -- a quad-uniform "is any lane still active" value, so that
    // a lane which has already dropped out of a divergent loop still gets a
    // fresh answer every iteration. BRZ/BRNZ only ever reads lane 0's
    // operand; gating EXANY's write-back on execActive like everything else
    // would freeze lane 0's copy the moment lane 0 itself masks off, and the
    // loop could never observe every OTHER lane finishing after that.
    val pipe_write = running && is_busy && busy_counter === 1.U &&
                     !io.opFlags.branch && !io.opFlags.execOp &&
                     (io.opFlags.exany || io.execActive)
    val w_en = mmio_write || pipe_write
    // Truncated to log2Ceil(32) for the same reason as wireGprReads' mmioAddr:
    // this branch is only selected when mmio_write is true, which bounds
    // io.bus.address to the 32-word GPR region.
    val w_addr = Mux(pipe_write, regs.rd, ((io.bus.address - BorgGpuRegs.gpr_offset) >> 2)(log2Ceil(32) - 1, 0))
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

    // Memory-FSM write-back override (FTEX's texel triple, or LOAD's word).
    // Last-connect wins, matching the original wireTexStall-after-
    // wireWriteBack ordering. Named memWrite rather than texWrite since
    // FTEX is no longer its only driver.
    when(io.memWrite.en && io.execActive) {
      writeReg(io.memWrite.addr, true.B, io.memWrite.data)
      io.pipeWrite.en   := true.B
      io.pipeWrite.addr := io.memWrite.addr
      io.pipeWrite.data := io.memWrite.data
    }
  }

  private def writeReg(addr: UInt, en: Bool, data: UInt): Unit = {
    regFile.io.wr.addr := addr
    regFile.io.wr.en := en
    regFile.io.wr.data := data
  }
}
