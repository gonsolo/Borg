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
  * write-back) lives in [[BorgLane]], instantiated `cfg.fragLanes` times.
  *
  * Pipeline (docs/B9_pipeline.md): an instruction issues at decode, reading rs1/rs2/rs3 from the
  * lane's three register-file copies, then moves through stages cfg.cBusyLoad→1 (5→1):
  * operands held at cHoldC, valid at cOperands, the FMA until 2, write-back at 1.
  * The next instruction issues every cycle unless it reads a register an instruction in flight
  * writes (the lane bypasses the one in write-back), follows a branch, or waits for a stall FSM.
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
  val attIndex           = Input(UInt(3.W))   // attachment + 4 * slice
  // Per lane, the colour attachment's word at its pixel (TLD).
  val laneDst            = Input(Vec(cfg.fragLanes, Vec(2, UInt(32.W))))
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
  val gpuMem  = new GpuMemIO
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
  val ids = Input(new InvocationIdsIO(cfg))
  // Compute mode, the other direction: did the invocation that just stopped
  // (coreStatus.running -> false) stop at a BARRIER, and if so, where should
  // it resume. Valid from the same cycle running drops until the next
  // invocation starts; BorgComputeSequencer reads it off coreStatus's own
  // running-drop detection, so there is no separate handshake.
  val barrier = if (cfg.hasCompute) Some(Output(new ComputeBarrierIO)) else None

  // ZTEST (early per-fragment tests): the core stalls on zTestReq until the
  // dispatcher has run the quad's depth/stencil test and pulses zTestDone.
  val zTestReq  = Output(Bool())
  val zTestDone = Input(Bool())
  // Per lane: this invocation must have no side effects -- a helper lane
  // (no covered sample, outside the scissor), a discarded fragment, or one
  // that failed ZTEST. Suppresses STORE, the only side effect a shader has.
  val laneHelper = Input(Vec(cfg.fragLanes, Bool()))

  // Instruction cache: the running program's
  // image in DRAM -- word `pc` lives at codeBase + 4*pc -- and a pulse that
  // forgets every word fetched from it, raised whenever the program changes.
  val codeBase    = Input(UInt(GpuMemIO.AddrBits.W))
  val icacheFlush = Input(Bool())

  // Draw front end: where SOUT and FATTR go (see Opcodes.MATCH_SOUT_V2).
  val record = Input(new CoreRecordIO)
  // DRAW_CFG's mode: the raster ROM runs the draw front end's program.
  val drawMode = Input(Bool())
  // One sample: the raster ROM stops before the per-sample depths.
  val drawSingle = Input(Bool())
  val setupShort = Input(Bool())   // single sample, no depth bias
  // A pulse that forgets the texture unit's cached descriptors (DESC_FLUSH).
  val descWritten  = Input(Bool())
  // Per lane, the samples the current fragment covers (SMASK).
  val laneCoverage = Input(Vec(cfg.fragLanes, UInt(cfg.samples.W)))
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
  val waitEmpty  = Bool()
  val waitUnit   = Bool()
  val drains     = Bool()
}

