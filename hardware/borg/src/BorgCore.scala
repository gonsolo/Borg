// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.*
import chisel3.util.*

class BorgCoreIO(val cfg: BorgConfig) extends Bundle {
  private val N = cfg.fragLanes
  val bus = Flipped(new BorgBusIO())

  // The quad the dispatcher shades: pixel coordinates, the framebuffer origin of the render window
  // (r30/r31 are framebuffer coordinates), the attachment (ATTIDX), each lane's destination word
  // (TLD), its covered samples (SMASK), and whether it must have no side effects (a helper lane,
  // discarded, or failed ZTEST: its stores are dropped).
  val iter         = Input(Vec(N, new Coord(cfg.coordWidth)))
  val pixelOrigin  = Input(new Coord(14))
  val attIndex     = Input(UInt(3.W))   // attachment + 4 * slice
  val laneDst      = Input(Vec(N, Vec(2, UInt(32.W))))
  val laneCoverage = Input(Vec(N, UInt(cfg.samples.W)))
  val laneHelper   = Input(Vec(N, Bool()))

  val coreTrigger = Flipped(new CoreTriggerIO)
  val uniformPage = Input(UInt(1.W))    // the 32-word uniform page triggered programs read
  val control     = Input(new CoreControlIO)

  // DMA loads of programs and uniforms; they take priority over MMIO.
  val dmaImemWrite    = Flipped(new MemWritePort(7, 32))
  val dmaUniformWrite = Flipped(new MemWritePort(6, cfg.totalBits))

  val csrWrite    = Vec(N, new CsrWriteIO(cfg.totalBits))   // fragment outputs, rast_*, setup_*
  val csrSlot     = Output(UInt(cfg.slotBits.W))            // the quad slot csrWrite comes from
  val status      = new CoreStatusIO
  val regReadData = Output(UInt(cfg.totalBits.W))           // MMIO register read

  val seqBusy = Input(Bool())           // a sequencer's program: r30/r31 read 0 unless ids supply them
  val ids     = Input(new InvocationIdsIO(cfg))   // compute's or the vertex stage's r30/r31 and lanes

  val gpuMem  = new GpuMemIO
  val memBusy = Output(Bool())

  // Sticky: a branch condition differed between the active lanes, or the mask stack over- or
  // underflowed. Either program gives wrong results; these make that visible.
  val branchDivergent = Output(Bool())
  val execFault       = Output(Bool())

  val root = Input(UInt(32.W))          // CSR root
  // Valid from the cycle a compute invocation stops until the next starts: it stopped at a
  // BARRIER, and where it resumes.
  val barrier = if (cfg.hasCompute) Some(Output(new ComputeBarrierIO)) else None

  val zTestReq  = Output(Bool())        // held until the dispatcher has tested the quad
  val zTestDone = Input(Bool())

  // The running program's image in DRAM (word pc at codeBase + 4 pc), and a pulse when it changes.
  val codeBase    = Input(UInt(GpuMemIO.AddrBits.W))
  val icacheFlush = Input(Bool())

  val record      = Input(new CoreRecordIO)
  val drawMode    = Input(Bool())       // the raster ROM runs the draw front end's program
  val drawSingle  = Input(Bool())       // one sample: it stops before the per-sample depths
  val setupShort  = Input(Bool())       // one sample, no depth bias
  val descWritten = Input(Bool())       // the texture unit forgets its descriptors
  val issueStats  = Output(new IssueStatsIO)
}

/** The triangle record the running shader writes (SOUT) or reads (FATTR), from the sequencers
  * (docs/B1_geometry_front_end.md). */
