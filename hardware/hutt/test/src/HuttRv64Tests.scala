// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

// RV64I validation: instantiates Hutt at xlen=64 with a 64-bit memory model and
// exercises the instructions the widening adds — 64-bit shifts, the *W word ops
// (ADDIW/ADDW/SUBW/SLLIW), and the LD/SD/LWU doubleword/word loads/stores.

import chisel3.{assert => _, test => _, _}
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import utest._

class HuttRv64HarnessIO(val dataAddrWidth: Int) extends Bundle {
  val peekAddr = Input(UInt(dataAddrWidth.W))
  val peekData = Output(UInt(64.W))
}

/** 64-bit test harness: 32-bit instruction memory, 64-bit data memory (8-byte
  * words, size 0..3). Mirrors HuttTestHarness but at XLEN=64. */
class HuttRv64Harness(
    val program: Seq[BigInt],
    val instrAddrWidth: Int = 10,
    val dataAddrWidth: Int  = 10
) extends Module {
  val io = IO(new HuttRv64HarnessIO(dataAddrWidth))

  val cpu = Module(new Hutt(instrAddrWidth, dataAddrWidth, xlen = 64))
  cpu.io.interrupt := false.B

  // ── Instruction memory (32-bit) ──
  val iMemDepth = 1 << instrAddrWidth
  val iMemInit  = VecInit((0 until iMemDepth).map(i =>
    if (i < program.length) program(i).U(32.W) else 0.U(32.W)))
  val iReqHeld   = RegInit(false.B)
  val iRespValid = RegInit(false.B)
  val iRespData  = Reg(UInt(32.W))
  cpu.io.instr.req.ready := !iReqHeld
  when(cpu.io.instr.req.fire) {
    iReqHeld := true.B; iRespData := iMemInit(cpu.io.instr.req.bits); iRespValid := true.B
  }
  cpu.io.instr.resp.valid := iRespValid
  cpu.io.instr.resp.bits  := iRespData
  when(cpu.io.instr.resp.fire) { iRespValid := false.B; iReqHeld := false.B }

  // ── Data memory (64-bit, 8 bytes/word) ──
  val dMemDepth = (1 << dataAddrWidth) / 8
  val dMem = SyncReadMem(dMemDepth, Vec(8, UInt(8.W)))

  // mask = ((1 << (1<<size)) - 1) << addrLo  (bytes covered, positioned)
  def byteMask(size: UInt, addrLo: UInt): UInt =
    (((1.U(9.W) << (1.U << size)) - 1.U) << addrLo)(7, 0)

  val dReqHeld    = RegInit(false.B)
  val dRespValid  = RegInit(false.B)
  val dRespData   = Reg(UInt(64.W))
  val readPending = RegInit(false.B)
  val readAddrLoR = Reg(UInt(3.W))

  cpu.io.data.req.ready := !dReqHeld
  val dReq      = cpu.io.data.req.bits
  val dWordAddr = dReq.addr(dataAddrWidth - 1, 3)
  val dAddrLo   = dReq.addr(2, 0)
  val readEn    = cpu.io.data.req.fire && !dReq.write
  val rdVec     = dMem.read(dWordAddr, readEn)

  when(cpu.io.data.req.fire) {
    dReqHeld := true.B
    when(dReq.write) {
      val mask    = byteMask(dReq.size, dAddrLo)
      val shifted = (dReq.data << (dAddrLo << 3))(63, 0)   // CPU sends low-aligned
      val maskVec = VecInit((0 until 8).map(b => mask(b).asBool))
      val dataVec = VecInit((0 until 8).map(b => shifted(8 * b + 7, 8 * b)))
      dMem.write(dWordAddr, dataVec, maskVec)
      dRespData := 0.U; dRespValid := true.B
    }.otherwise {
      readPending := true.B; readAddrLoR := dAddrLo
    }
  }
  when(readPending) {
    // Contract: return the addressed bytes in the low bits; CPU sign/zero-extends.
    dRespData   := (rdVec.asUInt >> (readAddrLoR << 3))(63, 0)
    dRespValid  := true.B; readPending := false.B
  }
  cpu.io.data.resp.valid := dRespValid
  cpu.io.data.resp.bits  := dRespData
  when(cpu.io.data.resp.fire) { dRespValid := false.B; dReqHeld := false.B }

  io.peekData := dMem.read(io.peekAddr(dataAddrWidth - 1, 3)).asUInt
}