/** What the issue logic needs to know about an instruction in flight. */
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
  val programCounter = RegInit(0.U(cfg.pcBits.W))
  val running = RegInit(false.B)
  val auto_run_pending = RegInit(false.B)
  val running_by_rasterizer = RegInit(false.B)

  // The program a trigger started: the raster ROM's, the setup ROM's, or IMEM's.
  val fetchRast  = RegInit(false.B)
  val fetchSetup = RegInit(false.B)

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

  // --- Compute: BARRIER stop point (BorgConfig.hasCompute) -----------
  //
  // Not sticky across invocations: written on every stop (either branch of
  // runPipeline's BARRIER/HALT check below), so a stale true from a PRIOR
  // invocation can never survive into the next one's read.
  val atBarrierReg = RegInit(false.B)
  val barrierPCReg = RegInit(0.U(cfg.pcBits.W))

  // --- Instruction Fetch ---
  // The PC advances at issue, a branch's one stage after its operands.
  private val redirect = cfg.cOperands - 1
  val pcAfterThis = Mux(brTakenReg, brTargetReg, programCounter + 1.U)
  val nextPC = Wire(UInt(cfg.pcBits.W))
  private val fetch = Module(new Fetch(cfg))
  fetch.io.pc         := nextPC
  fetch.io.fromRast   := fetchRast
  fetch.io.fromSetup  := fetchSetup
  fetch.io.drawMode   := io.drawMode
  fetch.io.drawSingle := io.drawSingle
  fetch.io.setupShort := io.setupShort
  fetch.io.codeBase   := io.codeBase
  fetch.io.flush      := io.icacheFlush
  val fetchedInstruction = fetch.io.word
  val fetchReady = fetch.io.ready

  // Resume delay: after a stall FSM's writeback completes, the IMEM still
  // holds the stale opcode (1-cycle SyncReadMem latency); suppress restart
  // for 1 cycle. Shared by every multi-cycle FSM below (LOAD/STORE, ZTEST,
  // TEX/TEXA).
  val texResumeDelay = RegInit(false.B)
  when(texResumeDelay) { texResumeDelay := false.B }
  fetch.io.missOk := running && !is_busy && !texResumeDelay

  // --- Decode + FSM ---
  val (regs, opFlags) = decode(fetchedInstruction)
  // A BARRIER with every lane masked off is one the workgroup skipped (barriers are in uniform
  // control flow): it steps over, since a resume would restart those lanes unmasked.
  val barrierStop = opFlags.barrier && execMask =/= 0.U
  val next = RvInt(fetchedInstruction)
  val issued = Wire(new StageInfo)
  issued.writes := !opFlags.branch && !opFlags.execOp && !opFlags.rvNoWrite
  issued.rd     := regs.rd
  issued.branch := opFlags.branch
  issued.drain  := opFlags.load || opFlags.store || opFlags.sout || opFlags.fattr || opFlags.tex ||
    opFlags.texa || opFlags.texs || opFlags.ztest || next.fexp2 || next.flog2
  val canIssue = Wire(Bool())
  val decoding = running && canIssue
  val fma_start = decoding && !texResumeDelay && fetchReady && fetchedInstruction =/= 0.U
  val redirecting = Wire(Bool())   // the PC goes to pcAfterThis
  io.issueStats.issued := fma_start && !opFlags.barrier
  locally {
    // info(k): the instruction in stage k, valid while at(k).
    val infos = Seq.iterate(RegEnable(issued, fma_start), cfg.cBusyLoad)(r => RegEnable(r, !holdPipe))
    def info(k: Int): StageInfo = infos(cfg.cBusyLoad - k)
    def any(ks: Seq[Int])(p: StageInfo => Bool): Bool = ks.map(k => at(k) && p(info(k))).reduce(_ || _)
    val reads = Seq(next.readsRs1 -> regs.rs1, next.readsRs2 -> regs.rs2, next.readsRs3 -> regs.rs3)
    // A source waits until its writer is in write-back; the lane's bypass gives it that word.
    val hazard = any(2 to cfg.cBusyLoad)(i => i.writes && i.rd =/= 0.U &&
      reads.map { case (used, rs) => used && rs === i.rd }.reduce(_ || _))
    val stops = fetchedInstruction === 0.U || opFlags.barrier || issued.drain
    val behindBranch = any(redirect to cfg.cBusyLoad)(_.branch)
    canIssue := !hazard && !behindBranch && !any(1 to cfg.cBusyLoad)(_.drain) &&
      (!stops || !stages(cfg.cBusyLoad - 1, 1).orR)
    val waiting = running && !texResumeDelay && fetchReady && !canIssue
    io.issueStats.waitReg    := waiting && hazard
    io.issueStats.waitBranch := waiting && !hazard && behindBranch
    io.issueStats.waitDrain  := waiting && !hazard && !behindBranch
    io.issueStats.waitEmpty  := waiting && !hazard && !behindBranch && !any(1 to cfg.cBusyLoad)(_.drain)
    io.issueStats.waitUnit   := waiting && !hazard && !behindBranch && holdPipe
    io.issueStats.drains     := fma_start && issued.drain
    val step = fma_start && !opFlags.branch && !issued.drain && !barrierStop
    redirecting := at(redirect) && info(redirect).branch
    nextPC := Mux(redirecting, pcAfterThis, Mux(step, programCounter + 1.U, programCounter))
  }
  runPipeline(fma_start)
  // The flags of the instruction at its operands (stage cOperands).
  val flagsX = BorgLane.later(!holdPipe)(RegEnable(opFlags, 0.U.asTypeOf(opFlags), fma_start), cfg.cBusyLoad - cfg.cOperands)

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
    lane.io.covMask := io.laneCoverage(i)
    lane.io.dstWord := io.laneDst(i)
    lane.io.ids.mode := io.ids.mode
    lane.io.ids.r30  := io.ids.r30(i)
    lane.io.ids.r31  := io.ids.r31(i)
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

  private val zTest = Module(new ZTestUnit(cfg))
  attach(zTest.io)
  io.zTestReq := zTest.io.req
  zTest.io.done := io.zTestDone

  private val memUnit = Module(new MemUnit(cfg))
  attach(memUnit.io)
  memUnit.io.record     := io.record
  memUnit.io.laneHelper := io.laneHelper
  memUnit.io.seqBusy    := io.seqBusy
  memUnit.io.mem.data    := io.gpuMem.data
  memUnit.io.mem.ready   := io.gpuMem.ready
  memUnit.io.mem.waccept := io.gpuMem.waccept
  private val texUnit = Module(new TexUnit(cfg))
  attach(texUnit.io)
  texUnit.io.descWritten := io.descWritten
  texUnit.io.mem.data    := io.gpuMem.data
  texUnit.io.mem.ready   := io.gpuMem.ready
  texUnit.io.mem.waccept := io.gpuMem.waccept
  fetch.io.mem.data    := io.gpuMem.data
  fetch.io.mem.ready   := io.gpuMem.ready
  fetch.io.mem.waccept := io.gpuMem.waccept

  // The memory port: the sampler's while it runs, the instruction cache's while it fills, else
  // the memory unit's. They never overlap: each runs only while the pipeline waits for it.
  locally {
    val users = Seq(texUnit.io.busy -> texUnit.io.mem, fetch.io.filling -> fetch.io.mem, true.B -> memUnit.io.mem)
    def pick[T <: Data](f: GpuMemIO => T): T = PriorityMux(users.map(_._1), users.map(u => f(u._2)))
    io.gpuMem.req   := pick(_.req)
    io.gpuMem.addr  := pick(_.addr)
    io.gpuMem.wr    := pick(_.wr)
    io.gpuMem.wdata := pick(_.wdata)
    io.gpuMem.wlen  := pick(_.wlen)
    io.memBusy := texUnit.io.busy || fetch.io.filling || memUnit.io.busy
  }

  attach(Module(new ExpLogUnit(cfg)).io)

  // --- Branch evaluation and execution mask ---
  wireBranch(lanes.map(_.io.recARaw), lanes.map(_.io.recBRaw))
  wireExecMask(lanes.map(_.io.recARaw))

  // --- Quad derivatives (DDX/DDY): broadcast cross-lane operands to every lane.
  //   ddx = lane1 - lane0, ddy = lane2 - lane0 (constant across a flat 2×2 quad:
  //   lane0=TL, lane1=TR, lane2=BL, lane3=BR — see BorgIterator).  Each lane's FMA
  //   computes crossA + crossC where crossC = -lane0 (fp16-negated), so the result
  //   is the neighbour difference. */
  {
    val recAvec = VecInit(lanes.map(_.io.recARaw))
    def pick(i: Int): UInt = recAvec(i)
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
    flags.load  := rv.load || rv.amo
    flags.store := rv.store
    flags.branch := rv.br || rv.jal
    flags.expush := rv.expush
    flags.exelse := rv.exelse
    flags.expop  := rv.expop
    flags.execOp := flags.expush || flags.exelse || flags.expop
    flags.barrier := (if (cfg.hasCompute) rv.barrier else false.B)
    // With the execution mask, like EXPUSH/EXELSE/EXPOP: a fragment shader's
    // divergent loop needs it as much as a compute shader's.
    flags.exany  := rv.exany
    flags.isrl   := rv.srl
    flags.isltu  := rv.sltu
    flags.ztest  := rv.ztest
    flags.sout   := rv.sout
    flags.fattr  := rv.fattr
    flags.tex    := rv.texd
    flags.texs   := rv.texs
    flags.smask  := rv.csrMask
    flags.tld    := rv.tld
    flags.texa   := rv.texad
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
      }.elsewhen(barrierStop) {
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

    // IMEM write: DMA has priority over MMIO.
    val mmioImemWrite =
        io.bus.is_writing && io.bus.address >= BorgGpuRegs.imem_offset && io.bus.address < 416.U // 128 + 72*4
    fetch.io.load.valid := io.dmaImemWrite.en || mmioImemWrite
    fetch.io.load.bits.addr := Mux(io.dmaImemWrite.en, io.dmaImemWrite.addr, (io.bus.address - BorgGpuRegs.imem_offset) >> 2)
    fetch.io.load.bits.data := Mux(io.dmaImemWrite.en, io.dmaImemWrite.data, io.bus.data_in)

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
    val csrValue = MuxLookup(rv.csr, 0.U(w.W))(Seq(Csr.Root.U -> io.root,
      Csr.Attachment.U -> io.attIndex.pad(w)))
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

  /** Connects a multi-cycle unit: it holds the pipeline while it works; when it finishes, the
    * stages are squashed and the PC steps past its instruction. */
  private def attach(u: CoreUnitIO): Unit = {
    u.issue.valid := fma_start
    u.issue.bits.word := fetchedInstruction
    u.issue.bits.regs := regs
    u.atOps := at(cfg.cOperands)
    u.mask  := execMask
    u.a := VecInit(lanes.map(_.io.recARaw)); u.b := VecInit(lanes.map(_.io.recBRaw)); u.c := VecInit(lanes.map(_.io.recCRaw))
    when(u.hold) { holdPipe := true.B }
    when(u.finish) {
      squashPipe := true.B
      programCounter := programCounter + 1.U
      texResumeDelay := true.B
    }
    lanes.zip(u.wb).foreach { case (l, w) =>
      when(w.en) { l.io.memWrite.en := true.B; l.io.memWrite.addr := w.addr; l.io.memWrite.data := w.data }
    }
  }

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
    * The condition is taken from lane 0. The quad shares one program counter, so a branch whose condition differs between lanes
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
    * be tested against instead of a subtly wrong image.
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
      execMask := Mux(io.ids.mode && io.coreTrigger.valid, io.ids.laneMask, allLanes)
      execSp   := 0.U
    }
  }
  // @doc:end
}
