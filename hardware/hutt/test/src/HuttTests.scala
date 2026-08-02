// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3.{assert => _, test => _, _}
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import utest._

class HuttTestHarnessIO(instrAddrWidth: Int, dataAddrWidth: Int) extends Bundle {
  val peekAddr  = Input(UInt(dataAddrWidth.W))
  val peekData  = Output(UInt(32.W))
  val interrupt = Input(Bool())   // drives cpu.io.interrupt (timer IRQ from CLINT)
}

class HuttTestHarness(
    val program: Seq[BigInt],
    val instrAddrWidth: Int = 10,
    val dataAddrWidth: Int  = 10,
    val pipelinedCsrRead: Boolean = true
) extends Module {
  val io = IO(new HuttTestHarnessIO(instrAddrWidth, dataAddrWidth))

  val cpu = Module(new Hutt(instrAddrWidth, dataAddrWidth, pipelinedCsrRead = pipelinedCsrRead))
  cpu.io.interrupt := io.interrupt

  val iMemDepth = 1 << instrAddrWidth
  val iMemInit = VecInit(
    (0 until iMemDepth).map(i =>
      if (i < program.length) program(i).U(32.W) else 0.U(32.W)
    )
  )
  val iReqValid = cpu.io.instr.req.valid
  val iReqAddr  = cpu.io.instr.req.bits

  val iReqHeld    = RegInit(false.B)
  val iRespValid  = RegInit(false.B)
  val iRespData   = Reg(UInt(32.W))

  cpu.io.instr.req.ready := !iReqHeld
  when(cpu.io.instr.req.fire) {
    iReqHeld   := true.B
    iRespData  := iMemInit(iReqAddr)
    iRespValid := true.B
  }
  cpu.io.instr.resp.valid := iRespValid
  cpu.io.instr.resp.bits  := iRespData
  when(cpu.io.instr.resp.fire) {
    iRespValid := false.B
    iReqHeld   := false.B
  }

  val dMemDepth = (1 << dataAddrWidth) / 4
  val dMem = SyncReadMem(dMemDepth, Vec(4, UInt(8.W)))

  def byteMask(size: UInt, addrLo: UInt): UInt = {
    val m = WireDefault(0.U(4.W))
    switch(size) {
      is(0.U) {
        switch(addrLo) {
          is(0.U) { m := "b0001".U }
          is(1.U) { m := "b0010".U }
          is(2.U) { m := "b0100".U }
          is(3.U) { m := "b1000".U }
        }
      }
      is(1.U) {
        when(addrLo(1) === 0.U) { m := "b0011".U }.otherwise { m := "b1100".U }
      }
      is(2.U) { m := "b1111".U }
    }
    m
  }

  def shiftStoreData(size: UInt, addrLo: UInt, data: UInt): UInt = {
    val out = WireDefault(data)
    switch(size) {
      is(0.U) { out := data(7, 0) << (addrLo << 3) }
      is(1.U) {
        when(addrLo(1) === 0.U) { out := Cat(0.U(16.W), data(15, 0)) }
          .otherwise              { out := Cat(data(15, 0), 0.U(16.W)) }
      }
      is(2.U) { out := data }
    }
    out
  }

  def extractLoadData(size: UInt, addrLo: UInt, word: UInt): UInt = {
    val out = WireDefault(word)
    switch(size) {
      is(0.U) {
        val byte = (word >> (addrLo << 3))(7, 0)
        out := Cat(0.U(24.W), byte)
      }
      is(1.U) {
        val half = Mux(addrLo(1) === 0.U, word(15, 0), word(31, 16))
        out := Cat(0.U(16.W), half)
      }
      is(2.U) { out := word }
    }
    out
  }

  val dReqHeld    = RegInit(false.B)
  val dRespValid  = RegInit(false.B)
  val dRespData   = Reg(UInt(32.W))
  val readPending = RegInit(false.B)
  val readSizeR   = Reg(UInt(2.W))
  val readAddrLoR = Reg(UInt(2.W))

  cpu.io.data.req.ready := !dReqHeld

  val dReq      = cpu.io.data.req.bits
  val dWordAddr = dReq.addr(dataAddrWidth - 1, 2)
  val dAddrLo   = dReq.addr(1, 0)

  val readEn = cpu.io.data.req.fire && !dReq.write
  val rdVec  = dMem.read(dWordAddr, readEn)

  when(cpu.io.data.req.fire) {
    dReqHeld := true.B
    when(dReq.write) {
      val mask    = byteMask(dReq.size, dAddrLo)
      val shifted = shiftStoreData(dReq.size, dAddrLo, dReq.data)
      val maskVec = VecInit((0 until 4).map(b => mask(b).asBool))
      val dataVec = VecInit((0 until 4).map(b => shifted(8 * b + 7, 8 * b)))
      dMem.write(dWordAddr, dataVec, maskVec)
      dRespData  := 0.U
      dRespValid := true.B
    }.otherwise {
      readPending := true.B
      readSizeR   := dReq.size
      readAddrLoR := dAddrLo
    }
  }

  when(readPending) {
    dRespData   := extractLoadData(readSizeR, readAddrLoR, rdVec.asUInt)
    dRespValid  := true.B
    readPending := false.B
  }

  cpu.io.data.resp.valid := dRespValid
  cpu.io.data.resp.bits  := dRespData
  when(cpu.io.data.resp.fire) {
    dRespValid := false.B
    dReqHeld   := false.B
  }

  io.peekData := dMem.read(io.peekAddr(dataAddrWidth - 1, 2)).asUInt
}

