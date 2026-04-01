// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.*
import chisel3.util.*

/** BorgCore — FPU pipeline, register files, instruction memory, and MMIO.
  *
  * Contains the 4-cycle FMA pipeline, triplicated register file,
  * instruction memory, coordinate LUT, and all MMIO decode logic
  * except rasterizer-specific registers (bbox/iter).
  * This module can be independently unit-tested for FPU correctness.
  */

class BorgCoreIO(val config: FloatConfig) extends Bundle {
  // MMIO bus (directly from BorgIO)
  val address      = Input(UInt(9.W))
  val data_in      = Input(UInt(32.W))
  val is_writing   = Input(Bool())
  val is_reading   = Input(Bool())

  // Rasterizer interface
  val iterX          = Input(UInt(6.W))
  val iterY          = Input(UInt(6.W))
  val triggerShader  = Input(Bool())       // pulse from rasterizer: set PC=0 + pending

  // Pipeline write-back snoop (exposed to rasterizer)
  val pipeWriteEn   = Output(Bool())
  val pipeWriteAddr = Output(UInt(log2Ceil(MmioMap.BORG_NUM_REGS).W))
  val pipeWriteData = Output(UInt(config.totalBits.W))

  // Status outputs (exposed to rasterizer and top-level read mux)
  val running        = Output(Bool())
  val autoRunPending = Output(Bool())

  // MMIO register read data (for top-level read mux)
  val regReadData = Output(UInt(config.totalBits.W))

  // Status register (for top-level read mux)
  val statusReg = Output(UInt(config.totalBits.W))
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

  val instructionMemory = SyncReadMem(MmioMap.BORG_IMEM_SLOTS, UInt(32.W))
  val programCounter = RegInit(0.U(log2Ceil(MmioMap.BORG_IMEM_SLOTS).W))
  val running = RegInit(false.B)
  val auto_run_pending = RegInit(false.B)

  // --- Coordinate Expansion LUT ---
  // Maps 6-bit integer pixel coordinates (0-63) to float16 pixel centers (+0.5)
  val coordLut = VecInit(Seq.tabulate(64) { i =>
    val f = i.toFloat + 0.5f
    val bits = if (config == FloatConfig.FP32) {
      java.lang.Float.floatToRawIntBits(f) & 0xffffffffL
    } else {
      val raw = java.lang.Float.floatToRawIntBits(f)
      val exp = ((raw >>> 23) & 0xff) - 127 + 15
      val sig = (raw >>> 13) & 0x3ff
      (exp << 10) | sig
    }
    bits.U(config.totalBits.W)
  })
  // @doc:end

  // --- Pipeline Control ---
  val busy_counter = RegInit(0.U(3.W))
  val is_busy = busy_counter > 0.U

  // --- Instruction Fetch ---
  val nextPC =
    Mux(is_busy && busy_counter === 1.U, programCounter + 1.U, programCounter)
  val fetchedInstruction = instructionMemory.read(nextPC)

  // @doc:instruction-format
  // --- Instruction Decode ---
  val (rs1_idx, rs2_idx, rs3_idx, rd_idx,
       is_fma, is_mul, is_fneg, is_fstep, is_frcp) = decode(fetchedInstruction)
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
  io.statusReg := Cat(0.U((config.totalBits - 2).W), !running, 0.U(1.W))
  // @doc:end

  // =========================================================================
  // Helper functions
  // =========================================================================