class CoreRecordIO extends Bundle {
  val outBase       = UInt(GpuMemIO.AddrBits.W)   // output component 0
  // Vertex stage: component c of corner k at outBase + 4 (3c + k); the setup ROM: at outBase + 4c.
  val outInterleave = Bool()
  val outCorner     = UInt(2.W)                   // lane 0's corner; lane i is corner + i
  val attrBase      = UInt(GpuMemIO.AddrBits.W)   // component 0's three per-vertex values
  val attrFlush     = Bool()                      // Pass 2 starts: FATTR's cache forgets
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

/** A quad the core keeps: where its program is and whether it runs. */
class QuadSlot(cfg: BorgConfig) extends Bundle {
  val pc           = UInt(cfg.pcBits.W)
  val running      = Bool()
  val startPending = Bool()   // triggered: running from the next cycle
  val byRasterizer = Bool()   // triggered, not started by MMIO: reads uniformPage
  // The program a trigger started: the raster ROM's, the setup ROM's, or IMEM's.
  val fetchRast    = Bool()
  val fetchSetup   = Bool()
  // Where a compute invocation stopped; written on every stop, so never stale.
  val atBarrier    = Bool()
  val barrierPC    = UInt(cfg.pcBits.W)
}

/** What the issue logic knows about an instruction in flight. */
class StageInfo extends Bundle {
  val writes = Bool()
  val rd     = UInt(5.W)
  val branch = Bool()
  val drain  = Bool()   // a unit runs it: it issues into empty stages, nothing issues behind it
}

/** The shader core: one quad of [[BorgLane]]s behind a shared fetch, decode and issue, and the
  * multi-cycle units ([[MemUnit]], [[TexUnit]], [[ExpLogUnit]], [[ZTestUnit]]).
  *
  * Pipeline (docs/B9_pipeline.md): an instruction issues at decode, reading rs1, rs2 and rs3 from
  * the lanes' three register-file copies, then moves through stages cBusyLoad to 1: operands held
  * at cHoldC, valid at cOperands, the FMA until 2, write-back at 1. The next instruction issues
  * every cycle unless it reads a register an instruction in flight writes (the lanes bypass the one
  * in write-back), follows a branch, or waits for a unit. */
class BorgCore(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgCoreIO(cfg))
  private val config = cfg.fp
  private val allLanes = ((1 << cfg.fragLanes) - 1).U(cfg.fragLanes.W)

  // @doc:storage
  val slots = RegInit(VecInit(Seq.fill(cfg.quadSlots)(0.U.asTypeOf(new QuadSlot(cfg)))))
  // The slot that issues; units write their result to the slot that issued them.
  val issueSlot = WireDefault(0.U(cfg.slotBits.W))
  // Triggers and MMIO start slot 0 until the dispatcher picks slots (step 2c).
  private val startSlot = slots(0)
  private val slot = slots(issueSlot)
  val programCounter = slot.pc
  val running = slot.running
  val uniformMem = SyncReadMem(cfg.maxUniforms, UInt(config.totalBits.W))
  // @doc:end

  // Bit k - 1: an instruction in stage k. The units hold or squash them.
  val stages = RegInit(0.U(cfg.cBusyLoad.W))
  def at(k: Int): Bool = stages(k - 1)
  val busy = stages.orR
  val holdPipe = WireDefault(false.B); val squashPipe = WireDefault(false.B)

  // A branch's decision, registered: nextPC feeds the fetch whose word decodes the branch.
  val brTakenReg  = RegInit(false.B)
  val brTargetReg = RegInit(0.U(cfg.pcBits.W))
  val branchDivergent = RegInit(false.B)

  private val exec = Module(new ExecMask(cfg.fragLanes, cfg.quadSlots))

  // The PC advances at issue, a branch's one stage after its operands.
  private val redirect = cfg.cOperands - 1
  val pcAfterThis = Mux(brTakenReg, brTargetReg, programCounter + 1.U)
  val nextPC = Wire(UInt(cfg.pcBits.W))
  private val fetch = Module(new Fetch(cfg))
  fetch.io.pc         := nextPC
  fetch.io.fromRast   := slot.fetchRast
  fetch.io.fromSetup  := slot.fetchSetup
  fetch.io.drawMode   := io.drawMode
  fetch.io.drawSingle := io.drawSingle
  fetch.io.setupShort := io.setupShort
  fetch.io.codeBase   := io.codeBase
  fetch.io.flush      := io.icacheFlush
  val fetchedInstruction = fetch.io.word
  val fetchReady = fetch.io.ready