/** RV64 instruction encoders (the ones beyond Asm's RV32 set). */
object Rv64Asm {
  private def iType(op: Int, f3: Int, rd: Int, rs1: Int, imm: Int): BigInt =
    ((BigInt(imm) & 0xfff) << 20) | (BigInt(rs1) << 15) | (BigInt(f3) << 12) | (BigInt(rd) << 7) | op
  private def rType(op: Int, f3: Int, f7: Int, rd: Int, rs1: Int, rs2: Int): BigInt =
    (BigInt(f7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) | (BigInt(f3) << 12) | (BigInt(rd) << 7) | op
  private def sType(op: Int, f3: Int, rs1: Int, rs2: Int, imm: Int): BigInt = {
    val i = BigInt(imm) & 0xfff
    (((i >> 5) & 0x7f) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) | (BigInt(f3) << 12) | ((i & 0x1f) << 7) | op
  }
  // OpImm32 (0x1b) / Op32 (0x3b)
  def addiw(rd: Int, rs1: Int, imm: Int): BigInt = iType(0x1b, 0x0, rd, rs1, imm)
  def slliw(rd: Int, rs1: Int, sh: Int): BigInt  = iType(0x1b, 0x1, rd, rs1, sh & 0x1f)
  def addw (rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x3b, 0x0, 0x00, rd, rs1, rs2)
  def subw (rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x3b, 0x0, 0x20, rd, rs1, rs2)
  def sllw (rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x3b, 0x1, 0x00, rd, rs1, rs2)
  def srlw (rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x3b, 0x5, 0x00, rd, rs1, rs2)
  def sraw (rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x3b, 0x5, 0x20, rd, rs1, rs2)
  def srliw(rd: Int, rs1: Int, sh: Int): BigInt  = iType(0x1b, 0x5, rd, rs1, sh & 0x1f)
  def sraiw(rd: Int, rs1: Int, sh: Int): BigInt  = iType(0x1b, 0x5, rd, rs1, 0x400 | (sh & 0x1f))
  // RV64 shift-immediates use a 6-bit shamt (funct6).
  def slli64(rd: Int, rs1: Int, sh: Int): BigInt = iType(0x13, 0x1, rd, rs1, sh & 0x3f)
  def srli64(rd: Int, rs1: Int, sh: Int): BigInt = iType(0x13, 0x5, rd, rs1, sh & 0x3f)
  def srai64(rd: Int, rs1: Int, sh: Int): BigInt = iType(0x13, 0x5, rd, rs1, 0x400 | (sh & 0x3f))
  // Doubleword + unsigned-word memory ops
  def ld (rd: Int, off: Int, rs1: Int): BigInt = iType(0x03, 0x3, rd, rs1, off)
  def lwu(rd: Int, off: Int, rs1: Int): BigInt = iType(0x03, 0x6, rd, rs1, off)
  def sd (rs2: Int, off: Int, rs1: Int): BigInt = sType(0x23, 0x3, rs1, rs2, off)
}

object HuttRv64Tests extends TestSuite {
  import Asm._

  def runPeek(program: Seq[BigInt], peekAddr: Int, maxCycles: Int = 300): BigInt = {
    var result: BigInt = -1
    simulate(new HuttRv64Harness(program)) { dut =>
      dut.reset.poke(true.B); dut.io.peekAddr.poke(0.U); dut.clock.step(2)
      dut.reset.poke(false.B); dut.clock.step(maxCycles)
      dut.io.peekAddr.poke(peekAddr.U); dut.clock.step(1)
      result = dut.io.peekData.peek().litValue
    }
    result
  }

  val mask64 = (BigInt(1) << 64) - 1

