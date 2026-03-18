// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class TinyQVCoreIO(regAddrBits: Int) extends Bundle {
  val imm = Input(UInt(4.W))
  val imm_lo = Input(UInt(12.W))

  val is_load = Input(Bool())
  val is_alu_imm = Input(Bool())
  val is_auipc = Input(Bool())
  val is_store = Input(Bool())
  val is_alu_reg = Input(Bool())
  val is_lui = Input(Bool())
  val is_branch = Input(Bool())
  val is_jalr = Input(Bool())
  val is_jal = Input(Bool())
  val is_system = Input(Bool())
  val is_interrupt = Input(Bool())
  val is_stall = Input(Bool())

  val alu_op = Input(UInt(4.W))
  val mem_op = Input(UInt(3.W))

  val rs1 = Input(UInt(regAddrBits.W))
  val rs2 = Input(UInt(regAddrBits.W))
  val rd = Input(UInt(regAddrBits.W))

  val counter = Input(UInt(3.W))
  val pc = Input(UInt(4.W))
  val next_pc = Input(UInt(4.W))
  val data_in = Input(UInt(4.W))
  val load_data_ready = Input(Bool())

  val data_out = Output(UInt(4.W))
  val addr_out = Output(UInt(28.W))
  val address_ready = Output(Bool())
  val instr_complete = Output(Bool())
  val branch = Output(Bool())

  val return_addr = Output(UInt(23.W))

  val interrupt_req = Input(UInt(16.W))
  val timer_interrupt = Input(Bool())
  val interrupt_pending = Output(Bool())

  val debug_reg_wen = Output(Bool())
  val debug_rd = Output(UInt(4.W))
}

class TinyQVCore(numRegs: Int = 16, regAddrBits: Int = 4) extends Module {
  
  val io = IO(new TinyQVCoreIO(regAddrBits))

  val is_shift = io.alu_op(1, 0) === "b01".U
  val is_czero = io.alu_op(3, 1) === "b111".U

  val is_priv = io.is_system && (io.alu_op(2, 0) === "b000".U)
  val is_trap = is_priv && (io.imm_lo(9, 8) === "b00".U)
  val is_exception = is_trap || io.is_interrupt
  val is_mret = is_priv && (io.imm_lo(9, 8) === "b11".U)

  val is_csr = io.is_system && io.alu_op(1, 0) =/= "b00".U
  val is_csr_write = is_csr && io.alu_op(1, 0) === "b01".U
  val is_csr_set = is_csr && io.alu_op(1, 0) === "b10".U
  val is_csr_clear = is_csr && io.alu_op(1, 0) === "b11".U

  // CSR write/set/clear helpers
  def csrUpdate(reg: UInt, data: UInt): Unit = {
    when(is_csr_write) { reg := data }
    .elsewhen(is_csr_set) { reg := reg | data }
    .elsewhen(is_csr_clear) { reg := reg & ~data }
  }
  def csrUpdateBit(reg: Bool, dataBit: Bool): Unit = {
    when(is_csr_write) { reg := dataBit }
    .elsewhen(is_csr_set && dataBit) { reg := true.B }
    .elsewhen(is_csr_clear && dataBit) { reg := false.B }
  }

  val is_slt = io.alu_op(3, 1) === "b001".U
  val alu_cycles = is_slt || is_shift

  val last_count = io.counter === 7.U

  // Batch Registers
  val cycle = RegInit(0.U(2.W))
  val load_done = RegInit(false.B)
  val tmp_data = RegInit(0.U(32.W))
  val load_top_bit = RegInit(false.B)

  // @doc:core-datapath
  // Registers Module
  val registers = Module(new TinyQVRegisters(numRegs, regAddrBits))
  registers.io.counter := io.counter
  registers.io.rs1 := io.rs1
  registers.io.rs2 := io.rs2
  registers.io.rd := io.rd
  val data_rs1 = registers.io.data_rs1
  val data_rs2 = registers.io.data_rs2
  io.return_addr := registers.io.return_addr

  // Alu instance and state
  val cy = RegInit(false.B)
  val cmp = RegInit(false.B)
  val alu = Module(new TinyQVAlu())
  