  // After ZTEST finishes, IMEM still holds its word for a cycle: nothing issues then.
  val resumeDelay = RegInit(false.B)
  when(resumeDelay) { resumeDelay := false.B }
  // Unit instructions in flight per slot; a slot issues only with none (the implicit wait).
  val pending = RegInit(VecInit(Seq.fill(cfg.quadSlots)(0.U(2.W))))
  private val unitDone = scala.collection.mutable.ArrayBuffer[(Bool, UInt)]()   // each unit's finish and slot
  // The memory port has no lock: no instruction-cache fill while a unit may use it.
  fetch.io.missOk := running && !busy && !resumeDelay && !pending.map(_ =/= 0.U).reduce(_ || _)

  val (regs, opFlags) = Decode(fetchedInstruction, cfg)
  // A BARRIER with every lane masked is one the workgroup skipped (barriers are in uniform control
  // flow): it steps over, since a resume would restart those lanes unmasked.
  val barrierStop = opFlags.barrier && exec.io.mask(issueSlot) =/= 0.U
  val next = RvInt(fetchedInstruction)
  val issued = Wire(new StageInfo)
  issued.writes := !opFlags.branch && !opFlags.execOp && !opFlags.rvNoWrite && !opFlags.unit
  issued.rd     := regs.rd
  issued.branch := opFlags.branch
  issued.drain  := opFlags.ztest
  val canIssue = Wire(Bool())
  val decoding = running && canIssue
  val issuing = decoding && !resumeDelay && fetchReady && fetchedInstruction =/= 0.U
  val redirecting = Wire(Bool())   // the PC goes to pcAfterThis
  val wbBusy = Wire(Bool())        // the pipeline writes back this cycle
  io.issueStats.issued := issuing && !opFlags.barrier
  locally {
    // info(k): the instruction in stage k, valid while at(k).
    val infos = Seq.iterate(RegEnable(issued, issuing), cfg.cBusyLoad)(r => RegEnable(r, !holdPipe))
    def info(k: Int): StageInfo = infos(cfg.cBusyLoad - k)
    def any(ks: Seq[Int])(p: StageInfo => Bool): Bool = ks.map(k => at(k) && p(info(k))).reduce(_ || _)
    wbBusy := at(1) && info(1).writes
    val reads = Seq(next.readsRs1 -> regs.rs1, next.readsRs2 -> regs.rs2, next.readsRs3 -> regs.rs3)
    // A source waits until its writer is in write-back; the lane's bypass gives it that word.
    val hazard = any(2 to cfg.cBusyLoad)(i => i.writes && i.rd =/= 0.U &&
      reads.map { case (used, rs) => used && rs === i.rd }.reduce(_ || _))
    val stops = fetchedInstruction === 0.U || opFlags.barrier || issued.drain
    val behindBranch = any(redirect to cfg.cBusyLoad)(_.branch)
    val unitWait = pending(issueSlot) =/= 0.U
    canIssue := !hazard && !behindBranch && !unitWait && !any(1 to cfg.cBusyLoad)(_.drain) &&
      (!stops || !stages(cfg.cBusyLoad - 1, 1).orR)
    val waiting = running && !resumeDelay && fetchReady && !canIssue
    io.issueStats.waitReg    := waiting && hazard
    io.issueStats.waitBranch := waiting && !hazard && behindBranch
    io.issueStats.waitDrain  := waiting && !hazard && !behindBranch
    io.issueStats.waitEmpty  := waiting && !hazard && !behindBranch && !unitWait && !any(1 to cfg.cBusyLoad)(_.drain)
    io.issueStats.waitUnit   := waiting && !hazard && !behindBranch && (unitWait || holdPipe)
    io.issueStats.drains     := issuing && (issued.drain || opFlags.unit)
    val step = issuing && !opFlags.branch && !issued.drain && !barrierStop
    redirecting := at(redirect) && info(redirect).branch
    nextPC := Mux(redirecting, pcAfterThis, Mux(step, programCounter + 1.U, programCounter))
  }
  runPipeline()
  // The flags and slot of the instruction at its operands (stage cOperands), and its exec mask.
  val flagsX = BorgLane.later(!holdPipe)(RegEnable(opFlags, 0.U.asTypeOf(opFlags), issuing), cfg.cBusyLoad - cfg.cOperands)
  val slotX = BorgLane.later(!holdPipe)(RegEnable(issueSlot, issuing), cfg.cBusyLoad - cfg.cOperands)
  val execMask = exec.io.mask(slotX)
  io.csrSlot := BorgLane.later(!holdPipe)(slotX, cfg.cOperands - 1)

