// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3.{assert => _, test => _, _}
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import utest._

// -----------------------------------------------------------------------------
//  TestHarness wires a Hutt up to two tiny synchronous-read memories so the FSM
//  can run real instructions against a deterministic backing store.
//
//  Both buses obey the same single-outstanding Decoupled protocol Hutt expects:
//    - The memory is always ready to accept a request.
//    - Response is produced one cycle after the request fires.
//
//  Data memory is 1 KiB byte-addressable; stores honour size + addr[1:0] by
//  selectively overwriting bytes within the 32-bit word.  Loads return the
//  addressed bytes already extracted to the low bits (the contract Hutt
//  expects per `extendLoad`).
// -----------------------------------------------------------------------------

class HuttTestHarnessIO(instrAddrWidth: Int, dataAddrWidth: Int) extends Bundle {
  // Allow the test to peek at the data memory's current contents.
  val peekAddr = Input(UInt(dataAddrWidth.W))
  val peekData = Output(UInt(32.W))
}

class HuttTestHarness(
    val program: Seq[BigInt],
    val instrAddrWidth: Int = 10,
    val dataAddrWidth: Int  = 10
) extends Module {
  val io = IO(new HuttTestHarnessIO(instrAddrWidth, dataAddrWidth))

  val cpu = Module(new Hutt(instrAddrWidth, dataAddrWidth))
  cpu.io.interrupt := false.B

  // -- Instruction memory: ROM preloaded with `program` -----------------------
  val iMemDepth = 1 << instrAddrWidth
  val iMemInit = VecInit(
    (0 until iMemDepth).map(i =>
      if (i < program.length) program(i).U(32.W) else 0.U(32.W)
    )
  )
  // Single-cycle latency to mimic real memory.
  val iReqValid = cpu.io.instr.req.valid
  val iReqAddr  = cpu.io.instr.req.bits

  val iReqHeld    = RegInit(false.B)   // true between req.fire and resp.fire
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

  // -- Data memory: RW, 1 KiB.  Use a Vec(4, byte) so masked writes work.
  val dMemDepth = (1 << dataAddrWidth) / 4
  val dMem = SyncReadMem(dMemDepth, Vec(4, UInt(8.W)))

  // Per-byte mask derived from size + addr[1:0]:
  //   size 0 (byte): one byte, position = addr[1:0]
  //   size 1 (half): two bytes, position = addr[1] * 2
  //   size 2 (word): all four bytes
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

  // Pre-shift the low-bits store data into the addressed byte position.
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

  // Extract the addressed bytes from a 32-bit word into the low bits.
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

  // Memory FSM:
  //   - On req.fire(write): write immediately; respond next cycle.
  //   - On req.fire(read):  latch addr → SyncReadMem outputs data one cycle later;
  //                         respond on the cycle after that.
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

  // SyncReadMem read port — drives data into rdVec the cycle after `readEn` high.
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

  // -- Peek port for test assertions -----------------------------------------
  io.peekData := dMem.read(io.peekAddr(dataAddrWidth - 1, 2)).asUInt
}

// -----------------------------------------------------------------------------
//  Tiny RV32I assembler for hand-built test programs.
// -----------------------------------------------------------------------------
object Asm {
  // R-type
  def addi(rd: Int, rs1: Int, imm: Int): BigInt =
    iType(0x13, 0x0, rd, rs1, imm)
  def add(rd: Int, rs1: Int, rs2: Int): BigInt =
    rType(0x33, 0x0, 0x00, rd, rs1, rs2)
  def sub(rd: Int, rs1: Int, rs2: Int): BigInt =
    rType(0x33, 0x0, 0x20, rd, rs1, rs2)
  def lui(rd: Int, imm: Int): BigInt =
    uType(0x37, rd, imm)
  def sw(rs2: Int, offset: Int, rs1: Int): BigInt =
    sType(0x23, 0x2, rs1, rs2, offset)
  def sb(rs2: Int, offset: Int, rs1: Int): BigInt =
    sType(0x23, 0x0, rs1, rs2, offset)
  def lw(rd: Int, offset: Int, rs1: Int): BigInt =
    iType(0x03, 0x2, rd, rs1, offset)
  def beq(rs1: Int, rs2: Int, offset: Int): BigInt =
    bType(0x63, 0x0, rs1, rs2, offset)
  def bne(rs1: Int, rs2: Int, offset: Int): BigInt =
    bType(0x63, 0x1, rs1, rs2, offset)
  def jal(rd: Int, offset: Int): BigInt =
    jType(0x6f, rd, offset)

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
    val b12  = bits(i, 12, 12)
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

