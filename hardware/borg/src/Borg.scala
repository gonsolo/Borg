// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3.*
import chisel3.util.*

sealed abstract class FloatConfig(val exp: Int, val sig: Int) {
  def totalBits: Int = 1 + exp + (sig - 1)
}

object FloatConfig {
  case object FP16 extends FloatConfig(5, 11)
  case object FP32 extends FloatConfig(8, 24)
}

/** BorgIO defines the interface for the shading processor. It uses
  * memory-mapped I/O for register and instruction memory access.
  */
class BorgIO(val config: FloatConfig = FloatConfig.FP32) extends Bundle {
  val address = Input(
    UInt(9.W)
  ) // 512-byte address space (byte-addressed internally by shifting)
  val data_in = Input(UInt(32.W))  // 32-bit data for IMEM writes; register writes use low config.totalBits
  val data_write_n = Input(UInt(2.W)) // 0b10 for write
  val data_read_n = Input(UInt(2.W))
  val data_out = Output(UInt(config.totalBits.W))
  val data_ready = Output(Bool())
  val uo_out = Output(UInt(8.W))
  val user_interrupt = Output(Bool())
}

class RegFileCopyIO(width: Int) extends Bundle {
  val readAddr  = Input(UInt(log2Ceil(MmioMap.BORG_NUM_REGS).W))
  val readEn    = Input(Bool())
  val readData  = Output(UInt(width.W))
  val writeAddr = Input(UInt(log2Ceil(MmioMap.BORG_NUM_REGS).W))
  val writeEn   = Input(Bool())
  val writeData = Input(UInt(width.W))
}

/** Single-copy register file with exactly 1 read + 1 write port.
  * Each instance gets a unique Verilog module name to prevent CIRCT
  * deduplication, ensuring yosys can infer iCE40 Block RAMs.
  */
class RegFileCopy(width: Int, instName: String) extends Module {
  override def desiredName = instName

  val io = IO(new RegFileCopyIO(width))

  val mem = SyncReadMem(MmioMap.BORG_NUM_REGS, UInt(width.W))
  io.readData := mem.read(io.readAddr, io.readEn)

  when(io.writeEn) {
    mem.write(io.writeAddr, io.writeData)
  }
}

/** Borg — minimal FP16 shading processor with 4-cycle FMA pipeline.
  *
  * Instruction encoding is defined in [[Instructions]].
  *
  * == Pipeline (4 cycles per instruction) ==
  *
  *   - Cycle 1: Fetch instruction, read rs1/rs2/rs3 from register file
  *   - Cycles 2–3: FMA unit computes result
  *   - Cycle 4: Write-back to rd
  *
  * == MMIO Interface ==
  *
  *   - Registers 0–124 (32 words): read/write register file r0–r31
  *   - IMEM 128–248 (31 usable words): write instruction memory (32-bit)
  *   - Control/Status 252: write bit 0 = start, bit 1 = reset; read bit 1 = idle
  */
class Borg(val config: FloatConfig = FloatConfig.FP32) extends Module {
  val io = IO(new BorgIO(config))
  dontTouch(io)

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

  // --- Pixel Iterator ---
  val iter_x  = RegInit(0.U(6.W))
  val iter_y  = RegInit(0.U(6.W))
  val bbox_x0 = RegInit(0.U(6.W))
  val bbox_y0 = RegInit(0.U(6.W))
  val bbox_x1 = RegInit(0.U(6.W))
  val bbox_y1 = RegInit(0.U(6.W))
  val e0_outside = RegInit(false.B)
  val e1_outside = RegInit(false.B)
  val e2_outside = RegInit(false.B)
  val inside_flag = !e0_outside && !e1_outside && !e2_outside
  val auto_run_stall = RegInit(false.B)    // held high during auto-triggered shader execution
  val auto_run_pending = RegInit(false.B)  // delays running by 1 cycle for SyncReadMem fetch

  // --- Coordinate Expansion LUT ---
  // Maps 6-bit integer pixel coordinates (0-63) to float16 pixel centers (+0.5)
  // The hardware internally uses recFN for its pipeline, but coordLut should 
  // output the raw IEEE FP16 bit pattern. The recFN wrapper converts standard
  // external IEEE-754 format to HardFloat's recoded format automatically!
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
  val is_writing = io.data_write_n === 2.U && RegNext(io.data_write_n) =/= 2.U
  val is_reading = io.data_read_n === 2.U

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

  // --- MMIO ---
  wireMmio()
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
    when(is_writing && io.address === MmioMap.BORG_CONTROL_OFFSET.U) {
      when(io.data_in(0)) { running := true.B }
      when(io.data_in(1)) {
        programCounter := io.data_in(MmioMap.BORG_CTL_PC_MSB, MmioMap.BORG_CTL_PC_LSB)
        running := false.B
        busy_counter := 0.U
      }
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
    val rs1_idx_del = RegNext(rs1_idx, 0.U)
    regFileA.io.readAddr := rs1_idx
    regFileA.io.readEn := en
    val resolved_data = Mux(rs1_idx_del === 30.U, coordLut(iter_x),
                        Mux(rs1_idx_del === 31.U, coordLut(iter_y),
                        regFileA.io.readData))
    Mux(en_del, resolved_data, 0.U)
  }