  val (uniformData, funct3Del) = wireUniformRead()
  val ovs = wireRvOperands()

  val lanes = Seq.fill(cfg.fragLanes)(Module(new BorgLane(cfg)))
  lanes.zipWithIndex.foreach { case (lane, i) =>
    lane.io.regs        := regs
    lane.io.slot        := issueSlot
    lane.io.opFlags     := opFlags
    lane.io.stages      := stages
    lane.io.running     := running
    lane.io.fmaStart    := issuing
    lane.io.decode      := decoding
    lane.io.advance     := !holdPipe
    lane.io.seqBusy     := io.seqBusy
    lane.io.uniformData := uniformData
    lane.io.funct3Del   := funct3Del
    lane.io.ovA         := ovs._1
    lane.io.ovB         := ovs._2
    lane.io.bus.address    := io.bus.address
    lane.io.bus.data_in    := io.bus.data_in
    lane.io.bus.is_writing := io.bus.is_writing
    lane.io.bus.is_reading := io.bus.is_reading
    lane.io.execActive  := execMask(i)
    lane.io.execAny     := execMask.orR   // EXANY's answer: stable while it executes
    lane.io.covMask     := io.laneCoverage(i)
    lane.io.dstWord     := io.laneDst(i)
    lane.io.ids.mode    := io.ids.mode
    lane.io.ids.r30     := io.ids.r30(i)
    lane.io.ids.r31     := io.ids.r31(i)
    lane.io.iter        := io.iter(i)
    lane.io.pixelOrigin := io.pixelOrigin
    io.csrWrite(i)      := lane.io.csrWrite
  }
  io.regReadData := lanes(0).io.regReadData
  io.status.running := slots.map(_.running).reduce(_ || _)
  io.status.autoRunPending := slots.map(_.startPending).reduce(_ || _)
  io.branchDivergent := branchDivergent
  io.execFault       := exec.io.fault
  io.barrier.foreach { b =>
    b.hit      := slots(0).atBarrier
    b.resumePC := slots(0).barrierPC
  }

  // The units' register writes; at most one unit runs at a time.
  lanes.map(_.io.memWrite).foreach { mw => mw.en := false.B; mw.addr := 0.U; mw.data := 0.U }

  private val zTest = Module(new ZTestUnit(cfg))
  attach(zTest.io, _.ztest, blocking = true)
  io.zTestReq := zTest.io.req
  zTest.io.done := io.zTestDone

  private val memUnit = Module(new MemUnit(cfg))
  attach(memUnit.io, f => f.load || f.store || f.sout || f.fattr)
  memUnit.io.record     := io.record
  memUnit.io.laneHelper := io.laneHelper
  memUnit.io.seqBusy    := io.seqBusy

  private val texUnit = Module(new TexUnit(cfg))
  attach(texUnit.io, f => f.tex || f.texa || f.texs)
  texUnit.io.descWritten := io.descWritten

