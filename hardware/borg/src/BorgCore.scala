// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.*
import chisel3.util.*
import chisel3.util.experimental.loadMemoryFromFileInline
import hardfloat._

/** BorgCore — FPU pipeline, register files, instruction memory, and MMIO.
  *
  * Contains the 4-cycle FMA pipeline, triplicated register file,
  * instruction memory, coordinate LUT, and all MMIO decode logic
  * except rasterizer-specific registers (bbox/iter).
  * This module can be independently unit-tested for FPU correctness.
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

  // DMA write ports (Step 22.1): used instead of MMIO on FPGA (cfg.hasImemMmio=false)
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
  // Maps 9-bit integer pixel coordinates (0-511) to float16 pixel centers (+0.5)
  // Two BRAM copies: one for X coord reads, one for Y — allows simultaneous access.
  // Saves ~100 LUTs vs. the previous VecInit combinational ROM.
  val coordLutX = SyncReadMem(512, UInt(config.totalBits.W))
  val coordLutY = SyncReadMem(512, UInt(config.totalBits.W))
  loadMemoryFromFileInline(coordLutX, "coord_lut.hex")
  loadMemoryFromFileInline(coordLutY, "coord_lut.hex")

  // CoordLut write port (for simulation initialization — tied off in synthesis)
  when(io.lutInit.en && !io.lutInit.isRcp) {
    coordLutX.write(io.lutInit.addr, io.lutInit.data)
    coordLutY.write(io.lutInit.addr, io.lutInit.data)
  }
  // @doc:end

  // --- Reciprocal LUT (BRAM) ---
  // 17-entry × 10-bit LUT for FP16 reciprocal with linear interpolation.
  // Two BRAM copies: one reads at lutIdx, one at lutIdx+1 — allows simultaneous access.
  // Dimensions (17×10) are intentionally unique across the design to prevent CIRCT
  // from merging this module with other SyncReadMem instances (which drops $readmemh).
  // Saves ~40-60 LUTs vs. the previous VecInit combinational ROM.
  // @doc:rcp-lut
  val rcpLutA = SyncReadMem(17, UInt(10.W))  // rcpLut[lutIdx]
  val rcpLutB = SyncReadMem(17, UInt(10.W))  // rcpLut[lutIdx + 1]
  loadMemoryFromFileInline(rcpLutA, "rcp_lut.hex")
  loadMemoryFromFileInline(rcpLutB, "rcp_lut.hex")
  // @doc:end

  // RcpLut write port (for simulation initialization — tied off in synthesis)
  when(io.lutInit.en && io.lutInit.isRcp) {
    rcpLutA.write(io.lutInit.addr(4, 0), io.lutInit.data(9, 0))
    rcpLutB.write(io.lutInit.addr(4, 0), io.lutInit.data(9, 0))
  }

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

  // FTEX resume delay: after FTEX writeback completes, the IMEM still
  // holds the stale FTEX opcode (SyncReadMem, 1-cycle latency).  This
  // flag suppresses pipeline restart for 1 cycle so the IMEM can fetch
  // the correct next instruction.
  val texResumeDelay = RegInit(false.B)
  when(texResumeDelay) { texResumeDelay := false.B }

  // @doc:instruction-format
  // --- Instruction Decode ---
  val (regs, opFlags) = decode(fetchedInstruction)
  // @doc:end

  // @doc:fetch-execute
  // --- Fetch & Execute FSM ---
  val fma_start = running && !is_busy && !texResumeDelay && fetchedInstruction =/= 0.U
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
  io.status.running := running
  io.status.autoRunPending := auto_run_pending
  io.regReadData := mmio_reg_data

  // Step 34.4: FTEX stall + multi-register writeback
  wireTexStall(recA_raw, recB_raw)
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

    // Control register from RDL (singlepulse fields)
    when(io.control.start) { 
      running := true.B 
      running_by_rasterizer := false.B
    }
    when(io.control.reset) {
      programCounter := io.control.startPC
      running := false.B
      busy_counter := 0.U
    }

    // Auto-trigger from rasterizer (Step 10.6.2: carries PC)
    when(io.coreTrigger.valid) {
      programCounter := io.coreTrigger.pc
      auto_run_pending := true.B
      running_by_rasterizer := true.B
    }

    // Delayed start: SyncReadMem has now fetched imem[0]
    when(auto_run_pending) {
      running := true.B
      auto_run_pending := false.B
    }

    // IMEM write: DMA has priority when hasDMA=true; MMIO gated by cfg.hasImemMmio (Step 22.0).
    // Single write() call so CIRCT generates a 1-write-port BRAM (avoids unused W1_clk).
    val mmioImemWrite = cfg.hasImemMmio.B &&
        io.bus.is_writing && io.bus.address >= BorgGpuRegs.imem_offset && io.bus.address < 352.U
    val imemWen  = io.dmaImemWrite.en || mmioImemWrite
    val imemAddr = Mux(io.dmaImemWrite.en, io.dmaImemWrite.addr,
                       (io.bus.address - BorgGpuRegs.imem_offset) >> 2)
    val imemData = Mux(io.dmaImemWrite.en, io.dmaImemWrite.data, io.bus.data_in)
    when(imemWen) { instructionMemory.write(imemAddr, imemData) }

    // Uniform write: same single-port pattern.
    val mmioUnifWrite = cfg.hasImemMmio.B &&
        io.bus.is_writing && io.bus.address >= BorgGpuRegs.uniform_offset && io.bus.address < 496.U
    val unifIdx = (io.bus.address - BorgGpuRegs.uniform_offset) >> 2
    val unifWen  = io.dmaUniformWrite.en || mmioUnifWrite
    val unifAddr = Mux(io.dmaUniformWrite.en, io.dmaUniformWrite.addr,
                       Cat(io.control.uniformWritePage, unifIdx(4, 0)))
    val unifData = Mux(io.dmaUniformWrite.en, io.dmaUniformWrite.data,
                       io.bus.data_in(config.totalBits - 1, 0))
    when(unifWen) {
      uniformMem.write(unifAddr, unifData)
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
    
    val read_page = Mux(running_by_rasterizer, io.uniformPage, io.control.uniformWritePage)
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
    regFileA.io.rd.addr := regs.rs1
    regFileA.io.rd.en := en
    val is_coord_reg_A = rs1_idx_del === 30.U || rs1_idx_del === 31.U
    val resolved_data = Mux(is_coord_reg_A,
                        Mux(!io.seqBusy,
                          Mux(rs1_idx_del === 30.U, coordX, coordY),
                          0.U),  // r30/r31 = 0 when not in rasterizer context
                        regFileA.io.rd.data)
    (Mux(en_del, resolved_data, 0.U), rs1_idx_del)
  }

  /** Port B: pipeline rs2 read from regFileB. */
  private def wirePortB(): (UInt, UInt) = {
    val en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
    val en_del = RegNext(en, false.B)
    val rs2_idx_del = RegEnable(regs.rs2, en)
    regFileB.io.rd.addr := regs.rs2
    regFileB.io.rd.en := en
    val is_coord_reg_B = rs2_idx_del === 30.U || rs2_idx_del === 31.U
    val resolved_data = Mux(is_coord_reg_B,
                        Mux(!io.seqBusy,
                          Mux(rs2_idx_del === 30.U, coordX, coordY),
                          0.U),  // r30/r31 = 0 when not in rasterizer context
                        regFileB.io.rd.data)
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
    regFileC.io.rd.addr := addr
    regFileC.io.rd.en := en
    val is_coord_reg_C = addr_del === 30.U || addr_del === 31.U
    val resolved_data = Mux(is_coord_reg_C,
                        Mux(!io.seqBusy,
                          Mux(addr_del === 30.U, coordX, coordY),
                          0.U),  // r30/r31 = 0 when not in rasterizer context
                        regFileC.io.rd.data)
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
  /** FRCP: hardware FP16 reciprocal via LUT + interpolation.
    *
    * RCP LUT is stored in BRAM (2 copies for parallel access).
    * Pipeline timing:
    *   counter=0: fma_start fires, instruction decoded, rs1 address presented
    *   counter=4: recA_raw valid → extract lutIdx → present to BRAM
    *   counter=3: BRAM data available → register it
    *   counter=2: registered data stable
    *   counter=1: write-back uses registered BRAM data + combinational interpolation
    *
    * We issue the BRAM read at counter=4 (when recA_raw first becomes valid)
    * and register the output at counter=3.  The registered values are held
    * stable through write-back at counter=1.
    */
  private def computeFrcp(recA_raw: UInt): UInt = {
    // Extract LUT index from FP16 mantissa (top 4 bits)
    val rcpMant = recA_raw(9, 0)
    val rcpLutIdx = rcpMant(9, 6)

    // Issue BRAM reads continuously — the read address tracks recA_raw(9,6).
    // SyncReadMem returns data 1 cycle after address+enable are presented.
    val rcpReadEn = is_busy && busy_counter >= 2.U
    val rcpLutRawVal  = rcpLutA.read(rcpLutIdx, rcpReadEn)
    val rcpLutRawNext = rcpLutB.read(rcpLutIdx +& 1.U, rcpReadEn)

    // Register the BRAM output at counter=3 (data from the read at counter=4).
    // This ensures the LUT values are stable for the combinational Fp16Rcp
    // logic at write-back (counter=1).
    val rcpLutValReg  = RegEnable(rcpLutRawVal,  is_busy && busy_counter === 3.U)
    val rcpLutNextReg = RegEnable(rcpLutRawNext, is_busy && busy_counter === 3.U)

    val rcp = Module(new Fp16Rcp)
    rcp.io.in      := recA_raw(15, 0)
    rcp.io.lutVal  := rcpLutValReg
    rcp.io.lutNext := rcpLutNextReg
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
    io.pipeWrite.en   := pipe_write
    io.pipeWrite.addr := w_addr
    io.pipeWrite.data := w_data

    writeAllCopies(w_addr, w_en, w_data)
  }

  /** Write the same addr/en/data to all three register file copies. */
  private def writeAllCopies(addr: UInt, en: Bool, data: UInt): Unit = {
    for (rf <- Seq(regFileA, regFileB, regFileC)) {
      rf.io.wr.addr := addr
      rf.io.wr.en := en
      rf.io.wr.data := data
    }
  }

  // @doc:ftex-stall
  /** Step 34.4: FTEX texture-sample stall and 3-register write-back.
    *
    * When an FTEX instruction is decoded:
    *   1. counter=4: latch recA_raw→texU, recB_raw→texV, assert texReq
    *   2. Enter tex-stall: freeze busy_counter at 3, do NOT advance PC
    *   3. On texDone: write texR→GPR[rd], texG→GPR[rd+1], texB→GPR[rd+2]
    *      over 3 clock cycles (single write port)
    *   4. Resume normal pipeline → advance PC
    */
  private def wireTexStall(recA_raw: UInt, recB_raw: UInt): Unit = {
    // Latch FTEX flag through pipeline
    val is_ftex_reg = RegInit(false.B)
    when(running && !is_busy && fetchedInstruction =/= 0.U) {
      is_ftex_reg := opFlags.ftex
      when(opFlags.ftex) {
        if (BorgDebug.trace) printf("[CORE] FTEX-DECODE pc=%d inst=0x%x\n", programCounter, fetchedInstruction)
      }
    }

    // FSM states for texture stall
    val sTexIdle :: sTexWait :: sTexWB0 :: sTexWB1 :: sTexWB2 :: Nil = Enum(5)
    val texState = RegInit(sTexIdle)

    // Latch rd for the 3-register writeback (rd, rd+1, rd+2)
    val texRdReg = RegInit(0.U(5.W))
    // Latch texel results from texture unit
    val texResultR = RegInit(0.U(16.W))
    val texResultG = RegInit(0.U(16.W))
    val texResultB = RegInit(0.U(16.W))

    // Default outputs
    io.texReq := false.B
    io.texU   := 0.U
    io.texV   := 0.U

    // --- Initiate FTEX at counter=4 (operands just became valid) ---
    when(is_busy && busy_counter === 4.U && is_ftex_reg) {
      io.texReq := true.B
      if (BorgDebug.trace) printf("[CORE] FTEX texReq=1 U=0x%x V=0x%x texDone=%d\n", recA_raw(15, 0), recB_raw(15, 0), io.texDone)
      io.texU   := recA_raw(15, 0)
      io.texV   := recB_raw(15, 0)
      texRdReg  := regs.rd
      // Handle same-cycle texDone (tex disabled → immediate white response).
      // texState is still sTexIdle this cycle, so the sTexWait check below
      // won't match.  Latch results and go straight to writeback.
      when(io.texDone) {
        texResultR := io.texR
        texResultG := io.texG
        texResultB := io.texB
        texState   := sTexWB0
      }.otherwise {
        texState := sTexWait
      }
    }

    // --- Stall: freeze busy_counter while waiting for texture unit ---
    when(texState === sTexWait) {
      busy_counter := busy_counter  // hold — override the -1 in runPipeline
    }

    // --- texDone: latch results, start writeback ---
    when(texState === sTexWait && io.texDone) {
      texResultR := io.texR
      texResultG := io.texG
      texResultB := io.texB
      texState   := sTexWB0
    }

    // --- 3-cycle writeback: write rd, rd+1, rd+2 ---
    when(texState === sTexWB0) {
      writeAllCopies(texRdReg, true.B, texResultR)
      io.pipeWrite.en   := true.B
      io.pipeWrite.addr := texRdReg
      io.pipeWrite.data := texResultR
      texState := sTexWB1
    }
    when(texState === sTexWB1) {
      writeAllCopies(texRdReg + 1.U, true.B, texResultG)
      io.pipeWrite.en   := true.B
      io.pipeWrite.addr := texRdReg + 1.U
      io.pipeWrite.data := texResultG
      texState := sTexWB2
    }
    when(texState === sTexWB2) {
      writeAllCopies(texRdReg + 2.U, true.B, texResultB)
      io.pipeWrite.en   := true.B
      io.pipeWrite.addr := texRdReg + 2.U
      io.pipeWrite.data := texResultB
      texState   := sTexIdle
      is_ftex_reg := false.B
      // Advance PC past the FTEX instruction and suppress pipeline
      // restart for 1 cycle (texResumeDelay).  The IMEM is SyncReadMem
      // with 1-cycle latency; without the delay the pipeline would
      // re-decode the stale FTEX opcode and double-execute it.
      // busy_counter=0 avoids the counter=1 drain cycle that would
      // write FMA garbage to rd (clobbering the texture results).
      busy_counter := 0.U
      programCounter := programCounter + 1.U
      texResumeDelay := true.B
    }

    // --- Stall during writeback (WB0 and WB1 only; WB2 zeroes the counter) ---
    when(texState === sTexWB0 || texState === sTexWB1) {
      busy_counter := busy_counter  // hold
    }
  }
  // @doc:end
}