  val alu_op_in = Mux(is_czero, "b0100".U, io.alu_op)
  val alu_a_in = Mux(is_czero, 0.U, Mux(io.is_auipc || io.is_jal, io.pc, data_rs1))
  val alu_b_in = Mux(io.is_alu_reg || io.is_branch, data_rs2, io.imm)

  alu.io.op := alu_op_in
  alu.io.a := alu_a_in
  alu.io.b := alu_b_in
  alu.io.cy_in := Mux(io.counter === 0.U, alu_op_in(1) || alu_op_in(3), cy)
  alu.io.cmp_in := Mux(io.counter === 0.U, 1.B, cmp)

  val alu_out = alu.io.d
  val cmp_out = alu.io.cmp_res

  cy := alu.io.cy_out
  cmp := cmp_out
  // @doc:end

  // Shifter instance and state
  val shift_amt = RegInit(0.U(5.W))
  when(cycle === 0.U) {
    when(io.counter === 0.U) {
      shift_amt := Cat(shift_amt(4), Mux(io.is_alu_imm, io.imm, data_rs2))
    }.elsewhen(io.counter === 1.U) {
      shift_amt := Cat(Mux(io.is_alu_imm, io.imm(0), data_rs2(0)), shift_amt(3, 0))
    }
  }

  val shifter = Module(new TinyQVShifter())
  shifter.io.op := io.alu_op(3, 2)
  shifter.io.counter := io.counter
  shifter.io.a := tmp_data
  shifter.io.b := shift_amt
  val shift_out = shifter.io.d

  // load_top_bit logic
  val load_top_bit_next = Wire(Bool())
  load_top_bit_next := Mux(io.counter === 0.U, false.B, load_top_bit)
  when(io.is_load && io.load_data_ready &&
       ((io.mem_op === 1.U && io.counter === 3.U) ||
        (io.mem_op === 0.U && io.counter === 1.U))) {
    load_top_bit_next := io.data_in(3)
  }
  load_top_bit := load_top_bit_next

  // Selection logic for data_rd
  val wr_en = WireDefault(false.B)
  val data_rd = WireDefault(0.U(4.W))

  val csr_read = WireDefault(0.U(4.W))

  when(io.is_alu_imm || io.is_alu_reg || io.is_auipc) {
    wr_en := true.B
    when(is_czero) {
      when(cycle === 1.U) { data_rd := tmp_data(3, 0) }
    }.elsewhen(is_slt && cycle === 1.U && io.counter === 0.U) {
      data_rd := cmp.asUInt
    }.elsewhen(is_shift && cycle === 1.U) {
      data_rd := shift_out
    }.otherwise {
      data_rd := alu_out
    }
  }.elsewhen(io.is_load && io.load_data_ready) {
    wr_en := true.B
    when((io.mem_op(1, 0) === 0.U && io.counter > 1.U) || (io.mem_op(1, 0) === 1.U && io.counter > 3.U)) {
      data_rd := Fill(4, load_top_bit)
    }.otherwise {
      data_rd := io.data_in
    }
  }.elsewhen(io.is_lui) {
    wr_en := true.B
    data_rd := io.imm
  }.elsewhen(io.is_jal || io.is_jalr) {
    wr_en := true.B
    data_rd := io.next_pc
  }.elsewhen(is_csr) {
    wr_en := true.B
    data_rd := csr_read
  }

  registers.io.wr_en := wr_en
  registers.io.data_rd := data_rd
  io.debug_reg_wen := wr_en
  io.debug_rd := data_rd

  // Cycle management
  when(last_count) {
    when(io.instr_complete) {
      cycle := 0.U
    }.elsewhen(cycle =/= 3.U) {
      cycle := cycle + 1.U
    }
  }

  // load_done
  when(io.counter === 0.U) {
    load_done := io.load_data_ready && (cycle =/= 0.U)
  }

  val mepc = RegInit(0.U(24.W))
  val mstatus_mte = RegInit(true.B)

  // Working temporary data
  val tmp_data_in = Wire(UInt(4.W))
  val tmp_data_shift = WireDefault(true.B)

  tmp_data_in := 0.U
  when(is_exception) {
    tmp_data_in := Mux(io.counter === 0.U, Cat(io.is_interrupt, is_trap && mstatus_mte, 0.U(2.W)), 0.U)
  }.elsewhen(is_shift || is_czero) {
    tmp_data_in := data_rs1
  }.elsewhen(cycle === 0.U) {
    tmp_data_in := alu_out
  }.otherwise {
    tmp_data_in := data_rs2
  }