  attach(Module(new ExpLogUnit(cfg)).io, _.explog)
  locally {
    val issue = Mux(issuing && opFlags.unit, UIntToOH(issueSlot, cfg.quadSlots), 0.U)
    pending.zipWithIndex.foreach { case (p, s) =>
      p := (p + issue(s).asUInt - PopCount(unitDone.map { case (done, sl) => done && sl === s.U }.toSeq))(1, 0)
    }
  }

  // The memory port: the sampler's while it runs, the instruction cache's while it fills, else
  // the memory unit's. They never overlap: each runs only while the pipeline waits for it.
  locally {
    val users = Seq(texUnit.io.busy -> texUnit.io.mem, fetch.io.filling -> fetch.io.mem, true.B -> memUnit.io.mem)
    val grant = PriorityEncoderOH(users.map(_._1))
    users.zip(grant).foreach { case ((_, m), g) => m.data := io.gpuMem.data; m.ready := io.gpuMem.ready && g; m.waccept := io.gpuMem.waccept && g }
    def pick[T <: Data](f: GpuMemIO => T): T = Mux1H(grant, users.map(u => f(u._2)))
    io.gpuMem.req   := pick(_.req)
    io.gpuMem.addr  := pick(_.addr)
    io.gpuMem.wr    := pick(_.wr)
    io.gpuMem.wdata := pick(_.wdata)
    io.gpuMem.wlen  := pick(_.wlen)
    io.memBusy := texUnit.io.busy || fetch.io.filling || memUnit.io.busy
  }

  wireBranch()
  locally {
    val ex = at(cfg.cOperands) && flagsX.execOp
    exec.io.slot := slotX
    exec.io.startSlot := 0.U(cfg.slotBits.W)
    exec.io.push := ex && flagsX.expush; exec.io.els := ex && flagsX.exelse; exec.io.pop := ex && flagsX.expop
    exec.io.cond := VecInit(lanes.map(_.io.recARaw))
    // A compute quad starts with only its real invocations: a workgroup may not fill the quad.
    exec.io.start.valid := io.control.start || io.coreTrigger.valid
    exec.io.start.bits  := Mux(io.ids.mode && io.coreTrigger.valid, io.ids.laneMask, allLanes)
  }

  // DDX/DDY: lane 1 - lane 0 and lane 2 - lane 0 of rs1 (TL, TR, BL, BR, see BorgIterator); fine
  // (rs2 = 1) takes each lane's own row for x (1-0 or 3-2) and column for y (2-0 or 3-1). The
  // lane's FMA adds crossA and crossC = -lo.
  locally {
    val a = VecInit(lanes.map(_.io.recARaw))
    def neg(x: UInt): UInt = Cat(~x(cfg.totalBits - 1), x(cfg.totalBits - 2, 0))
    lanes.zipWithIndex.foreach { case (lane, i) =>
      val (xHi, xLo) = if ((i & 2) != 0) (3, 2) else (1, 0)
      val (yHi, yLo) = if ((i & 1) != 0) (3, 1) else (2, 0)
      val hi = Mux(flagsX.fine, Mux(flagsX.ddy, a(yHi), a(xHi)), Mux(flagsX.ddy, a(2), a(1)))
      val lo = Mux(flagsX.fine, Mux(flagsX.ddy, a(yLo), a(xLo)), a(0))
      lane.io.crossA := hi
      lane.io.crossC := neg(lo)
    }
  }

