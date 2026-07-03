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
  * Pipeline timing (busy_counter counts 4→0):
  *   4..2: Stage 1 — operand reads valid; op-type flags latched (in the lane)
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
  val dmaUniformWrite = Flipped(new MemWritePort(6, 16))

  // Pipeline write-back snoop, per lane (exposed to rasterizer + sequencer)
  val pipeWrite = Vec(cfg.fragLanes, new PipeWriteIO(cfg.totalBits))

  // Status outputs (exposed to rasterizer and top-level read mux)
  val status = new CoreStatusIO

  // MMIO register read data (for top-level read mux)
  val regReadData = Output(UInt(cfg.totalBits.W))

  // Step 30.1d: when sequencer is running vertex/setup shaders, r30/r31 must
  // return 0 (not coordX/coordY) because those shaders use r31 as zero.
  val seqBusy = Input(Bool())

  // Step 34.4: FTEX texture sample request/response
  val texReq  = Output(Bool())       // core requests texture fetch
  val texU    = Output(UInt(16.W))   // U coordinate from rs1
  val texV    = Output(UInt(16.W))   // V coordinate from rs2
  val texDone = Input(Bool())        // texture unit completion pulse
  val texR    = Input(UInt(16.W))    // fetched texel R
  val texG    = Input(UInt(16.W))    // fetched texel G
  val texB    = Input(UInt(16.W))    // fetched texel B
}

class BorgCore(val cfg: BorgConfig = BorgConfig.Default) extends Module {
  val io = IO(new BorgCoreIO(cfg))

  private val config = cfg.fp  // shorthand for FP config used in arithmetic

  // @doc:storage
  // --- Shared storage ---
  val instructionMemory = SyncReadMem(cfg.maxInstructions, UInt(32.W))
  val programCounter = RegInit(0.U(log2Ceil(cfg.maxInstructions).W))
  val running = RegInit(false.B)
  val auto_run_pending = RegInit(false.B)
  val running_by_rasterizer = RegInit(false.B)

  val uniformMem = SyncReadMem(cfg.maxUniforms, UInt(config.totalBits.W))
  // @doc:end

  // --- Pipeline Control ---
  val busy_counter = RegInit(0.U(3.W))
  val is_busy = busy_counter > 0.U

  // --- Instruction Fetch ---
  val nextPC =
    Mux(is_busy && busy_counter === 1.U, programCounter + 1.U, programCounter)
  val fetchedInstruction = instructionMemory.read(nextPC)

  // FTEX resume delay: after FTEX writeback completes, the IMEM still holds the
  // stale FTEX opcode (1-cycle SyncReadMem latency); suppress restart for 1 cycle.
  val texResumeDelay = RegInit(false.B)
  when(texResumeDelay) { texResumeDelay := false.B }

  // --- Decode + FSM ---
  val (regs, opFlags) = decode(fetchedInstruction)
  val fma_start = running && !is_busy && !texResumeDelay && fetchedInstruction =/= 0.U
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
  // Per-lane pixel coordinate (2×2 quad fanned out by the iterator).
  lanes.zipWithIndex.foreach { case (lane, i) => lane.io.iter := io.iter(i) }

  // Each lane exposes its own write-back snoop; lane 0 drives the MMIO read.
  lanes.zipWithIndex.foreach { case (lane, i) => io.pipeWrite(i) := lane.io.pipeWrite }
  io.regReadData := lanes(0).io.regReadData
  io.status.running := running
  io.status.autoRunPending := auto_run_pending

  // --- FTEX FSM (shared): drives each lane's texWrite, uses lane 0's operands ---
  wireTexStall(lanes.map(_.io.recARaw), lanes.map(_.io.recBRaw), lanes.map(_.io.texWrite))

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
    flags.mul   := !flags.fma && f7op === Instructions.FUNCT7_MUL.U
    flags.fneg  := !flags.fma && f7op === Instructions.FUNCT7_FNEG.U
    flags.fstep := !flags.fma && f7op === Instructions.FUNCT7_FSTEP.U
    flags.frcp  := !flags.fma && f7op === Instructions.FUNCT7_FRCP.U
    flags.ftex  := !flags.fma && f7op === Instructions.FUNCT7_FTEX.U
    flags.iadd  := !flags.fma && f7op === Instructions.FUNCT7_IADD.U
    flags.ishl  := !flags.fma && f7op === Instructions.FUNCT7_ISHL.U
    flags.ishr  := !flags.fma && f7op === Instructions.FUNCT7_ISHR.U
    flags.imul  := !flags.fma && f7op === Instructions.FUNCT7_IMUL.U
    flags.i2f   := !flags.fma && f7op === Instructions.FUNCT7_I2F.U
    flags.f2i   := !flags.fma && f7op === Instructions.FUNCT7_F2I.U
    flags.frsq  := !flags.fma && f7op === Instructions.FUNCT7_FRSQ.U
    flags.fsrgb := !flags.fma && f7op === Instructions.FUNCT7_FSRGB.U
    flags.ddx   := !flags.fma && f7op === Instructions.FUNCT7_DDX.U
    flags.ddy   := !flags.fma && f7op === Instructions.FUNCT7_DDY.U
    flags.funct3 := Instructions.BF_FUNCT3(instr)

