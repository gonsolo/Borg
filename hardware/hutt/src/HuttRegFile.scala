// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

import chisel3._
import chisel3.util._

class HuttRegFileIO(val xlen: Int = 32) extends Bundle {
  val rs1Addr = Input(UInt(5.W))
  val rs2Addr = Input(UInt(5.W))
  val rs1Data = Output(UInt(xlen.W))
  val rs2Data = Output(UInt(xlen.W))

  val wen   = Input(Bool())
  val wAddr = Input(UInt(5.W))
  val wData = Input(UInt(xlen.W))

  // Debug-only: chasing task #15's real-SDRAM-cosim hang, now traced to
  // OpenSBI's nputs() looping forever -- expose the 4 GPRs its loop uses
  // (s1=x9 current ptr, a5=x15 device-struct/putc-fn ptr, s3=x19
  // device-struct base, s5=x21 end ptr) so a corrupted `len` (s5-s1) can be
  // read directly instead of inferred from PC census. Only wired up from
  // debug-only sim harnesses; never present in a synthesized ASIC/FPGA build.
  val dbgS1 = Output(UInt(xlen.W))
  val dbgA5 = Output(UInt(xlen.W))
  val dbgS3 = Output(UInt(xlen.W))
  val dbgS5 = Output(UInt(xlen.W))
  // The observed loop never actually visits print()'s literal-character
  // walk (dbgS10=x26 read back as 0, confirming it's untouched) -- it's
  // confined to print()'s tiny console_tbuf flush loop (fw_payload.elf
  // 0x13f4-0x1404): `nputs(buf+s2,s3-s2); s2+=returned_count; loop while
  // s2<s3`. s3=x19 (already probed) reads 1; s2=x18 is the progress
  // counter incremented by nputs()'s return value -- if it's not actually
  // advancing, that alone explains the infinite loop.
  val dbgS2 = Output(UInt(xlen.W))
  // a0=x10 -- nputs()'s actual return value register, read continuously to
  // check whether it genuinely holds 1 (as nputs's own disassembly implies
  // it must) right when print+0x388's `add s2,s2,a0` executes, or whether
  // something clobbers/never-sets it -- distinguishes a software-visible
  // data bug from a genuine Hutt call/return-value correctness bug.
  val dbgA0 = Output(UInt(xlen.W))
  // ra=x1 -- chasing task #15's Bug A scheduler-stall: __sbi_ecall's own
  // trap from_pc is always its own address (the ecall instruction), never
  // the caller's; ra at the exact ecall-trapping cycle identifies who's
  // actually making the repeated SBI calls seen during the stall.
  val dbgRa = Output(UInt(xlen.W))
}

/** 32-entry x XLEN-bit RISC-V integer register file (RV32I: xlen=32, RV64I: 64).
  *
  * x0 is hardwired to zero: writes are silently dropped, reads always return 0.
  * Both read ports are asynchronous; write happens on rising clock when `wen`.
  */
class HuttRegFile(val xlen: Int = 32, val hasDebugPorts: Boolean = true) extends Module {
  val io = IO(new HuttRegFileIO(xlen))

  // Two explicit 16-entry halves (matching ECP5's native TRELLIS_DPR16X4
  // primitive depth) instead of one Mem(32, ...). A single Mem(32, ...)
  // needs yosys's memory_libmap to synthesize the depth-combine (2 halves
  // -> address bit 4 picks one) itself; measured on real hardware this
  // produced a ~189ns wide carry-chain in the write-data routing
  // (regFile.mem_ext), matching in size the CSR-read pathology fixed in
  // Hutt.scala. Splitting explicitly and combining with a plain Chisel
  // Mux keeps the depth-select a single, cheap 2-way pick instead of
  // something yosys has to infer. Each 16-entry half still needs no
  // width-combine logic: xlen/4 DPR16X4 primitives in parallel at the
  // same address, entirely independent of each other.
  val memLo = Mem(16, UInt(xlen.W))
  val memHi = Mem(16, UInt(xlen.W))

  def readPort(addr: UInt): UInt = Mux(addr(4), memHi(addr(3, 0)), memLo(addr(3, 0)))

  io.rs1Data := Mux(io.rs1Addr === 0.U, 0.U, readPort(io.rs1Addr))
  io.rs2Data := Mux(io.rs2Addr === 0.U, 0.U, readPort(io.rs2Addr))
  // Each of these is its own read-port mux against the register array --
  // real area (measured), not free. Tied to 0 (not connected to the array
  // at all) when !hasDebugPorts.
  io.dbgS1 := (if (hasDebugPorts) readPort(9.U)  else 0.U)
  io.dbgA5 := (if (hasDebugPorts) readPort(15.U) else 0.U)
  io.dbgS3 := (if (hasDebugPorts) readPort(19.U) else 0.U)
  io.dbgS5 := (if (hasDebugPorts) readPort(21.U) else 0.U)
  io.dbgS2 := (if (hasDebugPorts) readPort(18.U) else 0.U)
  io.dbgA0 := (if (hasDebugPorts) readPort(10.U) else 0.U)
  io.dbgRa := (if (hasDebugPorts) readPort(1.U)  else 0.U)

  when(io.wen && io.wAddr =/= 0.U) {
    when(io.wAddr(4)) {
      memHi.write(io.wAddr(3, 0), io.wData)
    }.otherwise {
      memLo.write(io.wAddr(3, 0), io.wData)
    }
  }
}
