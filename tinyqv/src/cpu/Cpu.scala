// Copyright Michael Bell 2024
// CERN-OHL-S-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class TinyQVCpu(numRegs: Int = 16, regAddrBits: Int = 4) extends RawModule {
  override val desiredName = "tinyqv_cpu"
  val io = IO(new TinyQVCpuIO)

  def setRange(u: UInt, hi: Int, lo: Int, data: UInt): UInt = {
    val width = hi - lo + 1
    val mask = ((1L << width) - 1).U(32.W) << lo
    (u & ~mask) | ((data << lo) & mask)
  }

  withClockAndReset(io.clk, !io.rstn) {
    // Decoder interface
    val decoder = Module(new TinyQVDecode)
    val instr = Wire(UInt(32.W))
    decoder.instr := instr
    
    val imm_de = decoder.imm
    val is_load_de = decoder.is_load
    val is_alu_imm_de = decoder.is_alu_imm
    val is_auipc_de = decoder.is_auipc
    val is_store_de = decoder.is_store
    val is_alu_reg_de = decoder.is_alu_reg
    val is_lui_de = decoder.is_lui
    val is_branch_de = decoder.is_branch
    val is_jalr_de = decoder.is_jalr
    val is_jal_de = decoder.is_jal
    val is_ret_de = decoder.is_ret
    val is_system_de = decoder.is_system
    val instr_len_de = decoder.instr_len
    val alu_op_de = decoder.alu_op
    val mem_op_de = decoder.mem_op
    val rs1_de = decoder.rs1
    val rs2_de = decoder.rs2
    val rd_de = decoder.rd
    val additional_mem_ops_de = decoder.additional_mem_ops
    val mem_op_increment_reg_de = decoder.mem_op_increment_reg

    // Pipeline Registers
    val imm = Reg(UInt(32.W))
    val is_load = RegInit(false.B)
    val is_alu_imm = RegInit(false.B)
    val is_auipc = RegInit(false.B)
    val is_store = RegInit(false.B)
    val is_alu_reg = RegInit(false.B)
    val is_lui = RegInit(false.B)
    val is_branch = RegInit(false.B)
    val is_jalr = RegInit(false.B)
    val is_jal = RegInit(false.B)
    val is_system = RegInit(false.B)
    val instr_len = RegInit(2.U(2.W))
    val alu_op = RegInit(0.U(4.W))
    val mem_op = Reg(UInt(3.W))
    val rs1 = Reg(UInt(4.W))
    val rs2 = Reg(UInt(4.W))
    val rd = Reg(UInt(4.W))
    val additional_mem_ops = RegInit(0.U(3.W))
    val addr_offset = RegInit(0.U(2.W))
    val mem_op_increment_reg = Reg(Bool())

    val interrupt_core = RegInit(false.B)
    val instr_valid = RegInit(false.B)

    // Core Instantiation
    val core = Module(new TinyQVCore(numRegs, regAddrBits))
    core.io.clk := io.clk
    core.io.rstn := io.rstn
    val counter_hi = RegInit(0.U(3.W))
    val counter = Cat(counter_hi, 0.U(2.W))
    
    core.io.counter := counter_hi
    core.io.imm := MuxLookup(counter_hi, 0.U(4.W))( (0 until 8).map(i => i.U -> imm(i*4+3, i*4)) )
    core.io.imm_lo := imm(11, 0)
    
    val no_write_in_progress = RegInit(true.B)
    val stall_core = !instr_valid || ((is_store || is_load) && !no_write_in_progress)
    
    core.io.is_load := is_load && instr_valid && no_write_in_progress
    core.io.is_alu_imm := is_alu_imm && instr_valid
    core.io.is_auipc := is_auipc && instr_valid
    core.io.is_store := is_store && instr_valid && no_write_in_progress
    core.io.is_alu_reg := is_alu_reg && instr_valid
    core.io.is_lui := is_lui && instr_valid
    core.io.is_branch := is_branch && instr_valid
    core.io.is_jalr := is_jalr && instr_valid
    core.io.is_jal := is_jal && instr_valid
    core.io.is_system := is_system && instr_valid
    core.io.is_interrupt := interrupt_core
    core.io.is_stall := stall_core && !interrupt_core
    
    core.io.alu_op := alu_op
    core.io.mem_op := mem_op
    core.io.rs1 := rs1
    core.io.rs2 := rs2
    core.io.rd := rd
    core.io.interrupt_req := io.interrupt_req
    
    val pc = Reg(UInt(32.W))
    core.io.pc := MuxLookup(counter_hi, 0.U(4.W))( (0 until 8).map(i => i.U -> pc(i*4+3, i*4)) )
    
    val next_pc_for_core = Wire(UInt(32.W))
    core.io.next_pc := MuxLookup(counter_hi, 0.U(4.W))( (0 until 8).map(i => i.U -> next_pc_for_core(i*4+3, i*4)) )
    
    val timers = Module(new TinyQVTime)
    timers.clk := io.clk
    timers.rstn := io.rstn
    timers.time_pulse := io.time_pulse
    timers.counter := counter_hi
    
    val is_timer_addr = io.data_addr(27, 4) === 0xFFFFF0.U && !io.data_addr(3)
    val timer_data = timers.data_out
    
    core.io.data_in := Mux(is_timer_addr, timer_data, MuxLookup(counter_hi, 0.U(4.W))( (0 until 8).map(i => i.U -> io.data_in(i*4+3, i*4)) ))
    
    val data_ready_core = Wire(Bool())
    core.io.load_data_ready := data_ready_core
    
    val data_out_slice = core.io.data_out
    val addr_out = core.io.addr_out
    val address_ready = core.io.address_ready
    val instr_complete_core = core.io.instr_complete
    val branch = core.io.branch
    val return_addr = core.io.return_addr
    val interrupt_pending = core.io.interrupt_pending
    
    val any_additional_mem_ops = additional_mem_ops =/= 0.U
    val instr_complete = instr_complete_core && !stall_core && !any_additional_mem_ops

    // Instruction Fetch Logic
    val instr_data = RegInit(VecInit(Seq.fill(4)(0.U(16.W))))
    val instr_data_start = RegInit(0.U(21.W))
    val instr_fetch_running = RegInit(false.B)
    val pc_offset = RegInit(0.U(2.W))
    val instr_write_offset = RegInit(0.U(3.W))
    val was_early_branch = RegInit(false.B)
    
    val next_pc_offset = Cat(0.B, pc_offset) + Cat(0.B, instr_len)
    val instr_avail_len = Mux(was_early_branch, 0.U(4.W), instr_write_offset.asUInt - Mux(instr_valid, next_pc_offset, Cat(0.B, pc_offset)))
    
    val early_branch = WireDefault(false.B)
    val is_ret = WireDefault(false.B)

    // Pipeline Logic
    when(any_additional_mem_ops && instr_complete_core && !stall_core) {
      rs2 := rs2 + mem_op_increment_reg.asUInt
      rd := rd + 1.U
      additional_mem_ops := additional_mem_ops - 1.U
      addr_offset := addr_offset + 1.U
    }.elsewhen(instr_complete_core && !any_additional_mem_ops && no_write_in_progress && interrupt_pending) {
      instr_valid := false.B
      interrupt_core := true.B
    }.elsewhen((counter_hi === 7.U && !instr_valid) || instr_complete || branch) {
      interrupt_core := false.B
      when(Cat(0.U(2.W), instr_len_de) <= instr_avail_len) {
        imm := imm_de
        is_load := is_load_de
        is_alu_imm := is_alu_imm_de
        is_auipc := is_auipc_de
        is_store := is_store_de
        is_alu_reg := is_alu_reg_de
        is_lui := is_lui_de
        is_branch := is_branch_de
        is_jalr := is_jalr_de
        is_jal := is_jal_de
        is_system := is_system_de
        instr_len := instr_len_de
        alu_op := alu_op_de
        mem_op := mem_op_de
        rs1 := rs1_de
        rs2 := rs2_de
        rd := rd_de
        additional_mem_ops := additional_mem_ops_de
        addr_offset := 0.U
        mem_op_increment_reg := mem_op_increment_reg_de
        instr_valid := !branch && !is_ret_de
        
        early_branch := is_jal_de && !branch
        is_ret := is_ret_de && !branch
      }.otherwise {
        instr_valid := false.B
      }
    }
    
    when(counter_hi === 7.U) {
      was_early_branch := early_branch && !branch
    }

    // Data ready sync logic
    val data_ready_ext = io.data_ready && io.data_addr(27, 26) =/= 3.U
    val data_ready_latch = RegInit(false.B)
    val data_ready_sync = RegInit(false.B)
    
    counter_hi := counter_hi + 1.U
    when(counter_hi === 0.U) {
      data_ready_latch := false.B
      data_ready_sync := data_ready_ext || data_ready_latch || is_timer_addr
    }.otherwise {
      when(!data_ready_latch) { data_ready_latch := data_ready_ext }
      when(address_ready) { data_ready_latch := false.B }
    }
    data_ready_core := Mux(counter_hi === 0.U, data_ready_ext || data_ready_latch || is_timer_addr, data_ready_sync)

    // Data address and control logic
    val data_addr_reg = RegInit(0.U(28.W))
    io.data_addr := data_addr_reg
    when(address_ready) {
      data_addr_reg := Cat(addr_out(27, 4), (addr_out(3, 2) + addr_offset)(1, 0), addr_out(1, 0))
    }

    val load_started = RegInit(false.B)
    val data_write_n_reg = RegInit(3.U(2.W))
    io.data_write_n := data_write_n_reg
    val data_read_n_reg = RegInit(3.U(2.W))
    io.data_read_n := data_read_n_reg
    val data_continue_reg = RegInit(false.B)
    io.data_continue := data_continue_reg

    when(is_store && address_ready) {
      data_write_n_reg := mem_op(1, 0)
      no_write_in_progress := addr_out(27)
      data_continue_reg := any_additional_mem_ops
    }.elsewhen(data_ready_ext) {
      data_write_n_reg := 3.U
      when(counter_hi === 7.U) { no_write_in_progress := true.B }
    }.elsewhen(counter_hi === 7.U) {
      when(data_ready_sync) {
        data_write_n_reg := 3.U
        no_write_in_progress := true.B
      }.otherwise {
        no_write_in_progress := data_write_n_reg === 3.U
      }
    }
    
    when(is_load && !instr_complete) {
      when(address_ready) {
        data_read_n_reg := mem_op(1, 0)
        load_started := true.B
        data_continue_reg := any_additional_mem_ops
      }
      when(data_ready_ext && load_started) {
        data_read_n_reg := 3.U
      }
    }.otherwise {
      data_read_n_reg := 3.U
      load_started := false.B
    }
    
    val data_out_reg = RegInit(0.U(32.W))
    io.data_out := data_out_reg
    when(is_store && no_write_in_progress) {
      data_out_reg := MuxLookup(counter_hi, data_out_reg)( (0 until 8).map { i =>
        i.U -> setRange(data_out_reg, i*4+3, i*4, data_out_slice)
      })
    }

    // Instruction Fetch Wiring
    val next_pc = Cat(instr_data_start, 0.U(3.W)) + Cat(0.U(20.W), next_pc_offset, 0.B)
    val pc_wrap = next_pc_offset(2) && instr_complete
    val next_instr_write_offset = instr_write_offset.asUInt + (io.instr_ready && instr_fetch_running).asUInt - Mux(pc_wrap, 4.U, 0.U)
    val next_instr_stall = next_instr_write_offset === Cat(1.B, pc_offset).asUInt
    
    val early_branch_addr = pc(23, 1) + imm(23, 1)

    when(branch) {
       when(is_branch && instr_valid) {
         instr_data_start := early_branch_addr(22, 2)
         instr_write_offset := Cat(0.B, early_branch_addr(1, 0))
         pc_offset := early_branch_addr(1, 0)
       }.otherwise {
         instr_data_start := addr_out(23, 3)
         instr_write_offset := Cat(0.B, addr_out(2, 1))
         pc_offset := addr_out(2, 1)
       }
       instr_fetch_running := was_early_branch
    }.elsewhen(is_ret) {
       instr_data_start := return_addr(22, 2)
       instr_write_offset := Cat(0.B, return_addr(1, 0))
       pc_offset := return_addr(1, 0)
       instr_fetch_running := false.B
    }.otherwise {
       when(early_branch) { instr_fetch_running := false.B }
       .elsewhen(io.instr_fetch_started) { instr_fetch_running := true.B }
       .elsewhen(io.instr_fetch_stopped) { instr_fetch_running := false.B }
       
       instr_write_offset := next_instr_write_offset
       when(instr_complete) {
         pc_offset := next_pc_offset(1, 0)
         instr_data_start := next_pc(23, 3)
       }
       when(io.instr_ready && instr_fetch_running) {
         instr_data(instr_write_offset(1, 0)) := io.instr_data_in
       }
    }

    io.instr_fetch_restart := !instr_fetch_running && (!branch || was_early_branch) && !early_branch && !is_ret
    io.instr_fetch_stall := next_instr_stall
    io.instr_addr := Mux(was_early_branch, early_branch_addr, Cat(instr_data_start, 0.U(2.W)) + Cat(0.U(20.W), instr_write_offset))

    val pc_offset_hi = pc_offset + 1.U
    val next_pc_offset_hi = next_pc_offset(1, 0) + 1.U
    instr := Mux(instr_valid, Cat(instr_data(next_pc_offset_hi), instr_data(next_pc_offset(1, 0))), Cat(instr_data(pc_offset_hi), instr_data(pc_offset)))
    pc := Cat(0.U(8.W), instr_data_start, pc_offset, 0.B)
    next_pc_for_core := Cat(0.U(8.W), next_pc)

    // Timer wiring
    timers.set_mtime := is_timer_addr && io.data_write_n =/= 3.U && !io.data_addr(2)
    timers.set_mtimecmp := is_timer_addr && io.data_write_n =/= 3.U && io.data_addr(2)
    timers.data_in := data_out_slice
    timers.read_mtimecmp := io.data_addr(2)
    core.io.timer_interrupt := timers.timer_interrupt

    // Debugging
    io.data_read_complete := is_load && instr_complete_core && !stall_core
    io.debug_instr_complete := instr_complete
    io.debug_instr_valid := instr_valid
    io.debug_interrupt_pending := interrupt_pending
    io.debug_branch := branch
    io.debug_early_branch := early_branch
    io.debug_ret := is_ret
    io.debug_reg_wen := core.io.debug_reg_wen
    io.debug_counter_0 := counter_hi === 0.U
    io.debug_rd := core.io.debug_rd
  }
}
