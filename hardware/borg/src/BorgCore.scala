// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.*
import chisel3.util.*

/** BorgCore — shared shader-core control: instruction memory, program counter,
  * the multi-cycle pipeline FSM, instruction decode, the (shared) uniform RAM,
  * MMIO/DMA write routing, and the FTEX texture-stall FSM.
  *
  * The per-lane datapath (register files, coordinate expansion, FP16 ALU,
  * write-back, pipeWrite snoop) lives in [[BorgLane]], instantiated `cfg.fragLanes`
  * times.  At `fragLanes==1` (current default) a single lane reproduces the
  * original monolithic core bit-for-bit.
  *
  * Pipeline timing (busy_counter counts cfg.cBusyLoad→0; 7→0 at fmaStages=3,
  * 8→0 at 4, 9→0 at 5 -- see BorgConfig's cPipeEn2/cOperands/cRs2 table):
  *   cRs2..cHoldC: rs1/rs2/rs3 read serially, one per cycle, off the lane's single
  *         register-file port (was 3 parallel ports/copies -- see BorgLane's
  *         `regFile` doc comment for the area rationale); each result is
  *         captured into a hold register the cycle after it's issued.
  *   cOperands..2: Stage 1 — operand reads valid (now held, not live); op-type flags
  *         latched (in the lane) at decode
  *   2→1:  Lane pipeline register captures the FMA mid-result
  *   1:    Stage 2 — round; register file written; pipeWrite exposed
  *   0:    idle — next instruction can start
  */

class BorgCoreIO(val cfg: BorgConfig) extends Bundle {
  // MMIO bus
  val bus = Flipped(new BorgBusIO())

  // Rasterizer interface — per-lane pixel coordinates (2×2 quad at fragLanes=4)
  val iter               = Input(Vec(cfg.fragLanes, new Coord(cfg.coordWidth)))
  val coreTrigger       = Flipped(new CoreTriggerIO)  // pulse from rasterizer: trigger shader
  val uniformPage        = Input(UInt(1.W))      // which 32-entry uniform page the GPU reads from

  // Control signals from SystemRDL register block
  val control = Input(new CoreControlIO)

  // DMA write ports (Step 22.1): DMA takes priority over MMIO writes
  val dmaImemWrite    = Flipped(new MemWritePort(7, 32)) // 7-bit: IMEM up to 72 entries
  val dmaUniformWrite = Flipped(new MemWritePort(6, cfg.totalBits))

  // Pipeline write-back snoop, per lane (exposed to rasterizer + sequencer)
  val pipeWrite = Vec(cfg.fragLanes, new PipeWriteIO(cfg.totalBits))

  // Status outputs (exposed to rasterizer and top-level read mux)
  val status = new CoreStatusIO

  // MMIO register read data (for top-level read mux)
  val regReadData = Output(UInt(cfg.totalBits.W))

  // Step 30.1d: when sequencer is running vertex/setup shaders, r30/r31 must
  // return 0 (not coordX/coordY) because those shaders use r31 as zero.
  val seqBusy = Input(Bool())

  // LOAD/STORE: the core's own DRAM port. Given directly to the core rather
  // than threaded through the dispatcher the way FTEX's narrow texReq/texU/
  // texV interface is: Borg.scala already arbitrates four gpuMem masters with
  // a priority mux, so adding a fifth is a smaller change than routing a
  // second memory protocol through BorgRasterizer and BorgShaderDispatcher.
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
  // LS_BASE: the base address loads and stores are relative to. Effectively
  // the SSBO descriptor -- see Instructions.FUNCT7_LOAD for why the register
  // operand is an index rather than a full address.
  val lsBase  = if (cfg.hasMemoryOps) Some(Input(UInt(25.W))) else None

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

  // Step 34.4: FTEX texture sample request/response
  val texReq  = Output(Bool())       // core requests texture fetch
  val texU    = Output(UInt(16.W))   // U coordinate from rs1
  val texV    = Output(UInt(16.W))   // V coordinate from rs2
  // Multi-texture binding: FTEX's rs3 operand, valid alongside texReq/texU/
  // texV. Selects which of cfg.maxTextureBindings base-address slots the
  // caller (Borg.scala) should feed the texture unit for this sample.
  val texSelect = Output(UInt(math.max(1, log2Ceil(cfg.maxTextureBindings)).W))
  val texDone = Input(Bool())        // texture unit completion pulse
  val texR    = Input(UInt(16.W))    // fetched texel R
  val texG    = Input(UInt(16.W))    // fetched texel G
  val texB    = Input(UInt(16.W))    // fetched texel B
  val texA    = Input(UInt(16.W))    // fetched texel A

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
  val codeBase    = if (cfg.shaderICacheEnabled) Some(Input(UInt(25.W))) else None
  val icacheFlush = if (cfg.shaderICacheEnabled) Some(Input(Bool())) else None

