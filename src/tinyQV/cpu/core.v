/* TinyQV: A RISC-V core designed to use minimal area.
  
   This core module takes decoded instructions and produces output data
 */

module tinyqv_core #(
    parameter NUM_REGS = 16,
    parameter REG_ADDR_BITS = 4
) (
    input clk,
    input rstn,

    input [ 3:0] imm,
    input [11:0] imm_lo,

    input is_load,
    input is_alu_imm,
    input is_auipc,
    input is_store,
    input is_alu_reg,
    input is_lui,
    input is_branch,
    input is_jalr,
    input is_jal,
    input is_system,
    input is_interrupt,
    input is_stall,

    input [3:0] alu_op,  // See tinyqv_alu for format
    input [2:0] mem_op,

    input [REG_ADDR_BITS-1:0] rs1,
    input [REG_ADDR_BITS-1:0] rs2,
    input [REG_ADDR_BITS-1:0] rd,

    input [2:0] counter,  // Sub cycle counter, must increment on every clock
    input [3:0] pc,
    input [3:0] next_pc,
    input [3:0] data_in,
    input load_data_ready,

    output [3:0] data_out,  // Data for the active store instruction
    output [27:0] addr_out,
    output address_ready,  // The addr_out holds the address for the active load/store instruction
    output instr_complete,  // The current instruction will complete this clock, so the instruction may be updated.
    // If no new instruction is available all a NOOP should be issued, which will complete in 1 cycle.
    output branch,  // addr_out holds the address to branch to

    output [23:1] return_addr,  // On count 7 this is the low 24 bits of x1

    input  [15:0] interrupt_req,
    input         timer_interrupt,
    output        interrupt_pending,

    output       debug_reg_wen,
    output [3:0] debug_rd
);

  // Forward declarations
  wire last_count = (counter == 7);
  wire [1:0] cycle;
  wire load_done;
  wire [31:0] tmp_data;

  wire is_shift, is_czero, is_priv, is_trap, is_exception, is_mret;
  wire is_csr, is_csr_write, is_csr_set, is_csr_clear;
  wire is_slt, alu_cycles;
  wire take_branch;

  wire mcause_we;
  wire [5:0] mcause_next;

  TinyQVCoreSnippet i_snippet (
      .clock(clk),
      .reset(!rstn),
      .io_alu_op(alu_op),
      .io_is_system(is_system),
      .io_imm_lo(imm_lo),
      .io_is_interrupt(is_interrupt),
      .io_pc(pc),
      .io_imm(imm),
      .io_is_auipc(is_auipc),
      .io_is_jal(is_jal),
      .io_is_jalr(is_jalr),
      .io_is_alu_imm(is_alu_imm),
      .io_is_alu_reg(is_alu_reg),
      .io_is_branch(is_branch),
      .io_rs1(rs1),
      .io_rs2(rs2),
      .io_rd(rd),
      .io_next_pc(next_pc),
      .io_return_addr(return_addr),
      .io_counter(counter),
      .io_last_count(last_count),
      .io_mem_op(mem_op),
      .io_is_lui(is_lui),
      .io_is_stall(is_stall),
      .io_is_store(is_store),
      .io_is_load(is_load),
      .io_load_data_ready(load_data_ready),
      .io_data_in(data_in),
      .io_mstatus_mte(mstatus_mte),
      .io_mepc(mepc),
      .io_data_rs1(data_rs1),
      .io_data_rs2(data_rs2),
      .io_debug_reg_wen(wr_en),
      .io_debug_rd(data_rd),
      .io_is_shift(is_shift),
      .io_is_czero(is_czero),
      .io_is_priv(is_priv),
      .io_is_trap(is_trap),
      .io_is_exception(is_exception),
      .io_is_mret(is_mret),
      .io_is_csr(is_csr),
      .io_is_csr_write(is_csr_write),
      .io_is_csr_set(is_csr_set),
      .io_is_csr_clear(is_csr_clear),
      .io_is_slt(is_slt),
      .io_alu_cycles(alu_cycles),
      .io_take_branch(take_branch),
      .io_branch(branch),
      .io_instr_complete(instr_complete),
      .io_address_ready(address_ready),
      .io_cycle_out(cycle),
      .io_load_done_out(load_done),
      .io_addr_out(addr_out),
      .io_data_out(data_out),
      .io_tmp_data_out(tmp_data),
      .io_cycle_count_out(cycle_count),
      .io_time_count_out(time_count),
      .io_interrupt_req(interrupt_req),
      .io_timer_interrupt(timer_interrupt),
      .io_is_double_fault(is_double_fault),
      .io_mip_out(mip),
      .io_mie_out(mie),
      .io_mcause_we(mcause_we),
      .io_mcause_next(mcause_next),
      .io_mstatus_mie(mstatus_mie),
      .io_mstatus_mpie(mstatus_mpie),
      .io_mcause(mcause)
  );

  reg [23:0] mepc;

  reg mstatus_mte;  // Trap enable - this is non-standard, but allows trapping without
                    //               double fault while interrupts are disabled.
  reg mstatus_mie;  // Interrupt enable
  reg mstatus_mpie;  // Prior interrupt enable (whether interrupts were enabled on entry to trap)

  ///////// Register file /////////

  wire [3:0] data_rs1;
  wire [3:0] data_rs2;
  wire [3:0] data_rd;
  wire wr_en;


  wire [3:0] cycle_count;
  wire [3:0] time_count;


  ///////// Traps and interrupts /////////    

  wire [16:0] mip;
  wire [16:0] mie;

  reg [5:0] mcause;
  always @(posedge clk) begin
    if (!rstn) mcause <= 0;
    else if (mcause_we) mcause <= mcause_next;
  end

  // mstatus_mte is cleared while handling a trap, so need to latch double fault on counter==0.
  reg is_double_fault_r;
  always @(posedge clk) begin
    if (counter == 0) is_double_fault_r <= is_trap && !mstatus_mte;
  end
  wire is_double_fault = (counter == 0 && is_trap && !mstatus_mte) || is_double_fault_r;

  always @(posedge clk) begin
    if (counter <= 5) begin
      mepc[23:20] <= (!rstn)                             ? 4'b0000 :
                           (is_exception)                      ? pc : 
                           (is_csr_write && imm_lo == 12'h341) ? data_rs1 :
                                                                 mepc[3:0];
      mepc[19:0] <= mepc[23:4];
    end
  end

  // There is a circular dependency at reset between mstatus_mte and is_double_fault.
  // Break this by using an async reset to ensure mstatus_mte is set regardless of
  // the value of is_double_fault.
  /* verilator lint_off SYNCASYNCNET */
  always @(posedge clk or negedge rstn) begin
    if (!rstn) begin
      mstatus_mte <= 1;
    end else if (is_double_fault) begin
      mstatus_mte <= 1;
    end else if (counter == 0 && (is_exception)) begin
      mstatus_mte <= 0;
    end else if (is_mret) begin
      mstatus_mte <= 1;
    end
  end
  /* verilator lint_on SYNCASYNCNET */

  always @(posedge clk) begin
    if (!rstn || is_double_fault) begin
      mstatus_mie  <= 1;
      mstatus_mpie <= 0;
    end else if (counter == 0 && (is_exception)) begin
      mstatus_mpie <= mstatus_mie;
      mstatus_mie  <= 0;
    end else if (is_mret) begin
      mstatus_mie <= mstatus_mpie;
    end else if (imm_lo == 12'h300) begin
      if (counter == 0) begin
        if (is_csr_write) mstatus_mie <= data_rs1[3];
        else if (is_csr_set && data_rs1[3]) mstatus_mie <= 1;
        else if (is_csr_clear && data_rs1[3]) mstatus_mie <= 0;
      end else if (counter == 1) begin
        if (is_csr_write) mstatus_mpie <= data_rs1[3];
        else if (is_csr_set && data_rs1[3]) mstatus_mpie <= 1;
        else if (is_csr_clear && data_rs1[3]) mstatus_mpie <= 0;
      end
    end
  end

  // The CSR operations for mie and mip are now generated inside TinyQVCoreSnippet

  assign interrupt_pending = mstatus_mie && |(mip & mie);


  ///////// CSRs /////////    




  ////////// Debug //////////
  assign debug_reg_wen = wr_en;
  assign debug_rd = data_rd;

endmodule