  when(cycle === 1.U && is_shift) {
    tmp_data_shift := false.B
  }

  when(tmp_data_shift) {
    tmp_data := Cat(tmp_data_in, tmp_data(31, 4))
  }

  io.addr_out := Mux(is_mret, Cat(0.U(4.W), mepc), tmp_data(31, 4))

  // data_out (nibble for active store)
  val data_out_val = Wire(UInt(4.W))
  data_out_val := data_rs2
  when((io.mem_op(1, 0) === 0.U && io.counter > 1.U) || (io.mem_op(1, 0) === 1.U && io.counter > 3.U)) {
    data_out_val := 0.U
  }
  io.data_out := data_out_val

  // Batch 3 logic (updated to use internal state)
  val take_branch = last_count && (cmp_out ^ io.mem_op(0))
  io.branch := last_count && ((io.is_jal || io.is_jalr || is_trap || io.is_interrupt || is_mret) || (io.is_branch && take_branch))

  val instr_complete_store = Mux(tmp_data(31, 30) === 3.U, cycle(0), 1.B)
  
  io.instr_complete := false.B
  when(last_count) {
    when(io.is_auipc || io.is_lui || io.is_jal || io.is_jalr || io.is_system || io.is_stall || is_exception || io.is_branch) {
      io.instr_complete := true.B
    }.elsewhen(io.is_store) {
      io.instr_complete := instr_complete_store
    }.elsewhen(is_czero) {
      io.instr_complete := cycle(0) || (cmp_out ^ io.alu_op(0))
    }.elsewhen(io.is_alu_imm || io.is_alu_reg) {
      io.instr_complete := cycle === alu_cycles.asUInt
    }.elsewhen(load_done && io.is_load) {
      io.instr_complete := true.B
    }
  }

  io.address_ready := last_count && (cycle === 0.U) && (io.is_load || io.is_store)

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

  // cycle_count = cycle_count_wide[3:0]
  // time_count = (counter == 7) ? {time_hi, cycle_count_wide[3]} : cycle_count_wide[6:3]
  val cycle_count_out_val = cycle_count_wide(3, 0)
  val time_count_out_val = Mux(io.counter === 7.U, Cat(time_hi, cycle_count_wide(3)), cycle_count_wide(6, 3))

  // Traps and interrupts
  val mcause = RegInit(0.U(6.W))
  val mcause_we = WireDefault(false.B)
  val mcause_next = WireDefault(16.U(6.W))

  when(mcause_we) {
    mcause := mcause_next
  }

  val is_double_fault_r = RegInit(false.B)
  when(io.counter === 0.U) {
    is_double_fault_r := is_trap && !mstatus_mte
  }
  val is_double_fault = (io.counter === 0.U && is_trap && !mstatus_mte) || is_double_fault_r

  when(io.counter <= 5.U) {
    val mepc_top = Wire(UInt(4.W))
    when(is_exception) { mepc_top := io.pc }
    .elsewhen(is_csr_write && io.imm_lo === "h341".U) { mepc_top := data_rs1 }
    .otherwise { mepc_top := mepc(3, 0) }
    mepc := Cat(mepc_top, mepc(23, 4))
  }

  when(is_double_fault) {
    mstatus_mte := true.B
  }.elsewhen(io.counter === 0.U && is_exception) {
    mstatus_mte := false.B
  }.elsewhen(is_mret) {
    mstatus_mte := true.B
  }

  val mstatus_mie = RegInit(true.B)
  val mstatus_mpie = RegInit(false.B)

  when(is_double_fault) {
    mstatus_mie := true.B
    mstatus_mpie := false.B
  }.elsewhen(io.counter === 0.U && is_exception) {
    mstatus_mpie := mstatus_mie
    mstatus_mie := false.B
  }.elsewhen(is_mret) {
    mstatus_mie := mstatus_mpie
  }.elsewhen(io.imm_lo === "h300".U) {
    when(io.counter === 0.U) {
      csrUpdateBit(mstatus_mie, data_rs1(3))
    }.elsewhen(io.counter === 1.U) {
      csrUpdateBit(mstatus_mpie, data_rs1(3))
    }
  }

