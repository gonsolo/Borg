// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Behavioral SDR SDRAM chip model (IS42S16160G class: 4 banks x 8192 rows x 512 cols x 16 bit),
// driven by the real SdramController pins: per-bank open rows and CAS latency give real,
// variable read timing. It takes Flipped(SdramPinsIO), so dq_in is its output.
// `readLatency` (READ command to valid dq_in) is tuned by the round-trip test.
package memory

import chisel3._
import chisel3.util._

class SdramChipDbgIO extends Bundle {
  val we    = Input(Bool())
  val waddr = Input(UInt(24.W))
  val wdata = Input(UInt(16.W))
  val raddr = Input(UInt(24.W))
  val rdata = Output(UInt(16.W))
}

class SdramChipModel(addrBits: Int = 22, readLatency: Int = 2) extends Module {
  val io = IO(Flipped(new SdramPinsIO))

  val words = 1 << addrBits
  val mem   = Mem(words, UInt(16.W))   // combinational read; explicit delay below

  // Optional host backdoor for preload/readback (sim only).
  val dbg = IO(new SdramChipDbgIO)

  // Taps: the chip's own address reconstruction and command decode.
  val dbgIsRead   = IO(Output(Bool()))
  val dbgIsAct    = IO(Output(Bool()))
  val dbgBa       = IO(Output(UInt(2.W)))
  val dbgAddrPin  = IO(Output(UInt(13.W)))
  val dbgCol      = IO(Output(UInt(9.W)))
  val dbgOpenRow  = IO(Output(UInt(13.W)))  // openRow(io.ba), the bank being accessed
  val dbgAccAddr  = IO(Output(UInt(addrBits.W)))
  val dbgRdData   = IO(Output(UInt(16.W)))

  // Every write, sequence-counted.
  val dbgWriteSeq     = IO(Output(UInt(32.W)))
  val dbgWriteAccAddr = IO(Output(UInt(addrBits.W)))
  val dbgWriteData    = IO(Output(UInt(16.W)))
  val dbgWriteDqm     = IO(Output(UInt(2.W)))

  // Bit order must match SdramController's sdrCmd: {cs_n, we_n, ras_n, cas_n}.
  val cmd = Cat(io.cs_n, io.we_n, io.ras_n, io.cas_n)
  val CMD_PRECHRG  = "b0001".U
  val CMD_READ     = "b0110".U
  val CMD_WRITE    = "b0010".U
  val CMD_ACTIVATE = "b0101".U

  val isAct   = io.cke && cmd === CMD_ACTIVATE
  val isRead  = io.cke && cmd === CMD_READ
  val isWrite = io.cke && cmd === CMD_WRITE

  val openRow = Reg(Vec(4, UInt(13.W)))
  when(isAct) { openRow(io.ba) := io.addr }

  val col = io.addr(8, 0)                       // column from addr pins at R/W
  // The controller's word address is {row, ba, col}.
  val accAddr = Cat(openRow(io.ba), io.ba, col)(addrBits - 1, 0)

  // Write dq_out, honouring the per-byte dqm masks.
  val dbgWriteSeqReg = RegInit(0.U(32.W))
  val dbgWriteAccAddrReg = RegInit(0.U(addrBits.W))
  val dbgWriteDataReg    = RegInit(0.U(16.W))
  val dbgWriteDqmReg     = RegInit(0.U(2.W))
  when(isWrite) {
    val old = mem.read(accAddr)
    val lo  = Mux(io.dqm(0), old(7, 0),   io.dq_out(7, 0))
    val hi  = Mux(io.dqm(1), old(15, 8),  io.dq_out(15, 8))
    val written = Cat(hi, lo)
    mem.write(accAddr, written)
    dbgWriteSeqReg     := dbgWriteSeqReg + 1.U
    dbgWriteAccAddrReg := accAddr
    dbgWriteDataReg    := written
    dbgWriteDqmReg     := io.dqm
  }
  dbgWriteSeq     := dbgWriteSeqReg
  dbgWriteAccAddr := dbgWriteAccAddrReg
  dbgWriteData    := dbgWriteDataReg
  dbgWriteDqm     := dbgWriteDqmReg

  val rdShift = Reg(Vec(readLatency, UInt(16.W)))
  val rdData  = mem.read(accAddr)
  rdShift(0) := Mux(isRead, rdData, rdShift(0))
  for (i <- 1 until readLatency) { rdShift(i) := rdShift(i - 1) }
  io.dq_in := rdShift(readLatency - 1)

  when(dbg.we) { mem.write(dbg.waddr(addrBits - 1, 0), dbg.wdata) }
  dbg.rdata := mem.read(dbg.raddr(addrBits - 1, 0))

  dbgIsRead  := isRead
  dbgIsAct   := isAct
  dbgBa      := io.ba
  dbgAddrPin := io.addr
  dbgCol     := col
  dbgOpenRow := openRow(io.ba)
  dbgAccAddr := accAddr
  dbgRdData  := rdData
}
