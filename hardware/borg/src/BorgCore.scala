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

  // Rasterizer interface
  val iter               = Input(new Coord(cfg.coordWidth))
  val coreTrigger       = Flipped(new CoreTriggerIO)  // pulse from rasterizer: trigger shader
  val uniformPage        = Input(UInt(1.W))      // which 32-entry uniform page the GPU reads from

  // Control signals from SystemRDL register block
  val control = Input(new CoreControlIO)

  // CoordLut/RcpLut initialization (for simulation — synthesis uses loadMemoryFromFileInline)
  val lutInit = Input(new LutInitIO(9, cfg.totalBits))

  // DMA write ports (Step 22.1): DMA takes priority over MMIO writes
  val dmaImemWrite    = Flipped(new MemWritePort(6, 32))
  val dmaUniformWrite = Flipped(new MemWritePort(6, 16))

  // Pipeline write-back snoop (exposed to rasterizer)
  val pipeWrite = new PipeWriteIO(cfg.totalBits)

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

  // --- Shared storage ---
  val instructionMemory = SyncReadMem(cfg.maxInstructions, UInt(32.W))
  val programCounter = RegInit(0.U(log2Ceil(cfg.maxInstructions).W))
  val running = RegInit(false.B)
  val auto_run_pending = RegInit(false.B)
  val running_by_rasterizer = RegInit(false.B)

  val uniformMem = SyncReadMem(cfg.maxUniforms, UInt(config.totalBits.W))

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
    lane.io.lutInit     := io.lutInit
    // MMIO bus is broadcast (all lanes are slaves seeing the same transaction).
    lane.io.bus.address    := io.bus.address
    lane.io.bus.data_in    := io.bus.data_in
    lane.io.bus.is_writing := io.bus.is_writing
    lane.io.bus.is_reading := io.bus.is_reading
  }
  // Per-lane pixel coordinate (Phase 4 will fan out a 2×2 quad; today N=1).
  lanes.foreach { _.io.iter := io.iter }

  // Lane 0 drives the scalar outputs (MMIO read + write-back snoop).
  io.pipeWrite   := lanes(0).io.pipeWrite
  io.regReadData := lanes(0).io.regReadData
  io.status.running := running
  io.status.autoRunPending := auto_run_pending

  // --- FTEX FSM (shared): drives each lane's texWrite, uses lane 0's operands ---
  wireTexStall(lanes(0).io.recARaw, lanes(0).io.recBRaw, lanes.map(_.io.texWrite))

  // =========================================================================
  // Helper functions
  // =========================================================================

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
    flags.funct3 := Instructions.BF_FUNCT3(instr)

    (regs, flags)
  }

  /** Fetch/execute FSM: start pipeline, count down busy cycles, advance PC. */
  private def runPipeline(fma_start: Bool): Unit = {
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
        io.bus.is_writing && io.bus.address >= BorgGpuRegs.imem_offset && io.bus.address < 352.U
    val imemWen  = io.dmaImemWrite.en || mmioImemWrite
    val imemAddr = Mux(io.dmaImemWrite.en, io.dmaImemWrite.addr,
                       (io.bus.address - BorgGpuRegs.imem_offset) >> 2)
    val imemData = Mux(io.dmaImemWrite.en, io.dmaImemWrite.data, io.bus.data_in)
    when(imemWen) { instructionMemory.write(imemAddr, imemData) }

    // Uniform write: same single-port pattern.
    val mmioUnifWrite =
        io.bus.is_writing && io.bus.address >= BorgGpuRegs.uniform_offset && io.bus.address < 496.U
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
  private def wireTexStall(recA_raw: UInt, recB_raw: UInt, texWrites: Seq[MemWritePort]): Unit = {
    val is_ftex_reg = RegInit(false.B)
    when(running && !is_busy && fetchedInstruction =/= 0.U) {
      is_ftex_reg := opFlags.ftex
    }

    val sTexIdle :: sTexWait :: sTexWB0 :: sTexWB1 :: sTexWB2 :: Nil = Enum(5)
    val texState = RegInit(sTexIdle)

    val texRdReg = RegInit(0.U(5.W))
    val texResultR = RegInit(0.U(16.W))
    val texResultG = RegInit(0.U(16.W))
    val texResultB = RegInit(0.U(16.W))

    // Defaults
    io.texReq := false.B
    io.texU   := 0.U
    io.texV   := 0.U
    def driveTexWrite(en: Bool, addr: UInt, data: UInt): Unit =
      texWrites.foreach { tw => tw.en := en; tw.addr := addr; tw.data := data }
    driveTexWrite(false.B, 0.U, 0.U)

    // Initiate FTEX at counter=4 (operands valid)
    when(is_busy && busy_counter === 4.U && is_ftex_reg) {
      io.texReq := true.B
      io.texU   := recA_raw(15, 0)
      io.texV   := recB_raw(15, 0)
      texRdReg  := regs.rd
      when(io.texDone) {
        texResultR := io.texR; texResultG := io.texG; texResultB := io.texB
        texState   := sTexWB0
      }.otherwise {
        texState := sTexWait
      }
    }

    when(texState === sTexWait) { busy_counter := busy_counter }      // hold

    when(texState === sTexWait && io.texDone) {
      texResultR := io.texR; texResultG := io.texG; texResultB := io.texB
      texState   := sTexWB0
    }

    when(texState === sTexWB0) {
      driveTexWrite(true.B, texRdReg, texResultR); texState := sTexWB1
    }
    when(texState === sTexWB1) {
      driveTexWrite(true.B, texRdReg + 1.U, texResultG); texState := sTexWB2
    }
    when(texState === sTexWB2) {
      driveTexWrite(true.B, texRdReg + 2.U, texResultB)
      texState   := sTexIdle
      is_ftex_reg := false.B
      busy_counter := 0.U
      programCounter := programCounter + 1.U
      texResumeDelay := true.B
    }

    when(texState === sTexWB0 || texState === sTexWB1) {
      busy_counter := busy_counter  // hold
    }
  }
  // @doc:end
}