  // Interrupt registers
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
    when (io.imm_lo === "h304".U) {
      csrUpdateBit(mie_16, data_rs1(3))
    }
  } .elsewhen (io.counter === 4.U) {
    when (io.imm_lo === "h304".U) {
      csrUpdate(mie_3_0, data_rs1)
    } .elsewhen (io.imm_lo === "h344".U) {
      csrUpdate(mip_reg, data_rs1(1, 0))
    }
  } .elsewhen (io.counter === 5.U) {
    last_interrupt_req := io.interrupt_req(1, 0)
    mip_reg := mip_reg | (io.interrupt_req(1, 0) & ~last_interrupt_req)
    when (io.imm_lo === "h304".U) {
      csrUpdate(mie_7_4, data_rs1)
    }
  } .elsewhen (io.counter === 6.U) {
    when (io.imm_lo === "h304".U) {
      csrUpdate(mie_11_8, data_rs1)
    }
  } .elsewhen (io.counter === 7.U) {
    when (io.imm_lo === "h304".U) {
      csrUpdate(mie_15_12, data_rs1)
    }
  }

  val mie = Cat(mie_16, mie_15_12, mie_11_8, mie_7_4, mie_3_0)
  val mip = Cat(io.timer_interrupt, io.interrupt_req(15, 2), mip_reg)
  io.interrupt_pending := mstatus_mie && (mip & mie).orR

  when(io.counter === 0.U) {
    when(io.is_interrupt) {
      mcause_we := true.B
      val masked_mip = mip & mie
      when(masked_mip(16)) {
        mcause_next := Cat(1.U(1.W), 7.U(5.W))
      }.otherwise {
        mcause_next := Cat(1.U(1.W), 16.U(5.W) + PriorityEncoder(masked_mip(15, 0)))
      }
    }.elsewhen(is_trap) {
      mcause_we := true.B
      mcause_next := Mux(io.imm === 0.U, 11.U, Mux(io.imm === 1.U, 3.U, 2.U))
    }
  }

  switch(io.imm_lo) {
    is("h300".U) { // mstatus
      csr_read := Mux(io.counter === 0.U, Cat(mstatus_mie, mstatus_mte, 0.U(2.W)),
                  Mux(io.counter === 1.U, Cat(mstatus_mpie, 0.U(3.W)), 0.U))
    }
    is("h301".U) { // misa
      csr_read := Mux(io.counter === 0.U || io.counter === 7.U, "b0100".U,
                  Mux(io.counter === 1.U, "b0001".U, 0.U))
    }
    is("h304".U) { // mie
      csr_read := Mux(io.counter === 1.U, Cat(mie(16), 0.U(3.W)),
                  Mux(io.counter === 4.U, mie(3, 0),
                  Mux(io.counter === 5.U, mie(7, 4),
                  Mux(io.counter === 6.U, mie(11, 8),
                  Mux(io.counter === 7.U, mie(15, 12), 0.U)))))
    }
    is("h341".U) { // mepc
      csr_read := Mux(io.counter <= 5.U, mepc(3, 0), 0.U)
    }
    is("h342".U) { // mcause
      csr_read := Mux(io.counter === 0.U, mcause(3, 0),
                  Mux(io.counter === 1.U, Cat(0.U(3.W), mcause(4)),
                  Mux(io.counter === 7.U, Cat(mcause(5), 0.U(3.W)), 0.U)))
    }
    is("h344".U) { // mip
      csr_read := Mux(io.counter === 1.U, Cat(mip(16), 0.U(3.W)),
                  Mux(io.counter === 4.U, mip(3, 0),
                  Mux(io.counter === 5.U, mip(7, 4),
                  Mux(io.counter === 6.U, mip(11, 8),
                  Mux(io.counter === 7.U, mip(15, 12), 0.U)))))
    }
    is("hC00".U) { // cycle_count
      csr_read := cycle_count_out_val
    }
    is("hC01".U) { // time_count
      csr_read := time_count_out_val
    }
    is("hF13".U) { // mimpid
      csr_read := Mux(io.counter === 0.U, "b0011".U, 0.U)
    }
  }
}