  /** Port B: pipeline rs2 read from regFileB. */
  private def wirePortB(): UInt = {
    val en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
    val en_del = RegNext(en, false.B)
    val rs2_idx_del = RegNext(rs2_idx, 0.U)
    regFileB.io.readAddr := rs2_idx
    regFileB.io.readEn := en
    val resolved_data = Mux(rs2_idx_del === 30.U, coordLut(iter_x),
                        Mux(rs2_idx_del === 31.U, coordLut(iter_y),
                        regFileB.io.readData))
    Mux(en_del, resolved_data, 0.U)
  }

  /** Port C: rs3 during execution, MMIO register access when idle. */
  private def wirePortC(): (UInt, UInt) = {
    val mmio_en = !running && !is_busy && (is_reading || is_writing) && io.address >= MmioMap.BORG_REG_OFFSET.U && io.address < MmioMap.BORG_IMEM_OFFSET.U
    val rs3_en = (running && !is_busy) || (is_busy && busy_counter >= 2.U)
    val addr = Mux(running || is_busy, rs3_idx, (io.address - MmioMap.BORG_REG_OFFSET.U) >> 2)
    val en = mmio_en || rs3_en
    val mmio_en_del = RegNext(mmio_en && is_reading, false.B)
    val rs3_en_del = RegNext(rs3_en, false.B)
    val addr_del = RegNext(addr, 0.U)
    regFileC.io.readAddr := addr
    regFileC.io.readEn := en
    val resolved_data = Mux(addr_del === 30.U, coordLut(iter_x),
                        Mux(addr_del === 31.U, coordLut(iter_y),
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
    val mmio_write = is_writing && io.address >= MmioMap.BORG_REG_OFFSET.U && io.address < MmioMap.BORG_IMEM_OFFSET.U
    val pipe_write = running && is_busy && busy_counter === 1.U
    val w_en = mmio_write || pipe_write
    val w_addr = Mux(pipe_write, rd_idx, (io.address - MmioMap.BORG_REG_OFFSET.U) >> 2)

    val w_data = Mux(pipe_write,
      Mux(is_fstep_reg, fstep_result,
        Mux(is_frcp_reg, frcp_result, fma_result)),
      io.data_in(config.totalBits - 1, 0))

    // @doc:inside-snoop
    // Snoop on shader output registers (r0, r1, r2) to automatically record edge signs
    when(pipe_write) {
      val sign_bit = fma_result(config.totalBits - 1).asBool
      val magn_non_zero = fma_result(config.totalBits - 2, 0) =/= 0.U
      val is_outside = (!sign_bit) && magn_non_zero
      when(w_addr === 0.U) {
        e0_outside := is_outside
      }
      when(w_addr === 1.U) {
        e1_outside := is_outside
      }
      when(w_addr === 2.U) {
        e2_outside := is_outside
      }
    }
    // @doc:end

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

  // @doc:mmio
  /** MMIO: IMEM writes, register read mux, status/ready signals, pixel iterator. */
  private def wireMmio(): Unit = {
    // IMEM write
    when(is_writing && io.address >= MmioMap.BORG_IMEM_OFFSET.U && io.address < MmioMap.BORG_IMEM_END.U) {
      val imem_idx = (io.address - MmioMap.BORG_IMEM_OFFSET.U) >> 2
      instructionMemory.write(imem_idx, io.data_in)
    }

    // Pixel iterator: write bbox — resets counters
    when(is_writing && io.address === MmioMap.BORG_ITER_BBOX_OFFSET.U) {
      bbox_x0 := io.data_in(5, 0)
      bbox_y0 := io.data_in(11, 6)
      bbox_x1 := io.data_in(17, 12)
      bbox_y1 := io.data_in(23, 18)
      iter_x  := io.data_in(5, 0)
      iter_y  := io.data_in(11, 6)
    }

    // Pixel iterator: write to advance + auto-trigger rasterizer shader
    when(is_writing && io.address === MmioMap.BORG_ITER_OFFSET.U) {
      when(iter_x + 1.U >= bbox_x1) {
        iter_x := bbox_x0
        iter_y := iter_y + 1.U
      }.otherwise {
        iter_x := iter_x + 1.U
      }
      // Auto-trigger: set PC=0 now, delay running by 1 cycle for SyncReadMem
      programCounter := 0.U
      auto_run_pending := true.B
      auto_run_stall := true.B
    }

    // Delayed start: SyncReadMem has now fetched imem[0]
    when(auto_run_pending) {
      running := true.B
      auto_run_pending := false.B
    }

    // Clear auto-run stall when shader halts
    when(auto_run_stall && !running && !auto_run_pending) {
      auto_run_stall := false.B
    }

    // Read mux
    val read_addr_del = RegInit(0.U(9.W))
    read_addr_del := io.address
    val status_reg = Cat(0.U((config.totalBits - 2).W), !running, 0.U(1.W))
    val iter_valid = iter_y < bbox_y1
    val iter_reg   = Cat(inside_flag, iter_valid, iter_y, iter_x)
    io.data_out := Mux(read_addr_del >= MmioMap.BORG_REG_OFFSET.U && read_addr_del < MmioMap.BORG_IMEM_OFFSET.U, mmio_reg_data,
      Mux(read_addr_del === MmioMap.BORG_ITER_OFFSET.U,
        iter_reg,
        Mux(read_addr_del === MmioMap.BORG_CONTROL_OFFSET.U, status_reg, 0.U)))

    val read_ready_del = RegNext(is_reading, false.B)
    io.data_ready := Mux(auto_run_stall, false.B,
      (io.data_read_n === 3.U) || read_ready_del)
    io.uo_out := 0.U
    io.user_interrupt := false.B
  }
  // @doc:end

}
