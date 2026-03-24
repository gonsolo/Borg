// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class CsrFileIO extends Bundle {
  // Nibble-serial counter (0-7)
  val counter = Input(UInt(3.W))

  // CSR addressing and data
  val imm_lo = Input(UInt(12.W))  // CSR address (from immediate)
  val imm = Input(UInt(4.W))      // Current nibble of immediate (for trap type)
  val data_rs1 = Input(UInt(4.W)) // Current nibble of rs1 (for CSR write data)
  val pc = Input(UInt(4.W))       // Current nibble of PC (for mepc save)

  // Control signals
  val is_csr_write = Input(Bool())
  val is_csr_set = Input(Bool())
  val is_csr_clear = Input(Bool())
  val is_exception = Input(Bool())
  val is_trap = Input(Bool())
  val is_mret = Input(Bool())
  val is_interrupt = Input(Bool())

  // Interrupts
  val interrupt_req = Input(UInt(16.W))
  val timer_interrupt = Input(Bool())

  // Machine trap enable (bidirectional with Core for double-fault)
  val mstatus_mte = Output(Bool())

  // Outputs
  val csr_read = Output(UInt(4.W))
  val interrupt_pending = Output(Bool())
  val mepc = Output(UInt(24.W))
  val mcause = Output(UInt(6.W))
}

/** RISC-V Machine-mode CSR file with interrupt controller.
  *
  * Contains all machine-mode CSRs (mstatus, mie, mip, mepc, mcause, misa),
  * cycle/time performance counters, and interrupt priority logic.
  *
  * CSR reads and writes are nibble-serialized: each CSR field is accessed
  * at a specific `counter` position matching its bit position in the
  * full 32-bit register.
  *
  * Interrupt handling:
  *   - Evaluates `(mip & mie) != 0` with `mstatus.mie` global enable
  *   - Timer interrupt (bit 16) has highest priority
  *   - External interrupts (bits 15:0) use priority encoder
  *   - Double-fault detection disables all interrupts
  */
class CsrFile extends Module {
  val io = IO(new CsrFileIO)

  // CSR write/set/clear helpers
  def csrUpdate(reg: UInt, data: UInt): Unit = {
    when(io.is_csr_write) { reg := data }
    .elsewhen(io.is_csr_set) { reg := reg | data }
    .elsewhen(io.is_csr_clear) { reg := reg & ~data }
  }
  def csrUpdateBit(reg: Bool, dataBit: Bool): Unit = {
    when(io.is_csr_write) { reg := dataBit }
    .elsewhen(io.is_csr_set && dataBit) { reg := true.B }
    .elsewhen(io.is_csr_clear && dataBit) { reg := false.B }
  }

  // Counters
  val cycle_counter = Module(new TinyQVCounter(7))
  cycle_counter.io.add := 1.B
  cycle_counter.io.counter := io.counter
  cycle_counter.io.set := 0.B
  cycle_counter.io.data_in := 0.U

  val cycle_count_wide = cycle_counter.io.data // 7 bits
  val cycle_cy = cycle_counter.io.cy_out

  val time_hi = RegInit(0.U(3.W))
  when(io.counter === 7.U && cycle_cy) {
    time_hi := time_hi + 1.U
  }

  val cycle_count_out_val = cycle_count_wide(3, 0)
  val time_count_out_val = Mux(io.counter === 7.U, Cat(time_hi, cycle_count_wide(3)), cycle_count_wide(6, 3))

  // Traps and interrupts
  val mcause = RegInit(0.U(6.W))
  val mcause_we = WireDefault(false.B)
  val mcause_next = WireDefault(16.U(6.W))

  when(mcause_we) {
    mcause := mcause_next
  }
  io.mcause := mcause

  val mepc = RegInit(0.U(24.W))
  val mstatus_mte = RegInit(true.B)
  io.mstatus_mte := mstatus_mte
  io.mepc := mepc

  val is_double_fault_r = RegInit(false.B)
  when(io.counter === 0.U) {
    is_double_fault_r := io.is_trap && !mstatus_mte
  }
  val is_double_fault = (io.counter === 0.U && io.is_trap && !mstatus_mte) || is_double_fault_r

  // MEPC
  when(io.counter <= 5.U) {
    val mepc_top = Wire(UInt(4.W))
    when(io.is_exception) { mepc_top := io.pc }
    .elsewhen(io.is_csr_write && io.imm_lo === CSR.MEPC) { mepc_top := io.data_rs1 }
    .otherwise { mepc_top := mepc(3, 0) }
    mepc := Cat(mepc_top, mepc(23, 4))
  }

  // Machine Trap Enable
  when(is_double_fault) {
    mstatus_mte := true.B
  }.elsewhen(io.counter === 0.U && io.is_exception) {
    mstatus_mte := false.B
  }.elsewhen(io.is_mret) {
    mstatus_mte := true.B
  }

  // Machine Status
  val mstatus_mie = RegInit(true.B)
  val mstatus_mpie = RegInit(false.B)

  when(is_double_fault) {
    mstatus_mie := true.B
    mstatus_mpie := false.B
  }.elsewhen(io.counter === 0.U && io.is_exception) {
    mstatus_mpie := mstatus_mie
    mstatus_mie := false.B
  }.elsewhen(io.is_mret) {
    mstatus_mie := mstatus_mpie
  }.elsewhen(io.imm_lo === CSR.MSTATUS) {
    when(io.counter === 0.U) {
      csrUpdateBit(mstatus_mie, io.data_rs1(3))
    }.elsewhen(io.counter === 1.U) {
      csrUpdateBit(mstatus_mpie, io.data_rs1(3))
    }
  }

