// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

// Phase 3 validation: an RV64 Hutt driving a 32-bit memory fabric THROUGH the
// HuttDataWidthAdapter (64↔32).  Doubleword LD/SD are split into two 32-bit
// transactions by the adapter; byte/half/word pass through.  This is the real
// SoC configuration (the Borg fabric stays 32-bit).  Same programs as
// HuttRv64Tests, but here the data path is 32-bit behind the adapter.

import chisel3.{assert => _, test => _, _}
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import utest._

/** RV64 Hutt → HuttDataWidthAdapter → 32-bit data memory. */
class HuttRv64AdapterHarness(
    val program: Seq[BigInt],
    val instrAddrWidth: Int = 10,
    val dataAddrWidth: Int  = 10
) extends Module {
  val io = IO(new HuttRv64HarnessIO(dataAddrWidth))   // peekData is 64-bit

  val cpu     = Module(new Hutt(instrAddrWidth, dataAddrWidth, xlen = 64))
  val adapter = Module(new HuttDataWidthAdapter(dataAddrWidth))
  cpu.io.interrupt := false.B
  adapter.io.cpu <> cpu.io.data                       // 64-bit CPU ↔ adapter

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

  // ── Data memory: 32-bit (4-byte words), driven by the adapter's 32-bit side ──
  val dMemDepth = (1 << dataAddrWidth) / 4
  val dMem = SyncReadMem(dMemDepth, Vec(4, UInt(8.W)))

  def byteMask(size: UInt, addrLo: UInt): UInt = {
    val m = WireDefault(0.U(4.W))
    switch(size) {
      is(0.U) { switch(addrLo) {
        is(0.U) { m := "b0001".U }; is(1.U) { m := "b0010".U }
        is(2.U) { m := "b0100".U }; is(3.U) { m := "b1000".U } } }
      is(1.U) { when(addrLo(1) === 0.U) { m := "b0011".U }.otherwise { m := "b1100".U } }
      is(2.U) { m := "b1111".U }
    }
    m
  }
  def shiftStore(size: UInt, addrLo: UInt, data: UInt): UInt = {
    val out = WireDefault(data)
    switch(size) {
      is(0.U) { out := data(7, 0) << (addrLo << 3) }
      is(1.U) { when(addrLo(1) === 0.U) { out := Cat(0.U(16.W), data(15, 0)) }
                .otherwise               { out := Cat(data(15, 0), 0.U(16.W)) } }
      is(2.U) { out := data }
    }
    out
  }
  def extractLoad(size: UInt, addrLo: UInt, word: UInt): UInt = {
    val out = WireDefault(word)
    switch(size) {
      is(0.U) { out := Cat(0.U(24.W), (word >> (addrLo << 3))(7, 0)) }
      is(1.U) { out := Cat(0.U(16.W), Mux(addrLo(1) === 0.U, word(15, 0), word(31, 16))) }
      is(2.U) { out := word }
    }
    out
  }

  val m = adapter.io.mem
  val dReqHeld    = RegInit(false.B)
  val dRespValid  = RegInit(false.B)
  val dRespData   = Reg(UInt(32.W))
  val readPending = RegInit(false.B)
  val readSizeR   = Reg(UInt(2.W))
  val readAddrLoR = Reg(UInt(2.W))

  m.req.ready := !dReqHeld
  val dWordAddr = m.req.bits.addr(dataAddrWidth - 1, 2)
  val dAddrLo   = m.req.bits.addr(1, 0)
  val readEn    = m.req.fire && !m.req.bits.write
  val rdVec     = dMem.read(dWordAddr, readEn)

  when(m.req.fire) {
    dReqHeld := true.B
    when(m.req.bits.write) {
      val mask    = byteMask(m.req.bits.size, dAddrLo)
      val shifted = shiftStore(m.req.bits.size, dAddrLo, m.req.bits.data)
      val maskVec = VecInit((0 until 4).map(b => mask(b).asBool))
      val dataVec = VecInit((0 until 4).map(b => shifted(8 * b + 7, 8 * b)))
      dMem.write(dWordAddr, dataVec, maskVec)
      dRespData := 0.U; dRespValid := true.B
    }.otherwise {
      readPending := true.B; readSizeR := m.req.bits.size; readAddrLoR := dAddrLo
    }
  }
  when(readPending) {
    dRespData := extractLoad(readSizeR, readAddrLoR, rdVec.asUInt)
    dRespValid := true.B; readPending := false.B
  }
  m.resp.valid := dRespValid
  m.resp.bits  := dRespData
  when(m.resp.fire) { dRespValid := false.B; dReqHeld := false.B }

  // 64-bit peek = two consecutive 32-bit words {high@addr+4, low@addr}.
  val pWord = io.peekAddr(dataAddrWidth - 1, 2)
  io.peekData := Cat(dMem.read(pWord + 1.U).asUInt, dMem.read(pWord).asUInt)
}

object HuttRv64AdapterTests extends TestSuite {
  import Asm._

  def runPeek(program: Seq[BigInt], peekAddr: Int, maxCycles: Int = 400): BigInt = {
    var result: BigInt = -1
    simulate(new HuttRv64AdapterHarness(program)) { dut =>
      dut.reset.poke(true.B); dut.io.peekAddr.poke(0.U); dut.clock.step(2)
      dut.reset.poke(false.B); dut.clock.step(maxCycles)
      dut.io.peekAddr.poke(peekAddr.U); dut.clock.step(1)
      result = dut.io.peekData.peek().litValue
    }
    result
  }

  val tests: Tests = Tests {

    // Doubleword store/load round trip — splits into two 32-bit beats in the adapter.
    test("ld/sd round trip through 32-bit fabric") {
      val prog = Seq(
        lui(1, 0xDEADC),              // x1 = sext(0xDEADC000) = 0xFFFFFFFF_DEADC000
        Rv64Asm.sd(1, 16, 0),         // adapter: word@16=low, word@20=high
        Rv64Asm.ld(5, 16, 0),         // adapter: read word@16 then word@20 → x5
        Rv64Asm.sd(5, 0, 0),          // store back at [0] for peek
        park())
      assert(runPeek(prog, 0) == BigInt("FFFFFFFFDEADC000", 16))
    }

    // Word store/load (size 2) passes straight through the adapter.
    test("word store/load passthrough") {
      val prog = Seq(
        addi(1, 0, 0x123),
        sw(1, 0, 0),                  // 32-bit store at [0]
        lw(2, 0, 0),                  // 32-bit load
        sw(2, 8, 0),                  // store at [8] for peek (low word)
        park())
      // peek at 8 → word2 = 0x123 in the low 32 bits.
      assert((runPeek(prog, 8) & BigInt("FFFFFFFF", 16)) == BigInt(0x123))
    }

    // A 64-bit value built high+low, stored/reloaded as a doubleword.
    test("doubleword preserves both halves") {
      val prog = Seq(
        addi(1, 0, 1),
        Rv64Asm.slli64(1, 1, 40),     // x1 = 1<<40 (only in the high 32 bits)
        addi(2, 0, 0x55),
        or(1, 1, 2),                  // x1 = (1<<40) | 0x55
        Rv64Asm.sd(1, 0, 0),
        Rv64Asm.ld(3, 0, 0),
        Rv64Asm.sd(3, 24, 0),
        park())
      assert(runPeek(prog, 24) == (BigInt(1) << 40 | BigInt(0x55)))
    }
  }
}