  val tests: Tests = Tests {

    // SLLI with a 64-bit shamt (32): 1 << 32 = 0x1_0000_0000 — would be 0 on RV32.
    test("slli 64-bit shamt") {
      val prog = Seq(
        addi(1, 0, 1),
        Rv64Asm.slli64(1, 1, 32),
        Rv64Asm.sd(1, 0, 0),
        park())
      assert(runPeek(prog, 0) == BigInt("100000000", 16))
    }

    // ADDIW sign-extends the low 32 bits: 1<<31 = 0x8000_0000 → 0xFFFF_FFFF_8000_0000.
    test("addiw sign-extends low 32") {
      val prog = Seq(
        addi(1, 0, 1),
        Rv64Asm.slli64(1, 1, 31),     // x1 = 0x0000_0000_8000_0000
        Rv64Asm.addiw(2, 1, 0),       // x2 = sext32(0x8000_0000)
        Rv64Asm.sd(2, 0, 0),
        park())
      assert(runPeek(prog, 0) == BigInt("FFFFFFFF80000000", 16))
    }

    // ADDW wraps at 32 bits then sign-extends: 0x8000_0000 + 0x8000_0000 = 0 (mod 2^32).
    test("addw wraps and sign-extends") {
      val prog = Seq(
        addi(1, 0, 1),
        Rv64Asm.slli64(1, 1, 31),     // x1 = 0x8000_0000
        Rv64Asm.addw(3, 1, 1),        // (0x8000_0000 + 0x8000_0000) mod 2^32 = 0
        Rv64Asm.sd(3, 0, 0),
        park())
      assert(runPeek(prog, 0) == BigInt(0))
    }

    // SD/LD full 64-bit round trip through memory.
    test("ld/sd 64-bit round trip") {
      val prog = Seq(
        lui(1, 0xDEADC),              // x1 = sext(0xDEADC000) = 0xFFFFFFFF_DEADC000
        Rv64Asm.sd(1, 16, 0),         // store doubleword at [16]
        Rv64Asm.ld(5, 16, 0),         // load it back into x5
        Rv64Asm.sd(5, 0, 0),          // store x5 at [0] for peek
        park())
      assert(runPeek(prog, 0) == BigInt("FFFFFFFFDEADC000", 16))
    }

    // LWU zero-extends a 32-bit load where LW would sign-extend.
    test("lwu zero-extends") {
      val prog = Seq(
        addi(1, 0, 1),
        Rv64Asm.slli64(1, 1, 31),     // x1 = 0x8000_0000
        Rv64Asm.sd(1, 8, 0),          // store doubleword 0x0000_0000_8000_0000
        Rv64Asm.lwu(2, 8, 0),         // load low word, zero-extended → 0x8000_0000
        Rv64Asm.sd(2, 0, 0),
        park())
      assert(runPeek(prog, 0) == BigInt("80000000", 16))
    }

    // SUBW: (0 - 1) mod 2^32 = 0xFFFF_FFFF, sign-extended → all ones.
    test("subw wraps and sign-extends") {
      val prog = Seq(
        addi(2, 0, 1),
        Rv64Asm.subw(3, 0, 2),        // 0 - 1
        Rv64Asm.sd(3, 0, 0),
        park())
      assert(runPeek(prog, 0) == mask64)
    }

    // SLLW: (1 << 31) as a 32-bit result, sign-extended → 0xFFFF_FFFF_8000_0000.
    test("sllw sign-extends bit 31 result") {
      val prog = Seq(
        addi(1, 0, 1),
        addi(4, 0, 31),
        Rv64Asm.sllw(2, 1, 4),
        Rv64Asm.sd(2, 0, 0),
        park())
      assert(runPeek(prog, 0) == BigInt("FFFFFFFF80000000", 16))
    }

    // SRAW: arithmetic shift of the low 32 bits, sign-extended.
    // low32 = 0x8000_0000 (negative) >>a 4 = 0xF800_0000 → 0xFFFF_FFFF_F800_0000.
    test("sraw arithmetic shift of word") {
      val prog = Seq(
        addi(1, 0, 1),
        Rv64Asm.slli64(1, 1, 31),     // x1 low32 = 0x8000_0000
        addi(4, 0, 4),
        Rv64Asm.sraw(2, 1, 4),
        Rv64Asm.sd(2, 0, 0),
        park())
      assert(runPeek(prog, 0) == BigInt("FFFFFFFFF8000000", 16))
    }

    // SRAI (64-bit): -2^63 >>a 60 = -8 = 0xFFFF_FFFF_FFFF_FFF8.
    test("srai64 arithmetic shift > 32") {
      val prog = Seq(
        addi(1, 0, 1),
        Rv64Asm.slli64(1, 1, 63),     // x1 = 0x8000_0000_0000_0000
        Rv64Asm.srai64(2, 1, 60),
        Rv64Asm.sd(2, 0, 0),
        park())
      assert(runPeek(prog, 0) == BigInt("FFFFFFFFFFFFFFF8", 16))
    }

    // ADDIW with a negative immediate: (5 - 10) as 32-bit, sign-extended.
    test("addiw negative immediate") {
      val prog = Seq(
        addi(1, 0, 5),
        Rv64Asm.addiw(2, 1, -10),     // 0xFFFF_FFFB → 0xFFFF_FFFF_FFFF_FFFB
        Rv64Asm.sd(2, 0, 0),
        park())
      assert(runPeek(prog, 0) == (mask64 - 4))
    }
  }
}
