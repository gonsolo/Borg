// Copyright Michael Bell 2024
// CERN-OHL-S-2.0

package tinyqv.cpu

import chisel3._
import chisel3.util._

class TinyQVTime extends RawModule {
  override val desiredName = "tinyQV_time"

  val clk = IO(Input(Clock()))
  val rstn = IO(Input(Bool()))

  val time_pulse = IO(Input(Bool()))
  val set_mtime = IO(Input(Bool()))
  val set_mtimecmp = IO(Input(Bool()))
  val data_in = IO(Input(UInt(4.W)))
  val counter = IO(Input(UInt(3.W)))

  val read_mtimecmp = IO(Input(Bool()))
  val data_out = IO(Output(UInt(4.W)))

  val timer_interrupt = IO(Output(Bool()))

  withClockAndReset(clk, !rstn) {
    val mtime_out = Wire(UInt(4.W))
    val time_pulse_r = RegInit(false.B)

    val i_mtime = Module(new TinyQVCounter(4))
    i_mtime.clk := clk
    i_mtime.rstn := rstn
    i_mtime.add := time_pulse | time_pulse_r
    i_mtime.counter := counter
    i_mtime.set := set_mtime
    i_mtime.data_in := data_in
    mtime_out := i_mtime.data

    // mtimecmp implementation
    // The Verilog uses a shift register for bits [31:4] and a separate Reg for [3:0]
    // mtimecmp[31:4] <= {mtimecmp[3:0], mtimecmp[31:8]}
    
    val mtimecmp = RegInit(0.U(32.W))
    
    // Shift logic for mtimecmp
    // Verilog: always @(posedge clk) mtimecmp[31:4] <= {mtimecmp[3:0], mtimecmp[31:8]};
    // This is essentially shifting 4 bits around.
    
    val next_mtimecmp_31_4 = Cat(mtimecmp(3, 0), mtimecmp(31, 8))
    
    // Verilog for [3:0]:
    // if (set_mtimecmp) mtimecmp[3:0] <= data_in;
    // else mtimecmp[3:0] <= mtimecmp[7:4];
    
    val next_mtimecmp_3_0 = Mux(set_mtimecmp, data_in, mtimecmp(7, 4))
    
    mtimecmp := Cat(next_mtimecmp_31_4, next_mtimecmp_3_0)

    // Comparison logic
    val cy = RegInit(false.B)
    // Verilog: wire [4:0] comparison = {1'b0, mtime_out} + {1'b0, ~mtimecmp[7:4]} + {4'b0, cy};
    // Note: mtimecmp[7:4] is the part that was just shifted from [3:0] or [11:8] in the prev cycle?
    // In Verilog: always @(posedge clk) mtimecmp[31:4] <= reg_buf; where reg_buf is {mtimecmp[3:0], mtimecmp[31:8]}
    // So mtimecmp[7:4] in the comparison (combinational) uses the value AFTER the non-blocking assignment?
    // No, it uses the current value.
    
    val comparison = mtime_out +& (~mtimecmp(7, 4)).asUInt + cy.asUInt
    
    // always @(posedge clk) cy <= (counter == 3'd7) ? 1'b1 : comparison[4];
    cy := Mux(counter === 7.U, true.B, comparison(4))
    
    // timer_interrupt logic
    val timer_interrupt_reg = RegInit(false.B)
    // always @(posedge clk) if (counter == 3'd7) timer_interrupt <= (comparison[3:2] == 2'b0);
    // Comparison is mtime - mtimecmp.
    // Verilog says: interrupt if 0 <= time - timecmp < 2^30.
    // comparison[3:2] == 0 at the end of 32-bit addition (counter=7) means bits [31:30] are 0.
    when (counter === 7.U) {
      timer_interrupt_reg := (comparison(3, 2) === 0.U)
    }
    timer_interrupt := timer_interrupt_reg
    
    // time_pulse_r logic
    // always @(posedge clk) begin
    //     if (counter == 0) time_pulse_r <= 0;
    //     else time_pulse_r <= time_pulse | time_pulse_r;
    // end
    when (counter === 0.U) {
      time_pulse_r := false.B
    } .otherwise {
      time_pulse_r := time_pulse | time_pulse_r
    }

    data_out := Mux(read_mtimecmp, mtimecmp(7, 4), mtime_out)
  }
}