  // Interrupt Enable registers
  val mie_16 = RegInit(false.B)
  val mie_15_12 = RegInit(0.U(4.W))
  val mie_11_8 = RegInit(0.U(4.W))
  val mie_7_4 = RegInit(0.U(4.W))
  val mie_3_0 = RegInit(0.U(4.W))

  val mip_reg = RegInit(0.U(2.W))
  val last_interrupt_req = RegInit(0.U(2.W))

  when (is_double_fault) {
    mie_16 := false.B
    mie_15_12 := 0.U
    mie_11_8 := 0.U
    mie_7_4 := 0.U
    mie_3_0 := 0.U
    mip_reg := 0.U
  } .elsewhen (io.counter === 1.U) {
    when (io.imm_lo === CSR.MIE) {
      csrUpdateBit(mie_16, io.data_rs1(3))
    }
  } .elsewhen (io.counter === 4.U) {
    when (io.imm_lo === CSR.MIE) {
      csrUpdate(mie_3_0, io.data_rs1)
    } .elsewhen (io.imm_lo === CSR.MIP) {
      csrUpdate(mip_reg, io.data_rs1(1, 0))
    }
  } .elsewhen (io.counter === 5.U) {
    last_interrupt_req := io.interrupt_req(1, 0)
    mip_reg := mip_reg | (io.interrupt_req(1, 0) & ~last_interrupt_req)
    when (io.imm_lo === CSR.MIE) {
      csrUpdate(mie_7_4, io.data_rs1)
    }
  } .elsewhen (io.counter === 6.U) {
    when (io.imm_lo === CSR.MIE) {
      csrUpdate(mie_11_8, io.data_rs1)
    }
  } .elsewhen (io.counter === 7.U) {
    when (io.imm_lo === CSR.MIE) {
      csrUpdate(mie_15_12, io.data_rs1)
    }
  }

  val mie = Cat(mie_16, mie_15_12, mie_11_8, mie_7_4, mie_3_0)
  val mip = Cat(io.timer_interrupt, io.interrupt_req(15, 2), mip_reg)
  io.interrupt_pending := mstatus_mie && (mip & mie).orR

  // MCause
  when(io.counter === 0.U) {
    when(io.is_interrupt) {
      mcause_we := true.B
      val masked_mip = mip & mie
      when(masked_mip(16)) {
        mcause_next := Cat(1.U(1.W), 7.U(5.W))
      }.otherwise {
        mcause_next := Cat(1.U(1.W), 16.U(5.W) + PriorityEncoder(masked_mip(15, 0)))
      }
    }.elsewhen(io.is_trap) {
      mcause_we := true.B
      mcause_next := Mux(io.imm === 0.U, 11.U, Mux(io.imm === 1.U, 3.U, 2.U))
    }
  }

  // CSR Read Mux
  io.csr_read := 0.U
  switch(io.imm_lo) {
    is(CSR.MSTATUS) { // mstatus
      io.csr_read := Mux(io.counter === 0.U, Cat(mstatus_mie, mstatus_mte, 0.U(2.W)),
                  Mux(io.counter === 1.U, Cat(mstatus_mpie, 0.U(3.W)), 0.U))
    }
    is(CSR.MISA) { // misa
      io.csr_read := Mux(io.counter === 0.U || io.counter === 7.U, "b0100".U,
                  Mux(io.counter === 1.U, "b0001".U, 0.U))
    }
    is(CSR.MIE) { // mie
      io.csr_read := Mux(io.counter === 1.U, Cat(mie(16), 0.U(3.W)),
                  Mux(io.counter === 4.U, mie(3, 0),
                  Mux(io.counter === 5.U, mie(7, 4),
                  Mux(io.counter === 6.U, mie(11, 8),
                  Mux(io.counter === 7.U, mie(15, 12), 0.U)))))
    }
    is(CSR.MEPC) { // mepc
      io.csr_read := Mux(io.counter <= 5.U, mepc(3, 0), 0.U)
    }
    is(CSR.MCAUSE) { // mcause
      io.csr_read := Mux(io.counter === 0.U, mcause(3, 0),
                  Mux(io.counter === 1.U, Cat(0.U(3.W), mcause(4)),
                  Mux(io.counter === 7.U, Cat(mcause(5), 0.U(3.W)), 0.U)))
    }
    is(CSR.MIP) { // mip
      io.csr_read := Mux(io.counter === 1.U, Cat(mip(16), 0.U(3.W)),
                  Mux(io.counter === 4.U, mip(3, 0),
                  Mux(io.counter === 5.U, mip(7, 4),
                  Mux(io.counter === 6.U, mip(11, 8),
                  Mux(io.counter === 7.U, mip(15, 12), 0.U)))))
    }
    is(CSR.CYCLE) { // cycle_count
      io.csr_read := cycle_count_out_val
    }
    is(CSR.TIME) { // time_count
      io.csr_read := time_count_out_val
    }
    is(CSR.MIMPID) { // mimpid
      io.csr_read := Mux(io.counter === 0.U, "b0011".U, 0.U)
    }
  }
}