// -----------------------------------------------------------------------------
//  Tiny RV32I assembler for hand-built test programs.
// -----------------------------------------------------------------------------
object Asm {
  // R-type
  def add (rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x33, 0x0, 0x00, rd, rs1, rs2)
  def sub (rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x33, 0x0, 0x20, rd, rs1, rs2)
  def sll (rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x33, 0x1, 0x00, rd, rs1, rs2)
  def slt (rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x33, 0x2, 0x00, rd, rs1, rs2)
  def sltu(rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x33, 0x3, 0x00, rd, rs1, rs2)
  def xor (rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x33, 0x4, 0x00, rd, rs1, rs2)
  def srl (rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x33, 0x5, 0x00, rd, rs1, rs2)
  def sra (rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x33, 0x5, 0x20, rd, rs1, rs2)
  def or  (rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x33, 0x6, 0x00, rd, rs1, rs2)
  def and (rd: Int, rs1: Int, rs2: Int): BigInt = rType(0x33, 0x7, 0x00, rd, rs1, rs2)
  // I-type ALU
  def addi (rd: Int, rs1: Int, imm: Int): BigInt = iType(0x13, 0x0, rd, rs1, imm)
  def slli (rd: Int, rs1: Int, shamt: Int): BigInt = iType(0x13, 0x1, rd, rs1, shamt & 0x1f)
  def slti (rd: Int, rs1: Int, imm: Int): BigInt  = iType(0x13, 0x2, rd, rs1, imm)
  def sltiu(rd: Int, rs1: Int, imm: Int): BigInt  = iType(0x13, 0x3, rd, rs1, imm)
  def xori (rd: Int, rs1: Int, imm: Int): BigInt  = iType(0x13, 0x4, rd, rs1, imm)
  def srli (rd: Int, rs1: Int, shamt: Int): BigInt = iType(0x13, 0x5, rd, rs1, shamt & 0x1f)
  def srai (rd: Int, rs1: Int, shamt: Int): BigInt = iType(0x13, 0x5, rd, rs1, 0x400 | (shamt & 0x1f))
  def ori  (rd: Int, rs1: Int, imm: Int): BigInt  = iType(0x13, 0x6, rd, rs1, imm)
  def andi (rd: Int, rs1: Int, imm: Int): BigInt  = iType(0x13, 0x7, rd, rs1, imm)
  // Loads
  def lb (rd: Int, offset: Int, rs1: Int): BigInt = iType(0x03, 0x0, rd, rs1, offset)
  def lh (rd: Int, offset: Int, rs1: Int): BigInt = iType(0x03, 0x1, rd, rs1, offset)
  def lw (rd: Int, offset: Int, rs1: Int): BigInt = iType(0x03, 0x2, rd, rs1, offset)
  def lbu(rd: Int, offset: Int, rs1: Int): BigInt = iType(0x03, 0x4, rd, rs1, offset)
  def lhu(rd: Int, offset: Int, rs1: Int): BigInt = iType(0x03, 0x5, rd, rs1, offset)
  // Stores
  def sb(rs2: Int, offset: Int, rs1: Int): BigInt = sType(0x23, 0x0, rs1, rs2, offset)
  def sh(rs2: Int, offset: Int, rs1: Int): BigInt = sType(0x23, 0x1, rs1, rs2, offset)
  def sw(rs2: Int, offset: Int, rs1: Int): BigInt = sType(0x23, 0x2, rs1, rs2, offset)
  // U-type
  def lui  (rd: Int, imm: Int): BigInt = uType(0x37, rd, imm)
  def auipc(rd: Int, imm: Int): BigInt = uType(0x17, rd, imm)
  // Jumps
  def jal (rd: Int, offset: Int): BigInt             = jType(0x6f, rd, offset)
  def jalr(rd: Int, rs1: Int, imm: Int): BigInt      = iType(0x67, 0x0, rd, rs1, imm)
  // Branches
  def beq (rs1: Int, rs2: Int, offset: Int): BigInt = bType(0x63, 0x0, rs1, rs2, offset)
  def bne (rs1: Int, rs2: Int, offset: Int): BigInt = bType(0x63, 0x1, rs1, rs2, offset)
  def blt (rs1: Int, rs2: Int, offset: Int): BigInt = bType(0x63, 0x4, rs1, rs2, offset)
  def bge (rs1: Int, rs2: Int, offset: Int): BigInt = bType(0x63, 0x5, rs1, rs2, offset)
  def bltu(rs1: Int, rs2: Int, offset: Int): BigInt = bType(0x63, 0x6, rs1, rs2, offset)
  def bgeu(rs1: Int, rs2: Int, offset: Int): BigInt = bType(0x63, 0x7, rs1, rs2, offset)
  // Infinite self-loop used to park the CPU at end of test programs.
  def park(): BigInt = beq(0, 0, 0)

