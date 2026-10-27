// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.*
import chisel3.util.*

/** BorgCore — shared shader-core control: instruction memory, program counter,
  * the multi-cycle pipeline FSM, instruction decode, the (shared) uniform RAM,
  * and MMIO/DMA write routing.
  *
  * The per-lane datapath (register files, coordinate expansion, FP16 ALU,
  * write-back) lives in [[BorgLane]], instantiated `cfg.fragLanes`
  * times.  At `fragLanes==1` (current default) a single lane reproduces the
  * original monolithic core bit-for-bit.
  *
  * Pipeline timing (an instruction moves through stages cfg.cBusyLoad→1; 7→1 at fmaStages=3,
  * 8→1 at 4, 9→1 at 5 -- see BorgConfig's cPipeEn2/cOperands/cRs2 table):
  *   cRs2..cHoldC: rs1/rs2/rs3 read serially, one per cycle, off the lane's single
  *         register-file port (was 3 parallel ports/copies -- see BorgLane's
  *         `regFile` doc comment for the area rationale); each result is
  *         captured into a hold register the cycle after it's issued. With
  *         cfg.regReadPorts == 3 all three are read at decode (5→1 at fmaStages=3).
  *   cOperands..2: Stage 1 — operand reads valid (now held, not live); op-type flags
  *         latched (in the lane) at decode
  *   2→1:  Lane pipeline register captures the FMA mid-result
  *   1:    Stage 2 — round; register file written
  *   none: idle — next instruction can start
  */

/** One instruction-cache line's state when it lives in a RAM (BorgCore.icacheLinesRam). */
class ICacheLine(tagBits: Int) extends Bundle {
  val valid = Bool()
  val epoch = UInt(ICacheLine.EpochBits.W)
  val far   = Bool()
  val tag   = UInt(tagBits.W)
}
object ICacheLine { val EpochBits = 8 }

class BorgCoreIO(val cfg: BorgConfig) extends Bundle {
  // MMIO bus
  val bus = Flipped(new BorgBusIO())

  // Rasterizer interface — per-lane pixel coordinates (2×2 quad at fragLanes=4)
  val iter               = Input(Vec(cfg.fragLanes, new Coord(cfg.coordWidth)))
  // The render window's origin in pixels: pixel centres (r30/r31,
  // FragCoord) are the framebuffer's, not the window's.
  val pixelOrigin        = Input(new Coord(14))
  // The colour attachment the current tile pass renders (ATTIDX).
  val attIndex           = if (cfg.drawEnabled) Some(Input(UInt(3.W))) else None   // attachment + 4 * slice
  // Per lane, the colour attachment's word at its pixel (TLD).
  val laneDst            = if (cfg.drawEnabled) Some(Input(Vec(cfg.fragLanes, Vec(2, UInt(32.W))))) else None
  val coreTrigger       = Flipped(new CoreTriggerIO)  // pulse from rasterizer: trigger shader
  val uniformPage        = Input(UInt(1.W))      // which 32-entry uniform page the GPU reads from

  // Control signals from SystemRDL register block
  val control = Input(new CoreControlIO)

  // DMA write ports (Step 22.1): DMA takes priority over MMIO writes
  val dmaImemWrite    = Flipped(new MemWritePort(7, 32)) // 7-bit: IMEM up to 72 entries
  val dmaUniformWrite = Flipped(new MemWritePort(6, cfg.totalBits))

  // Pipeline write-back snoop, per lane (exposed to rasterizer + sequencer)
  val csrWrite  = Vec(cfg.fragLanes, new CsrWriteIO(cfg.totalBits))

  // Status outputs (exposed to rasterizer and top-level read mux)
  val status = new CoreStatusIO

  // MMIO register read data (for top-level read mux)
  val regReadData = Output(UInt(cfg.totalBits.W))

  // Step 30.1d: when sequencer is running vertex/setup shaders, r30/r31 must
  // return 0 (not coordX/coordY) because those shaders use r31 as zero.
  val seqBusy = Input(Bool())

  // LOAD/STORE: the core's own DRAM port, given directly to the core rather
  // than threaded through the dispatcher -- Borg.scala already arbitrates
  // memory masters with a priority mux, so adding one more is a smaller
  // change than routing a second memory protocol through BorgRasterizer and
  // BorgShaderDispatcher.
  val gpuMem  = if (cfg.hasMemoryOps) Some(new GpuMemIO) else None
  val memBusy = Output(Bool())       // high while a LOAD/STORE owns the bus
  // Sticky: a branch condition differed between quad lanes. Deliberately NOT
  // a CoreStatusIO field -- that bundle is pipeline feedback consumed by the
  // rasterizer, the dispatcher and both sequencers, none of which care about
  // this. It is a top-level status bit for the MMIO register and nothing
  // else, so it lives here rather than making three unrelated modules thread
  // a field they ignore. See wireBranch for what it means.
  val branchDivergent = Output(Bool())
  // Sticky: the execution-mask stack over- or underflowed, i.e. the shader's
  // EXPUSH/EXPOP pairs are unbalanced or nested deeper than 8. Any such
  // program produces wrong results; this makes that detectable rather than
  // silent, same reasoning as branchDivergent.
  val execFault = Output(Bool())
  val root    = Input(UInt(32.W))                          // CSR root (ISA v2)

  // Raw invocation IDs for r30/r31 and the lanes a trigger starts with:
  // compute's (BorgComputeSequencer), or a vertex shader's VertexIndex and
  // InstanceIndex (the draw walker).
  val ids = if (cfg.hasInvocationIds) Some(Input(new InvocationIdsIO(cfg))) else None
  // Compute mode, the other direction: did the invocation that just stopped
  // (coreStatus.running -> false) stop at a BARRIER, and if so, where should
  // it resume. Valid from the same cycle running drops until the next
  // invocation starts; BorgComputeSequencer reads it off coreStatus's own
  // running-drop detection, so there is no separate handshake.
  val barrier = if (cfg.computeEnabled) Some(Output(new ComputeBarrierIO)) else None

  // ZTEST (early per-fragment tests): the core stalls on zTestReq until the
  // dispatcher has run the quad's depth/stencil test and pulses zTestDone.
  val zTestReq  = Output(Bool())
  val zTestDone = Input(Bool())
  // Per lane: this invocation must have no side effects -- a helper lane
  // (no covered sample, outside the scissor), a discarded fragment, or one
  // that failed ZTEST. Suppresses STORE; only exists with memory ops, the
  // only side effect a shader has.
  val laneHelper = if (cfg.hasMemoryOps) Some(Input(Vec(cfg.fragLanes, Bool()))) else None

  // Instruction cache (BorgConfig.hasShaderICache): the running program's
  // image in DRAM -- word `pc` lives at codeBase + 4*pc -- and a pulse that
  // forgets every word fetched from it, raised whenever the program changes.
  val codeBase    = if (cfg.shaderICacheEnabled) Some(Input(UInt(GpuMemIO.AddrBits.W))) else None
  val icacheFlush = if (cfg.shaderICacheEnabled) Some(Input(Bool())) else None

  // Draw front end: where SOUT and FATTR go (see Opcodes.MATCH_SOUT_V2).
  val record = if (cfg.drawEnabled) Some(Input(new CoreRecordIO)) else None
  // DRAW_CFG's mode: the raster ROM runs the draw front end's program.
  val drawMode = if (cfg.drawEnabled) Some(Input(Bool())) else None
  // One sample: the raster ROM stops before the per-sample depths.
  val drawSingle = if (cfg.drawEnabled) Some(Input(Bool())) else None
  val setupShort = if (cfg.drawEnabled) Some(Input(Bool())) else None   // single sample, no depth bias
  // A pulse that forgets the texture unit's cached descriptors (DESC_FLUSH).
  val descWritten  = if (cfg.samplerEnabled) Some(Input(Bool())) else None
  // Per lane, the samples the current fragment covers (SMASK).
  val laneCoverage = if (cfg.drawEnabled) Some(Input(Vec(cfg.fragLanes, UInt(cfg.samples.W)))) else None
  val issueStats   = Output(new IssueStatsIO)
}

/** The triangle record the running shader writes (SOUT) or reads (FATTR),
  * driven by the sequencers. See docs/B1_geometry_front_end.md. */
class CoreRecordIO extends Bundle {
  /** Byte address output component 0 goes to. */
  val outBase       = UInt(GpuMemIO.AddrBits.W)
  /** Vertex stage: component c of corner k at outBase + 4*(3c + k). Otherwise
    * (the setup ROM) at outBase + 4c. */
  val outInterleave = Bool()
  /** Corner of lane 0; lane i is corner outCorner + i. A single-lane build
    * runs the vertex shader once per corner and steps this instead. */
  val outCorner     = UInt(2.W)
  /** Byte address of component 0's three per-vertex values, for FATTR. */
  val attrBase      = UInt(GpuMemIO.AddrBits.W)
  /** One-cycle pulse when a draw's Pass 2 starts: every record has just been
    * (re)written by Pass 1, so FATTR's varying cache forgets what it holds. */
  val attrFlush     = Bool()
}