  /** Decode a 32-bit RISC-V instruction into register indices and op flags. */
  private def decode(instr: UInt) = {
    val rs1 = Instructions.BF_RS1(instr)
    val rs2 = Instructions.BF_RS2(instr)
    val rs3 = Instructions.BF_RS3(instr)
    val rd  = Instructions.BF_RD(instr)

    val fma   = instr(Instructions.BITS_OPCODE_FMA_BIT)
    val f7op  = Instructions.BF_F7_OP(instr)
    val mul   = !fma && f7op === Instructions.FUNCT7_MUL.U
    val fneg  = !fma && f7op === Instructions.FUNCT7_FNEG.U
    val fstep = !fma && f7op === Instructions.FUNCT7_FSTEP.U
    val frcp  = !fma && f7op === Instructions.FUNCT7_FRCP.U

    (rs1, rs2, rs3, rd, fma, mul, fneg, fstep, frcp)
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

    // Control register: bit 0 = start, bit 1 = reset
    when(io.is_writing && io.address === MmioMap.BORG_CONTROL_OFFSET.U) {
      when(io.data_in(0)) { running := true.B }
      when(io.data_in(1)) {
        programCounter := io.data_in(MmioMap.BORG_CTL_PC_MSB, MmioMap.BORG_CTL_PC_LSB)
        running := false.B
        busy_counter := 0.U
      }
    }

    // Auto-trigger from rasterizer
    when(io.triggerShader) {
      programCounter := 0.U
      auto_run_pending := true.B
    }

    // Delayed start: SyncReadMem has now fetched imem[0]
    when(auto_run_pending) {
      running := true.B
      auto_run_pending := false.B
    }

    // IMEM write
    when(io.is_writing && io.address >= MmioMap.BORG_IMEM_OFFSET.U && io.address < MmioMap.BORG_IMEM_END.U) {
      val imem_idx = (io.address - MmioMap.BORG_IMEM_OFFSET.U) >> 2
      instructionMemory.write(imem_idx, io.data_in)
    }
  }

  /** Wire the three register file read ports (A=rs1, B=rs2, C=rs3/MMIO). */
  private def wireRegisterReads() = {
    val recA = wirePortA()
    val recB = wirePortB()
    val (recC, mmio_data) = wirePortC()
    (recA, recB, recC, mmio_data)
  }

  /** Port A: pipeline rs1 read from regFileA. */
  private def wirePortA(): UInt = {
    val en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
    val en_del = RegNext(en, false.B)
    val rs1_idx_del = RegEnable(rs1_idx, en)
    regFileA.io.readAddr := rs1_idx
    regFileA.io.readEn := en
    val resolved_data = Mux(rs1_idx_del === 30.U, coordLut(io.iterX),
                        Mux(rs1_idx_del === 31.U, coordLut(io.iterY),
                        regFileA.io.readData))
    Mux(en_del, resolved_data, 0.U)
  }

  /** Port B: pipeline rs2 read from regFileB. */
  private def wirePortB(): UInt = {
    val en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
    val en_del = RegNext(en, false.B)
    val rs2_idx_del = RegEnable(rs2_idx, en)
    regFileB.io.readAddr := rs2_idx
    regFileB.io.readEn := en
    val resolved_data = Mux(rs2_idx_del === 30.U, coordLut(io.iterX),
                        Mux(rs2_idx_del === 31.U, coordLut(io.iterY),
                        regFileB.io.readData))
    Mux(en_del, resolved_data, 0.U)
  }

  /** Port C: rs3 during execution, MMIO register access when idle. */
  private def wirePortC(): (UInt, UInt) = {
    val mmio_en = !running && !is_busy && (io.is_reading || io.is_writing) && io.address >= MmioMap.BORG_REG_OFFSET.U && io.address < MmioMap.BORG_IMEM_OFFSET.U
    val rs3_en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
    val addr = Mux(running || is_busy, rs3_idx, (io.address - MmioMap.BORG_REG_OFFSET.U) >> 2)
    val en = mmio_en || rs3_en
    val mmio_en_del = RegNext(mmio_en && io.is_reading, false.B)
    val rs3_en_del = RegNext(rs3_en, false.B)
    val addr_del = RegEnable(addr, en)
    regFileC.io.readAddr := addr
    regFileC.io.readEn := en
    val resolved_data = Mux(addr_del === 30.U, coordLut(io.iterX),
                        Mux(addr_del === 31.U, coordLut(io.iterY),
                        regFileC.io.readData))
    (Mux(rs3_en_del, resolved_data, 0.U),
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
      is_mul_reg := is_mul
      is_fma_reg := is_fma
      is_fneg_reg := is_fneg
      is_fstep_reg := is_fstep
      is_frcp_reg := is_frcp
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
    val mmio_write = io.is_writing && io.address >= MmioMap.BORG_REG_OFFSET.U && io.address < MmioMap.BORG_IMEM_OFFSET.U
    val pipe_write = running && is_busy && busy_counter === 1.U
    val w_en = mmio_write || pipe_write
    val w_addr = Mux(pipe_write, rd_idx, (io.address - MmioMap.BORG_REG_OFFSET.U) >> 2)

    val w_data = Mux(pipe_write,
      Mux(is_fstep_reg, fstep_result,
        Mux(is_frcp_reg, frcp_result, fma_result)),
      io.data_in(config.totalBits - 1, 0))

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