  // Draw front end: where SOUT and FATTR go (see Instructions.FUNCT7_SOUT).
  val record = if (cfg.drawEnabled) Some(Input(new CoreRecordIO)) else None
  // DRAW_CFG's mode: the raster ROM runs the draw front end's program.
  val drawMode = if (cfg.drawEnabled) Some(Input(Bool())) else None
}

/** The triangle record the running shader writes (SOUT) or reads (FATTR),
  * driven by the sequencers. See docs/B1_geometry_front_end.md. */
class CoreRecordIO extends Bundle {
  /** Byte address output component 0 goes to. */
  val outBase       = UInt(25.W)
  /** Vertex stage: component c of corner k at outBase + 4*(3c + k). Otherwise
    * (the setup ROM) at outBase + 4c. */
  val outInterleave = Bool()
  /** Corner of lane 0; lane i is corner outCorner + i. A single-lane build
    * runs the vertex shader once per corner and steps this instead. */
  val outCorner     = UInt(2.W)
  /** Byte address of component 0's three per-vertex values, for FATTR. */
  val attrBase      = UInt(25.W)
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
  val busy_counter = RegInit(0.U(cfg.busyCounterWidth.W))
  val is_busy = busy_counter > 0.U

  // --- Branch decision (declared before the fetch that consumes it) ---
  //
  // Registered, not combinational, and that is required rather than tidy:
  // nextPC feeds the instruction-memory read, whose output is decoded to
  // produce the branch condition. Deriving `taken` combinationally from the
  // instruction currently being fetched would close a loop through the
  // memory read. Latching the decision mid-execution breaks it -- by the
  // time the PC is redirected the branch has long since been decoded.
  val brTakenReg  = RegInit(false.B)
  val brTargetReg = RegInit(0.U(10.W))
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
  val pcAfterThis = Mux(brTakenReg, brTargetReg, programCounter + 1.U)
  val nextPC =
    Mux(is_busy && busy_counter === 1.U, pcAfterThis, programCounter)
  val rasterRomAddrReg = RegNext(nextPC)   // the PC whose word fetchedInstruction holds
  private val rasterWord = drawRasterRom.map(rom =>
    Mux(io.drawMode.get, rom(rasterRomAddrReg), rasterRom(rasterRomAddrReg))).getOrElse(rasterRom(rasterRomAddrReg))
  val fetchedInstruction =
    Mux(fetchRast, rasterWord,
      setupRom.map(rom => Mux(fetchSetup, rom(rasterRomAddrReg), instructionMemory.read(imemIndex(nextPC))))
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
  val extWriteIdx = WireDefault(0.U(7.W))

  // FTEX resume delay: after FTEX writeback completes, the IMEM still holds the
  // stale FTEX opcode (1-cycle SyncReadMem latency); suppress restart for 1 cycle.
  val texResumeDelay = RegInit(false.B)
  when(texResumeDelay) { texResumeDelay := false.B }

  // --- Decode + FSM ---
  val (regs, opFlags) = decode(fetchedInstruction)
  val fma_start = running && !is_busy && !texResumeDelay && fetchReady && fetchedInstruction =/= 0.U
  runPipeline(fma_start)

  // --- Shared uniform-RAM read (broadcast to every lane) ---
  val (uniform_data, funct3_del) = wireUniformRead()

  // --- Per-lane datapath ---
  val lanes = Seq.fill(cfg.fragLanes)(Module(new BorgLane(cfg)))
  lanes.foreach { lane =>
    lane.io.regs        := regs
    lane.io.opFlags     := opFlags
    lane.io.busyCounter := busy_counter
    lane.io.running     := running
    lane.io.isBusy      := is_busy
    lane.io.fmaStart    := fma_start
    lane.io.seqBusy     := io.seqBusy
    lane.io.uniformData := uniform_data
    lane.io.funct3Del   := funct3_del
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

  io.ids.foreach { c =>
    lanes.zipWithIndex.foreach { case (lane, i) =>
      lane.io.ids.get.mode := c.mode
      lane.io.ids.get.r30  := c.r30(i)
      lane.io.ids.get.r31  := c.r31(i)
    }
  }

  // Per-lane pixel coordinate (2×2 quad fanned out by the iterator).
  lanes.zipWithIndex.foreach { case (lane, i) => lane.io.iter := io.iter(i) }

  // Each lane exposes its own write-back snoop; lane 0 drives the MMIO read.
  lanes.zipWithIndex.foreach { case (lane, i) => io.pipeWrite(i) := lane.io.pipeWrite }
  io.regReadData := lanes(0).io.regReadData
  io.status.running := running
  io.status.autoRunPending := auto_run_pending
  io.branchDivergent := branchDivergent
  io.execFault       := execFault
  io.barrier.foreach { b =>
    b.hit      := atBarrierReg
    b.resumePC := barrierPCReg
  }

  // --- FTEX FSM (shared): drives each lane's memWrite, uses lane 0's operands ---
  wireTexStall(lanes.map(_.io.recARaw), lanes.map(_.io.recBRaw), lanes.map(_.io.recCRaw), lanes.map(_.io.memWrite))

  // --- ZTEST: stall while the dispatcher runs the early per-fragment tests ---
  wireZTest()

  // --- Instruction cache: must follow wireMemStall, whose port it shares ---

  // --- LOAD/STORE FSM (shared): same stall shape, same write-back port ---
  // Called after wireTexStall and deliberately does NOT re-default memWrite:
  // the two FSMs are mutually exclusive in time (one instruction at a time),
  // so each drives the port only inside its own `when`, and FTEX's default
  // survives for every cycle this one is idle.
  if (cfg.hasMemoryOps)
    wireMemStall(lanes.map(_.io.recARaw), lanes.map(_.io.recBRaw), lanes.map(_.io.memWrite))
  else
    io.memBusy := false.B
  if (cfg.shaderICacheEnabled) wireICache()

  // --- Branch evaluation and execution mask ---
  if (cfg.hasControlFlow) {
    wireBranch(lanes.map(_.io.recARaw))
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
    val lane0      = pick(0)
    val crossHi    = Mux(opFlags.ddy, pick(2), pick(1))
    val crossLoNeg = Cat(~lane0(cfg.totalBits - 1), lane0(cfg.totalBits - 2, 0))
    lanes.foreach { lane =>
      lane.io.crossA := crossHi
      lane.io.crossC := crossLoNeg
    }
  }

  // =========================================================================
  // Helper functions
  // =========================================================================

  // @doc:instruction-format
  /** Decode a 32-bit RISC-V instruction into register indices and op flags. */
  private def decode(instr: UInt) = {
    val regs = Wire(new RegIndices())
    regs.rs1 := Instructions.BF_RS1(instr)
    regs.rs2 := Instructions.BF_RS2(instr)
    regs.rs3 := Instructions.BF_RS3(instr)
    regs.rd  := Instructions.BF_RD(instr)

    val flags = Wire(new FpuOpFlags())
    flags.fma   := instr(Instructions.BITS_OPCODE_FMA_BIT)
    val f7op    = Instructions.BF_F7_OP(instr)
    // R4-type sub-opcode (only meaningful when flags.fma is set): FMADD is
    // funct2=0, FTEX is funct2=1 -- see Instructions.FUNCT2_FTEX. FTEX moved
    // here from the ALU-opcode RType shape specifically to gain rs3 as a
    // texture-slot index; regs.rs3 (decoded unconditionally above) picks it
    // up automatically.
    val funct2  = instr(26, 25)
    flags.ftex  := flags.fma && funct2 === Instructions.FUNCT2_FTEX.U
    flags.mul   := !flags.fma && f7op === Instructions.FUNCT7_MUL.U
    flags.fneg  := !flags.fma && f7op === Instructions.FUNCT7_FNEG.U
    flags.fstep := !flags.fma && f7op === Instructions.FUNCT7_FSTEP.U
    flags.frcp  := !flags.fma && f7op === Instructions.FUNCT7_FRCP.U
    flags.iadd  := !flags.fma && f7op === Instructions.FUNCT7_IADD.U
    flags.ishl  := !flags.fma && f7op === Instructions.FUNCT7_ISHL.U
    flags.ishr  := !flags.fma && f7op === Instructions.FUNCT7_ISHR.U
    flags.imul  := !flags.fma && f7op === Instructions.FUNCT7_IMUL.U
    flags.isub  := !flags.fma && f7op === Instructions.FUNCT7_ISUB.U
    flags.iand  := !flags.fma && f7op === Instructions.FUNCT7_IAND.U
    flags.ior   := !flags.fma && f7op === Instructions.FUNCT7_IOR.U
    flags.ixor  := !flags.fma && f7op === Instructions.FUNCT7_IXOR.U
    flags.islt  := !flags.fma && f7op === Instructions.FUNCT7_ISLT.U
    flags.iseq  := !flags.fma && f7op === Instructions.FUNCT7_ISEQ.U
    flags.i2f   := !flags.fma && f7op === Instructions.FUNCT7_I2F.U
    flags.f2i   := !flags.fma && f7op === Instructions.FUNCT7_F2I.U
    flags.frsq  := !flags.fma && f7op === Instructions.FUNCT7_FRSQ.U
    flags.fsrgb := !flags.fma && f7op === Instructions.FUNCT7_FSRGB.U
    flags.ddx   := !flags.fma && f7op === Instructions.FUNCT7_DDX.U
    flags.ddy   := !flags.fma && f7op === Instructions.FUNCT7_DDY.U
    // A gated-out opcode decodes as false everywhere rather than falling
    // through to some other op's flag: an absent instruction must be inert,
    // not accidentally an ADD.
    flags.load  := (if (cfg.hasMemoryOps) !flags.fma && f7op === Instructions.FUNCT7_LOAD.U else false.B)
    flags.store := (if (cfg.hasMemoryOps) !flags.fma && f7op === Instructions.FUNCT7_STORE.U else false.B)
    val cf = cfg.hasControlFlow
    flags.brz    := (if (cf) !flags.fma && f7op === Instructions.FUNCT7_BRZ.U else false.B)
    flags.brnz   := (if (cf) !flags.fma && f7op === Instructions.FUNCT7_BRNZ.U else false.B)
    flags.branch := flags.brz || flags.brnz
    flags.expush := (if (cf) !flags.fma && f7op === Instructions.FUNCT7_EXPUSH.U else false.B)
    flags.exelse := (if (cf) !flags.fma && f7op === Instructions.FUNCT7_EXELSE.U else false.B)
    flags.expop  := (if (cf) !flags.fma && f7op === Instructions.FUNCT7_EXPOP.U else false.B)
    flags.execOp := flags.expush || flags.exelse || flags.expop
    flags.barrier := (if (cfg.computeEnabled) !flags.fma && f7op === Instructions.FUNCT7_BARRIER.U else false.B)
    // Gated on computeEnabled, not plain hasControlFlow like EXPUSH/EXELSE/
    // EXPOP: the only shader that currently emits a divergent loop is a
    // compute one, and computeEnabled implies hasControlFlow already, so
    // this costs nothing on a build (Wafer) that has hasControlFlow but not
    // compute -- verified byte-identical Wafer Verilog with this gating.
    flags.exany  := (if (cfg.computeEnabled) !flags.fma && f7op === Instructions.FUNCT7_EXANY.U else false.B)
    flags.ztest  := !flags.fma && f7op === Instructions.FUNCT7_ZTEST.U
    val draw = cfg.drawEnabled
    flags.sout   := (if (draw) !flags.fma && f7op === Instructions.FUNCT7_SOUT.U else false.B)
    flags.fattr  := (if (draw) !flags.fma && f7op === Instructions.FUNCT7_FATTR.U else false.B)
    flags.funct3 := Instructions.BF_FUNCT3(instr)

    (regs, flags)
  }
  // @doc:end

  /** Fetch/execute FSM: start pipeline, count down busy cycles, advance PC. */
  private def runPipeline(fma_start: Bool): Unit = {
    // @doc:fetch-execute
    when(running && !is_busy && !texResumeDelay && fetchReady) {
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
      }.otherwise {
        busy_counter := cfg.cBusyLoad.U
      }
    }.elsewhen(is_busy) {
      busy_counter := busy_counter - 1.U
      when(busy_counter === 1.U) {
        programCounter := pcAfterThis
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
      busy_counter := 0.U
    }
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
    extWriteIdx := imemAddr
    when(extWrite || fillWriteEn) {
      instructionMemory.write(Mux(fillWriteEn, fillWriteIdx, imemAddr(log2Ceil(cfg.maxInstructions) - 1, 0)),
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

  /** Shared uniform-RAM read.  Returns (gated read data, delayed funct3) for the
    * lanes' operand mux.  The address is instruction-derived (shared), so one
    * read serves every lane. */
  private def wireUniformRead(): (UInt, UInt) = {
    val op_en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
    val op_en_del = RegNext(op_en, false.B)
    val funct3_del = RegEnable(opFlags.funct3, op_en)

    val uniform_addr = Mux(opFlags.funct3 === 1.U, regs.rs1,
                       Mux(opFlags.funct3 === 2.U, regs.rs2, regs.rs3))
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

  // @doc:ftex-stall
  /** Step 34.4: FTEX texture-sample stall and 4-register write-back.  Shared FSM:
    * latches operands → texReq, freezes busy_counter while waiting, then writes
    * texR/G/B/A to rd..rd+3 via each lane's memWrite port over 4 cycles.
    *
    * Four, not three: a sampled image is a vec4 in SPIR-V, and alpha is a
    * real channel of every mandatory RGBA format. The texture unit always
    * fetched it (the upper half of the texel's second word) and threw it
    * away, so `texture(s, uv).a` had no source. */
  private def wireTexStall(recAs: Seq[UInt], recBs: Seq[UInt], recCs: Seq[UInt],
                           memWrites: Seq[MemWritePort]): Unit = {
    val N = cfg.fragLanes
    val is_ftex_reg = RegInit(false.B)
    when(running && !is_busy && fetchedInstruction =/= 0.U) {
      is_ftex_reg := opFlags.ftex
    }

    // One texture unit, serialized over lanes: request lane → wait → write that
    // lane's rd..rd+3 → next lane.  At fragLanes=1 this is the original path.
    val sTexIdle :: sTexReq :: sTexWait :: sTexWB0 :: sTexWB1 :: sTexWB2 :: sTexWB3 :: Nil = Enum(7)
    val texState = RegInit(sTexIdle)
    // Ranges over [0, N-1] only (wraps at N-1, never reaches N) — log2Ceil(N)
    // bits, not N+1: see BorgShaderDispatcher's laneCtr for the same bug and
    // its Yosys/ABC9 synthesis-blowup consequence. log2Up (not log2Ceil):
    // at N=1 a single lane needs zero index bits, but Chisel has no 0-width
    // literal syntax, so `:= 0.U`/`+ 1.U` against a genuinely 0-bit register
    // trips the implicit-truncation warning; log2Up floors at 1 bit instead.
    val texLane  = RegInit(0.U(log2Up(N).W))
    // Dynamic Vec index needs 0 width at N=1 (Chisel: log2Ceil(1) == 0), but
    // texLane itself must stay log2Up-width for its own `:= 0.U`/`+ 1.U` to
    // avoid a truncation warning instead -- see BorgShaderDispatcher's
    // laneIdx for the same split.
    val texLaneIdx: UInt = if (N == 1) 0.U(0.W) else texLane

    val texRdReg = RegInit(0.U(5.W))
    // cfg.totalBits wide: texture sampling stays FP16-native (io.texR/G/B
    // are the fixed 16-bit ports below), but these registers feed the
    // general register file via memWrite, which the FP32 ALU reads as a
    // real cfg.fp-width operand -- widen() converts the raw FP16 texel into
    // a genuine value in that wider format instead of zero-extending it.
    val texResultR = RegInit(0.U(config.totalBits.W))
    val texResultG = RegInit(0.U(config.totalBits.W))
    val texResultB = RegInit(0.U(config.totalBits.W))
    val texResultA = RegInit(0.U(config.totalBits.W))
    def widenTexel(t: UInt): UInt = if (config.totalBits > 16) Fp16Fp32.widen(t) else t
    def narrowTexCoord(c: UInt): UInt =
      if (config.totalBits > 16) Fp16Fp32.narrow(c(config.totalBits - 1, 0)) else c(15, 0)

    // Active lane's U/V/texSelect operands (read ports stay valid while busy_counter is held).
    val curA = VecInit(recAs)(texLaneIdx)
    val curB = VecInit(recBs)(texLaneIdx)
    val curC = VecInit(recCs)(texLaneIdx)
    val texSelectWidth = io.texSelect.getWidth

    // Defaults
    io.texReq    := false.B
    io.texU      := 0.U
    io.texV      := 0.U
    io.texSelect := 0.U
    // Lane-selective write: only the active lane's register file is written.
    def driveTexWriteLane(lane: UInt, en: Bool, addr: UInt, data: UInt): Unit =
      memWrites.zipWithIndex.foreach { case (tw, i) =>
        tw.en := en && (i.U === lane); tw.addr := addr; tw.data := data
      }
    driveTexWriteLane(0.U, false.B, 0.U, 0.U)

    // Initiate FTEX at counter=4 (operands valid): start with lane 0.
    when(is_busy && busy_counter === cfg.cOperands.U && is_ftex_reg) {
      texRdReg := regs.rd
      texLane  := 0.U
      texState := sTexReq
    }

    // Request the active lane's texel.
    when(texState === sTexReq) {
      busy_counter := busy_counter            // hold operands stable
      io.texReq := true.B
      // The coordinates are datapath floats; the texture unit is FP16-native.
      // Narrow the value (round to nearest), never slice the bit pattern:
      // curA(15, 0) of FP32 0.5f is 0x0000.
      io.texU   := narrowTexCoord(curA)
      io.texV   := narrowTexCoord(curB)
      // texSelect is a raw small integer (the compiler pins a compile-time
      // binding index into rs3), not a float -- take the low bits directly,
      // same convention as the int ALU's own raw-bits results.
      io.texSelect := curC(texSelectWidth - 1, 0)
      when(io.texDone) {                      // same-cycle (e.g. texture disabled → white)
        texResultR := widenTexel(io.texR); texResultG := widenTexel(io.texG); texResultB := widenTexel(io.texB)
        texResultA := widenTexel(io.texA)
        texState   := sTexWB0
      }.otherwise {
        texState := sTexWait
      }
    }

    when(texState === sTexWait) {
      busy_counter := busy_counter            // hold
      when(io.texDone) {
        texResultR := widenTexel(io.texR); texResultG := widenTexel(io.texG); texResultB := widenTexel(io.texB)
        texResultA := widenTexel(io.texA)
        texState   := sTexWB0
      }
    }

    when(texState === sTexWB0) {
      busy_counter := busy_counter
      driveTexWriteLane(texLane, true.B, texRdReg, texResultR); texState := sTexWB1
    }
    when(texState === sTexWB1) {
      busy_counter := busy_counter
      driveTexWriteLane(texLane, true.B, texRdReg + 1.U, texResultG); texState := sTexWB2
    }
    when(texState === sTexWB2) {
      busy_counter := busy_counter
      driveTexWriteLane(texLane, true.B, texRdReg + 2.U, texResultB); texState := sTexWB3
    }
    when(texState === sTexWB3) {
      driveTexWriteLane(texLane, true.B, texRdReg + 3.U, texResultA)
      when(texLane === (N - 1).U) {
        // All lanes textured — resume the fragment shader past the FTEX op.
        texState   := sTexIdle
        is_ftex_reg := false.B
        busy_counter := 0.U
        programCounter := programCounter + 1.U
        texResumeDelay := true.B
      }.otherwise {
        texLane := texLane + 1.U
        texState := sTexReq                   // fetch the next lane's texel
        busy_counter := busy_counter
      }
    }
  }
  // @doc:end

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
    val n       = cfg.maxInstructions
    val lb      = cfg.icacheLinesLog2
    val L       = 1 << lb
    val tagBits = cfg.pcBits - lb
    val valid    = RegInit(VecInit(Seq.fill(n)(true.B)))
    val isFar    = RegInit(VecInit(Seq.fill(L)(false.B)))
    val farTag   = Reg(Vec(L, UInt(tagBits.W)))

    val pc     = rasterRomAddrReg
    val line   = pc(lb - 1, 0)
    val inImem = pc < n.U
    val idx    = imemIndex(pc)
    val nearOk = if (L < n) Mux(pc >= L.U, true.B, !isFar(line)) else !isFar(line)
    val hit = fetchRast || fetchSetup || (valid(idx) && Mux(inImem, nearOk,
      isFar(line) && farTag(line) === pc(cfg.pcBits - 1, lb)))

    val sIdle :: sFetch :: sSettle :: Nil = Enum(3)
    val state  = RegInit(sIdle)
    val missPc = RegInit(0.U(cfg.pcBits.W))
    fetchReady := hit && state === sIdle

    when(state === sIdle && running && !is_busy && !texResumeDelay && !hit) {
      missPc := pc
      state  := sFetch
      if (BorgDebug.trace) printf("[ICACHE] miss pc=%d\n", pc)
    }

    val g = io.gpuMem.get
    when(state === sFetch) {
      io.memBusy := true.B
      g.req  := true.B
      g.wr   := false.B
      g.addr := (io.codeBase.get + (missPc << 2))(24, 0)
      when(g.ready) {
        fillWriteEn   := true.B
        fillWriteIdx  := imemIndex(missPc)
        fillWriteData := g.data
        val l = missPc(lb - 1, 0)
        valid(imemIndex(missPc)) := true.B
        when(missPc < n.U) {
          when(missPc < L.U) { isFar(l) := false.B }   // a near word (re)fetched
        }.otherwise {
          isFar(l)  := true.B
          farTag(l) := missPc(cfg.pcBits - 1, lb)
        }
        state := sSettle
      }
    }
    // IMEM returns the filled word one read later; hold issue for that cycle.
    when(state === sSettle) { state := sIdle }

    // A flush starts a new program: nothing in IMEM belongs to it yet. The
    // DMA/MMIO writes that follow each make their line near and valid. (A
    // write in the same cycle as a flush wins: the flush pulses at DMA start,
    // before its first word arrives, so they never actually coincide.)
    when(io.icacheFlush.get) {
      valid.foreach(_ := false.B)
      isFar.foreach(_ := false.B)
    }
    when(extWriteEn) {
      valid(extWriteIdx(log2Ceil(n) - 1, 0)) := true.B
      when(extWriteIdx < L.U) { isFar(extWriteIdx(lb - 1, 0)) := false.B }
    }
  }
  // @doc:end

  /** ZTEST stall. Same freeze-at-operands shape as FTEX and LOAD/STORE, but
    * there is no per-lane loop here: the dispatcher tests every lane of the
    * quad itself (it owns the tile buffer) and answers once. Resuming by
    * setting busy_counter to 0 skips the lanes' write-back cycle, so ZTEST
    * writes no register, exactly like STORE. */
  private def wireZTest(): Unit = {
    val is_ztest_reg = RegInit(false.B)
    when(running && !is_busy && fetchedInstruction =/= 0.U) {
      is_ztest_reg := opFlags.ztest
    }
    val waiting = is_busy && busy_counter === cfg.cOperands.U && is_ztest_reg
    io.zTestReq := waiting
    when(waiting) {
      busy_counter := busy_counter
      when(io.zTestDone) {
        busy_counter   := 0.U
        programCounter := programCounter + 1.U
        is_ztest_reg   := false.B
        // IMEM still holds the ZTEST word for a cycle, as after FTEX.
        texResumeDelay := true.B
      }
    }
  }

  // @doc:mem-stall
  /** LOAD / STORE stall FSM -- the shared memory-access path.
    *
    * Structurally the same shape as [[wireTexStall]], and deliberately so:
    * latch the op at fetch, start once operands are valid, freeze
    * `busy_counter` so the pipeline stalls, serialize over lanes, then resume
    * past the instruction. Two consequences of freezing at 4 are load-bearing
    * rather than incidental:
    *
    *  - the lane's own ALU write-back fires at `busy_counter === 1`, which is
    *    never reached while frozen, so a LOAD/STORE produces no spurious
    *    arithmetic result even though BorgLane has no decode for it;
    *  - the register read ports stay valid, so the address and store-data
    *    operands are stable for the whole access.
    *
    * Per-lane serialization matters more here than for FTEX: at fragLanes=4
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
  private def wireMemStall(addrOperands: Seq[UInt], dataOperands: Seq[UInt],
                           memWrites: Seq[MemWritePort]): Unit = {
    val N = cfg.fragLanes

    val is_load_reg  = RegInit(false.B)
    // STORE and SOUT: both write rs2 per lane and differ only in the address.
    val is_store_reg = RegInit(false.B)
    val is_sout_reg  = RegInit(false.B)
    val is_fattr_reg = RegInit(false.B)
    when(running && !is_busy && fetchedInstruction =/= 0.U) {
      is_load_reg  := opFlags.load
      is_store_reg := opFlags.store || opFlags.sout
      is_sout_reg  := opFlags.sout
      is_fattr_reg := opFlags.fattr
    }

    val sMemIdle :: sMemReq :: sMemWB :: sAttrReq :: sAttrWB :: Nil = Enum(5)
    val memState = RegInit(sMemIdle)
    // log2Up, and a separate 0-width index at N==1, for the same two reasons
    // spelled out on wireTexStall's texLane.
    val memLane = RegInit(0.U(log2Up(N).W))
    val memLaneIdx: UInt = if (N == 1) 0.U(0.W) else memLane

    val memRdReg   = RegInit(0.U(5.W))
    val memDataReg = RegInit(0.U(config.totalBits.W))

    val curIndex = VecInit(addrOperands)(memLaneIdx)
    val curData  = VecInit(dataOperands)(memLaneIdx)

    // Effective address: LS_BASE + (index << 2). The shift is what makes the
    // index a word index and misalignment unrepresentable; +& keeps the carry
    // so a base near the top of the space does not wrap silently.
    val lsAddr = (io.lsBase.get +& (curIndex << 2))(24, 0)

    // SOUT/FATTR: a component index from the instruction, not a register.
    // Taken at start (below), since the index is packed into register fields
    // that the operand reads decode as registers.
    val compIdx = RegInit(0.U(10.W))
    val attrK   = RegInit(0.U(2.W))          // FATTR: which of the three words
    val byteAddr = io.record.map { rec =>
      val times3 = (compIdx << 1) +& compIdx
      val corner = rec.outCorner +& memLane
      val word   = Mux(rec.outInterleave, times3 +& corner, compIdx)
      val soutAddr = (rec.outBase +& (word << 2))(24, 0)
      val attrAddr = (rec.attrBase +& ((times3 +& attrK) << 2))(24, 0)
      Mux(is_fattr_reg, attrAddr, Mux(is_sout_reg, soutAddr, lsAddr))
    }.getOrElse(lsAddr)

    io.gpuMem.get.req   := false.B
    io.gpuMem.get.addr  := 0.U
    io.gpuMem.get.wr    := false.B
    io.gpuMem.get.wdata := 0.U
    io.gpuMem.get.wlen  := 1.U            // single word; only the flusher bursts
    io.memBusy      := memState =/= sMemIdle

    // Start once operands are valid, exactly like FTEX.
    when(is_busy && busy_counter === cfg.cOperands.U && (is_load_reg || is_store_reg)) {
      memRdReg := regs.rd
      memLane  := 0.U
      memState := sMemReq
      compIdx  := Cat(regs.rs1, regs.rd)       // SOUT: index in rs1:rd
    }
    // FATTR is one access for the whole quad, not one per lane: the three
    // per-vertex values are the same for every fragment of the triangle.
    when(is_busy && busy_counter === cfg.cOperands.U && is_fattr_reg) {
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
      busy_counter := 0.U
      programCounter := programCounter + 1.U
      // Same one-cycle suppression as FTEX: IMEM still holds the stale
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
        busy_counter := busy_counter
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
    val laneActive = VecInit(execMask.asBools)(memLaneIdx) &&
                     !(is_store_reg && !io.seqBusy && io.laneHelper.get(memLaneIdx))

    when(memState === sMemReq && !laneActive) {
      busy_counter := busy_counter
      finishLane()
    }

    when(memState === sMemReq && laneActive) {
      busy_counter    := busy_counter   // hold operands stable
      io.gpuMem.get.addr  := byteAddr
      io.gpuMem.get.req   := is_load_reg
      io.gpuMem.get.wr    := is_store_reg
      io.gpuMem.get.wdata := curData
      when(io.gpuMem.get.ready) {
        if (BorgDebug.trace) printf("[MEM] load=%d lane=%d addr=0x%x data=0x%x\n",
          is_load_reg, memLane, byteAddr,
          Mux(is_load_reg, io.gpuMem.get.data, curData))
        when(is_load_reg) {
          memDataReg := io.gpuMem.get.data(config.totalBits - 1, 0)
          memState   := sMemWB
        }.otherwise {
          finishLane()                  // a store has nothing to write back
        }
      }
    }

    when(memState === sMemWB) {
      busy_counter := busy_counter
      memWrites.zipWithIndex.foreach { case (mw, i) =>
        mw.en   := i.U === memLane
        mw.addr := memRdReg
        mw.data := memDataReg
      }
      finishLane()
    }

    // FATTR: read word k, write it to rd+k of every active lane, three times.
    when(memState === sAttrReq) {
      busy_counter       := busy_counter
      io.gpuMem.get.addr := byteAddr
      io.gpuMem.get.req  := true.B
      when(io.gpuMem.get.ready) {
        memDataReg := io.gpuMem.get.data(config.totalBits - 1, 0)
        memState   := sAttrWB
      }
    }
    when(memState === sAttrWB) {
      busy_counter := busy_counter
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
    * Evaluated at busy_counter 4 -- the same point wireTexStall and
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
  private def wireBranch(condOperands: Seq[UInt]): Unit = {
    // Raw-bits comparison: FP16 -0.0 is 0x8000 and therefore non-zero, the
    // same convention the discard register already uses.
    val isZero = condOperands.map(_ === 0.U)
    val takeIt = (opFlags.brz && isZero.head) || (opFlags.brnz && !isZero.head)

    when(is_busy && busy_counter === cfg.cOperands.U) {
      brTakenReg  := opFlags.branch && takeIt
      brTargetReg := Cat(regs.rs2, regs.rd)
      when(opFlags.branch && isZero.map(_ =/= isZero.head).foldLeft(false.B)(_ || _)) {
        branchDivergent := true.B
        if (BorgDebug.trace) printf("[BR] DIVERGENT pc=%d\n", programCounter)
      }
      if (BorgDebug.trace) {
        when(opFlags.branch) {
          printf("[BR] pc=%d taken=%d target=%d\n", programCounter, takeIt,
                 Cat(regs.rs2, regs.rd))
        }
      }
    }
    // Consumed by the PC advance this cycle; must not survive into the next
    // instruction or every op after a taken branch would branch too.
    when(is_busy && busy_counter === 1.U) { brTakenReg := false.B }
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
    * Evaluated at busy_counter 4, the same point every other operand-reading
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

    when(is_busy && busy_counter === cfg.cOperands.U && opFlags.execOp) {
      when(opFlags.expush) {
        when(execSp === EXEC_STACK_DEPTH.U) {
          execFault := true.B          // no room; results will be wrong
        }.otherwise {
          execStack(spIdx) := execMask
          execSp   := execSp + 1.U
          execMask := execMask & perLaneTrue
        }
      }
      when(opFlags.exelse) {
        when(execSp === 0.U) {
          execFault := true.B          // EXELSE outside any EXPUSH
        }.otherwise {
          // Enclosing mask is the top of stack -- see the doc above for why
          // this must not be a plain inversion of execMask.
          execMask := execStack(spPrev) & (~execMask).asUInt
        }
      }
      when(opFlags.expop) {
        when(execSp === 0.U) {
          execFault := true.B
          execMask  := ((1 << cfg.fragLanes) - 1).U
        }.otherwise {
          execSp   := execSp - 1.U
          execMask := execStack(spPrev)
        }
      }
      if (BorgDebug.trace) printf("[EXEC] pc=%d push=%d else=%d pop=%d cond=0x%x mask=0x%x sp=%d\n",
        programCounter, opFlags.expush, opFlags.exelse, opFlags.expop,
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
