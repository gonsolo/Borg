// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.*
import chisel3.util.*
import chisel3.util.experimental.loadMemoryFromFileInline

/** BorgCore — FPU pipeline, register files, instruction memory, and MMIO.
  *
  * Contains the 4-cycle FMA pipeline, triplicated register file,
  * instruction memory, coordinate LUT, and all MMIO decode logic
  * except rasterizer-specific registers (bbox/iter).
  * This module can be independently unit-tested for FPU correctness.
  */

class BorgCoreIO(val config: FloatConfig) extends Bundle {
  // MMIO bus
  val bus = Flipped(new BorgBusIO())

  // Rasterizer interface
  val iter               = Input(new Coord())
  val triggerShaderValid = Input(Bool())        // pulse from rasterizer: trigger shader
  val triggerShaderPC    = Input(UInt(6.W))      // PC to start at
  val uniformPage        = Input(UInt(1.W))      // which 32-entry uniform page the GPU reads from
  val uniformWritePage   = Input(UInt(1.W))      // which 32-entry page MMIO writes target

  // Control signals from SystemRDL register block
  val controlStart       = Input(Bool())
  val controlReset       = Input(Bool())
  val controlStartPC     = Input(UInt(6.W))

  // CoordLut initialization (for simulation — synthesis uses loadMemoryFromFileInline)
  val coordWriteEn   = Input(Bool())
  val coordWriteAddr = Input(UInt(6.W))
  val coordWriteData = Input(UInt(config.totalBits.W))

  // Pipeline write-back snoop (exposed to rasterizer)
  val pipeWriteEn   = Output(Bool())
  val pipeWriteAddr = Output(UInt(log2Ceil(32).W))
  val pipeWriteData = Output(UInt(config.totalBits.W))

  // Status outputs (exposed to rasterizer and top-level read mux)
  val running        = Output(Bool())
  val autoRunPending = Output(Bool())

  // MMIO register read data (for top-level read mux)
  val regReadData = Output(UInt(config.totalBits.W))
}

class BorgCore(val config: FloatConfig = FloatConfig.FP32) extends Module {
  val io = IO(new BorgCoreIO(config))

  // @doc:storage
  // --- Storage ---
  // Triplicated register file: GPU-standard multi-port BRAM pattern.
  // 3 identical copies (1 read + 1 write each) for rs1, rs2, rs3/MMIO.
  // All three receive the same writes, so they always hold identical data.
  // Cost: 3 iCE40 BRAMs (of 30 available), saving ~1000 flip-flops.
  val regFileA = Module(new RegFileCopy(config.totalBits, "regFileA"))
  val regFileB = Module(new RegFileCopy(config.totalBits, "regFileB"))
  val regFileC = Module(new RegFileCopy(config.totalBits, "regFileC"))

  val instructionMemory = SyncReadMem(56, UInt(32.W))
  val programCounter = RegInit(0.U(log2Ceil(56).W))
  val running = RegInit(false.B)
  val auto_run_pending = RegInit(false.B)
  val running_by_rasterizer = RegInit(false.B)

  val uniformMem = SyncReadMem(64, UInt(config.totalBits.W))

  // --- Coordinate Expansion LUT (BRAM) ---
  // Maps 6-bit integer pixel coordinates (0-63) to float16 pixel centers (+0.5)
  // Two BRAM copies: one for X coord reads, one for Y — allows simultaneous access.
  // Saves ~100 LUTs vs. the previous VecInit combinational ROM.
  val coordLutX = SyncReadMem(64, UInt(config.totalBits.W))
  val coordLutY = SyncReadMem(64, UInt(config.totalBits.W))
  loadMemoryFromFileInline(coordLutX, "coord_lut.hex")
  loadMemoryFromFileInline(coordLutY, "coord_lut.hex")

  // CoordLut write port (for simulation initialization — tied off in synthesis)
  when(io.coordWriteEn) {
    coordLutX.write(io.coordWriteAddr, io.coordWriteData)
    coordLutY.write(io.coordWriteAddr, io.coordWriteData)
  }
  // @doc:end