  /** Issue into the stages, advance them and the PC; start, stop and trigger programs; the DMA
    * and MMIO writes of IMEM and the uniforms. */
  private def runPipeline(): Unit = {
    // @doc:fetch-execute
    stages := Cat(issuing && !opFlags.barrier, stages(cfg.cBusyLoad - 1, 1))
    programCounter := nextPC
    when(decoding && !resumeDelay && fetchReady) {
      when(fetchedInstruction === 0.U) {
        running        := false.B
        slot.atBarrier := false.B
      }.elsewhen(barrierStop) {
        // The compute sequencer resumes the invocation one word past the BARRIER.
        running        := false.B
        slot.atBarrier := true.B
        slot.barrierPC := (programCounter + 1.U)(cfg.pcBits - 1, 0)
      }
    }

    when(io.control.start) {
      startSlot.running := true.B
      startSlot.byRasterizer := false.B
      startSlot.fetchRast := false.B
      startSlot.fetchSetup := false.B
    }
    when(io.control.reset) {
      startSlot.pc := io.control.startPC
      startSlot.running := false.B
      stages := 0.U
    }
    when(holdPipe) { stages := stages }
    when(squashPipe) { stages := 0.U }
    // @doc:end

    when(io.coreTrigger.valid) {
      startSlot.pc := io.coreTrigger.pc(cfg.pcBits - 1, 0)
      startSlot.startPending := true.B
      startSlot.byRasterizer := true.B
      startSlot.fetchRast := io.coreTrigger.isRast
      startSlot.fetchSetup := io.coreTrigger.isSetup
      if (BorgDebug.trace) printf("[CORE] coreTrigger pc=%d isRast=%d\n",
        io.coreTrigger.pc, io.coreTrigger.isRast)
    }
    slots.foreach { q =>
      when(q.startPending) {
        q.running := true.B
        q.startPending := false.B
      }
    }

    val mmioImemWrite =
        io.bus.is_writing && io.bus.address >= BorgGpuRegs.imem_offset && io.bus.address < 416.U // 128 + 72*4
    fetch.io.load.valid := io.dmaImemWrite.en || mmioImemWrite
    fetch.io.load.bits.addr := Mux(io.dmaImemWrite.en, io.dmaImemWrite.addr, (io.bus.address - BorgGpuRegs.imem_offset) >> 2)
    fetch.io.load.bits.data := Mux(io.dmaImemWrite.en, io.dmaImemWrite.data, io.bus.data_in)

    val mmioUnifWrite =
        io.bus.is_writing && io.bus.address >= BorgGpuRegs.uniform_offset && io.bus.address < 560.U // 432 + 128
    val unifIdx = (io.bus.address - BorgGpuRegs.uniform_offset) >> 2
    val unifWen  = io.dmaUniformWrite.en || mmioUnifWrite
    val unifAddr = if (cfg.maxUniforms > 32)
      Mux(io.dmaUniformWrite.en, io.dmaUniformWrite.addr, Cat(io.control.uniformWritePage, unifIdx(4, 0)))
    else
      Mux(io.dmaUniformWrite.en, io.dmaUniformWrite.addr(4, 0), unifIdx(4, 0))
    val unifData = Mux(io.dmaUniformWrite.en, io.dmaUniformWrite.data, io.bus.data_in(config.totalBits - 1, 0))
    when(unifWen) { uniformMem.write(unifAddr, unifData) }
  }

  /** Operands that are no register: x0, LUI's zero, AUIPC's address, immediates, CSRs. Latched
    * with the uniform read. */
  private def wireRvOperands(): (Valid[UInt], Valid[UInt]) = {
    val opEn = decoding || stages(cfg.cBusyLoad - 1, 1).orR
    val rv = RvInt(fetchedInstruction)
    val w = cfg.totalBits
    def latch(valid: Bool, bits: UInt) = {
      val v = Wire(Valid(UInt(w.W)))
      v.valid := RegEnable(valid, false.B, opEn); v.bits := RegEnable(bits(w - 1, 0), opEn)
      v
    }
    val csrValue = MuxLookup(rv.csr, 0.U(w.W))(Seq(Csr.Root.U -> io.root,
      Csr.Attachment.U -> io.attIndex.pad(w)))
    (latch(rv.aZero || rv.auipc || rv.csrrs && !rv.csrReg && !rv.csrWin,
           Mux(rv.csrrs, csrValue, Mux(rv.auipc, (programCounter << 2).pad(32), 0.U(32.W)))),
     latch(rv.bZero || rv.bImm || rv.csrrs, Mux(rv.bImm, rv.imm, 0.U(32.W))))
  }

