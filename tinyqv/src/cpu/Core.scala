// Copyright Michael Bell 2024
package tinyqv.cpu

import chisel3._
import chisel3.util._
import chisel3.experimental.ExtModule
import chisel3.experimental.fromIntToIntParam

class TinyQVCoreIO(regAddrBits: Int) extends Bundle {
  val clk = Input(Clock())
  val rstn = Input(Bool())

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

class TinyQVCore(numRegs: Int = 16, regAddrBits: Int = 4) extends ExtModule(Map(
  "NUM_REGS" -> numRegs,
  "REG_ADDR_BITS" -> regAddrBits
)) {
  override val desiredName = "tinyqv_core"
  
  val io = FlatIO(new TinyQVCoreIO(regAddrBits))
}

class TinyQVCoreSnippetIO(regAddrBits: Int) extends Bundle {
  val alu_op = Input(UInt(4.W))
  val is_system = Input(Bool())
  val imm_lo = Input(UInt(12.W))
  val is_interrupt = Input(Bool())

  val pc = Input(UInt(4.W))
  val imm = Input(UInt(4.W))
  val is_auipc = Input(Bool())
  val is_jal = Input(Bool())
  val is_jalr = Input(Bool())
  val is_alu_imm = Input(Bool())
  val is_alu_reg = Input(Bool())
  val is_branch = Input(Bool())
  val rs1 = Input(UInt(regAddrBits.W))
  val rs2 = Input(UInt(regAddrBits.W))
  val rd = Input(UInt(regAddrBits.W))
  val next_pc = Input(UInt(4.W))
  val counter = Input(UInt(3.W))

  // Batch 4 inputs
  val last_count = Input(Bool())
  val mem_op = Input(UInt(3.W))
  val is_lui = Input(Bool())
  val is_stall = Input(Bool())
  val is_store = Input(Bool())
  val is_load = Input(Bool())
  val load_data_ready = Input(Bool())
  val data_in = Input(UInt(4.W))
  val mstatus_mte = Input(Bool())
  val mstatus_mie = Input(Bool())
  val mstatus_mpie = Input(Bool())
  val mepc = Input(UInt(24.W))
  val mip = Input(UInt(17.W))
  val mie = Input(UInt(17.W))
  val mcause = Input(UInt(6.W))

  val is_shift = Output(Bool())
  val is_czero = Output(Bool())
  val is_priv = Output(Bool())
  val is_trap = Output(Bool())
  val is_exception = Output(Bool())
  val is_mret = Output(Bool())

  val is_csr = Output(Bool())
  val is_csr_write = Output(Bool())
  val is_csr_set = Output(Bool())
  val is_csr_clear = Output(Bool())
  val is_slt = Output(Bool())
  val alu_cycles = Output(Bool())

  val return_addr = Output(UInt(23.W))
  val data_rs1 = Output(UInt(4.W))
  val data_rs2 = Output(UInt(4.W))
  val debug_reg_wen = Output(Bool())
  val debug_rd = Output(UInt(4.W))

  // Batch 4 outputs
  val take_branch = Output(Bool())
  val branch = Output(Bool())
  val instr_complete = Output(Bool())
  val address_ready = Output(Bool())
  val cycle_out = Output(UInt(2.W))
  val load_done_out = Output(Bool())
  val addr_out = Output(UInt(28.W))
  val data_out = Output(UInt(4.W))
  val tmp_data_out = Output(UInt(32.W))
  val cycle_count_out = Output(UInt(4.W))
  val time_count_out = Output(UInt(4.W))
  val mcause_we = Output(Bool())
  val mcause_next = Output(UInt(6.W))
}

class TinyQVCoreSnippet(numRegs: Int = 16, regAddrBits: Int = 4) extends Module {
  val io = IO(new TinyQVCoreSnippetIO(regAddrBits))

  io.is_shift := io.alu_op(1, 0) === "b01".U
  io.is_czero := io.alu_op(3, 1) === "b111".U

  io.is_priv := io.is_system && (io.alu_op(2, 0) === "b000".U)
  io.is_trap := io.is_priv && (io.imm_lo(9, 8) === "b00".U)
  io.is_exception := io.is_trap || io.is_interrupt
  io.is_mret := io.is_priv && (io.imm_lo(9, 8) === "b11".U)

  io.is_csr := io.is_system && io.alu_op(1, 0) =/= "b00".U
  io.is_csr_write := io.is_csr && io.alu_op(1, 0) === "b01".U
  io.is_csr_set := io.is_csr && io.alu_op(1, 0) === "b10".U
  io.is_csr_clear := io.is_csr && io.alu_op(1, 0) === "b11".U

