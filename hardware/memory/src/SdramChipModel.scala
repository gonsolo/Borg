// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Behavioral SDR-SDRAM CHIP model (IS42S16160G-class: 4 banks × 8192 rows ×
// 512 cols × 16-bit), driven by the REAL SdramController pins.  Unlike
// SdramBackendSim (a fixed-latency stand-in for the whole backend), this models
// the actual JEDEC SDR protocol the controller speaks — ACTIVATE→READ/WRITE→
// PRECHARGE, per-bank open rows, CAS latency — so a co-sim of
// MemoryController + SdramBackend + SdramController + this chip exhibits the
// real variable read timing (the thing the green-pixel bug needs and the
// behavioral sim flattens away).
//
// Connect: chip.io <> controller.io.pins is WRONG (same dir); instead the chip
// takes Flipped(SdramPinsIO) so cs/ras/cas/we/addr/ba/dq_out are Inputs and
// dq_in is the chip's Output back to the controller.
//
// `readLatency` is the cycle offset from seeing a READ command to driving valid
// data on dq_in.  It is tuned empirically (round-trip test) to match how the
// controller samples DQ in sRWRDY (it uses dly:=CL, one extra to cover the 90°
// SDRAM clock phase on real HW; in a same-edge sim the effective offset differs
// — the test pins it down).
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

  // Debug (task #15): expose the chip's own address reconstruction and
  // read-command decode so a corrupted read can be checked against the
  // hand-computed expected ba/row/col instead of inferred from the
  // controller's own (possibly-also-wrong) view.
  val dbgIsRead   = IO(Output(Bool()))
  val dbgIsAct    = IO(Output(Bool()))
  val dbgBa       = IO(Output(UInt(2.W)))
  val dbgAddrPin  = IO(Output(UInt(13.W)))
  val dbgCol      = IO(Output(UInt(9.W)))
  val dbgOpenRow  = IO(Output(UInt(13.W)))  // openRow(io.ba), the bank being accessed
  val dbgAccAddr  = IO(Output(UInt(addrBits.W)))
  val dbgRdData   = IO(Output(UInt(16.W)))

  // Debug (task #15): the read at word 0x470 (byte 0x8e0) came back wrong
  // with a CORRECTLY-reconstructed accAddr, meaning the stored content
  // itself was already bad -- yet hundreds of earlier reads of the same
  // word (cyc 8.8M-48.7M) were correct. That means something WROTE over
  // legitimate code between those reads and the corrupted one. Latch every
  // write event (seq-counted, like InstrCache's fill tracer) so the host can
  // filter for any write landing on this address across the whole run.
  val dbgWriteSeq     = IO(Output(UInt(32.W)))
  val dbgWriteAccAddr = IO(Output(UInt(addrBits.W)))
  val dbgWriteData    = IO(Output(UInt(16.W)))
  val dbgWriteDqm     = IO(Output(UInt(2.W)))

  // ── Command decode.  Bit order MUST match SdramController's sdrCmd encoding:
  // sdrCmd(3)=cs_n, sdrCmd(2)=we_n, sdrCmd(1)=ras_n, sdrCmd(0)=cas_n.
  val cmd = Cat(io.cs_n, io.we_n, io.ras_n, io.cas_n)
  val CMD_PRECHRG  = "b0001".U
  val CMD_READ     = "b0110".U
  val CMD_WRITE    = "b0010".U
  val CMD_ACTIVATE = "b0101".U

  val isAct   = io.cke && cmd === CMD_ACTIVATE
  val isRead  = io.cke && cmd === CMD_READ
  val isWrite = io.cke && cmd === CMD_WRITE

  // ── Per-bank open row (latched at ACTIVATE) ──
  val openRow = Reg(Vec(4, UInt(13.W)))
  when(isAct) { openRow(io.ba) := io.addr }

  val col = io.addr(8, 0)                       // column from addr pins at R/W
  // Reconstruct the controller's 24-bit word address: ab = {row, ba, col}
  // (controller decode: col=ab(8,0), ba=ab(10,9), row=ab(23,11)).
  val accAddr = Cat(openRow(io.ba), io.ba, col)(addrBits - 1, 0)

  // ── Write: store dq_out, honouring per-byte dqm masks ──
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

  // ── Read: drive dq_in `readLatency` cycles after the READ command ──
  val rdShift = Reg(Vec(readLatency, UInt(16.W)))
  val rdData  = mem.read(accAddr)
  rdShift(0) := Mux(isRead, rdData, rdShift(0))
  for (i <- 1 until readLatency) { rdShift(i) := rdShift(i - 1) }
  io.dq_in := rdShift(readLatency - 1)

  // ── Host backdoor ──
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