  /** The uniform window's word at rs1, one read for every lane, and funct3 to pick it. */
  private def wireUniformRead(): (UInt, UInt) = {
    val opEn = decoding || stages(cfg.cBusyLoad - 1, 1).orR
    val funct3 = RegEnable(opFlags.funct3, opEn)
    val addr = if (cfg.maxUniforms > 32) {
      Cat(Mux(slot.byRasterizer, io.uniformPage, io.control.uniformWritePage), regs.rs1(4, 0))
    } else {
      regs.rs1(4, 0)
    }
    val data = uniformMem.read(addr, opEn)
    (Mux(RegNext(opEn, false.B), data, 0.U), funct3)
  }

  /** Connects a multi-cycle unit, which takes the instructions `mine` picks. A blocking one holds
    * the pipeline while it works; when it finishes, the stages are squashed and the PC steps past
    * its instruction. The others run beside the pipeline and count in their slot's `pending`. */
  private def attach(u: CoreUnitIO, mine: FpuOpFlags => Bool, blocking: Boolean = false): Unit = {
    val issue = issuing && mine(opFlags)
    u.issue.valid := issue
    u.issue.bits.word := fetchedInstruction
    u.issue.bits.regs := regs
    u.atOps := at(cfg.cOperands) && mine(flagsX)
    u.mask  := execMask
    u.wbReady := !wbBusy
    u.a := VecInit(lanes.map(_.io.recARaw)); u.b := VecInit(lanes.map(_.io.recBRaw)); u.c := VecInit(lanes.map(_.io.recCRaw))
    val unitSlot = RegEnable(issueSlot, issue)
    if (blocking) {
      when(u.hold) { holdPipe := true.B }
      when(u.finish) {
        squashPipe := true.B
        programCounter := programCounter + 1.U
        resumeDelay := true.B
      }
    } else unitDone += (u.finish -> unitSlot)
    lanes.zip(u.wb).foreach { case (l, w) =>
      when(w.en) { l.io.memWrite.en := true.B; l.io.memWrite.addr := RegFileCopy.addr(cfg, unitSlot, w.addr); l.io.memWrite.data := w.data }
    }
  }

  /** BEQ..BGEU decide on the lowest active lane at the operands, JAL always jumps; the PC
    * redirects a stage later. A condition that differs between active lanes sets the sticky
    * branchDivergent: the quad has one PC, so such a branch cannot be right for every lane. */
  private def wireBranch(): Unit = {
    val rv = RvInt(RegEnable(fetchedInstruction, issuing && opFlags.branch))
    val conds = lanes.map(l => rv.cond(l.io.recARaw, l.io.recBRaw))
    val lead  = PriorityMux(execMask.asBools, conds)
    val taken = (rv.br && lead) || rv.jal
    val offset = Mux(rv.jal, rv.jalOff, rv.brOff)
    when(at(cfg.cOperands)) {
      brTakenReg  := flagsX.branch && taken
      brTargetReg := (programCounter.zext + offset).asUInt(cfg.pcBits - 1, 0)
      val split = conds.zip(execMask.asBools).map { case (c, on) => on && c =/= lead }.reduce(_ || _)
      when(flagsX.branch && rv.br && split) {
        branchDivergent := true.B
        if (BorgDebug.trace) printf("[BR] DIVERGENT pc=%d\n", programCounter)
      }
      if (BorgDebug.trace) when(flagsX.branch) { printf("[BR] pc=%d taken=%d target=%d\n", programCounter, taken, offset) }
    }
    // Consumed by this cycle's PC advance; it must not carry into the next instruction.
    when(redirecting) { brTakenReg := false.B }
  }
}