    (regs, flags)
  }
  // @doc:end

  /** Fetch/execute FSM: start pipeline, count down busy cycles, advance PC. */
  private def runPipeline(fma_start: Bool): Unit = {
    // @doc:fetch-execute
    when(running && !is_busy && !texResumeDelay) {
      when(fetchedInstruction === 0.U) {
        running := false.B
      }.otherwise {
        busy_counter := 4.U
      }
    }.elsewhen(is_busy) {
      busy_counter := busy_counter - 1.U
      when(busy_counter === 1.U) {
        programCounter := programCounter + 1.U
      }
    }

    when(io.control.start) {
      running := true.B
      running_by_rasterizer := false.B
    }
    when(io.control.reset) {
      programCounter := io.control.startPC
      running := false.B
      busy_counter := 0.U
    }
    // @doc:end

    when(io.coreTrigger.valid) {
      programCounter := io.coreTrigger.pc
      auto_run_pending := true.B
      running_by_rasterizer := true.B
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
    when(imemWen) { instructionMemory.write(imemAddr, imemData) }

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
  /** Step 34.4: FTEX texture-sample stall and 3-register write-back.  Shared FSM:
    * latches operands → texReq, freezes busy_counter while waiting, then writes
    * texR/G/B to rd/rd+1/rd+2 via each lane's texWrite port over 3 cycles. */
  private def wireTexStall(recAs: Seq[UInt], recBs: Seq[UInt], texWrites: Seq[MemWritePort]): Unit = {
    val N = cfg.fragLanes
    val is_ftex_reg = RegInit(false.B)
    when(running && !is_busy && fetchedInstruction =/= 0.U) {
      is_ftex_reg := opFlags.ftex
    }

    // One texture unit, serialized over lanes: request lane → wait → write that
    // lane's rd/rd+1/rd+2 → next lane.  At fragLanes=1 this is the original path.
    val sTexIdle :: sTexReq :: sTexWait :: sTexWB0 :: sTexWB1 :: sTexWB2 :: Nil = Enum(6)
    val texState = RegInit(sTexIdle)
    val texLane  = RegInit(0.U(log2Ceil(N + 1).W))

    val texRdReg = RegInit(0.U(5.W))
    val texResultR = RegInit(0.U(16.W))
    val texResultG = RegInit(0.U(16.W))
    val texResultB = RegInit(0.U(16.W))

    // Active lane's U/V operands (read ports stay valid while busy_counter is held).
    val curA = VecInit(recAs)(texLane)
    val curB = VecInit(recBs)(texLane)

    // Defaults
    io.texReq := false.B
    io.texU   := 0.U
    io.texV   := 0.U
    // Lane-selective write: only the active lane's register file is written.
    def driveTexWriteLane(lane: UInt, en: Bool, addr: UInt, data: UInt): Unit =
      texWrites.zipWithIndex.foreach { case (tw, i) =>
        tw.en := en && (i.U === lane); tw.addr := addr; tw.data := data
      }
    driveTexWriteLane(0.U, false.B, 0.U, 0.U)

    // Initiate FTEX at counter=4 (operands valid): start with lane 0.
    when(is_busy && busy_counter === 4.U && is_ftex_reg) {
      texRdReg := regs.rd
      texLane  := 0.U
      texState := sTexReq
    }

    // Request the active lane's texel.
    when(texState === sTexReq) {
      busy_counter := busy_counter            // hold operands stable
      io.texReq := true.B
      io.texU   := curA(15, 0)
      io.texV   := curB(15, 0)
      when(io.texDone) {                      // same-cycle (e.g. texture disabled → white)
        texResultR := io.texR; texResultG := io.texG; texResultB := io.texB
        texState   := sTexWB0
      }.otherwise {
        texState := sTexWait
      }
    }

    when(texState === sTexWait) {
      busy_counter := busy_counter            // hold
      when(io.texDone) {
        texResultR := io.texR; texResultG := io.texG; texResultB := io.texB
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
      driveTexWriteLane(texLane, true.B, texRdReg + 2.U, texResultB)
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
}
