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
class HuttIO(val instrAddrWidth: Int, val dataAddrWidth: Int) extends Bundle {
  val instr     = new HuttInstrBus(instrAddrWidth)
  val data      = new HuttBus(dataAddrWidth)
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
  * No RV32IM, no CSRs, no traps, no compressed insns — pure base RV32I.
  *
  * @param resetVector word-aligned byte address of the first instruction
  */
class Hutt(
    val instrAddrWidth: Int = 23,
    val dataAddrWidth: Int  = 28,
    val resetVector: BigInt = 0
) extends Module {

  val io = IO(new HuttIO(instrAddrWidth, dataAddrWidth))

  // -- Architectural state ---------------------------------------------------
  val pc    = RegInit(resetVector.U(32.W))
  val instr = RegInit(0.U(32.W))

  val regFile = Module(new HuttRegFile)
  val alu     = Module(new HuttAlu)

  // -- FSM -------------------------------------------------------------------
  val sFetchReq :: sFetchResp :: sExec :: sMemReq :: sMemResp :: Nil = Enum(5)
  val state = RegInit(sFetchReq)

  // -- Decode (combinational over current `instr` register) ------------------
  val d = HuttDecode(instr)

  // Register file read ports
  regFile.io.rs1Addr := d.rs1
  regFile.io.rs2Addr := d.rs2
  val rs1Val = regFile.io.rs1Data
  val rs2Val = regFile.io.rs2Data

  // ALU operand muxing
  val aluUseImm = d.isOpImm || d.isLoad || d.isStore || d.isJalr
  val aluImm = MuxCase(d.iImm, Seq(
    d.isStore -> d.sImm
  ))
  alu.io.a := rs1Val
  alu.io.b := Mux(aluUseImm, aluImm, rs2Val)

  alu.io.op := MuxCase(AluOp.Add, Seq(
    d.isOp     -> HuttAluDecode(d.funct3, d.funct7(5), isOpReg = true.B),
    d.isOpImm  -> HuttAluDecode(d.funct3, d.funct7(5), isOpReg = false.B)
    // Load/Store/JALR all use ADD for address calculation — default.
  ))

  val branchTaken = HuttBranchTaken(d.funct3, rs1Val, rs2Val)

  // Next-PC computation (per-instruction-class)
  val pcPlus4    = pc + 4.U
  val branchTgt  = pc + d.bImm
  val jalTgt     = pc + d.jImm
  val jalrTgt    = (rs1Val + d.iImm) & "hFFFFFFFE".U(32.W)  // clear LSB per RV32I spec

  val pcNext = WireDefault(pcPlus4)
  when(d.isBranch && branchTaken) { pcNext := branchTgt }
  when(d.isJal)                   { pcNext := jalTgt }
  when(d.isJalr)                  { pcNext := jalrTgt }

  // Writeback value (for non-load instructions)
  val wbAlu     = alu.io.out
  val wbLink    = pcPlus4                        // JAL/JALR link register
  val wbUpper   = d.uImm                         // LUI
  val wbAuipc   = pc + d.uImm                    // AUIPC
  val wbExec    = MuxCase(wbAlu, Seq(
    d.isJal   -> wbLink,
    d.isJalr  -> wbLink,
    d.isLui   -> wbUpper,
    d.isAuipc -> wbAuipc
  ))

  val wbExecEn  = (d.isOp || d.isOpImm || d.isJal || d.isJalr || d.isLui || d.isAuipc) && (d.rd =/= 0.U)

  // -- Memory access support -------------------------------------------------
  // Effective byte address for loads/stores = rs1 + (load? iImm : sImm)
  val memAddr = (rs1Val.asSInt + Mux(d.isStore, d.sImm.asSInt, d.iImm.asSInt)).asUInt
  // Default-extracted load value held across sMemReq → sMemResp
  val loadAddrLo = Reg(UInt(2.W))
  val loadFunct3 = Reg(UInt(3.W))
  val loadRd     = Reg(UInt(5.W))
  val isLoadOp   = Reg(Bool())   // distinguishes load vs store in sMemResp

  // Sign/zero-extend the memory response to a 32-bit register value.
  //   funct3: 000 LB, 001 LH, 010 LW, 100 LBU, 101 LHU
  // Contract: memory ctrl returns the addressed bytes in the low bits of resp.
  def extendLoad(funct3: UInt, raw: UInt): UInt = {
    val bSigned = Cat(Fill(24, raw(7)),  raw(7, 0))
    val hSigned = Cat(Fill(16, raw(15)), raw(15, 0))
    val bUnsign = Cat(0.U(24.W), raw(7, 0))
    val hUnsign = Cat(0.U(16.W), raw(15, 0))
    MuxLookup(funct3, raw)(Seq(
      "b000".U -> bSigned,
      "b001".U -> hSigned,
      "b010".U -> raw,
      "b100".U -> bUnsign,
      "b101".U -> hUnsign
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
      io.instr.req.valid := true.B
      when(io.instr.req.fire) { state := sFetchResp }
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
        // Capture context that EXEC-time wires will lose by the time MEM_RESP fires.
        loadAddrLo := memAddr(1, 0)
        loadFunct3 := d.funct3
        loadRd     := d.rd
        isLoadOp   := d.isLoad
        state      := sMemReq
      }.otherwise {
        // Single-cycle commit: writeback (if any) + advance PC.
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