  // --- Pipeline Control ---
  val busy_counter = RegInit(0.U(3.W))
  val is_busy = busy_counter > 0.U

  // Shared BRAM reads: all 3 register ports use the same pixel coordinates
  // (must be after busy_counter/is_busy to avoid forward reference)
  val coordReadEn = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
  val coordX = coordLutX.read(io.iter.x, coordReadEn)
  val coordY = coordLutY.read(io.iter.y, coordReadEn)

  // --- Instruction Fetch ---
  val nextPC =
    Mux(is_busy && busy_counter === 1.U, programCounter + 1.U, programCounter)
  val fetchedInstruction = instructionMemory.read(nextPC)

  // @doc:instruction-format
  // --- Instruction Decode ---
  val (regs, opFlags) = decode(fetchedInstruction)
  // @doc:end

  // @doc:fetch-execute
  // --- Fetch & Execute FSM ---
  val fma_start = running && !is_busy && fetchedInstruction =/= 0.U
  runPipeline(fma_start)

  // --- Register File Read Ports ---
  val (recA_raw, recB_raw, recC_raw, mmio_reg_data) = wireRegisterReads()

  // --- ALU ---
  val (fma_result, is_fstep_reg, is_frcp_reg) =
    wireFma(recA_raw, recB_raw, recC_raw, fma_start)
  val fstep_result = computeFstep(recA_raw)
  val frcp_result = computeFrcp(recA_raw)

  // --- Write-Back ---
  wireWriteBack(fma_result, fstep_result, frcp_result,
    is_fstep_reg, is_frcp_reg, mmio_reg_data)

  // --- Status outputs ---
  io.running := running
  io.autoRunPending := auto_run_pending
  io.regReadData := mmio_reg_data
  // @doc:end

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
    flags.funct3 := Instructions.BF_FUNCT3(instr)