  io.is_slt := io.alu_op(3, 1) === "b001".U
  io.alu_cycles := io.is_slt || io.is_shift

  // Batch Registers
  val cycle = RegInit(0.U(2.W))
  val load_done = RegInit(false.B)
  val tmp_data = RegInit(0.U(32.W))
  val load_top_bit = RegInit(false.B)

  io.cycle_out := cycle
  io.load_done_out := load_done

  // Registers Module
  val registers = Module(new TinyQVRegisters(numRegs, regAddrBits))
  registers.clk := clock
  registers.rstn := !reset.asBool
  registers.counter := io.counter
  registers.rs1 := io.rs1
  registers.rs2 := io.rs2
  registers.rd := io.rd
  val data_rs1 = registers.data_rs1
  val data_rs2 = registers.data_rs2
  io.data_rs1 := data_rs1
  io.data_rs2 := data_rs2
  io.return_addr := registers.return_addr

  // Alu instance and state
  val cy = RegInit(false.B)
  val cmp = RegInit(false.B)
  val alu = Module(new TinyQVAlu())
  
  val alu_op_in = Mux(io.is_czero, "b0100".U, io.alu_op)
  val alu_a_in = Mux(io.is_czero, 0.U, Mux(io.is_auipc || io.is_jal, io.pc, data_rs1))
  val alu_b_in = Mux(io.is_alu_reg || io.is_branch, data_rs2, io.imm)

  alu.op := alu_op_in
  alu.a := alu_a_in
  alu.b := alu_b_in
  alu.cy_in := Mux(io.counter === 0.U, alu_op_in(1) || alu_op_in(3), cy)
  alu.cmp_in := Mux(io.counter === 0.U, 1.B, cmp)

  val alu_out = alu.d
  val cmp_out = alu.cmp_res

  cy := alu.cy_out
  cmp := cmp_out

  // Shifter instance and state
  val shift_amt = Reg(UInt(5.W))
  when(cycle === 0.U) {
    when(io.counter === 0.U) {
      shift_amt := Cat(shift_amt(4), Mux(io.is_alu_imm, io.imm, data_rs2))
    }.elsewhen(io.counter === 1.U) {
      shift_amt := Cat(Mux(io.is_alu_imm, io.imm(0), data_rs2(0)), shift_amt(3, 0))
    }
  }

  val shifter = Module(new TinyQVShifter())
  shifter.op := io.alu_op(3, 2)
  shifter.counter := io.counter
  shifter.a := tmp_data
  shifter.b := shift_amt
  val shift_out = shifter.d

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
    when(io.is_czero) {
      when(cycle === 1.U) { data_rd := tmp_data(3, 0) }
    }.elsewhen(io.is_slt && cycle === 1.U && io.counter === 0.U) {
      data_rd := cmp.asUInt
    }.elsewhen(io.is_shift && cycle === 1.U) {
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
  }.elsewhen(io.is_csr) {
    wr_en := true.B
    data_rd := csr_read
  }

  registers.wr_en := wr_en
  registers.data_rd := data_rd
  io.debug_reg_wen := wr_en
  io.debug_rd := data_rd