/** One cycle's issue outcome, for the perf counters. */
class IssueStatsIO extends Bundle {
  val issued     = Bool()
  val waitReg    = Bool()
  val waitBranch = Bool()
  val waitDrain  = Bool()
}

/** What the issue logic needs to know about an instruction in flight (cfg.pipelined). */
class StageInfo extends Bundle {
  val writes = Bool()
  val rd     = UInt(5.W)
  val branch = Bool()
  val drain  = Bool()   // a stall FSM runs it: issues into empty stages, nothing issues behind it
}

class BorgCore(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgCoreIO(cfg))

  private val config = cfg.fp  // shorthand for FP config used in arithmetic

  // @doc:storage
  // --- Shared storage ---
  val instructionMemory = SyncReadMem(cfg.maxInstructions, UInt(32.W))
  val programCounter = RegInit(0.U(cfg.pcBits.W))
  val running = RegInit(false.B)
  val auto_run_pending = RegInit(false.B)
  val running_by_rasterizer = RegInit(false.B)

  // Baked rasterizer edge-test shader (see BorgRasterRom) -- a permanent ROM,
  // fetched instead of instructionMemory while fetchRast is latched. Timing-
  // matched to instructionMemory.read(nextPC)'s 1-cycle synchronous latency by
  // registering the address, not the (combinational) ROM output.
  val rasterRom  = VecInit(BorgRasterRom.instructions.map(_.U(32.W)))
  val fetchRast  = RegInit(false.B)
  // The draw front end's triangle setup (BorgSetupRom), fetched the same way.
  val setupRom   = Option.when(cfg.drawEnabled)(VecInit(BorgSetupRom.instructions.map(_.U(32.W))))
  val fetchSetup = RegInit(false.B)
  // ...and its per-pixel program, which replaces the edge test in draw mode.
  val drawRasterRom = Option.when(cfg.drawEnabled)(VecInit(BorgRasterRom.drawInstructions.map(_.U(32.W))))

  val uniformMem = SyncReadMem(cfg.maxUniforms, UInt(config.totalBits.W))
  // @doc:end

  // --- Pipeline Control ---
  // Bit k - 1: an instruction in stage k, counting down from cfg.cBusyLoad (operands read) to 1
  // (write-back). The stall FSMs hold or squash it.
  val stages = RegInit(0.U(cfg.cBusyLoad.W))
  def at(k: Int): Bool = stages(k - 1)
  val is_busy = stages.orR
  val holdPipe = WireDefault(false.B); val squashPipe = WireDefault(false.B)

  // --- Branch decision (declared before the fetch that consumes it) ---
  //
  // Registered, not combinational, and that is required rather than tidy:
  // nextPC feeds the instruction-memory read, whose output is decoded to
  // produce the branch condition. Deriving `taken` combinationally from the
  // instruction currently being fetched would close a loop through the
  // memory read. Latching the decision mid-execution breaks it -- by the
  // time the PC is redirected the branch has long since been decoded.
  val brTakenReg  = RegInit(false.B)
  val brTargetReg = RegInit(0.U(cfg.pcBits.W))
  // Sticky: set by a non-uniform branch condition, cleared only by a core
  // reset. See wireBranch's doc for why this exists rather than a choice of
  // which lane to believe.
  val branchDivergent = RegInit(false.B)

  // --- Execution mask (divergent control flow) ---------------------------
  //
  // One bit per lane. All-ones means every lane is running, which is the
  // state a shader with no EXPUSH stays in forever -- so a program that
  // never uses these instructions behaves exactly as before.
  //
  // The stack holds the ENCLOSING mask at each nesting level, so EXELSE can
  // compute `enclosing & ~exec` and EXPOP can restore. Depth 8 is 8 x
  // fragLanes bits (32 FFs at fragLanes=4) and nests deeper than any shader
  // Borg's instruction memory could hold; overflow and underflow are still
  // flagged rather than silently wrapping.
  val EXEC_STACK_DEPTH = 8
  val execMask  = RegInit(((1 << cfg.fragLanes) - 1).U(cfg.fragLanes.W))
  val execStack = Reg(Vec(EXEC_STACK_DEPTH, UInt(cfg.fragLanes.W)))
  val execSp    = RegInit(0.U(log2Ceil(EXEC_STACK_DEPTH + 1).W))
  val execFault = RegInit(false.B)   // sticky: stack over/underflow

  // --- Compute: BARRIER stop point (BorgConfig.computeEnabled) -----------
  //
  // Not sticky across invocations: written on every stop (either branch of
  // runPipeline's BARRIER/HALT check below), so a stale true from a PRIOR
  // invocation can never survive into the next one's read.
  val atBarrierReg = RegInit(false.B)
  val barrierPCReg = RegInit(0.U(cfg.pcBits.W))

  // --- Instruction Fetch ---
  // cfg.pipelined: the PC advances at issue, a branch's one stage after its operands.
  private val redirect = if (cfg.pipelined) cfg.cOperands - 1 else 1
  val pcAfterThis = Mux(brTakenReg, brTargetReg, programCounter + 1.U)
  val nextPC = Wire(UInt(cfg.pcBits.W))
  val rasterRomAddrReg = RegNext(nextPC)   // the PC whose word fetchedInstruction holds
  private val rasterWord = drawRasterRom.map { rom =>
    val short = io.drawSingle.get && rasterRomAddrReg === BorgRasterRom.drawSampleDepthPc.U
    Mux(io.drawMode.get, Mux(short, 0.U, rom(rasterRomAddrReg)), rasterRom(rasterRomAddrReg))
  }.getOrElse(rasterRom(rasterRomAddrReg))
  val fetchedInstruction =
    Mux(fetchRast, rasterWord,
      setupRom.map { rom =>
        val stop = io.setupShort.get && rasterRomAddrReg === BorgSetupRom.shortPc.U
        Mux(fetchSetup, Mux(stop, 0.U, rom(rasterRomAddrReg)), instructionMemory.read(imemIndex(nextPC)))
      }
        .getOrElse(instructionMemory.read(imemIndex(nextPC))))
  // False while the fetched word is not the program's word at that PC (an
  // instruction-cache miss or fill); nothing issues until it is. Always true
  // without the cache.
  val fetchReady = WireDefault(true.B)
  // Cache fill: shares IMEM's single write port with DMA/MMIO (see runPipeline).
  val fillWriteEn   = WireDefault(false.B)
  val fillWriteIdx  = WireDefault(0.U(log2Ceil(cfg.maxInstructions).W))
  val fillWriteData = WireDefault(0.U(32.W))
  // DMA/MMIO write this cycle, for the cache's bookkeeping.
  val extWriteEn  = WireDefault(false.B)
  val extWriteIdx = WireDefault(0.U(log2Ceil(cfg.maxInstructions).W))

  // Resume delay: after a stall FSM's writeback completes, the IMEM still
  // holds the stale opcode (1-cycle SyncReadMem latency); suppress restart
  // for 1 cycle. Shared by every multi-cycle FSM below (LOAD/STORE, ZTEST,
  // TEX/TEXA).
  val texResumeDelay = RegInit(false.B)
  when(texResumeDelay) { texResumeDelay := false.B }

  // --- Decode + FSM ---
  val (regs, opFlags) = decode(fetchedInstruction)
  val next = RvInt(fetchedInstruction)
  val issued = Wire(new StageInfo)
  issued.writes := !opFlags.branch && !opFlags.execOp && !opFlags.rvNoWrite
  issued.rd     := regs.rd
  issued.branch := opFlags.branch
  issued.drain  := opFlags.load || opFlags.store || opFlags.sout || opFlags.fattr || opFlags.tex ||
    opFlags.texa || opFlags.texs || opFlags.ztest || next.fexp2 || next.flog2
  val canIssue = WireDefault(!is_busy)
  val decoding = running && canIssue
  val fma_start = decoding && !texResumeDelay && fetchReady && fetchedInstruction =/= 0.U
  val redirecting = WireDefault(at(redirect))   // the PC goes to pcAfterThis
  nextPC := Mux(redirecting, pcAfterThis, programCounter)
  io.issueStats := 0.U.asTypeOf(io.issueStats)
  io.issueStats.issued := fma_start && !opFlags.barrier
  if (cfg.pipelined) {
    // info(k): the instruction in stage k, valid while at(k).
    val infos = Seq.iterate(RegEnable(issued, fma_start), cfg.cBusyLoad)(r => RegEnable(r, !holdPipe))
    def info(k: Int): StageInfo = infos(cfg.cBusyLoad - k)
    def any(ks: Seq[Int])(p: StageInfo => Bool): Bool = ks.map(k => at(k) && p(info(k))).reduce(_ || _)
    val reads = Seq(next.readsRs1 -> regs.rs1, next.readsRs2 -> regs.rs2, next.readsRs3 -> regs.rs3)
    // No forwarding: a source waits until its writer has passed write-back.
    val hazard = any(1 to cfg.cBusyLoad)(i => i.writes && i.rd =/= 0.U &&
      reads.map { case (used, rs) => used && rs === i.rd }.reduce(_ || _))
    val stops = fetchedInstruction === 0.U || opFlags.barrier || issued.drain
    val behindBranch = any(redirect to cfg.cBusyLoad)(_.branch)
    canIssue := !hazard && !behindBranch && !any(1 to cfg.cBusyLoad)(_.drain) &&
      (!stops || !stages(cfg.cBusyLoad - 1, 1).orR)
    val waiting = running && !texResumeDelay && fetchReady && !canIssue
    io.issueStats.waitReg    := waiting && hazard
    io.issueStats.waitBranch := waiting && !hazard && behindBranch
    io.issueStats.waitDrain  := waiting && !hazard && !behindBranch
    val step = fma_start && !opFlags.branch && !issued.drain && !opFlags.barrier
    redirecting := at(redirect) && info(redirect).branch
    nextPC := Mux(redirecting, pcAfterThis, Mux(step, programCounter + 1.U, programCounter))
  }
  runPipeline(fma_start)
  // The flags of the instruction at its operands (stage cOperands).
  val flagsX = if (cfg.pipelined)
    BorgLane.later(cfg, !holdPipe)(RegEnable(opFlags, 0.U.asTypeOf(opFlags), fma_start), cfg.cBusyLoad - cfg.cOperands)
  else opFlags

  // --- Shared uniform-RAM read (broadcast to every lane) ---
  val (uniform_data, funct3_del) = wireUniformRead()
  val rv_ov = wireRvOperands()

  // --- Per-lane datapath ---
  val lanes = Seq.fill(cfg.fragLanes)(Module(new BorgLane(cfg)))
  lanes.foreach { lane =>
    lane.io.regs        := regs
    lane.io.opFlags     := opFlags
    lane.io.stages      := stages
    lane.io.running     := running
    lane.io.fmaStart    := fma_start
    lane.io.decode      := decoding
    lane.io.advance     := !holdPipe
    lane.io.seqBusy     := io.seqBusy
    lane.io.uniformData := uniform_data
    lane.io.funct3Del   := funct3_del
    lane.io.ovA         := rv_ov._1
    lane.io.ovB         := rv_ov._2
    // MMIO bus is broadcast (all lanes are slaves seeing the same transaction).
    lane.io.bus.address    := io.bus.address
    lane.io.bus.data_in    := io.bus.data_in
    lane.io.bus.is_writing := io.bus.is_writing
    lane.io.bus.is_reading := io.bus.is_reading
  }
  // Per-lane execution mask bit.
  lanes.zipWithIndex.foreach { case (lane, i) => lane.io.execActive := execMask(i) }
  // Broadcast (one shared reduction, not per-lane): EXANY's "is any lane in
  // the current mask still active" result. Read live at write-back time, not
  // latched -- execMask only changes via another EXPUSH/EXELSE/EXPOP, which
  // cannot execute concurrently with this instruction, so it is stable for
  // EXANY's whole multi-cycle execution window.
  lanes.foreach(_.io.execAny := execMask.orR)
  lanes.zipWithIndex.foreach { case (lane, i) =>
    lane.io.covMask.foreach(_ := io.laneCoverage.get(i))
    lane.io.dstWord.foreach(_ := io.laneDst.get(i))
  }

  io.ids.foreach { c =>
    lanes.zipWithIndex.foreach { case (lane, i) =>
      lane.io.ids.get.mode := c.mode
      lane.io.ids.get.r30  := c.r30(i)
      lane.io.ids.get.r31  := c.r31(i)
    }
  }

  // Per-lane pixel coordinate (2×2 quad fanned out by the iterator).
  lanes.zipWithIndex.foreach { case (lane, i) =>
    lane.io.iter := io.iter(i)
    lane.io.pixelOrigin := io.pixelOrigin
  }

  // Each lane exposes its own CSR writes; lane 0 drives the MMIO read.
  lanes.zipWithIndex.foreach { case (lane, i) => io.csrWrite(i) := lane.io.csrWrite }
  io.regReadData := lanes(0).io.regReadData
  io.status.running := running
  io.status.autoRunPending := auto_run_pending
  io.branchDivergent := branchDivergent
  io.execFault       := execFault
  io.barrier.foreach { b =>
    b.hit      := atBarrierReg
    b.resumePC := barrierPCReg
  }

  // Default write-back port: every FSM below (LOAD/STORE, TEX/TEXA) shares
  // this Vec and drives it only inside its own `when`, one at a time (they
  // stall the whole core, so at most one is ever active) -- last-connect-wins
  // over this default for every cycle none of them is.
  lanes.map(_.io.memWrite).foreach { mw => mw.en := false.B; mw.addr := 0.U; mw.data := 0.U }

  // --- ZTEST: stall while the dispatcher runs the early per-fragment tests ---
  wireZTest()

  // --- Instruction cache: must follow wireMemStall, whose port it shares ---

  // --- LOAD/STORE FSM (shared): same stall shape, same write-back port ---
  if (cfg.hasMemoryOps)
    wireMemStall(lanes.map(_.io.recARaw), lanes.map(_.io.recBRaw), lanes.map(_.io.recCRaw), lanes.map(_.io.memWrite))
  else
    io.memBusy := false.B
  if (cfg.shaderICacheEnabled) wireICache()
  // --- TEX/TEXA: the descriptor-based texture unit, on the same port ---
  if (cfg.samplerEnabled)
    wireSampler(lanes.map(_.io.recARaw), lanes.map(_.io.recBRaw), lanes.map(_.io.recCRaw), lanes.map(_.io.memWrite))

  // --- FEXP2/FLOG2: one unit, the lanes in turn, on the same port ---
  wireExpLog(lanes.map(_.io.recARaw), lanes.map(_.io.memWrite))

  // --- Branch evaluation and execution mask ---
  if (cfg.hasControlFlow) {
    wireBranch(lanes.map(_.io.recARaw), lanes.map(_.io.recBRaw))
    wireExecMask(lanes.map(_.io.recARaw))
  }

  // --- Quad derivatives (DDX/DDY): broadcast cross-lane operands to every lane.
  //   ddx = lane1 - lane0, ddy = lane2 - lane0 (constant across a flat 2×2 quad:
  //   lane0=TL, lane1=TR, lane2=BL, lane3=BR — see BorgIterator).  Each lane's FMA
  //   computes crossA + crossC where crossC = -lane0 (fp16-negated), so the result
  //   is the neighbour difference.  At fragLanes=1 the neighbours fall back to lane0
  //   → derivative 0 (DDX/DDY are only emitted for the 4-lane fragment core). */
  {
    val recAvec = VecInit(lanes.map(_.io.recARaw))
    def pick(i: Int): UInt = if (i < cfg.fragLanes) recAvec(i) else recAvec(0)
    def neg(x: UInt): UInt = Cat(~x(cfg.totalBits - 1), x(cfg.totalBits - 2, 0))
    // Coarse: the quad's top row / left column for every lane. Fine (rs2 = 1,
    // SPIR-V DPdxFine/DPdyFine, core with DerivativeControl): each lane's own
    // row for x (lanes 1-0 or 3-2) and own column for y (2-0 or 3-1).
    lanes.zipWithIndex.foreach { case (lane, i) =>
      val (xHi, xLo) = if ((i & 2) != 0) (3, 2) else (1, 0)
      val (yHi, yLo) = if ((i & 1) != 0) (3, 1) else (2, 0)
      val hi = Mux(flagsX.fine, Mux(flagsX.ddy, pick(yHi), pick(xHi)), Mux(flagsX.ddy, pick(2), pick(1)))
      val lo = Mux(flagsX.fine, Mux(flagsX.ddy, pick(yLo), pick(xLo)), pick(0))
      lane.io.crossA := hi
      lane.io.crossC := neg(lo)
    }
  }

  // =========================================================================
  // Helper functions
  // =========================================================================

  // @doc:instruction-format
  /** Decode a 32-bit RISC-V instruction into register indices and op flags. */
  private def decode(instr: UInt) = {
    val regs = Wire(new RegIndices())
    val sys = RvInt(instr)
    regs.rs1 := Mux(sys.csrReg, sys.sysReg, Mux(sys.csrWin, sys.sysWin, Instructions.BF_RS1(instr)))
    regs.rs2 := Instructions.BF_RS2(instr)
    regs.rs3 := Mux(sys.amocas, Instructions.BF_RD(instr), Instructions.BF_RS3(instr))
    regs.rd  := Instructions.BF_RD(instr)

    val flags = Wire(new FpuOpFlags())
    val rv = RvInt(instr)
    flags.mul   := rv.fmul
    flags.fma   := rv.r4
    flags.negC  := rv.fsub || rv.fmsub || rv.fnmadd
    flags.negProd := rv.fnmadd || rv.fnmsub
    flags.frcp  := rv.frcp
    flags.iadd  := rv.add
    flags.ishl  := rv.sll
    flags.ishr  := rv.sra
    flags.imul  := rv.mul
    flags.isub  := rv.sub
    flags.iand  := rv.and
    flags.ior   := rv.or
    flags.ixor  := rv.xor
    flags.islt  := rv.slt
    flags.f2h   := rv.fcvtHS
    flags.h2f   := rv.fcvtSH
    flags.frsq  := rv.frsq
    flags.ddx   := rv.ddx
    flags.ddy   := rv.ddy
    flags.fine  := Instructions.BF_RS2(instr) === 1.U     // DDX/DDY: fine (per row/column)
    // A gated-out opcode decodes as false everywhere rather than falling
    // through to some other op's flag: an absent instruction must be inert,
    // not accidentally an ADD.
    flags.load  := (if (cfg.hasMemoryOps) rv.load || rv.amo else false.B)
    flags.store := (if (cfg.hasMemoryOps) rv.store else false.B)
    val cf = cfg.hasControlFlow
    flags.branch := rv.br || rv.jal
    flags.expush := (if (cf) rv.expush else false.B)
    flags.exelse := (if (cf) rv.exelse else false.B)
    flags.expop  := (if (cf) rv.expop else false.B)
    flags.execOp := flags.expush || flags.exelse || flags.expop
    flags.barrier := (if (cfg.computeEnabled) rv.barrier else false.B)
    // With the execution mask, like EXPUSH/EXELSE/EXPOP: a fragment shader's
    // divergent loop needs it as much as a compute shader's.
    flags.exany  := (if (cf) rv.exany else false.B)
    flags.isrl   := rv.srl
    flags.isltu  := rv.sltu
    flags.ztest  := rv.ztest
    val draw = cfg.drawEnabled
    flags.sout   := (if (draw) rv.sout else false.B)
    flags.fattr  := (if (draw) rv.fattr else false.B)
    val smp = cfg.samplerEnabled
    flags.tex    := (if (smp) rv.texd else false.B)
    flags.texs   := (if (smp) rv.texs else false.B)
    flags.smask  := (if (draw) rv.csrMask else false.B)
    flags.tld    := (if (draw) rv.tld else false.B)
    flags.texa   := (if (smp) rv.texad else false.B)
    flags.funct3 := Mux(rv.csrWin, 1.U, Mux(rv.tld, Instructions.BF_RS2(instr)(0), 0.U))
    flags.rvNoWrite := rv.any && regs.rd === 0.U || rv.csrrw
    flags.csrw := rv.csrrw
    flags.rvx  := rv.rvx
    flags.rvxOp := rv.rvxOp
    flags.csr  := rv.csr

    (regs, flags)
  }
  // @doc:end

  /** Fetch/execute FSM: issue into the stages, advance them, advance the PC. */
  private def runPipeline(fma_start: Bool): Unit = {
    // @doc:fetch-execute
    stages := Cat(fma_start && !opFlags.barrier, stages(cfg.cBusyLoad - 1, 1))
    programCounter := nextPC
    when(decoding && !texResumeDelay && fetchReady) {
      when(fetchedInstruction === 0.U) {
        running      := false.B
        atBarrierReg := false.B
      }.elsewhen(opFlags.barrier) {
        // Zero-latency stop, same as HALT above -- the difference is only in
        // what BorgComputeSequencer does with it (resume this same invocation
        // one word past BARRIER next segment, rather than treating it as
        // finished). BARRIER is never a branch, so the resume address is
        // simply the next word -- no need for pcAfterThis's brTakenReg mux.
        running      := false.B
        atBarrierReg := true.B
        barrierPCReg := (programCounter + 1.U)(cfg.pcBits - 1, 0)
      }
    }

    when(io.control.start) {
      running := true.B
      running_by_rasterizer := false.B
      fetchRast := false.B  // CPU/MMIO-driven runs always fetch from writable IMEM
      fetchSetup := false.B
    }
    when(io.control.reset) {
      programCounter := io.control.startPC
      running := false.B
      stages := 0.U
    }
    when(holdPipe) { stages := stages }
    when(squashPipe) { stages := 0.U }
    // @doc:end

    when(io.coreTrigger.valid) {
      programCounter := io.coreTrigger.pc(cfg.pcBits - 1, 0)
      auto_run_pending := true.B
      running_by_rasterizer := true.B
      fetchRast := io.coreTrigger.isRast
      fetchSetup := io.coreTrigger.isSetup
      if (BorgDebug.trace) printf("[CORE] coreTrigger pc=%d isRast=%d\n",
        io.coreTrigger.pc, io.coreTrigger.isRast)
    }

    when(auto_run_pending) {
      running := true.B
      auto_run_pending := false.B
    }

    // IMEM write: DMA has priority; MMIO always present.
    val mmioImemWrite =
        io.bus.is_writing && io.bus.address >= BorgGpuRegs.imem_offset && io.bus.address < 416.U // 128 + 72*4
    val imemWen  = io.dmaImemWrite.en || mmioImemWrite
    val imemAddr = Mux(io.dmaImemWrite.en, io.dmaImemWrite.addr,
                       (io.bus.address - BorgGpuRegs.imem_offset) >> 2)
    val imemData = Mux(io.dmaImemWrite.en, io.dmaImemWrite.data, io.bus.data_in)
    // One write port, shared with the instruction-cache fill (which only runs
    // while a shader is executing, never alongside a DMA/MMIO load). An
    // address past IMEM is dropped: a preload longer than IMEM leaves the
    // rest of the program to the cache.
    val extWrite = imemWen && imemAddr < cfg.maxInstructions.U
    extWriteEn  := extWrite
    extWriteIdx := imemAddr.pad(log2Ceil(cfg.maxInstructions))(log2Ceil(cfg.maxInstructions) - 1, 0)
    when(extWrite || fillWriteEn) {
      instructionMemory.write(Mux(fillWriteEn, fillWriteIdx, extWriteIdx),
                              Mux(fillWriteEn, fillWriteData, imemData))
    }

    // Uniform write: same single-port pattern.
    val mmioUnifWrite =
        io.bus.is_writing && io.bus.address >= BorgGpuRegs.uniform_offset && io.bus.address < 560.U // 432 + 128
    val unifIdx = (io.bus.address - BorgGpuRegs.uniform_offset) >> 2
    val unifWen  = io.dmaUniformWrite.en || mmioUnifWrite
    val unifAddr = if (cfg.maxUniforms > 32)
      Mux(io.dmaUniformWrite.en, io.dmaUniformWrite.addr,
          Cat(io.control.uniformWritePage, unifIdx(4, 0)))
    else
      Mux(io.dmaUniformWrite.en, io.dmaUniformWrite.addr(4, 0), unifIdx(4, 0))
    val unifData = Mux(io.dmaUniformWrite.en, io.dmaUniformWrite.data,
                       io.bus.data_in(config.totalBits - 1, 0))
    when(unifWen) {
      uniformMem.write(unifAddr, unifData)
    }
  }

  /** ISA v2 operands that are no register: x0, LUI's zero, AUIPC's address, immediates. Latched
    * with the uniform read. */
  private def wireRvOperands(): (Valid[UInt], Valid[UInt]) = {
    val op_en = decoding || stages(cfg.cBusyLoad - 1, 1).orR
    val rv = RvInt(fetchedInstruction)
    val w = cfg.totalBits
    def latch(valid: Bool, bits: UInt) = {
      val v = Wire(Valid(UInt(w.W)))
      v.valid := RegEnable(valid, false.B, op_en); v.bits := RegEnable(bits(w - 1, 0), op_en)
      v
    }
    val csrValue = MuxLookup(rv.csr, 0.U(w.W))(Seq(Csr.Root.U -> io.root) ++
      io.attIndex.map(a => Csr.Attachment.U -> a.pad(w)))
    (latch(rv.aZero || rv.auipc || rv.csrrs && !rv.csrReg && !rv.csrWin,
           Mux(rv.csrrs, csrValue, Mux(rv.auipc, (programCounter << 2).pad(32), 0.U(32.W)))),
     latch(rv.bZero || rv.bImm || rv.csrrs, Mux(rv.bImm, rv.imm, 0.U(32.W))))
  }

  /** Shared uniform-RAM read.  Returns (gated read data, delayed funct3) for the
    * lanes' operand mux.  The address is instruction-derived (shared), so one
    * read serves every lane. */
  private def wireUniformRead(): (UInt, UInt) = {
    val op_en = decoding || stages(cfg.cBusyLoad - 1, 1).orR
    val op_en_del = RegNext(op_en, false.B)
    val funct3_del = RegEnable(opFlags.funct3, op_en)

    val uniform_addr = regs.rs1
    val unifReadAddr = if (cfg.maxUniforms > 32) {
      val read_page = Mux(running_by_rasterizer, io.uniformPage, io.control.uniformWritePage)
      Cat(read_page, uniform_addr(4, 0))
    } else {
      uniform_addr(4, 0)
    }
    val read_data = uniformMem.read(unifReadAddr, op_en)
    val uniform_data = Mux(op_en_del, read_data, 0.U)
    (uniform_data, funct3_del)
  }

  /** IMEM word for program counter `pc`. Without the cache the PC only ever
    * indexes IMEM. With it, IMEM is direct-mapped over the program: word `pc`
    * lives in line `pc mod L` (L = the largest power of two within IMEM),
    * except that every PC inside IMEM keeps its own word, so a 72-word IMEM
    * still holds a 72-word program exactly where it always did. */
  private def imemIndex(pc: UInt): UInt = {
    val n  = cfg.maxInstructions
    val lb = cfg.icacheLinesLog2
    if (!cfg.shaderICacheEnabled) pc
    else if ((1 << lb) == n) pc(lb - 1, 0)
    else Mux(pc < n.U, pc(log2Ceil(n) - 1, 0), pc(lb - 1, 0))
  }

  // @doc:icache
  /** Shader instruction cache (BorgConfig.hasShaderICache).
    *
    * IMEM is the cache's data array; this adds only bookkeeping. A line is
    * invalid, NEAR -- holding the word of the PC equal to its own index, which
    * is where the sequencer's DMA and MMIO writes put a program -- or FAR,
    * holding the word of a PC past IMEM, identified by a tag. Only the low L
    * lines can go far; lines L..N-1 (IMEM 64..71 of a 72-word build) are
    * near or invalid.
    *
    * A fetch hits if the line is valid and holds the word of the PC being
    * fetched (near for a PC inside IMEM, far with a matching tag past it).
    * On a miss the core does not issue. The fill reads codeBase + 4*pc over
    * the LOAD/STORE port (one 32-bit word), writes it into the line, marks
    * it, and waits one cycle for IMEM's synchronous read to return the new
    * word. Any PC can miss -- a near line far code evicted, or one the
    * preload never reached -- so the whole program, not just its tail, must
    * be in DRAM at codeBase. The sequencer's shader DMA arranges that
    * automatically (Borg.scala).
    *
    * `icacheFlush` marks a program change and invalidates every line; the
    * DMA/MMIO writes that follow make their lines near and valid, which is
    * what makes a preload a prefill of this cache. Lines reset valid, so a
    * program loaded without any flush -- every pre-cache use of IMEM --
    * behaves exactly as before. An MMIO-loaded program longer than IMEM must
    * write CODE_BASE (which flushes) BEFORE its IMEM words.
    */
  private def wireICache(): Unit = {
    val pc      = rasterRomAddrReg
    val inImem  = pc < cfg.maxInstructions.U

    val sIdle :: sFetch :: sSettle :: Nil = Enum(3)
    val state  = RegInit(sIdle)
    val missPc = RegInit(0.U(cfg.pcBits.W))
    val g = io.gpuMem.get
    val fill = state === sFetch && g.ready
    val lineHit =
      if (cfg.icacheMetaInRam) icacheLinesRam(pc, inImem, fill, missPc)
      else icacheLinesFlops(pc, inImem, fill, missPc)
    val hit = fetchRast || fetchSetup || lineHit
    fetchReady := hit && state === sIdle

    when(state === sIdle && running && !is_busy && !texResumeDelay && !hit) {
      missPc := pc
      state  := sFetch
      if (BorgDebug.trace) printf("[ICACHE] miss pc=%d\n", pc)
    }
    when(state === sFetch) {
      io.memBusy := true.B
      g.req  := true.B
      g.wr   := false.B
      g.addr := (io.codeBase.get + (missPc << 2))(GpuMemIO.AddrBits - 1, 0)
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

  /** Line state in flops, for a small IMEM: a flush clears it in one cycle.
    * True if the line holds pc's word. */
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
    // A flush starts a new program: nothing in IMEM belongs to it yet. The
    // DMA/MMIO writes that follow each make their line near and valid. (A
    // write in the same cycle as a flush wins: the flush pulses at DMA start,
    // before its first word arrives, so they never actually coincide.)
    when(io.icacheFlush.get) {
      valid.foreach(_ := false.B)
      isFar.foreach(_ := false.B)
    }
    when(extWriteEn) {
      valid(extWriteIdx) := true.B
      when(extWriteIdx < L.U) { isFar(extWriteIdx(lb - 1, 0)) := false.B }
    }
    hit
  }

  /** Line state in a RAM read next to IMEM, for a large IMEM, where flops
    * cost LUTs on the FPGA and per-cycle work in the simulator. A line is
    * valid only in the epoch it was written in, so a flush bumps the epoch.
    * A sweep rewrites every line after reset (valid, as the flops reset) and
    * when the epoch wraps (invalid); a line it has not reached yet reads as
    * what it will write. True if the line holds pc's word. */
  private def icacheLinesRam(pc: UInt, inImem: Bool, fill: Bool, missPc: UInt): Bool = {
    val n     = cfg.maxInstructions
    val lb    = cfg.icacheLinesLog2
    val lines = SyncReadMem(n, new ICacheLine(cfg.pcBits - lb))
    val epoch      = RegInit(0.U(ICacheLine.EpochBits.W))
    val sweeping   = RegInit(true.B)
    val sweepValid = RegInit(true.B)
    val sweepIdx   = RegInit(0.U(log2Ceil(n).W))
    val flush = io.icacheFlush.get

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
  // @doc:end

  /** TEX and TEXA (docs/B2_texture_unit.md).
    *
    * TEXA keeps each lane's three operands (w or layer, LOD or bias, depth
    * reference) for the TEXs after it. TEX hands the whole quad to
    * BorgSampler -- every lane's u, v and TEXA arguments at once, so it can
    * take the LOD from the quad's derivatives -- then writes each lane's
    * RGBA to rd..rd+3 as the sampler answers it. Both freeze at operand time
    * like LOAD/STORE and resume past the instruction, so the lanes' own FMA
    * (TEX and TEXA are R4-type) never writes back.
    *
    * The sampler owns the core's memory port while it runs; LOAD/STORE and
    * the instruction cache cannot run at the same time. */
  private def wireSampler(uOps: Seq[UInt], vOps: Seq[UInt], cOps: Seq[UInt],
                          memWrites: Seq[MemWritePort]): Unit = {
    val N = cfg.fragLanes
    val sampler = Module(new BorgSampler(cfg))
    sampler.io.invalidate := io.descWritten.get

    val auxW = Reg(Vec(N, UInt(32.W))); val auxLod = Reg(Vec(N, UInt(32.W))); val auxRef = Reg(Vec(N, UInt(32.W)))
    val is_tex_reg  = RegInit(false.B)
    val is_texa_reg = RegInit(false.B)
    val is_texs_reg = RegInit(false.B)
    // TEXS: the sampler descriptor address and the control word for the TEX after it.
    val sAddrReg = RegInit(0.U(GpuMemIO.AddrBits.W)); val sCtlReg = RegInit(0.U(32.W))
    when(fma_start) {
      is_tex_reg  := opFlags.tex
      is_texa_reg := opFlags.texa
      is_texs_reg := opFlags.texs
    }
    val sIdle :: sWait :: sWB :: sAck :: Nil = Enum(4)
    val st = RegInit(sIdle)
    val rdReg = RegInit(0.U(5.W))
    val wbK   = RegInit(0.U(2.W))
    val respLane = RegInit(0.U(log2Up(N).W))
    val respData = Reg(Vec(4, UInt(32.W)))

    def finish(): Unit = {
      st := sIdle
      is_tex_reg := false.B; is_texa_reg := false.B; is_texs_reg := false.B
      squashPipe := true.B
      programCounter := programCounter + 1.U
      texResumeDelay := true.B
    }

    sampler.io.req.valid := false.B
    val lead = (ops: Seq[UInt]) => PriorityMux(execMask.asBools, ops)
    sampler.io.req.bits.ctl := sCtlReg
    sampler.io.req.bits.texAddr := lead(cOps)(GpuMemIO.AddrBits - 1, 0)
    sampler.io.req.bits.sampAddr := sAddrReg
    for (i <- 0 until N) {
      sampler.io.req.bits.active(i) := execMask(i)
      val a = sampler.io.req.bits.lane(i)
      a.u := uOps(i); a.v := vOps(i); a.w := auxW(i); a.lod := auxLod(i); a.dref := auxRef(i)
    }
    sampler.io.respReady := st === sAck

    when(at(cfg.cOperands) && is_texs_reg) {
      sAddrReg := lead(uOps); sCtlReg := lead(vOps)
      finish()
    }
    when(at(cfg.cOperands) && is_texa_reg) {
      for (i <- 0 until N) { auxW(i) := uOps(i); auxLod(i) := vOps(i); auxRef(i) := cOps(i) }
      finish()
    }
    when(at(cfg.cOperands) && is_tex_reg && st === sIdle) {
      sampler.io.req.valid := true.B
      rdReg := regs.rd
      holdPipe := true.B
      st := sWait
    }
    when(st === sWait) {
      holdPipe := true.B
      when(sampler.io.resp.valid) {
        respLane := sampler.io.resp.bits.lane; respData := sampler.io.resp.bits.data
        wbK := 0.U; st := sWB
      }
    }
    when(st === sWB) {
      holdPipe := true.B
      memWrites.zipWithIndex.foreach { case (mw, i) =>
        mw.en   := i.U === respLane && execMask(i)
        mw.addr := rdReg + wbK
        mw.data := respData(wbK)
      }
      when(wbK === 3.U) { st := sAck }.otherwise { wbK := wbK + 1.U }
    }
    when(st === sAck) {
      holdPipe := true.B
      when(sampler.io.done) { finish() }.otherwise { st := sWait }
    }

    // The memory port: the sampler's while it runs.
    sampler.io.gpuMem.data    := io.gpuMem.get.data
    sampler.io.gpuMem.ready   := io.gpuMem.get.ready && sampler.io.busy
    sampler.io.gpuMem.waccept := false.B
    when(sampler.io.busy) {
      io.memBusy := true.B
      io.gpuMem.get.req   := sampler.io.gpuMem.req
      io.gpuMem.get.addr  := sampler.io.gpuMem.addr
      io.gpuMem.get.wr    := false.B
      io.gpuMem.get.wlen  := 1.U
    }
  }

  /** FEXP2 and FLOG2: freeze at the operands like TEX, feed the lanes to [[BorgExpLog]] one per
    * cycle, write each result back through the lane's memory write port, then resume. */
  private def wireExpLog(aOps: Seq[UInt], memWrites: Seq[MemWritePort]): Unit = {
    val N = cfg.fragLanes
    val unit = Module(new BorgExpLog)
    val isOp = RegInit(false.B); val isLog = RegInit(false.B)
    when(fma_start) {
      val rv = RvInt(fetchedInstruction)
      isOp := rv.fexp2 || rv.flog2; isLog := rv.flog2
    }
    val active = RegInit(false.B)
    val issue = RegInit(0.U(log2Up(N + 1).W)); val back = RegInit(0.U(log2Up(N + 1).W))
    val rdReg = RegInit(0.U(5.W))
    when(at(cfg.cOperands) && isOp && !active) {
      active := true.B; issue := 0.U; back := 0.U; rdReg := regs.rd
      holdPipe := true.B
    }
    val wide = (v: UInt) => if (config.totalBits > 16) v.pad(32) else Fp16Fp32.widen(v(15, 0))
    unit.io.log := isLog
    unit.io.in.valid := active && issue < N.U
    unit.io.in.bits  := wide(VecInit(aOps)(issue((log2Up(N) max 1) - 1, 0)))
    when(active) {
      holdPipe := true.B
      when(issue < N.U) { issue := issue + 1.U }
    }
    when(active && unit.io.out.valid) {
      val r = if (config.totalBits > 16) unit.io.out.bits else Fp16Fp32.narrow(unit.io.out.bits).pad(config.totalBits)
      memWrites.zipWithIndex.foreach { case (mw, i) =>
        mw.en   := i.U === back && execMask(i)
        mw.addr := rdReg
        mw.data := r
      }
      back := back + 1.U
      when(back === (N - 1).U) {
        active := false.B; isOp := false.B
        squashPipe := true.B
        programCounter := programCounter + 1.U
        texResumeDelay := true.B
      }
    }
  }

  /** ZTEST stall. Same freeze-at-operands shape as TEX/TEXA and LOAD/STORE, but
    * there is no per-lane loop here: the dispatcher tests every lane of the
    * quad itself (it owns the tile buffer) and answers once. Resuming by
    * squashing the stages skips the lanes' write-back cycle, so ZTEST
    * writes no register, exactly like STORE. */
  private def wireZTest(): Unit = {
    val is_ztest_reg = RegInit(false.B)
    when(fma_start) {
      is_ztest_reg := opFlags.ztest
    }
    val waiting = at(cfg.cOperands) && is_ztest_reg
    io.zTestReq := waiting
    when(waiting) {
      holdPipe := true.B
      when(io.zTestDone) {
        squashPipe   := true.B
        programCounter := programCounter + 1.U
        is_ztest_reg   := false.B
        // IMEM still holds the ZTEST word for a cycle, same as the other stall FSMs.
        texResumeDelay := true.B
      }
    }
  }

  // @doc:mem-stall
  /** LOAD / STORE stall FSM -- the shared memory-access path.
    *
    * Structurally the same shape as [[wireSampler]], and deliberately so:
    * latch the op at fetch, start once operands are valid, freeze
    * the stages so the pipeline stalls, serialize over lanes, then resume
    * past the instruction. Two consequences of freezing at 4 are load-bearing
    * rather than incidental:
    *
    *  - the lane's own ALU write-back fires at stage 1, which is
    *    never reached while frozen, so a LOAD/STORE produces no spurious
    *    arithmetic result even though BorgLane has no decode for it;
    *  - the register read ports stay valid, so the address and store-data
    *    operands are stable for the whole access.
    *
    * Per-lane serialization matters more here than for TEX/TEXA: at fragLanes=4
    * each lane computes its OWN address, so a quad's four invocations can
    * touch four unrelated words. There is no coalescing -- four separate
    * single-word accesses. That is the honest cost of the simplest correct
    * implementation, and the obvious later optimization.
    *
    * Not yet handled, and worth knowing before this is used for anything real:
    * no alignment fault (the index is shifted, so misalignment is
    * unrepresentable rather than checked), no bounds check against the
    * binding's size, and no memory ordering/barrier -- accesses complete in
    * program order because the core is stalled for each one, which is
    * stronger than Vulkan requires but only within a single invocation.
    */
  private def wireMemStall(addrOperands: Seq[UInt], dataOperands: Seq[UInt], cmpOperands: Seq[UInt],
                           memWrites: Seq[MemWritePort]): Unit = {
    val N = cfg.fragLanes

    val is_load_reg  = RegInit(false.B)
    // STORE and SOUT: both write rs2 per lane and differ only in the address.
    val is_store_reg = RegInit(false.B)
    val is_sout_reg  = RegInit(false.B)
    val is_fattr_reg = RegInit(false.B)
    // LB..LW, SW and the AMOs: byte address rs1 + imm.
    val rvMem = RegInit(false.B); val rvWidth = Reg(UInt(3.W)); val rvImm = Reg(UInt(32.W))
    val isAmo = RegInit(false.B); val amoOp = Reg(UInt(5.W)); val amoNew = Reg(UInt(32.W))
    when(fma_start) {
      val rv = RvInt(fetchedInstruction)
      rvMem := rv.load || rv.store || rv.amo; rvWidth := Mux(rv.amo, 2.U, rv.width); rvImm := rv.memImm
      isAmo := rv.amo; amoOp := rv.amoOp
      is_load_reg  := opFlags.load
      is_store_reg := opFlags.store || opFlags.sout
      is_sout_reg  := opFlags.sout
      is_fattr_reg := opFlags.fattr
    }

    val sMemIdle :: sMemReq :: sMemWB :: sAttrReq :: sAttrLook :: sAttrMem :: sAttrWB :: sAmoWr :: Nil = Enum(8)
    val memState = RegInit(sMemIdle)
    // log2Up, and a separate 0-width index at N==1, for the same two reasons
    // spelled out on wireSampler's respLane.
    val memLane = RegInit(0.U(log2Up(N).W))
    val memLaneIdx: UInt = if (N == 1) 0.U(0.W) else memLane

    val memRdReg   = RegInit(0.U(5.W))
    val memDataReg = RegInit(0.U(config.totalBits.W))

    val curIndex = VecInit(addrOperands)(memLaneIdx)
    val curData  = VecInit(dataOperands)(memLaneIdx)
    val curCmp   = VecInit(cmpOperands)(memLaneIdx)

    // Word accesses are aligned: the low address bits select within the word.
    val rvAddr = (curIndex.pad(32) + rvImm)(31, 0)
    val lsAddr = Cat(rvAddr(GpuMemIO.AddrBits - 1, 2), 0.U(2.W))
    def rvLoadData(w: UInt): UInt = {
      val sh = (w >> Cat(rvAddr(1, 0), 0.U(3.W)))(31, 0)
      riscv.Rv.loadExtend(rvWidth, sh, 32, full = Some(w))   // LW: the aligned word
    }

    // SOUT/FATTR: a component index from the instruction, not a register.
    // Taken at start (below), since the index is packed into register fields
    // that the operand reads decode as registers.
    val compIdx = RegInit(0.U(10.W))
    val attrK   = RegInit(0.U(2.W))          // FATTR: which of the three words
    // A vertex shader's SOUT index: bits 7:0 the component, bits 9:8 = k + 1 to store corner k's
    // lane only (0 = every corner), so a varying can go out as a0, a1 - a0, a2 - a0.
    val soutSel = compIdx(9, 8)
    val soutLaneOk = io.record.map { rec =>
      !(is_sout_reg && rec.outInterleave && soutSel =/= 0.U && (rec.outCorner +& memLane) =/= soutSel - 1.U)
    }.getOrElse(true.B)
    val byteAddr = io.record.map { rec =>
      val times3 = (compIdx << 1) +& compIdx
      val comp   = compIdx(7, 0)
      val corner = rec.outCorner +& memLane
      val word   = Mux(rec.outInterleave, ((comp << 1) +& comp) +& corner, compIdx)
      val soutAddr = (rec.outBase +& (word << 2))(GpuMemIO.AddrBits - 1, 0)
      val attrAddr = (rec.attrBase +& ((times3 +& attrK) << 2))(GpuMemIO.AddrBits - 1, 0)
      Mux(is_fattr_reg, attrAddr, Mux(is_sout_reg, soutAddr, lsAddr))
    }.getOrElse(lsAddr)

    io.gpuMem.get.req   := false.B
    io.gpuMem.get.addr  := 0.U
    io.gpuMem.get.wr    := false.B
    io.gpuMem.get.wdata := 0.U
    io.gpuMem.get.wlen  := 1.U            // single word; only the flusher bursts
    io.memBusy      := memState =/= sMemIdle

    // Start once operands are valid, exactly like TEX/TEXA.
    when(at(cfg.cOperands) && memState === sMemIdle && (is_load_reg || is_store_reg)) {
      holdPipe := true.B
      memRdReg := regs.rd
      memLane  := 0.U
      memState := sMemReq
      compIdx  := Cat(regs.rs1, regs.rd)       // SOUT: index in rs1:rd
    }
    // FATTR is one access for the whole quad, not one per lane: the three
    // per-vertex values are the same for every fragment of the triangle.
    when(at(cfg.cOperands) && memState === sMemIdle && is_fattr_reg) {
      holdPipe := true.B
      memRdReg := regs.rd
      attrK    := 0.U
      memState := sAttrReq
      compIdx  := Cat(regs.rs2, regs.rs1)      // FATTR: index in rs2:rs1
    }

    /** Resume the shader past the memory instruction. */
    def finishInstr(): Unit = {
      memState     := sMemIdle
      is_load_reg  := false.B
      is_store_reg := false.B
      is_sout_reg  := false.B
      is_fattr_reg := false.B
      squashPipe := true.B
      programCounter := programCounter + 1.U
      // Same one-cycle suppression as the other stall FSMs: IMEM still holds the stale
      // LOAD/STORE opcode for a cycle after we resume.
      texResumeDelay := true.B
    }

    /** Finish this lane: advance, or resume the shader past the instruction. */
    def finishLane(): Unit = {
      when(memLane === (N - 1).U) {
        finishInstr()
      }.otherwise {
        memLane      := memLane + 1.U
        memState     := sMemReq
        holdPipe := true.B
      }
    }

    // A lane masked off by a divergent `if` must perform no memory access at
    // all. For a STORE that is a correctness requirement, not an
    // optimization: the write would otherwise land in DRAM from a lane the
    // shader said was not running. Skipping also costs nothing -- the access
    // is simply not issued and the FSM moves to the next lane.
    //
    // A helper lane (see io.laneHelper) runs everything except its STOREs:
    // Vulkan requires stores by helper invocations, and by fragments that
    // failed the early tests or were discarded, to have no effect. Its LOADs
    // still happen, since derivatives of loaded values need them.
    // laneHelper describes the quad the dispatcher last shaded, so it never
    // applies to a sequencer-run program: a vertex shader's or the setup
    // ROM's SOUT must not inherit it.
    val laneActive = VecInit(execMask.asBools)(memLaneIdx) && soutLaneOk &&
                     !((is_store_reg || isAmo) && !io.seqBusy && io.laneHelper.get(memLaneIdx))

    when(memState === sMemReq && !laneActive) {
      holdPipe := true.B
      finishLane()
    }

    when(memState === sMemReq && laneActive) {
      holdPipe    := true.B   // hold operands stable
      io.gpuMem.get.addr  := byteAddr
      io.gpuMem.get.req   := is_load_reg
      io.gpuMem.get.wr    := is_store_reg
      io.gpuMem.get.wdata := curData
      when(io.gpuMem.get.ready) {
        if (BorgDebug.trace) printf("[MEM] load=%d lane=%d addr=0x%x data=0x%x\n",
          is_load_reg, memLane, byteAddr,
          Mux(is_load_reg, io.gpuMem.get.data, curData))
        when(is_load_reg) {
          memDataReg := Mux(rvMem, rvLoadData(io.gpuMem.get.data.pad(32)), io.gpuMem.get.data)(config.totalBits - 1, 0)
          memState   := Mux(isAmo, sAmoWr, sMemWB)
          amoNew     := riscv.Rv.amoResult(amoOp, io.gpuMem.get.data.pad(32)(31, 0), curData.pad(32),
                                           other = curData.pad(32), cmp = Some(curCmp.pad(32)))
        }.otherwise {
          finishLane()                  // a store has nothing to write back
        }
      }
    }

    // AMO: the new value goes to the same word, then rd gets the old one.
    when(memState === sAmoWr) {
      holdPipe := true.B
      io.gpuMem.get.addr  := byteAddr
      io.gpuMem.get.wr    := true.B
      io.gpuMem.get.wdata := amoNew
      when(io.gpuMem.get.ready) { memState := sMemWB }
    }

    when(memState === sMemWB) {
      holdPipe := true.B
      memWrites.zipWithIndex.foreach { case (mw, i) =>
        mw.en   := i.U === memLane && !(rvMem && memRdReg === 0.U)
        mw.addr := memRdReg
        mw.data := memDataReg
      }
      finishLane()
    }

    // FATTR: fetch word k and write it to rd+k of every active lane, three
    // times -- from the varying cache when it holds the word, else from DRAM.
    //
    // The varying cache (cfg.fattrCacheEnabled): a direct-mapped cache of
    // record words, each entry {word address, epoch, value}. Pass 2 re-reads
    // the same few words for every quad of a triangle and again in every
    // tile the triangle touches. Pass 1 wrote them all before Pass 2 started
    // and nothing writes them during Pass 2, so a copy stays good for the
    // whole of Pass 2 and nothing has to watch writes.
    //
    // Invalidation needs no valid bit per entry: each draw's Pass 2 start
    // (record.attrFlush) moves to a new epoch, and an entry hits only in the
    // epoch it was filled in. Epoch 0 is never current. When the epoch wraps
    // (every 255 draws), and out of reset when the RAM holds anything at all,
    // a sweep rewrites every entry's epoch to 0, one per cycle, and nothing
    // hits until it ends -- so no entry from an earlier draw can ever match.
    val fcLog2    = cfg.fattrCacheLog2
    val fcTagBits = GpuMemIO.AddrBits - 2
    val fcWidth   = fcTagBits + 8 + config.totalBits      // tag, epoch, data
    val fcMem     = Option.when(cfg.fattrCacheEnabled)(SyncReadMem(1 << fcLog2, UInt(fcWidth.W)))
    val fcEpoch    = RegInit(1.U(8.W))
    val fcSweeping = RegInit(true.B)
    val fcSweepIdx = RegInit(0.U(math.max(fcLog2, 1).W))
    val fcAddrReg  = RegInit(0.U(fcTagBits.W))
    /** The entry a byte address maps to: the word address XOR-folded, so
      * records that sit a power of two apart do not all collide. */
    def fcIndex(addr: UInt): UInt = {
      val w = addr(GpuMemIO.AddrBits - 1, 2)
      (0 until (fcTagBits + fcLog2 - 1) / fcLog2)
        .map(i => w(math.min((i + 1) * fcLog2, fcTagBits) - 1, i * fcLog2).pad(fcLog2))
        .reduce(_ ^ _)
    }
    val fcReadData = fcMem.map(_.read(fcIndex(byteAddr), memState === sAttrReq))
    val fcFill     = WireDefault(false.B)
    // Registered outside any `when`, so it sees every cycle: sAttrWB came
    // straight from a DRAM read (sAttrMem), not from a cache hit.
    val fcFromMem  = RegNext(memState === sAttrMem, false.B) && cfg.fattrCacheEnabled.B
    fcMem.foreach { m =>
      val flush = io.record.get.attrFlush
      when(fcSweeping) {
        fcSweepIdx := fcSweepIdx + 1.U
        when(fcSweepIdx === ((1 << fcLog2) - 1).U) { fcSweeping := false.B }
      }
      when(flush) {
        when(fcEpoch === 255.U) {
          fcEpoch := 1.U; fcSweeping := true.B; fcSweepIdx := 0.U
        }.otherwise {
          fcEpoch := fcEpoch + 1.U
        }
      }
      // One write port: the sweep wins over a fill, which only costs a hit.
      when(fcSweeping) {
        m.write(fcSweepIdx, 0.U(fcWidth.W))
      }.elsewhen(fcFill) {
        m.write(fcIndex(byteAddr), Cat(fcAddrReg, fcEpoch, memDataReg))
      }
    }

    /** Read FATTR's word from DRAM, then write it back (and cache it). */
    def attrFromMemory(): Unit = {
      holdPipe       := true.B
      io.gpuMem.get.addr := byteAddr
      io.gpuMem.get.req  := true.B
      when(io.gpuMem.get.ready) {
        memDataReg := io.gpuMem.get.data(config.totalBits - 1, 0)
        memState   := sAttrWB
      }
    }
    when(memState === sAttrReq) {
      if (cfg.fattrCacheEnabled) {
        holdPipe := true.B
        fcAddrReg    := byteAddr(GpuMemIO.AddrBits - 1, 2)
        memState     := sAttrLook
      } else {
        attrFromMemory()                 // no cache: straight to DRAM, as ever
      }
    }
    fcReadData.foreach { e =>
      when(memState === sAttrLook) {
        holdPipe := true.B
        val eTag   = e(fcWidth - 1, 8 + config.totalBits)
        val eEpoch = e(8 + config.totalBits - 1, config.totalBits)
        when(!fcSweeping && eEpoch === fcEpoch && eTag === fcAddrReg) {
          memDataReg := e(config.totalBits - 1, 0)
          memState   := sAttrWB
        }.otherwise {
          memState := sAttrMem
        }
      }
      when(memState === sAttrMem) {
        attrFromMemory()
        // memDataReg lands next cycle; fill from sAttrWB, where it is valid.
      }
    }
    when(memState === sAttrWB) {
      holdPipe := true.B
      // A word that came from DRAM (the cache missed) is now in memDataReg.
      fcFill := fcFromMem
      memWrites.zipWithIndex.foreach { case (mw, i) =>
        mw.en   := execMask(i)
        mw.addr := memRdReg + attrK
        mw.data := memDataReg
      }
      when(attrK === 2.U) {
        finishInstr()
      }.otherwise {
        attrK    := attrK + 1.U
        memState := sAttrReq
      }
    }
  }
  // @doc:end

  // @doc:branch
  /** Conditional branch: `BRZ`/`BRNZ rs1, target`.
    *
    * Evaluated at stage cOperands -- the same point wireSampler and
    * wireMemStall take their operands, for the same reason (the register read
    * ports are settled) -- and consumed at 1, where the PC advances. The
    * one-cycle-early `nextPC` already reads the target, so a taken branch
    * costs exactly the same as a fall-through: no bubble, no flush.
    *
    * == Divergence ==
    *
    * The condition is taken from lane 0. At fragLanes == 1 that is simply the
    * condition, and this is exact. At fragLanes == 4 the quad shares one
    * program counter, so a branch whose condition differs between lanes
    * cannot be executed correctly by ANY choice here -- taking it runs the
    * body for lanes that should have skipped it, not taking it skips the body
    * for lanes that should have run it. Correct divergent control flow needs a
    * per-lane execution mask and a reconvergence stack, which is a separate
    * piece of work.
    *
    * So the contract is that the condition must be quad-uniform, and the
    * hardware makes a violation OBSERVABLE rather than silent: `divergent`
    * is a sticky status bit set whenever any lane's condition disagrees with
    * lane 0's. A compiler emitting a non-uniform branch gets a flag it can
    * be tested against instead of a subtly wrong image. At fragLanes == 1 the
    * comparison is against an empty set and the bit can never set, so a
    * scalar build pays nothing for it.
    */
  private def wireBranch(condOperands: Seq[UInt], condOperandsB: Seq[UInt]): Unit = {
    // ISA v2: BEQ..BGEU decide on the lowest active lane, JAL always jumps.
    val rv = RvInt(fetchedInstruction)
    val rvConds = condOperands.zip(condOperandsB).map { case (a, b) => rv.cond(a, b) }
    val rvLead  = PriorityMux(execMask.asBools, rvConds)
    val takeIt = (rv.br && rvLead) || rv.jal
    val offset = Mux(rv.jal, rv.jalOff, rv.brOff)

    when(at(cfg.cOperands)) {
      brTakenReg  := flagsX.branch && takeIt
      brTargetReg := (programCounter.zext + offset).asUInt(cfg.pcBits - 1, 0)
      val rvSplit = rvConds.zip(execMask.asBools).map { case (c, on) => on && c =/= rvLead }.reduce(_ || _)
      when(flagsX.branch && rv.br && rvSplit) {
        branchDivergent := true.B
        if (BorgDebug.trace) printf("[BR] DIVERGENT pc=%d\n", programCounter)
      }
      if (BorgDebug.trace) {
        when(flagsX.branch) {
          printf("[BR] pc=%d taken=%d target=%d\n", programCounter, takeIt,
                 offset)
        }
      }
    }
    // Consumed by the PC advance this cycle; must not survive into the next
    // instruction or every op after a taken branch would branch too.
    when(redirecting) { brTakenReg := false.B }
  }
  // @doc:end

  // @doc:exec-mask
  /** Execution mask: `EXPUSH rs1` / `EXELSE` / `EXPOP`.
    *
    * Divergent control flow without divergent program counters. The 2x2 quad
    * has one PC, so an `if` whose condition differs between lanes cannot be
    * branched around -- instead BOTH arms execute and the lanes that should
    * not be running are masked off, so none of their writes land. BorgLane
    * gates its write-back on this bit, which covers registers, LOAD results
    * and the fragment outputs alike (the dispatcher snoops those through the
    * same port). wireMemStall skips masked lanes outright, so a masked STORE
    * never reaches DRAM.
    *
    * The stack holds the ENCLOSING mask, which is what makes EXELSE exact:
    * with enclosing M and condition C, EXPUSH gives `M & C` and EXELSE gives
    * `M & ~(M & C)` = `M & ~C` -- the else arm, correctly still restricted to
    * lanes that were running before the `if`. Getting that wrong by inverting
    * the full mask instead would re-activate lanes the enclosing `if` had
    * already masked off, which is the classic bug here.
    *
    * Evaluated at stage cOperands, the same point every other operand-reading
    * FSM uses.
    *
    * Not covered: a divergent LOOP, where lanes exit at different iterations.
    * That needs the mask plus a way to ask "is any lane still active" to
    * decide whether to take the backward branch. Uniform loops work today
    * (BRZ/BRNZ), and divergent `if`/`else` works now; divergent loops are the
    * remaining case and want one more instruction.
    */
  private def wireExecMask(condOperands: Seq[UInt]): Unit = {
    val perLaneTrue = VecInit(condOperands.map(_ =/= 0.U)).asUInt

    // execSp needs log2Ceil(DEPTH+1) bits to represent "full", but the Vec is
    // DEPTH deep and wants log2Ceil(DEPTH). Indexing with the wider value is
    // a Chisel W004 warning and, left alone, synthesizes selection logic for
    // twice the entries that exist -- the same class of waste as the 3-bit
    // index into a 4-element Vec that once blew up ABC9 for the full SoC (see
    // BorgShaderDispatcher's laneCtr). Slice explicitly; the guards above
    // already ensure the value is in range wherever it is used.
    val spIdx  = execSp(log2Ceil(EXEC_STACK_DEPTH) - 1, 0)
    val spPrev = (execSp - 1.U)(log2Ceil(EXEC_STACK_DEPTH) - 1, 0)

    when(at(cfg.cOperands) && flagsX.execOp) {
      when(flagsX.expush) {
        when(execSp === EXEC_STACK_DEPTH.U) {
          execFault := true.B          // no room; results will be wrong
        }.otherwise {
          execStack(spIdx) := execMask
          execSp   := execSp + 1.U
          execMask := execMask & perLaneTrue
        }
      }
      when(flagsX.exelse) {
        when(execSp === 0.U) {
          execFault := true.B          // EXELSE outside any EXPUSH
        }.otherwise {
          // Enclosing mask is the top of stack -- see the doc above for why
          // this must not be a plain inversion of execMask.
          execMask := execStack(spPrev) & (~execMask).asUInt
        }
      }
      when(flagsX.expop) {
        when(execSp === 0.U) {
          execFault := true.B
          execMask  := ((1 << cfg.fragLanes) - 1).U
        }.otherwise {
          execSp   := execSp - 1.U
          execMask := execStack(spPrev)
        }
      }
      if (BorgDebug.trace) printf("[EXEC] pc=%d push=%d else=%d pop=%d cond=0x%x mask=0x%x sp=%d\n",
        programCounter, flagsX.expush, flagsX.exelse, flagsX.expop,
        perLaneTrue, execMask, execSp)
    }

    // A fresh shader invocation starts with every lane running. Without this
    // an unbalanced EXPUSH in one invocation would leak into the next. A
    // compute quad starts with only its real invocations: the lanes past the
    // end of a workgroup that is not a multiple of fragLanes stay masked.
    when(io.control.start || io.coreTrigger.valid) {
      val allLanes = ((1 << cfg.fragLanes) - 1).U
      execMask := io.ids.map(c => Mux(c.mode && io.coreTrigger.valid, c.laneMask, allLanes))
                    .getOrElse(allLanes)
      execSp   := 0.U
    }
  }
  // @doc:end
}
