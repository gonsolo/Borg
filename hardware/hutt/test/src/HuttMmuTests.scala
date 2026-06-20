// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package hutt

// Tests for Sv39 MMU: satp CSR, 1 GB identity mapping, instruction/load page
// faults, and SFENCE.VMA.  All tests run at XLEN=64 via HuttRv64Harness with
// dataAddrWidth=13 (8 KB data memory, enough for root page table + results).
//
// Memory map used by these tests:
//   data byte 0  ..  7  — root PTE (VPN[2]=0 → 1 GB identity mapping)
//   data byte 8  .. 15  — root PTE (VPN[2]=1, left as 0 = invalid)
//   data byte 0x100 ..  — test result (SW/SD from firmware)

import chisel3.{assert => _, test => _, _}
import chisel3.simulator.EphemeralSimulator._
import utest._

object HuttMmuTests extends TestSuite {

  val RESULT_ADDR = 0x100
  val DATA_WIDTH  = 13  // 8 KB data memory

  // PTE flags: V=1 R=1 W=1 X=1 U=0 G=0 A=1 D=1 → 0xCF
  val PTE_RWX_AD = BigInt(0xCFL)
  // SFENCE.VMA (rs1=0, rs2=0): funct7=9, opcode=SYSTEM, funct3=0
  val SFENCE_VMA = BigInt(0x12000073L)

  def csrInstr(funct3: Int, csr: Int, rs1: Int, rd: Int): BigInt =
    (BigInt(csr & 0xFFF) << 20) | (BigInt(rs1 & 0x1F) << 15) |
    (BigInt(funct3 & 7)  << 12) | (BigInt(rd   & 0x1F) << 7)  | BigInt(0x73)
  def csrrw(rd: Int, csr: Int, rs1: Int): BigInt = csrInstr(1, csr, rs1, rd)
  def csrrs(rd: Int, csr: Int, rs1: Int): BigInt = csrInstr(2, csr, rs1, rd)

  val MRET = BigInt(0x30200073L)

  val CSR_MSTATUS = 0x300
  val CSR_MTVEC   = 0x305
  val CSR_MEPC    = 0x341
  val CSR_MCAUSE  = 0x342
  val CSR_SATP    = 0x180

  def run(program: Seq[BigInt], peekAddr: Int = RESULT_ADDR,
          maxCycles: Int = 1200): BigInt = {
    var result: BigInt = -1
    simulate(new HuttRv64Harness(program, dataAddrWidth = DATA_WIDTH)) { dut =>
      dut.reset.poke(true.B); dut.io.peekAddr.poke(0.U); dut.clock.step(2)
      dut.reset.poke(false.B); dut.clock.step(maxCycles)
      dut.io.peekAddr.poke(peekAddr.U); dut.clock.step(1)
      result = dut.io.peekData.peek().litValue
    }
    result
  }