  private def bits(value: BigInt, hi: Int, lo: Int): BigInt =
    (value >> lo) & ((BigInt(1) << (hi - lo + 1)) - 1)

  private def iType(opcode: Int, f3: Int, rd: Int, rs1: Int, imm: Int): BigInt = {
    val i = BigInt(imm) & 0xfff
    (i << 20) | (BigInt(rs1) << 15) | (BigInt(f3) << 12) | (BigInt(rd) << 7) | opcode
  }
  private def rType(opcode: Int, f3: Int, f7: Int, rd: Int, rs1: Int, rs2: Int): BigInt =
    (BigInt(f7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(f3) << 12) | (BigInt(rd) << 7) | opcode
  private def uType(opcode: Int, rd: Int, imm: Int): BigInt =
    (BigInt(imm) & 0xfffff) << 12 | (BigInt(rd) << 7) | opcode
  private def sType(opcode: Int, f3: Int, rs1: Int, rs2: Int, imm: Int): BigInt = {
    val i = BigInt(imm) & 0xfff
    (bits(i, 11, 5) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(f3) << 12) | (bits(i, 4, 0) << 7) | opcode
  }
  private def bType(opcode: Int, f3: Int, rs1: Int, rs2: Int, imm: Int): BigInt = {
    val i = BigInt(imm)
    val b12   = bits(i, 12, 12)
    val b10_5 = bits(i, 10, 5)
    val b4_1  = bits(i, 4, 1)
    val b11   = bits(i, 11, 11)
    (b12 << 31) | (b10_5 << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(f3) << 12) | (b4_1 << 8) | (b11 << 7) | opcode
  }
  private def jType(opcode: Int, rd: Int, imm: Int): BigInt = {
    val i = BigInt(imm)
    val b20    = bits(i, 20, 20)
    val b10_1  = bits(i, 10, 1)
    val b11    = bits(i, 11, 11)
    val b19_12 = bits(i, 19, 12)
    (b20 << 31) | (b10_1 << 21) | (b11 << 20) | (b19_12 << 12) | (BigInt(rd) << 7) | opcode
  }
}

object HuttTests extends TestSuite {

  def runAndPeek(program: Seq[BigInt], peekAddr: Int, maxCycles: Int = 200): BigInt = {
    var result: BigInt = -1
    simulate(new HuttTestHarness(program)) { dut =>
      dut.reset.poke(true.B)
      dut.io.peekAddr.poke(0.U)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step(maxCycles)
      dut.io.peekAddr.poke(peekAddr.U)
      dut.clock.step(1)
      result = dut.io.peekData.peek().litValue
    }
    result
  }

  def runAndPeekMany(program: Seq[BigInt], peekAddrs: Seq[Int], maxCycles: Int = 200): Seq[BigInt] = {
    var results = Seq.fill(peekAddrs.length)(BigInt(-1))
    simulate(new HuttTestHarness(program)) { dut =>
      dut.reset.poke(true.B)
      dut.io.peekAddr.poke(0.U)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step(maxCycles)
      results = peekAddrs.map { addr =>
        dut.io.peekAddr.poke(addr.U)
        dut.clock.step(1)
        dut.io.peekData.peek().litValue
      }
    }
    results
  }

