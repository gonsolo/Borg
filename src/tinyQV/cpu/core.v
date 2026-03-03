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
      .io_csr_read(csr_read),
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
      .io_time_count_out(time_count)
  );

  reg [23:0] mepc;

  reg mstatus_mte;  // Trap enable - this is non-standard, but allows trapping without
                    //               double fault while interrupts are disabled.
  reg mstatus_mie;  // Interrupt enable
  reg mstatus_mpie;  // Prior interrupt enable (whether interrupts were enabled on entry to trap)

  reg [3:0] csr_read;

  ///////// Register file /////////

  wire [3:0] data_rs1;
  wire [3:0] data_rs2;
  wire [3:0] data_rd;
  wire wr_en;


  wire [3:0] cycle_count;
  wire [3:0] time_count;


  ///////// Traps and interrupts /////////    

  reg [17:16] mip_reg;
  wire [16:0] mip = {timer_interrupt, interrupt_req[15:2], mip_reg};
  reg [16:0] mie;

  reg [5:0] mcause;
  always @(posedge clk) begin
    if (!rstn) mcause <= 0;
    else if (counter == 0) begin
      if (is_interrupt) begin
        mcause[5] <= 1;
        casez (mip[16:0] & mie[16:0])
          17'b1????????????????: mcause[4:0] <= 5'h07;
          17'b0???????????????1: mcause[4:0] <= 5'h10;
          17'b0??????????????10: mcause[4:0] <= 5'h11;
          17'b0?????????????100: mcause[4:0] <= 5'h12;
          17'b0????????????1000: mcause[4:0] <= 5'h13;
          17'b0???????????10000: mcause[4:0] <= 5'h14;
          17'b0??????????100000: mcause[4:0] <= 5'h15;
          17'b0?????????1000000: mcause[4:0] <= 5'h16;
          17'b0????????10000000: mcause[4:0] <= 5'h17;
          17'b0???????100000000: mcause[4:0] <= 5'h18;
          17'b0??????1000000000: mcause[4:0] <= 5'h19;
          17'b0?????10000000000: mcause[4:0] <= 5'h1a;
          17'b0????100000000000: mcause[4:0] <= 5'h1b;
          17'b0???1000000000000: mcause[4:0] <= 5'h1c;
          17'b0??10000000000000: mcause[4:0] <= 5'h1d;
          17'b0?100000000000000: mcause[4:0] <= 5'h1e;
          17'b01000000000000000: mcause[4:0] <= 5'h1f;
          default: mcause[4:0] <= 5'h10;  // Shouldn't be possible
        endcase
      end else if (is_trap) begin
        if (imm == 4'b0000) mcause <= 6'd11;  // ECALL
        else if (imm == 4'b0001) mcause <= 6'd3;  // EBREAK
        else mcause <= 6'd2;  // Illegal instruction
      end
    end
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

  // Interrupts 1 and 0 trigger on rising edge
  reg [1:0] last_interrupt_req;

  always @(posedge clk) begin
    if (!rstn || is_double_fault) begin
      mie <= 0;
      mip_reg <= 0;
    end else if (counter == 1) begin
      if (imm_lo == 12'h304) begin
        if (is_csr_write) mie[16] <= data_rs1[3];
        else if (is_csr_set) mie[16] <= mie[16] | data_rs1[3];
        else if (is_csr_clear) mie[16] <= mie[16] & ~data_rs1[3];
      end
    end else if (counter == 4) begin
      if (imm_lo == 12'h304) begin
        if (is_csr_write) mie[3:0] <= data_rs1;
        else if (is_csr_set) mie[3:0] <= mie[3:0] | data_rs1;
        else if (is_csr_clear) mie[3:0] <= mie[3:0] & ~data_rs1;
      end else if (imm_lo == 12'h344) begin
        if (is_csr_write) mip_reg <= data_rs1[1:0];
        else if (is_csr_set) mip_reg <= mip_reg | data_rs1[1:0];
        else if (is_csr_clear) mip_reg <= mip_reg & ~data_rs1[1:0];
      end
    end else if (counter == 5) begin
      last_interrupt_req <= interrupt_req[1:0];
      mip_reg <= mip_reg | (interrupt_req[1:0] & ~last_interrupt_req);
      if (imm_lo == 12'h304) begin
        if (is_csr_write) mie[7:4] <= data_rs1;
        else if (is_csr_set) mie[7:4] <= mie[7:4] | data_rs1;
        else if (is_csr_clear) mie[7:4] <= mie[7:4] & ~data_rs1;
      end
    end else if (counter == 6) begin
      if (imm_lo == 12'h304) begin
        if (is_csr_write) mie[11:8] <= data_rs1;
        else if (is_csr_set) mie[11:8] <= mie[11:8] | data_rs1;
        else if (is_csr_clear) mie[11:8] <= mie[11:8] & ~data_rs1;
      end
    end else if (counter == 7) begin
      if (imm_lo == 12'h304) begin
        if (is_csr_write) mie[15:12] <= data_rs1;
        else if (is_csr_set) mie[15:12] <= mie[15:12] | data_rs1;
        else if (is_csr_clear) mie[15:12] <= mie[15:12] & ~data_rs1;
      end
    end
  end

  assign interrupt_pending = mstatus_mie && |(mip & mie);


  ///////// CSRs /////////    

  always @(*) begin
    case (imm_lo)
      // mstatus
      12'h300:
      csr_read = (counter == 0) ? {mstatus_mie, mstatus_mte, 2'b00} :
                                (counter == 1) ? {mstatus_mpie, 3'b000} :
                                                 4'b0000;

      // misa
      12'h301:
      csr_read = (counter == 0 || counter == 7) ? 4'b0100 :  // C, 32
      (counter == 1) ? 4'b0001 :  // E
      4'b0000;

      // mie
      12'h304:
      csr_read = (counter == 1) ? {mie[16], 3'b000} :
                                (counter == 4) ? mie[3:0] :
                                (counter == 5) ? mie[7:4] :
                                (counter == 6) ? mie[11:8] :
                                (counter == 7) ? mie[15:12] : 4'b0000;

      // mepc
      12'h341: csr_read = (counter <= 5) ? mepc[3:0] : 4'b0000;

      // mcause
      12'h342:
      csr_read = (counter == 0) ? mcause[3:0] :
                                (counter == 1) ? {3'b000, mcause[4]} :
                                (counter == 7) ? {mcause[5], 3'b000} :
                                                 4'b0000;

      // mip
      12'h344:
      csr_read = (counter == 1) ? {mip[16], 3'b000} :
                                (counter == 4) ? mip[3:0] :
                                (counter == 5) ? mip[7:4] :
                                (counter == 6) ? mip[11:8] :
                                (counter == 7) ? mip[15:12] : 4'b0000;

      // Cycle and instruction counters
      12'hC00: csr_read = cycle_count;

      // Time based on cycle
      12'hC01: csr_read = time_count;

      // mimpid (3)
      12'hF13: csr_read = (counter == 0) ? 4'b0011 : 4'b0000;
      default: csr_read = 4'b0000;
    endcase
  end


  ////////// Debug //////////
  assign debug_reg_wen = wr_en;
  assign debug_rd = data_rd;

endmodule