  val tests = Tests {

    // -----------------------------------------------------------------------
    test("satp CSR is writable and readable") {
      import Asm._
      // Write Sv39 mode (bit 63 set) + PPN=0 to satp; read back.
      val prog = Seq(
        addi(1, 0, 1),
        Rv64Asm.slli64(1, 1, 63),             // x1 = 0x8000_0000_0000_0000
        csrrw(0, CSR_SATP, 1),                // satp = Sv39, PPN=0
        csrrs(2, CSR_SATP, 0),               // x2 = satp
        Rv64Asm.sd(2, 0, 0),                  // store at byte 0
        park()
      )
      val got = run(prog, peekAddr = 0)
      assert(got == BigInt("8000000000000000", 16))
    }

    // -----------------------------------------------------------------------
    test("Sv39 1 GB identity mapping: data load/store in S-mode") {
      import Asm._
      // Install 1 GB identity PTE (VPN[2]=0): PTE at byte 0 = 0xCF.
      // Enter S-mode; store 0x42 at byte 0x100 via MMU; verify.
      //
      // Layout (word = 4 bytes):
      //   words  0-10: M-mode setup
      //   word  11 (PC=0x2C): MRET → S-mode at 0x30
      //   word  12 (PC=0x30): park (never executed; gap)
      //   word  13 (PC=0x34): S_START — first S-mode instruction
      val S_START = 0x34
      val prog = Seq(
        addi(2, 0, 0xCF),                     // w0:  x2 = 0xCF (PTE flags)
        Rv64Asm.sd(2, 0, 0),                   // w1:  data[0..7] = 0xCF (1 GB superpage)
        addi(1, 0, 1),                         // w2:  x1 = 1
        Rv64Asm.slli64(1, 1, 63),             // w3:  x1 = Sv39 satp value
        csrrw(0, CSR_SATP, 1),               // w4:  enable Sv39
        lui(3, 1),                             // w5:  x3 = 0x1000
        srli(3, 3, 1),                         // w6:  x3 = 0x800 (MPP=01=S)
        csrrw(0, CSR_MSTATUS, 3),             // w7:  mstatus.MPP=S
        addi(4, 0, S_START),                  // w8:  x4 = S_START
        csrrw(0, CSR_MEPC, 4),               // w9:  mepc = S_START
        MRET,                                  // w10 (PC=0x28): → S-mode at S_START
        park(),                                // w11 (PC=0x2C): gap
        park(),                                // w12 (PC=0x30): gap
        // word 13 (PC=0x34 = S_START): S-mode execution
        addi(5, 0, 0x42),                     // x5 = 0x42
        Rv64Asm.sd(5, 0x100, 0),             // store 0x42 at byte 0x100 (via MMU)
        park()
      )
      assert(run(prog) == 0x42)
    }

    // -----------------------------------------------------------------------
    test("instruction page fault: V=0 PTE causes mcause=12") {
      import Asm._
      // PTE at byte 0 = 0 (invalid, V=0).
      // After MRET to S-mode, the first instruction fetch goes through PTW,
      // reads the invalid PTE, and takes an instruction page fault (cause=12).
      // M-mode handler stores mcause.
      //
      // Layout:
      //   words  0-11: M-mode setup (mtvec → M_HANDLER, satp Sv39, MRET)
      //   word  12 (PC=0x30): gap (never executes in S-mode; page fault fires first)
      //   word  13 (PC=0x34): gap
      //   word  14 (PC=0x38): M_HANDLER
      val M_HANDLER = 0x38
      val S_START   = 0x30
      val prog = Seq(
        addi(5, 0, M_HANDLER),               // w0:  x5 = M_HANDLER
        csrrw(0, CSR_MTVEC, 5),             // w1:  mtvec = M_HANDLER
        Rv64Asm.sd(0, 0, 0),                 // w2:  PTE[0..7] = 0 (invalid)
        addi(1, 0, 1),                       // w3
        Rv64Asm.slli64(1, 1, 63),           // w4:  x1 = Sv39 satp
        csrrw(0, CSR_SATP, 1),             // w5:  satp = Sv39, PPN=0
        lui(3, 1),                           // w6
        srli(3, 3, 1),                       // w7:  x3 = 0x800 (MPP=S)
        csrrw(0, CSR_MSTATUS, 3),           // w8
        addi(4, 0, S_START),               // w9:  x4 = S_START
        csrrw(0, CSR_MEPC, 4),             // w10: mepc = S_START
        MRET,                               // w11 (PC=0x2C): → S-mode at 0x30
        park(),                             // w12 (PC=0x30): S_START — never runs!
        park(),                             // w13 (PC=0x34): gap
        // word 14 (PC=0x38 = M_HANDLER):
        csrrs(1, CSR_MCAUSE, 0),           // x1 = mcause (should be 12)
        Rv64Asm.sd(1, 0x100, 0),           // store mcause at result addr
        park()
      )
      assert(run(prog) == 12)
    }

    // -----------------------------------------------------------------------
    test("load page fault: unmapped 1 GB region causes mcause=13") {
      import Asm._
      // Byte 0 (root PTE for VPN[2]=0): 0xCF → 1 GB identity mapping.
      // Byte 8 (root PTE for VPN[2]=1): 0 (invalid) → second 1 GB unmapped.
      // In S-mode: execute instructions (low VA, VPN[2]=0, MMU works), then
      // try LD from 0x40000000 (= 1<<30, VPN[2]=1) → PTW reads byte 8 = 0
      // → LOAD PAGE FAULT (cause=13).
      //
      // Layout:
      //   words  0-13: M-mode setup
      //   word  14 (PC=0x38): gap
      //   word  15 (PC=0x3C): S_START
      //   word  20 (PC=0x50): M_HANDLER
      val M_HANDLER = 0x50
      val S_START   = 0x3C
      val prog = Seq(
        addi(2, 0, 0xCF),                   // w0:  x2 = 0xCF (1 GB RWX PTE)
        Rv64Asm.sd(2, 0, 0),               // w1:  byte[0..7] = 0xCF (VPN[2]=0)
        Rv64Asm.sd(0, 8, 0),               // w2:  byte[8..15] = 0 (VPN[2]=1 invalid)
        addi(5, 0, M_HANDLER),             // w3:  x5 = M_HANDLER
        csrrw(0, CSR_MTVEC, 5),           // w4:  mtvec = M_HANDLER
        addi(1, 0, 1),                     // w5
        Rv64Asm.slli64(1, 1, 63),         // w6:  x1 = Sv39 satp
        csrrw(0, CSR_SATP, 1),           // w7:  satp = Sv39
        lui(3, 1),                         // w8
        srli(3, 3, 1),                     // w9:  x3 = 0x800 (MPP=S)
        csrrw(0, CSR_MSTATUS, 3),         // w10
        addi(4, 0, S_START),             // w11
        csrrw(0, CSR_MEPC, 4),           // w12: mepc = S_START
        MRET,                             // w13 (PC=0x34): → S-mode at S_START
        park(),                           // w14 (PC=0x38): gap
        // word 15 (PC=0x3C = S_START): S-mode
        addi(6, 0, 1),                   // x6 = 1 (dummy instruction to fill TLB)
        addi(1, 0, 1),                   // x1 = 1
        Rv64Asm.slli64(1, 1, 30),       // x1 = 0x4000_0000 = 1 GB (VPN[2]=1)
        Rv64Asm.ld(2, 0, 1),            // LD x2, 0(x1) → load from 1 GB → FAULT
        park(),                           // never executed (page fault)
        // word 20 (PC=0x50 = M_HANDLER):
        csrrs(1, CSR_MCAUSE, 0),         // x1 = mcause (should be 13)
        Rv64Asm.sd(1, 0x100, 0),         // store mcause
        park()
      )
      assert(run(prog) == 13)
    }

    // -----------------------------------------------------------------------
    test("SFENCE.VMA flushes TLB and execution continues") {
      import Asm._
      // Install 1 GB identity mapping, enter S-mode, execute SFENCE.VMA
      // (flushes TLB), then store a sentinel value — proves no crash and that
      // the TLB correctly re-fills after flush.
      val S_START = 0x2C
      val prog = Seq(
        addi(2, 0, 0xCF),                 // w0:  PTE=0xCF
        Rv64Asm.sd(2, 0, 0),             // w1:  byte[0..7] = 0xCF
        addi(1, 0, 1),                   // w2
        Rv64Asm.slli64(1, 1, 63),       // w3:  Sv39 satp
        csrrw(0, CSR_SATP, 1),         // w4
        lui(3, 1),                       // w5
        srli(3, 3, 1),                   // w6:  x3 = 0x800 (MPP=S)
        csrrw(0, CSR_MSTATUS, 3),       // w7
        addi(4, 0, S_START),           // w8:  x4 = S_START
        csrrw(0, CSR_MEPC, 4),         // w9:  mepc = S_START
        MRET,                           // w10 (PC=0x28): → S-mode at S_START
        // word 11 (PC=0x2C = S_START): S-mode
        SFENCE_VMA,                      // flush TLB (re-walk follows for every access)
        addi(7, 0, 0x55),              // x7 = 0x55 (sentinel)
        Rv64Asm.sd(7, 0x100, 0),      // store 0x55 at byte 0x100 via MMU
        park()
      )
      assert(run(prog) == 0x55)
    }
  }
}