  val tests = Tests {

    // -------------------------------------------------------------------------
    //  Original tests (kept intact)
    // -------------------------------------------------------------------------

    test("addi + sw stores immediate") {
      val program = Seq(
        Asm.addi(1, 0, 0x55),
        Asm.addi(2, 0, 0x80),
        Asm.sw(1, 0, 2)
      )
      assert(runAndPeek(program, 0x80, maxCycles = 100) == BigInt(0x55))
    }

    test("add of two immediates") {
      val program = Seq(
        Asm.addi(1, 0, 3),
        Asm.addi(2, 0, 4),
        Asm.add (3, 1, 2),
        Asm.addi(4, 0, 0x80),
        Asm.sw  (3, 0, 4)
      )
      assert(runAndPeek(program, 0x80, maxCycles = 100) == BigInt(7))
    }

    test("load-modify-store round trip") {
      val program = Seq(
        Asm.addi(1, 0, 100),
        Asm.addi(2, 0, 0x80),
        Asm.sw  (1, 0, 2),
        Asm.lw  (3, 0, 2),
        Asm.addi(3, 3, 23),
        Asm.addi(4, 0, 0x84),
        Asm.sw  (3, 0, 4)
      )
      assert(runAndPeek(program, 0x84, maxCycles = 200) == BigInt(123))
    }

    test("conditional branch — taken path") {
      val program = Seq(
        Asm.addi(1, 0, 1),
        Asm.addi(5, 0, 0x80),
        Asm.beq (1, 1, 12),
        Asm.addi(2, 0, 0xAA),
        Asm.sw  (2, 0, 5),
        Asm.addi(3, 0, 0xBB),
        Asm.sw  (3, 0, 5),
        Asm.park()
      )
      assert(runAndPeek(program, 0x80, maxCycles = 200) == BigInt(0xBB))
    }

    test("jal + return via jalr-like sequence (use jal only)") {
      val program = Seq(
        Asm.addi(2, 0, 0x80),
        Asm.jal (1, 8),
        Asm.addi(3, 0, 0xDEAD),
        Asm.sw  (1, 0, 2),
        Asm.park()
      )
      assert(runAndPeek(program, 0x80, maxCycles = 200) == BigInt(0x08))
    }

    test("loop with store — mirrors uart_hello firmware shape") {
      val program = Seq(
        Asm.addi(5, 0, 0x80),
        Asm.addi(10, 0, 0),
        Asm.addi(10, 10, 1),
        Asm.sw  (10, 0, 5),
        Asm.addi(6, 0, 3),
        Asm.addi(6, 6, -1),
        Asm.bne (6, 0, -4),
        Asm.jal (0, -20)
      )
      assert(runAndPeek(program, 0x80, maxCycles = 500) > BigInt(1))
    }

    // -------------------------------------------------------------------------
    //  R-type ALU
    // -------------------------------------------------------------------------

    test("sub") {
      // x3 = 10 - 3 = 7
      val program = Seq(
        Asm.addi(1, 0, 10),
        Asm.addi(2, 0, 3),
        Asm.sub (3, 1, 2),
        Asm.addi(5, 0, 0x80),
        Asm.sw  (3, 0, 5),
        Asm.park()
      )
      assert(runAndPeek(program, 0x80) == BigInt(7))
    }

    test("bitwise and or xor") {
      // x1=0xFF, x2=0x0F → and=0x0F, or=0xFF, xor=0xF0
      val program = Seq(
        Asm.addi(1, 0, 0xFF),
        Asm.addi(2, 0, 0x0F),
        Asm.and (3, 1, 2),
        Asm.or  (4, 1, 2),
        Asm.xor (5, 1, 2),
        Asm.addi(6, 0, 0x80),
        Asm.sw  (3, 0,  6),
        Asm.sw  (4, 4,  6),
        Asm.sw  (5, 8,  6),
        Asm.park()
      )
      val got = runAndPeekMany(program, Seq(0x80, 0x84, 0x88))
      assert(got(0) == BigInt(0x0F))
      assert(got(1) == BigInt(0xFF))
      assert(got(2) == BigInt(0xF0))
    }

    test("shifts sll srl sra") {
      // srl: 0x90 >> 3 = 0x12; sra: -8 >> 3 = -1 (0xFFFFFFFF); sll: 1 << 3 = 8
      val program = Seq(
        Asm.addi(1, 0, 0x90),
        Asm.addi(2, 0, 3),
        Asm.srl (3, 1, 2),
        Asm.addi(1, 0, -8),
        Asm.sra (4, 1, 2),
        Asm.addi(1, 0, 1),
        Asm.sll (5, 1, 2),
        Asm.addi(6, 0, 0x80),
        Asm.sw  (3, 0, 6),
        Asm.sw  (4, 4, 6),
        Asm.sw  (5, 8, 6),
        Asm.park()
      )
      val got = runAndPeekMany(program, Seq(0x80, 0x84, 0x88))
      assert(got(0) == BigInt(0x12))
      assert(got(1) == BigInt("FFFFFFFF", 16))
      assert(got(2) == BigInt(8))
    }

    test("slt and sltu") {
      // x1=-1 (0xFFFFFFFF), x2=1
      // slt:  -1 < 1 signed   → 1
      // sltu: 0xFFFFFFFF < 1 unsigned → 0
      val program = Seq(
        Asm.addi(1, 0, -1),
        Asm.addi(2, 0, 1),
        Asm.slt (3, 1, 2),
        Asm.sltu(4, 1, 2),
        Asm.addi(5, 0, 0x80),
        Asm.sw  (3, 0, 5),
        Asm.sw  (4, 4, 5),
        Asm.park()
      )
      val got = runAndPeekMany(program, Seq(0x80, 0x84))
      assert(got(0) == BigInt(1))
      assert(got(1) == BigInt(0))
    }

    // -------------------------------------------------------------------------
    //  I-type ALU
    // -------------------------------------------------------------------------

    test("andi ori xori") {
      // x1=0x55; andi 0x0F=0x05, ori 0xA0=0xF5, xori 0xFF=0xAA
      val program = Seq(
        Asm.addi(1, 0, 0x55),
        Asm.andi(2, 1, 0x0F),
        Asm.ori (3, 1, 0xA0),
        Asm.xori(4, 1, 0xFF),
        Asm.addi(5, 0, 0x80),
        Asm.sw  (2, 0, 5),
        Asm.sw  (3, 4, 5),
        Asm.sw  (4, 8, 5),
        Asm.park()
      )
      val got = runAndPeekMany(program, Seq(0x80, 0x84, 0x88))
      assert(got(0) == BigInt(0x05))
      assert(got(1) == BigInt(0xF5))
      assert(got(2) == BigInt(0xAA))
    }

    test("shift immediates slli srli srai") {
      // slli: 1 << 8 = 0x100; srli: 0x100 >> 4 = 0x10; srai: -32 >> 2 = -8 (0xFFFFFFF8)
      val program = Seq(
        Asm.addi(1, 0, 1),
        Asm.slli(2, 1, 8),
        Asm.addi(1, 0, 0x100),
        Asm.srli(3, 1, 4),
        Asm.addi(1, 0, -32),
        Asm.srai(4, 1, 2),
        Asm.addi(5, 0, 0x80),
        Asm.sw  (2, 0, 5),
        Asm.sw  (3, 4, 5),
        Asm.sw  (4, 8, 5),
        Asm.park()
      )
      val got = runAndPeekMany(program, Seq(0x80, 0x84, 0x88))
      assert(got(0) == BigInt(0x100))
      assert(got(1) == BigInt(0x10))
      assert(got(2) == BigInt("FFFFFFF8", 16))
    }

    test("slti and sltiu") {
      // x1=-1: slti x2,x1,0 → 1 (-1<0 signed); sltiu x3,x1,2 → 0 (0xFFFFFFFF>=2 unsigned)
      // x4=5:  slti x5,x4,10 → 1
      val program = Seq(
        Asm.addi (1, 0, -1),
        Asm.slti (2, 1, 0),
        Asm.sltiu(3, 1, 2),
        Asm.addi (4, 0, 5),
        Asm.slti (5, 4, 10),
        Asm.addi (6, 0, 0x80),
        Asm.sw   (2, 0, 6),
        Asm.sw   (3, 4, 6),
        Asm.sw   (5, 8, 6),
        Asm.park()
      )
      val got = runAndPeekMany(program, Seq(0x80, 0x84, 0x88))
      assert(got(0) == BigInt(1))
      assert(got(1) == BigInt(0))
      assert(got(2) == BigInt(1))
    }

    // -------------------------------------------------------------------------
    //  U-type
    // -------------------------------------------------------------------------

    test("lui upper immediate") {
      // lui x1, 0x12345 → x1 = 0x12345000
      val program = Seq(
        Asm.lui  (1, 0x12345),
        Asm.addi (2, 0, 0x80),
        Asm.sw   (1, 0, 2),
        Asm.park()
      )
      assert(runAndPeek(program, 0x80) == BigInt("12345000", 16))
    }

    test("auipc adds pc to upper immediate") {
      // AUIPC is at PC=0; imm=1 → x1 = 0 + 0x1000 = 0x1000
      val program = Seq(
        Asm.auipc(1, 1),
        Asm.addi (2, 0, 0x80),
        Asm.sw   (1, 0, 2),
        Asm.park()
      )
      assert(runAndPeek(program, 0x80) == BigInt(0x1000))
    }

    // -------------------------------------------------------------------------
    //  JALR
    // -------------------------------------------------------------------------

    test("jalr indirect jump sets link register") {
      // jalr x3, x1, 0 with x1=0x14 jumps to 0x14, x3 = return addr = 0x0C
      val program = Seq(
        Asm.addi(5, 0, 0x80),         // 0x00
        Asm.addi(1, 0, 0x14),         // 0x04: x1 = jump target
        Asm.jalr(3, 1, 0),            // 0x08: jump to 0x14; x3 = 0x0C
        Asm.addi(4, 0, 0xBB),         // 0x0C: dead
        Asm.addi(4, 0, 0xCC),         // 0x10: dead
        Asm.sw  (3, 0, 5),            // 0x14: store link register
        Asm.park()                    // 0x18
      )
      assert(runAndPeek(program, 0x80) == BigInt(0x0C))
    }

    // -------------------------------------------------------------------------
    //  Branches (not-taken + all conditions)
    // -------------------------------------------------------------------------

    test("beq not-taken falls through") {
      // x1=1 ≠ x2=2; BEQ not taken → addi x3,0,0x42 executes
      val program = Seq(
        Asm.addi(1, 0, 1),
        Asm.addi(2, 0, 2),
        Asm.addi(5, 0, 0x80),
        Asm.beq (1, 2, 8),            // not taken
        Asm.addi(3, 0, 0x42),
        Asm.sw  (3, 0, 5),
        Asm.park()
      )
      assert(runAndPeek(program, 0x80) == BigInt(0x42))
    }

    test("bne not-taken falls through") {
      // x0 == x0; BNE not taken → addi x3,0,0x77 executes
      val program = Seq(
        Asm.addi(5, 0, 0x80),
        Asm.bne (0, 0, 8),            // not taken (x0==x0)
        Asm.addi(3, 0, 0x77),
        Asm.sw  (3, 0, 5),
        Asm.park()
      )
      assert(runAndPeek(program, 0x80) == BigInt(0x77))
    }

    test("blt taken signed") {
      // x1=-1 < x2=1 signed → branch taken, store 0xAA
      val program = Seq(
        Asm.addi(1, 0, -1),           // 0x00
        Asm.addi(2, 0, 1),            // 0x04
        Asm.addi(5, 0, 0x80),         // 0x08
        Asm.blt (1, 2, 12),           // 0x0C: taken → 0x18
        Asm.addi(3, 0, 0xBB),         // 0x10: dead
        Asm.sw  (3, 0, 5),            // 0x14: dead
        Asm.addi(3, 0, 0xAA),         // 0x18
        Asm.sw  (3, 0, 5),            // 0x1C
        Asm.park()                    // 0x20
      )
      assert(runAndPeek(program, 0x80) == BigInt(0xAA))
    }

    test("bge taken equal") {
      // x1=5 >= x2=5 → branch taken, store 0xAA
      val program = Seq(
        Asm.addi(1, 0, 5),            // 0x00
        Asm.addi(2, 0, 5),            // 0x04
        Asm.addi(5, 0, 0x80),         // 0x08
        Asm.bge (1, 2, 12),           // 0x0C: taken → 0x18
        Asm.addi(3, 0, 0xBB),         // 0x10: dead
        Asm.sw  (3, 0, 5),            // 0x14: dead
        Asm.addi(3, 0, 0xAA),         // 0x18
        Asm.sw  (3, 0, 5),            // 0x1C
        Asm.park()                    // 0x20
      )
      assert(runAndPeek(program, 0x80) == BigInt(0xAA))
    }

    test("bltu not-taken and bgeu taken for unsigned negative") {
      // x1=0xFFFFFFFF (-1), x2=1; unsigned: x1 > x2
      // bltu x1,x2 → not taken; bgeu x1,x2 → taken → store 0xAA
      val program = Seq(
        Asm.addi(1, 0, -1),           // 0x00: x1 = 0xFFFFFFFF
        Asm.addi(2, 0, 1),            // 0x04
        Asm.addi(5, 0, 0x80),         // 0x08
        Asm.bltu(1, 2, 12),           // 0x0C: not taken (falls through)
        Asm.bgeu(1, 2, 8),            // 0x10: taken → 0x18
        Asm.addi(3, 0, 0xBB),         // 0x14: dead
        Asm.addi(3, 0, 0xAA),         // 0x18
        Asm.sw  (3, 0, 5),            // 0x1C
        Asm.park()                    // 0x20
      )
      assert(runAndPeek(program, 0x80) == BigInt(0xAA))
    }

    // -------------------------------------------------------------------------
    //  Loads — halfword and byte sign/zero extension
    // -------------------------------------------------------------------------

    test("lh signed and lhu unsigned halfword") {
      // Store word 0x00008000 (lower halfword 0x8000 = -32768 as int16)
      // lh  → sign-extend to 0xFFFF8000
      // lhu → zero-extend to 0x00008000
      val program = Seq(
        Asm.lui (1, 8),               // x1 = 0x8000 (lui 8 = 8<<12 = 0x8000)
        Asm.addi(2, 0, 0x80),
        Asm.sw  (1, 0, 2),            // mem[0x80] = 0x00008000
        Asm.lh  (3, 0, 2),            // x3 = 0xFFFF8000
        Asm.lhu (4, 0, 2),            // x4 = 0x00008000
        Asm.sw  (3, 4, 2),            // mem[0x84] = 0xFFFF8000
        Asm.sw  (4, 8, 2),            // mem[0x88] = 0x00008000
        Asm.park()
      )
      val got = runAndPeekMany(program, Seq(0x84, 0x88), maxCycles = 150)
      assert(got(0) == BigInt("FFFF8000", 16))
      assert(got(1) == BigInt("00008000", 16))
    }

    test("lb signed and lbu unsigned byte") {
      // Store 0xFFFFFF80 (byte[0] = 0x80 = -128 as int8)
      // lb  → sign-extend to 0xFFFFFF80
      // lbu → zero-extend to 0x00000080
      val program = Seq(
        Asm.addi(1, 0, -128),         // x1 = 0xFFFFFF80
        Asm.addi(2, 0, 0x80),
        Asm.sw  (1, 0, 2),            // mem[0x80] = 0xFFFFFF80; byte[0]=0x80
        Asm.lb  (3, 0, 2),            // x3 = 0xFFFFFF80
        Asm.lbu (4, 0, 2),            // x4 = 0x00000080
        Asm.sw  (3, 4, 2),            // mem[0x84] = 0xFFFFFF80
        Asm.sw  (4, 8, 2),            // mem[0x88] = 0x00000080
        Asm.park()
      )
      val got = runAndPeekMany(program, Seq(0x84, 0x88), maxCycles = 150)
      assert(got(0) == BigInt("FFFFFF80", 16))
      assert(got(1) == BigInt(0x80))
    }

    // -------------------------------------------------------------------------
    //  Stores — halfword and byte
    // -------------------------------------------------------------------------

    test("sh halfword store") {
      // sh of 0xFFFF into cleared word → lower halfword set, upper stays 0
      val program = Seq(
        Asm.addi(1, 0, -1),           // x1 = 0xFFFFFFFF
        Asm.addi(2, 0, 0x80),
        Asm.sw  (0, 0, 2),            // clear mem[0x80]
        Asm.sh  (1, 0, 2),            // sh: bytes[0:1] = 0xFFFF → mem[0x80] = 0x0000FFFF
        Asm.lw  (3, 0, 2),
        Asm.sw  (3, 4, 2),            // mem[0x84] = 0x0000FFFF
        Asm.park()
      )
      assert(runAndPeek(program, 0x84, maxCycles = 150) == BigInt("0000FFFF", 16))
    }

    test("sb byte store at all four positions") {
      // Write 0x55 to each of the four byte lanes; final word = 0x55555555
      val program = Seq(
        Asm.addi(1, 0, 0x55),
        Asm.addi(2, 0, 0x80),
        Asm.sw  (0, 0, 2),            // clear
        Asm.sb  (1, 0, 2),            // byte[0] = 0x55
        Asm.sb  (1, 1, 2),            // byte[1] = 0x55
        Asm.sb  (1, 2, 2),            // byte[2] = 0x55
        Asm.sb  (1, 3, 2),            // byte[3] = 0x55 → word = 0x55555555
        Asm.lw  (3, 0, 2),
        Asm.sw  (3, 4, 2),            // mem[0x84] = 0x55555555
        Asm.park()
      )
      assert(runAndPeek(program, 0x84, maxCycles = 200) == BigInt("55555555", 16))
    }

    // -------------------------------------------------------------------------
    //  Pipeline hazard & multi-cycle correctness tests
    // -------------------------------------------------------------------------

    test("JALR to dynamically computed address") {
      // auipc captures PC, addi adds offset to get target → tests indirect dispatch.
      // auipc at PC=4 → x1=4; addi x1,x1,24 → x1=28; jalr jumps to PC=28 (index 7).
      // Indices 4,5,6 are dead code that must NOT execute.
      val program = Seq(            //  idx  PC
        Asm.addi (5, 0, 0x80),     //   0   0   x5 = 0x80 (result addr)
        Asm.auipc(1, 0),            //   1   4   x1 = 4 (current PC)
        Asm.addi (1, 1, 24),        //   2   8   x1 = 28 (index 7)
        Asm.jalr (0, 1, 0),         //   3  12   jump to x1=28
        Asm.addi (3, 0, 0xDE),      //   4  16   (dead) must NOT execute
        Asm.sw   (3, 0, 5),         //   5  20   (dead)
        Asm.park(),                 //   6  24   (dead)
        Asm.addi (3, 0, 0xAB),      //   7  28   target ← jalr lands here
        Asm.sw   (3, 0, 5),         //   8  32   mem[0x80] = 0xAB
        Asm.park()                  //   9  36
      )
      assert(runAndPeek(program, 0x80, maxCycles = 200) == BigInt(0xAB))
    }

    test("JALR indirect jump with non-zero immediate offset") {
      // Jump to (base + imm) where imm != 0, verifying JALR's offset arithmetic.
      // base = PC of park; imm = +4 jumps one instruction further.
      // Layout: jal to func; func: addi x3, x0, 0xAB; sw; jalr x0, x1, 0.
      val program = Seq(          //  idx  PC
        Asm.addi(5, 0, 0x80),    //   0   0   x5 = 0x80
        Asm.jal (1, 16),         //   1   4   call func (PC→20, x1=8)
        Asm.sw  (3, 0, 5),       //   2   8   store result after return
        Asm.park(),              //   3  12
        Asm.park(),              //   4  16
        Asm.addi(3, 0, 0xAB),   //   5  20   func: x3 = 0xAB
        Asm.jalr(0, 1, 0),       //   6  24   return to x1=8
        Asm.park()               //   7  28
      )
      assert(runAndPeek(program, 0x80, maxCycles = 200) == BigInt(0xAB))
    }

    test("nested function calls preserve link registers") {
      // main → func1 → func2; each level uses a different link register.
      // func2 sets x3=0xCD; func1 passes it through; main stores it.
      val program = Seq(            //  idx  PC
        Asm.addi(5, 0, 0x80),      //   0   0   result addr
        Asm.jal (1, 28),           //   1   4   call func1 (PC→32, x1=8)
        Asm.sw  (3, 0, 5),         //   2   8   store x3 after func1 returns
        Asm.park(),                //   3  12
        Asm.park(),                //   4  16
        Asm.park(),                //   5  20
        Asm.park(),                //   6  24
        Asm.park(),                //   7  28
        Asm.jal (2, 16),           //   8  32   func1: call func2 (PC→48, x2=36)
        Asm.jalr(0, 1, 0),         //   9  36   func1 return → x1=8
        Asm.park(),                //  10  40
        Asm.park(),                //  11  44
        Asm.addi(3, 0, 0xCD),      //  12  48   func2: x3 = 0xCD
        Asm.jalr(0, 2, 0),         //  13  52   func2 return → x2=36
        Asm.park()                 //  14  56
      )
      assert(runAndPeek(program, 0x80, maxCycles = 300) == BigInt(0xCD))
    }

    test("load-use hazard — value used in immediately following instruction") {
      // lw x3, 0(x2) followed directly by addi x3, x3, imm — the CPU must
      // stall or forward the load result before the ALU consumes it.
      val program = Seq(
        Asm.addi(1, 0, 0x42),        // x1 = 0x42
        Asm.addi(2, 0, 0x80),        // x2 = 0x80
        Asm.sw  (1, 0, 2),           // mem[0x80] = 0x42
        Asm.lw  (3, 0, 2),           // x3 = mem[0x80] = 0x42  ← load
        Asm.addi(3, 3, 0x10),        // x3 = x3 + 0x10         ← immediate use
        Asm.sw  (3, 4, 2),           // mem[0x84] = 0x52
        Asm.park()
      )
      assert(runAndPeek(program, 0x84, maxCycles = 200) == BigInt(0x52))
    }

    test("50-iteration accumulator loop") {
      // Counts to 50 using bne; verifies branch-taken path and loop termination.
      // bne at PC=16 with offset −8 → loops back to PC=8 (index 2).
      val program = Seq(            //  idx  PC
        Asm.addi(1, 0, 0),         //   0   0   x1 = 0 (accumulator)
        Asm.addi(2, 0, 50),        //   1   4   x2 = 50 (count)
        Asm.addi(1, 1, 1),         //   2   8   x1++
        Asm.addi(2, 2, -1),        //   3  12   x2--
        Asm.bne (2, 0, -8),        //   4  16   if x2≠0 goto PC=8
        Asm.addi(3, 0, 0x80),      //   5  20   result addr
        Asm.sw  (1, 0, 3),         //   6  24   mem[0x80] = 50
        Asm.park()                 //   7  28
      )
      assert(runAndPeek(program, 0x80, maxCycles = 1200) == BigInt(50))
    }

    test("consecutive stores followed by loads — memory coherence") {
      // Three independent stores then three loads; verifies no store-to-load
      // forwarding confusion and correct memory ordering.
      val program = Seq(
        Asm.addi(2, 0, 0x80),       // base addr
        Asm.addi(1, 0, 0xAA),
        Asm.addi(3, 0, 0xBB),
        Asm.addi(4, 0, 0xCC),
        Asm.sw  (1, 0,  2),         // mem[0x80] = 0xAA
        Asm.sw  (3, 4,  2),         // mem[0x84] = 0xBB
        Asm.sw  (4, 8,  2),         // mem[0x88] = 0xCC
        Asm.lw  (6, 0,  2),         // x6 = 0xAA
        Asm.lw  (7, 4,  2),         // x7 = 0xBB
        Asm.lw  (8, 8,  2),         // x8 = 0xCC
        Asm.add (9, 6, 7),           // x9 = 0xAA + 0xBB = 0x165
        Asm.add (9, 9, 8),           // x9 = 0x165 + 0xCC = 0x231
        Asm.sw  (9, 12, 2),          // mem[0x8C] = 0x231
        Asm.park()
      )
      assert(runAndPeek(program, 0x8C, maxCycles = 300) == BigInt(0x231))
    }
  }
}
