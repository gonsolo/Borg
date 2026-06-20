// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

/** Top-level IO for the Hutt CPU core.
  *
  * @param instrAddrWidth word-address width on the instruction fetch port
  * @param dataAddrWidth  byte-address width on the data port
  */
class HuttIO(val instrAddrWidth: Int, val dataAddrWidth: Int, val xlen: Int = 32) extends Bundle {
  val instr     = new HuttInstrBus(instrAddrWidth)       // instructions are always 32-bit
  val data      = new HuttBus(dataAddrWidth, xlen)       // data path is XLEN-wide
  val interrupt = Input(Bool())  // optional level-sensitive IRQ (currently unused)
}

/** Hutt — a simple multi-cycle RV32I CPU.
  *
  * Five FSM states, one architectural instruction per loop:
  *
  * {{{
  *   sFetchReq → sFetchResp → sExec ─┬─ ALU/branch/jal/lui/auipc → sFetchReq
  *                                    └─ load/store → sMemReq → sMemResp → sFetchReq
  * }}}
  *
  * Bus contracts:
  *   - Instr bus: 32-bit word address request, full 32-bit word response.
  *   - Data bus, '''store''': CPU sends data unshifted in low bits; memory
  *     controller positions bytes within the word using addr[1:0] + size.
  *   - Data bus, '''load''': memory controller returns the addressed bytes
  *     already extracted to low bits; CPU performs sign/zero-extension based
  *     on funct3.
  *
  * M-mode CSRs + traps: mstatus/mtvec/mepc/mcause/mtval/mscratch/mie/mip/mhartid/misa.
  * ECALL → cause 11, EBREAK → cause 3, MRET restores MIE.  Timer IRQ via io.interrupt.
  * No S/U-mode, no MMU, no compressed insns.
  *
  * @param resetVector word-aligned byte address of the first instruction
  */