  // Cycle management
  when(io.last_count) {
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

  // Working temporary data
  val tmp_data_in = Wire(UInt(4.W))
  val tmp_data_shift = WireDefault(true.B)

  tmp_data_in := 0.U
  when(io.is_exception) {
    tmp_data_in := Mux(io.counter === 0.U, Cat(io.is_interrupt, io.is_trap && io.mstatus_mte, 0.U(2.W)), 0.U)
  }.elsewhen(io.is_shift || io.is_czero) {
    tmp_data_in := data_rs1
  }.elsewhen(cycle === 0.U) {
    tmp_data_in := alu_out
  }.otherwise {
    tmp_data_in := data_rs2
  }

  when(cycle === 1.U && io.is_shift) {
    tmp_data_shift := false.B
  }

  when(tmp_data_shift) {
    tmp_data := Cat(tmp_data_in, tmp_data(31, 4))
  }

  io.addr_out := Mux(io.is_mret, Cat(0.U(4.W), io.mepc), tmp_data(31, 4))

  // data_out (nibble for active store)
  val data_out_val = Wire(UInt(4.W))
  data_out_val := data_rs2
  when((io.mem_op(1, 0) === 0.U && io.counter > 1.U) || (io.mem_op(1, 0) === 1.U && io.counter > 3.U)) {
    data_out_val := 0.U
  }
  io.data_out := data_out_val
  io.tmp_data_out := tmp_data

  // Batch 3 logic (updated to use internal state)
  io.take_branch := io.last_count && (cmp_out ^ io.mem_op(0))
  io.branch := io.last_count && ((io.is_jal || io.is_jalr || io.is_trap || io.is_interrupt || io.is_mret) || (io.is_branch && io.take_branch))

  val instr_complete_store = Mux(tmp_data(31, 30) === 3.U, cycle(0), 1.B)
  
  io.instr_complete := false.B
  when(io.last_count) {
    when(io.is_auipc || io.is_lui || io.is_jal || io.is_jalr || io.is_system || io.is_stall || io.is_exception || io.is_branch) {
      io.instr_complete := true.B
    }.elsewhen(io.is_store) {
      io.instr_complete := instr_complete_store
    }.elsewhen(io.is_czero) {
      io.instr_complete := cycle(0) || (cmp_out ^ io.alu_op(0))
    }.elsewhen(io.is_alu_imm || io.is_alu_reg) {
      io.instr_complete := cycle === io.alu_cycles.asUInt
    }.elsewhen(load_done && io.is_load) {
      io.instr_complete := true.B
    }
  }

  io.address_ready := io.last_count && (cycle === 0.U) && (io.is_load || io.is_store)

  // Counters
  val cycle_counter = Module(new TinyQVCounter(7))
  cycle_counter.clk := clock
  cycle_counter.rstn := !reset.asBool
  cycle_counter.add := 1.B
  cycle_counter.counter := io.counter
  cycle_counter.set := 0.B
  cycle_counter.data_in := 0.U
  
  val cycle_count_wide = cycle_counter.data // 7 bits
  val cycle_cy = cycle_counter.cy_out

  val time_hi = RegInit(0.U(3.W))
  when(io.counter === 7.U && cycle_cy) {
    time_hi := time_hi + 1.U
  }

  // cycle_count = cycle_count_wide[3:0]
  // time_count = (counter == 7) ? {time_hi, cycle_count_wide[3]} : cycle_count_wide[6:3]
  val cycle_count_out_val = cycle_count_wide(3, 0)
  val time_count_out_val = Mux(io.counter === 7.U, Cat(time_hi, cycle_count_wide(3)), cycle_count_wide(6, 3))
  io.cycle_count_out := cycle_count_out_val
  io.time_count_out := time_count_out_val

  io.mcause_we := false.B
  io.mcause_next := 16.U

  when(io.counter === 0.U) {
    when(io.is_interrupt) {
      io.mcause_we := true.B
      val masked_mip = io.mip & io.mie
      when(masked_mip(16)) {
        io.mcause_next := Cat(1.U(1.W), 7.U(5.W))
      }.otherwise {
        io.mcause_next := Cat(1.U(1.W), 16.U(5.W) + PriorityEncoder(masked_mip(15, 0)))
      }
    }.elsewhen(io.is_trap) {
      io.mcause_we := true.B
      io.mcause_next := Mux(io.imm === 0.U, 11.U, Mux(io.imm === 1.U, 3.U, 2.U))
    }
  }

  switch(io.imm_lo) {
    is("h300".U) { // mstatus
      csr_read := Mux(io.counter === 0.U, Cat(io.mstatus_mie, io.mstatus_mte, 0.U(2.W)),
                  Mux(io.counter === 1.U, Cat(io.mstatus_mpie, 0.U(3.W)), 0.U))
    }
    is("h301".U) { // misa
      csr_read := Mux(io.counter === 0.U || io.counter === 7.U, "b0100".U,
                  Mux(io.counter === 1.U, "b0001".U, 0.U))
    }
    is("h304".U) { // mie
      csr_read := Mux(io.counter === 1.U, Cat(io.mie(16), 0.U(3.W)),
                  Mux(io.counter === 4.U, io.mie(3, 0),
                  Mux(io.counter === 5.U, io.mie(7, 4),
                  Mux(io.counter === 6.U, io.mie(11, 8),
                  Mux(io.counter === 7.U, io.mie(15, 12), 0.U)))))
    }
    is("h341".U) { // mepc
      csr_read := Mux(io.counter <= 5.U, io.mepc(3, 0), 0.U)
    }
    is("h342".U) { // mcause
      csr_read := Mux(io.counter === 0.U, io.mcause(3, 0),
                  Mux(io.counter === 1.U, Cat(0.U(3.W), io.mcause(4)),
                  Mux(io.counter === 7.U, Cat(io.mcause(5), 0.U(3.W)), 0.U)))
    }
    is("h344".U) { // mip
      csr_read := Mux(io.counter === 1.U, Cat(io.mip(16), 0.U(3.W)),
                  Mux(io.counter === 4.U, io.mip(3, 0),
                  Mux(io.counter === 5.U, io.mip(7, 4),
                  Mux(io.counter === 6.U, io.mip(11, 8),
                  Mux(io.counter === 7.U, io.mip(15, 12), 0.U)))))
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