  /** Run a program for `maxCycles` and return what's at `peekAddr` in data memory. */
  def runAndPeek(program: Seq[BigInt], peekAddr: Int, maxCycles: Int = 200): BigInt = {
    var result: BigInt = -1
    simulate(new HuttTestHarness(program)) { dut =>
      dut.reset.poke(true.B)
      dut.io.peekAddr.poke(0.U)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.clock.step(maxCycles)
      dut.io.peekAddr.poke(peekAddr.U)
      dut.clock.step(1)  // SyncReadMem has 1-cycle read latency
      result = dut.io.peekData.peek().litValue
    }
    result
  }

  val tests = Tests {

    test("addi + sw stores immediate") {
      // x1 = 0x55; mem[0x80] = x1
      val program = Seq(
        Asm.addi(1, 0, 0x55),
        Asm.addi(2, 0, 0x80),     // x2 = 0x80 (data addr)
        Asm.sw(1, 0, 2)           // sw x1, 0(x2)
      )
      val got = runAndPeek(program, 0x80, maxCycles = 100)
      assert(got == BigInt(0x55))
    }

    test("add of two immediates") {
      // x1=3; x2=4; x3=x1+x2=7; sw x3 → mem[0x80]
      val program = Seq(
        Asm.addi(1, 0, 3),
        Asm.addi(2, 0, 4),
        Asm.add (3, 1, 2),
        Asm.addi(4, 0, 0x80),
        Asm.sw  (3, 0, 4)
      )
      val got = runAndPeek(program, 0x80, maxCycles = 100)
      assert(got == BigInt(7))
    }

    test("load-modify-store round trip") {
      // mem[0x80] = 100; x1 = lw mem[0x80]; x1 = x1 + 23; sw x1 → mem[0x84]
      val program = Seq(
        Asm.addi(1, 0, 100),
        Asm.addi(2, 0, 0x80),
        Asm.sw  (1, 0, 2),
        Asm.lw  (3, 0, 2),
        Asm.addi(3, 3, 23),
        Asm.addi(4, 0, 0x84),
        Asm.sw  (3, 0, 4)
      )
      val got = runAndPeek(program, 0x84, maxCycles = 200)
      assert(got == BigInt(123))
    }

    test("conditional branch — taken path") {
      // x1 = 1; if (x1 == 1) goto store_one else store_two
      val program = Seq(
        Asm.addi(1, 0, 1),         // 0x00
        Asm.addi(5, 0, 0x80),      // 0x04
        Asm.beq (1, 1, 12),        // 0x08: if x1==x1 (always), jump +12 → 0x14
        Asm.addi(2, 0, 0xAA),      // 0x0C  (skipped)
        Asm.sw  (2, 0, 5),         // 0x10  (skipped)
        Asm.addi(3, 0, 0xBB),      // 0x14
        Asm.sw  (3, 0, 5),         // 0x18
        Asm.beq (0, 0, 0)          // 0x1C: park
      )
      val got = runAndPeek(program, 0x80, maxCycles = 200)
      assert(got == BigInt(0xBB))
    }

    test("jal + return via jalr-like sequence (use jal only)") {
      // Test JAL link register and target.
      val program = Seq(
        Asm.addi(2, 0, 0x80),       // 0x00
        Asm.jal (1, 8),             // 0x04: link to next, jump +8 → 0x0C
        Asm.addi(3, 0, 0xDEAD),     // 0x08 (skipped)
        Asm.sw  (1, 0, 2),          // 0x0C: store link register
        Asm.beq (0, 0, 0)           // 0x10: park
      )
      val got = runAndPeek(program, 0x80, maxCycles = 200)
      // x1 should hold pc+4 of the JAL = 0x08
      assert(got == BigInt(0x08))
    }

    test("loop with store — mirrors uart_hello firmware shape") {
      // Mirrors fpga/ulx3s/uart_hello.s but counts stores at a memory cell
      // instead of writing to MMIO.  After ~3 outer-loop iterations we
      // should see at least 3 increments at mem[0x80].
      //
      //    li    t0, 0x80                    // 0x00
      //    addi  a0, x0, 0                   // 0x04  counter
      // loop:
      //    addi  a0, a0, 1                   // 0x08
      //    sw    a0, 0(t0)                   // 0x0C  store counter
      //    addi  t1, x0, 3                   // 0x10  inner delay 3 iters
      // 1: addi  t1, t1, -1                  // 0x14
      //    bne   t1, x0, -4                  // 0x18  loop back to 0x14
      //    jal   x0, -20                     // 0x1C  loop back to 0x08
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
      // Run long enough for the loop to iterate several times.
      val got = runAndPeek(program, 0x80, maxCycles = 500)
      // After many iterations the counter must be > 1.
      assert(got > BigInt(1))
    }
  }
}