class Hutt(
    val instrAddrWidth: Int = 23,
    val dataAddrWidth: Int  = 28,
    val resetVector: BigInt = 0,
    val xlen: Int = 32          // 32 = RV32I, 64 = RV64I
) extends Module {

  val io = IO(new HuttIO(instrAddrWidth, dataAddrWidth, xlen))

  // -- Architectural state ---------------------------------------------------
  val pc    = RegInit(resetVector.U(xlen.W))
  val instr = RegInit(0.U(32.W))   // instruction word — always 32-bit

  // Free-running cycle counter for the RISC-V `cycle`/`mcycle` CSR (rdcycle).
  val cycleCounter = RegInit(0.U(xlen.W))
  cycleCounter := cycleCounter + 1.U

  // -- M-mode CSRs -----------------------------------------------------------
  // mstatus: bit 3=MIE, bit 7=MPIE, bits 12:11=MPP.
  val mstatus  = RegInit(0.U(xlen.W))
  val mtvec    = RegInit(0.U(xlen.W))
  val mscratch = RegInit(0.U(xlen.W))
  val mepc     = RegInit(0.U(xlen.W))
  val mcause   = RegInit(0.U(xlen.W))
  val mtval    = RegInit(0.U(xlen.W))
  val mie      = RegInit(0.U(xlen.W))
  // mip.MTIP (bit 7) is driven from io.interrupt (CLINT timer); other bits 0.
  val mip      = Cat(0.U((xlen - 8).W), io.interrupt, 0.U(7.W))
  // misa: MXL field + I-extension.
  val misaMxl  = if (xlen == 64) 2.U(2.W) else 1.U(2.W)
  val misa     = Cat(misaMxl, 0.U((xlen - 28).W), (1 << ('I' - 'A')).U(26.W))

  val regFile = Module(new HuttRegFile(xlen))
  val alu     = Module(new HuttAlu(xlen))

  // -- FSM -------------------------------------------------------------------
  val sFetchReq :: sFetchResp :: sExec :: sMemReq :: sMemResp :: Nil = Enum(5)
  val state = RegInit(sFetchReq)

  // -- Decode (combinational over current `instr` register) ------------------
  val d = HuttDecode(instr, xlen)

  // Register file read ports
  regFile.io.rs1Addr := d.rs1
  regFile.io.rs2Addr := d.rs2
  val rs1Val = regFile.io.rs1Data
  val rs2Val = regFile.io.rs2Data

  // ALU operand muxing
  val aluUseImm = d.isOpImm || d.isOpImm32 || d.isLoad || d.isStore || d.isJalr
  val aluImm = MuxCase(d.iImm, Seq(
    d.isStore -> d.sImm
  ))
  alu.io.a := rs1Val
  alu.io.b := Mux(aluUseImm, aluImm, rs2Val)

  alu.io.op := MuxCase(AluOp.Add, Seq(
    d.isOp      -> HuttAluDecode(d.funct3, d.funct7(5), isOpReg = true.B),
    d.isOpImm   -> HuttAluDecode(d.funct3, d.funct7(5), isOpReg = false.B),
    // RV64 word ops (ADDW/SUBW/SLLW/SRLW/SRAW and ADDIW/SLLIW/SRLIW/SRAIW).
    d.isOp32    -> HuttAluDecode(d.funct3, d.funct7(5), isOpReg = true.B,  isWord = true.B),
    d.isOpImm32 -> HuttAluDecode(d.funct3, d.funct7(5), isOpReg = false.B, isWord = true.B)
    // Load/Store/JALR all use ADD for address calculation — default.
  ))

  val branchTaken = HuttBranchTaken(d.funct3, rs1Val, rs2Val)

  // Next-PC computation (per-instruction-class)
  val pcPlus4    = pc + 4.U
  val branchTgt  = pc + d.bImm
  val jalTgt     = pc + d.jImm
  val jalrTgt    = Cat((rs1Val + d.iImm)(xlen - 1, 1), 0.U(1.W))  // clear LSB per spec

  val pcNext = WireDefault(pcPlus4)
  when(d.isBranch && branchTaken) { pcNext := branchTgt }
  when(d.isJal)                   { pcNext := jalTgt }
  when(d.isJalr)                  { pcNext := jalrTgt }

  // CSR read/write.
  val csrAddr = instr(31, 20)
  val csrReadData = MuxLookup(csrAddr, 0.U(xlen.W))(Seq(
    "h300".U -> mstatus,
    "h301".U -> misa,
    "h302".U -> 0.U(xlen.W),         // medeleg: no delegation in M-mode-only core
    "h303".U -> 0.U(xlen.W),         // mideleg
    "h304".U -> mie,
    "h305".U -> mtvec,
    "h340".U -> mscratch,
    "h341".U -> mepc,
    "h342".U -> mcause,
    "h343".U -> mtval,
    "h344".U -> mip,
    "hF11".U -> 0.U(xlen.W),         // mvendorid
    "hF12".U -> 0.U(xlen.W),         // marchid
    "hF13".U -> 0.U(xlen.W),         // mimpid
    "hF14".U -> 0.U(xlen.W),         // mhartid
    "hC00".U -> cycleCounter,         // cycle
    "hB00".U -> cycleCounter,         // mcycle
    "hC80".U -> 0.U(xlen.W),         // cycleh  (high half → 0)
    "hB80".U -> 0.U(xlen.W)          // mcycleh
  ))

  // CSR write: CSRRW/I → newVal = src; CSRRS/I → set bits; CSRRC/I → clear bits.
  // funct3[2]=1 means immediate form (zimm = zero-extend rs1 field).
  val csrZimm   = Cat(0.U((xlen - 5).W), d.rs1)
  val csrSrc    = Mux(d.funct3(2), csrZimm, rs1Val)
  val csrNewVal = MuxCase(csrSrc, Seq(
    (d.funct3(1, 0) === "b10".U) -> (csrReadData | csrSrc),
    (d.funct3(1, 0) === "b11".U) -> (csrReadData & ~csrSrc)
  ))

  // Trap helpers: next mstatus after a trap entry and after MRET.
  // mstatus layout (RV32/64): bit 3=MIE, bit 7=MPIE, bits 12:11=MPP.
  val mstatusTrap = Cat(
    mstatus(xlen - 1, 13),
    "b11".U(2.W),      // MPP ← M-mode (11)
    mstatus(10, 8),
    mstatus(3),        // MPIE ← MIE
    mstatus(6, 4),
    false.B,           // MIE ← 0
    mstatus(2, 0)
  )
  val mstatusMret = Cat(
    mstatus(xlen - 1, 13),
    "b00".U(2.W),      // MPP ← 0 (U-mode target)
    mstatus(10, 8),
    true.B,            // MPIE ← 1
    mstatus(6, 4),
    mstatus(7),        // MIE ← MPIE
    mstatus(2, 0)
  )
  // Trap vector (direct mode: mtvec[1:0] ignored, must be 0 for direct).
  val trapPC = Cat(mtvec(xlen - 1, 2), 0.U(2.W))

  // Writeback value (for non-load instructions)
  val wbAlu     = alu.io.out
  val wbLink    = pcPlus4                        // JAL/JALR link register
  val wbUpper   = d.uImm                         // LUI
  val wbAuipc   = pc + d.uImm                    // AUIPC
  val wbExec    = MuxCase(wbAlu, Seq(
    d.isJal   -> wbLink,
    d.isJalr  -> wbLink,
    d.isLui   -> wbUpper,
    d.isAuipc -> wbAuipc,
    d.isCsr   -> csrReadData
  ))

  val wbExecEn  = (d.isOp || d.isOpImm || d.isOp32 || d.isOpImm32 || d.isJal || d.isJalr || d.isLui || d.isAuipc || d.isCsr) && (d.rd =/= 0.U)

  // -- Memory access support -------------------------------------------------
  // Effective byte address for loads/stores = rs1 + (load? iImm : sImm)
  val memAddr = (rs1Val.asSInt + Mux(d.isStore, d.sImm.asSInt, d.iImm.asSInt)).asUInt
  // Default-extracted load value held across sMemReq → sMemResp
  val loadAddrLo = Reg(UInt(2.W))
  val loadFunct3 = Reg(UInt(3.W))
  val loadRd     = Reg(UInt(5.W))
  val isLoadOp   = Reg(Bool())   // distinguishes load vs store in sMemResp

  // Sign/zero-extend the memory response to an XLEN-bit register value.
  //   funct3: 000 LB, 001 LH, 010 LW, 011 LD(RV64), 100 LBU, 101 LHU, 110 LWU(RV64)
  // Contract: memory ctrl returns the addressed bytes in the low bits of resp.
  def extendLoad(funct3: UInt, raw: UInt): UInt = {
    def sext(from: Int): UInt =
      if (xlen <= from) raw(xlen - 1, 0) else Cat(Fill(xlen - from, raw(from - 1)), raw(from - 1, 0))
    def zext(from: Int): UInt =
      if (xlen <= from) raw(xlen - 1, 0) else Cat(0.U((xlen - from).W), raw(from - 1, 0))
    MuxLookup(funct3, raw)(Seq(
      "b000".U -> sext(8),    // LB
      "b001".U -> sext(16),   // LH
      "b010".U -> sext(32),   // LW (sign-extend to XLEN)
      "b011".U -> raw,        // LD (RV64; full width)
      "b100".U -> zext(8),    // LBU
      "b101".U -> zext(16),   // LHU
      "b110".U -> zext(32)    // LWU (RV64)
    ))
  }

  // -- IO defaults -----------------------------------------------------------
  io.instr.req.valid  := false.B
  io.instr.req.bits   := pc(instrAddrWidth + 1, 2)   // word address
  io.instr.resp.ready := false.B

  io.data.req.valid := false.B
  io.data.req.bits.addr  := memAddr(dataAddrWidth - 1, 0)
  io.data.req.bits.write := d.isStore
  io.data.req.bits.size  := d.funct3(1, 0)
  io.data.req.bits.data  := rs2Val  // store data unshifted; memory ctrl positions
  io.data.resp.ready := false.B

  regFile.io.wen   := false.B
  regFile.io.wAddr := d.rd
  regFile.io.wData := wbExec

  // -- FSM transitions -------------------------------------------------------
  switch(state) {

    is(sFetchReq) {
      // Check for a pending machine timer interrupt before issuing fetch.
      // Interrupt is taken only when: MIE=1 && MTIE=1 && MTIP=1.
      val timerPending = mstatus(3) && mie(7) && mip(7)
      when(timerPending) {
        mepc    := pc
        mcause  := Cat(true.B, 0.U((xlen - 4).W), 7.U(3.W))  // interrupt, cause=7
        mtval   := 0.U(xlen.W)
        mstatus := mstatusTrap
        pc      := trapPC
        // stay in sFetchReq — will fetch the trap handler next cycle
      }.otherwise {
        io.instr.req.valid := true.B
        when(io.instr.req.fire) { state := sFetchResp }
      }
    }

    is(sFetchResp) {
      io.instr.resp.ready := true.B
      when(io.instr.resp.fire) {
        instr := io.instr.resp.bits
        state := sExec
      }
    }

    is(sExec) {
      when(d.isLoad || d.isStore) {
        loadAddrLo := memAddr(1, 0)
        loadFunct3 := d.funct3
        loadRd     := d.rd
        isLoadOp   := d.isLoad
        state      := sMemReq
      }.elsewhen(d.isEcall) {
        mepc    := pc
        mcause  := 11.U(xlen.W)   // environment call from M-mode
        mtval   := 0.U(xlen.W)
        mstatus := mstatusTrap
        pc      := trapPC
        state   := sFetchReq
      }.elsewhen(d.isEbreak) {
        mepc    := pc
        mcause  := 3.U(xlen.W)    // breakpoint
        mtval   := pc
        mstatus := mstatusTrap
        pc      := trapPC
        state   := sFetchReq
      }.elsewhen(d.isMret) {
        mstatus := mstatusMret
        pc      := mepc
        state   := sFetchReq
      }.otherwise {
        // Single-cycle commit: ALU / branch / CSR / NOP.
        when(d.isCsr) {
          // Write the new CSR value; also perform the register writeback below.
          switch(csrAddr) {
            is("h300".U) { mstatus  := csrNewVal }
            is("h304".U) { mie      := csrNewVal }
            is("h305".U) { mtvec    := Cat(csrNewVal(xlen - 1, 2), 0.U(2.W)) }
            is("h340".U) { mscratch := csrNewVal }
            is("h341".U) { mepc     := Cat(csrNewVal(xlen - 1, 1), 0.U(1.W)) }
            is("h342".U) { mcause   := csrNewVal }
            is("h343".U) { mtval    := csrNewVal }
          }
        }
        regFile.io.wen := wbExecEn
        pc    := pcNext
        state := sFetchReq
      }
    }

    is(sMemReq) {
      io.data.req.valid := true.B
      when(io.data.req.fire) { state := sMemResp }
    }

    is(sMemResp) {
      io.data.resp.ready := true.B
      when(io.data.resp.fire) {
        when(isLoadOp && (loadRd =/= 0.U)) {
          regFile.io.wAddr := loadRd
          regFile.io.wData := extendLoad(loadFunct3, io.data.resp.bits)
          regFile.io.wen   := true.B
        }
        pc    := pcNext  // pcNext is still valid (d still reflects the load/store)
        state := sFetchReq
      }
    }
  }
}