    (regs, flags)
  }

  /** Fetch/execute FSM: start pipeline, count down busy cycles, advance PC. */
  private def runPipeline(fma_start: Bool): Unit = {
    when(running && !is_busy) {
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

    // Control register from RDL (singlepulse fields)
    when(io.controlStart) { 
      running := true.B 
      running_by_rasterizer := false.B
    }
    when(io.controlReset) {
      programCounter := io.controlStartPC
      running := false.B
      busy_counter := 0.U
    }

    // Auto-trigger from rasterizer (Step 10.6.2: carries PC)
    when(io.triggerShaderValid) {
      programCounter := io.triggerShaderPC
      auto_run_pending := true.B
      running_by_rasterizer := true.B
    }

    // Delayed start: SyncReadMem has now fetched imem[0]
    when(auto_run_pending) {
      running := true.B
      auto_run_pending := false.B
    }

    // IMEM write
    when(io.bus.is_writing && io.bus.address >= BorgGpuRegs.imem_offset && io.bus.address < 352.U) {
      val imem_idx = (io.bus.address - BorgGpuRegs.imem_offset) >> 2
      instructionMemory.write(imem_idx, io.bus.data_in)
    }

    when(io.bus.is_writing && io.bus.address >= BorgGpuRegs.uniform_offset && io.bus.address < 496.U) {
      val uniform_idx = (io.bus.address - BorgGpuRegs.uniform_offset) >> 2
      uniformMem.write(Cat(io.uniformWritePage, uniform_idx(4, 0)), io.bus.data_in(config.totalBits - 1, 0))
    }
  }

  /** Wire the three register file read ports (A=rs1, B=rs2, C=rs3/MMIO). */
  private def wireRegisterReads() = {
    val op_en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
    val op_en_del = RegNext(op_en, false.B)
    val funct3_del = RegEnable(opFlags.funct3, op_en) // delayed to match read data

    val (recA, rs1_idx_del) = wirePortA()
    val (recB, rs2_idx_del) = wirePortB()
    val (recC, rs3_idx_del, mmio_data) = wirePortC()

    val uniform_addr = Mux(opFlags.funct3 === 1.U, regs.rs1,
                       Mux(opFlags.funct3 === 2.U, regs.rs2, regs.rs3))
    
    val read_page = Mux(running_by_rasterizer, io.uniformPage, io.uniformWritePage)
    val read_data = uniformMem.read(Cat(read_page, uniform_addr(4, 0)), op_en)
    val uniform_data = Mux(op_en_del, read_data, 0.U)

    val uniform_recA = Mux(funct3_del === 1.U, uniform_data, recA)
    val uniform_recB = Mux(funct3_del === 2.U, uniform_data, recB)
    val uniform_recC = Mux(funct3_del === 3.U, uniform_data, recC)

    (uniform_recA, uniform_recB, uniform_recC, mmio_data)
  }

  /** Port A: pipeline rs1 read from regFileA. */
  private def wirePortA(): (UInt, UInt) = {
    val en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
    val en_del = RegNext(en, false.B)
    val rs1_idx_del = RegEnable(regs.rs1, en)
    regFileA.io.readAddr := regs.rs1
    regFileA.io.readEn := en
    val resolved_data = Mux(rs1_idx_del === 30.U, coordX,
                        Mux(rs1_idx_del === 31.U, coordY,
                        regFileA.io.readData))
    (Mux(en_del, resolved_data, 0.U), rs1_idx_del)
  }

  /** Port B: pipeline rs2 read from regFileB. */
  private def wirePortB(): (UInt, UInt) = {
    val en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
    val en_del = RegNext(en, false.B)
    val rs2_idx_del = RegEnable(regs.rs2, en)
    regFileB.io.readAddr := regs.rs2
    regFileB.io.readEn := en
    val resolved_data = Mux(rs2_idx_del === 30.U, coordX,
                        Mux(rs2_idx_del === 31.U, coordY,
                        regFileB.io.readData))
    (Mux(en_del, resolved_data, 0.U), rs2_idx_del)
  }

  /** Port C: rs3 during execution, MMIO register access when idle. */
  private def wirePortC(): (UInt, UInt, UInt) = {
    val mmio_en = !running && !is_busy && (io.bus.is_reading || io.bus.is_writing) && io.bus.address >= BorgGpuRegs.gpr_offset && io.bus.address < BorgGpuRegs.imem_offset
    val rs3_en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
    val addr = Mux(running || is_busy, regs.rs3, (io.bus.address - BorgGpuRegs.gpr_offset) >> 2)
    val en = mmio_en || rs3_en
    val mmio_en_del = RegNext(mmio_en && io.bus.is_reading, false.B)
    val rs3_en_del = RegNext(rs3_en, false.B)
    val addr_del = RegEnable(addr, en)
    regFileC.io.readAddr := addr
    regFileC.io.readEn := en
    val resolved_data = Mux(addr_del === 30.U, coordX,
                        Mux(addr_del === 31.U, coordY,
                        regFileC.io.readData))
    (Mux(rs3_en_del, resolved_data, 0.U), addr_del,
     Mux(mmio_en_del, resolved_data, 0.U))
  }

  /** Wire FMA unit: mux operands for ADD/MUL/FMA/FNEG.
    * Returns (fma_result, is_fstep_reg, is_frcp_reg).
    */
  private def wireFma(
      recA_raw: UInt, recB_raw: UInt, recC_raw: UInt, start: Bool
  ): (UInt, Bool, Bool) = {
    val recA = recFNFromFN(config.exp, config.sig, recA_raw)
    val recB = recFNFromFN(config.exp, config.sig, recB_raw)
    val recC = recFNFromFN(config.exp, config.sig, recC_raw)

    val one_fn = (((1 << (config.exp - 1)) - 1) << (config.sig - 1)).U(config.totalBits.W)
    val recOne = recFNFromFN(config.exp, config.sig, one_fn)
    val recZero = recFNFromFN(config.exp, config.sig, 0.U(config.totalBits.W))

    // Latch op type for the 4-cycle pipeline
    val is_mul_reg = RegInit(false.B)
    val is_fma_reg = RegInit(false.B)
    val is_fneg_reg = RegInit(false.B)
    val is_fstep_reg = RegInit(false.B)
    val is_frcp_reg = RegInit(false.B)
    when(start) {
      is_mul_reg := opFlags.mul
      is_fma_reg := opFlags.fma
      is_fneg_reg := opFlags.fneg
      is_fstep_reg := opFlags.fstep
      is_frcp_reg := opFlags.frcp
    }

    // @doc:fma-muxing
    val fma = Module(new MulAddRecFN(config.exp, config.sig))
    // ADD:  1.0 * rs1 + rs2       MUL: rs1 * rs2 + 0.0
    // FMA:  rs1 * rs2 + rs3       FNEG: -(1.0 * rs1) + 0.0
    fma.io.op := Mux(is_fneg_reg, 2.U, 0.U)
    fma.io.a := Mux(is_mul_reg || is_fma_reg, recA, recOne)
    fma.io.b := Mux(is_mul_reg || is_fma_reg, recB, recA)
    fma.io.c := Mux(is_fma_reg, recC, Mux(is_mul_reg || is_fneg_reg, recZero, recB))
    fma.io.roundingMode := 0.U
    fma.io.detectTininess := 1.U
    fma.io.valid := start
    // @doc:end

    (fNFromRecFN(config.exp, config.sig, fma.io.out), is_fstep_reg, is_frcp_reg)
  }

  // @doc:fstep
  /** FSTEP: 0.0 if rs1 ≤ 0, else 1.0. */
  private def computeFstep(recA_raw: UInt): UInt = {
    val one_fn = (((1 << (config.exp - 1)) - 1) << (config.sig - 1)).U(config.totalBits.W)
    val neg_or_zero = recA_raw(config.totalBits - 1) || (recA_raw === 0.U)
    Mux(neg_or_zero, 0.U(config.totalBits.W), one_fn)
  }
  // @doc:end

  // @doc:frcp
  /** FRCP: hardware FP16 reciprocal via LUT + interpolation. */
  private def computeFrcp(recA_raw: UInt): UInt = {
    val rcp = Module(new Fp16Rcp)
    rcp.io.in := recA_raw(15, 0)
    if (config.totalBits > 16) Cat(0.U((config.totalBits - 16).W), rcp.io.out)
    else rcp.io.out
  }
  // @doc:end

  /** Write-back: pipeline result or MMIO write to all three register copies. */
  private def wireWriteBack(
      fma_result: UInt, fstep_result: UInt, frcp_result: UInt,
      is_fstep_reg: Bool, is_frcp_reg: Bool, mmio_reg_data: UInt
  ): Unit = {
    val mmio_write = io.bus.is_writing && io.bus.address >= BorgGpuRegs.gpr_offset && io.bus.address < BorgGpuRegs.imem_offset
    val pipe_write = running && is_busy && busy_counter === 1.U
    val w_en = mmio_write || pipe_write
    val w_addr = Mux(pipe_write, regs.rd, (io.bus.address - BorgGpuRegs.gpr_offset) >> 2)

    val w_data = Mux(pipe_write,
      Mux(is_fstep_reg, fstep_result,
        Mux(is_frcp_reg, frcp_result, fma_result)),
      io.bus.data_in(config.totalBits - 1, 0))

    // Expose write-back for rasterizer snooping
    io.pipeWriteEn   := pipe_write
    io.pipeWriteAddr := w_addr
    io.pipeWriteData := w_data

    writeAllCopies(w_addr, w_en, w_data)
  }

  /** Write the same addr/en/data to all three register file copies. */
  private def writeAllCopies(addr: UInt, en: Bool, data: UInt): Unit = {
    for (rf <- Seq(regFileA, regFileB, regFileC)) {
      rf.io.writeAddr := addr
      rf.io.writeEn := en
      rf.io.writeData := data
    }
  }
}
