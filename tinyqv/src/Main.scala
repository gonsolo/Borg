// Copyright Andreas Wendleder 2025-2026
// CERN-OHL-S-2.0

package tinyqv

import circt.stage.ChiselStage
import tinyqv.cpu.TinyQVCounter
import java.io.PrintWriter
import java.io.File

object Main extends App {

  val outDir = "out/tinyqv/verilog"
  new File(outDir).mkdirs()

  val argsArray = Array("--target-dir", outDir)
  val firtoolOptsArray = Array("--split-verilog", "--lowering-options=disallowLocalVariables,disallowPackedArrays", "--disable-all-randomization", "--strip-debug-info")

  ChiselStage.emitSystemVerilogFile(new TinyQVCounter(4), argsArray, firtoolOptsArray)
  ChiselStage.emitSystemVerilogFile(new TinyQVCounter(5), argsArray, firtoolOptsArray)
  ChiselStage.emitSystemVerilogFile(new TinyQVCounter(7), argsArray, firtoolOptsArray)

  // Write a wrapper for tinyqv_counter that selects the correct version based on OUTPUT_WIDTH
  val wrapper = """
module tinyqv_counter #(parameter OUTPUT_WIDTH=4) (
    input clk,
    input rstn,
    input add,
    input [2:0] counter,
    input set,
    input [3:0] data_in,
    output [OUTPUT_WIDTH-1:0] data,
    output cy_out
);
    generate
        if (OUTPUT_WIDTH == 4) begin : gen_width_4
            tinyqv_counter_4 i (.clk(clk), .rstn(rstn), .add(add), .counter(counter), .set(set), .data_in(data_in), .data(data), .cy_out(cy_out));
        end else if (OUTPUT_WIDTH == 5) begin : gen_width_5
            tinyqv_counter_5 i (.clk(clk), .rstn(rstn), .add(add), .counter(counter), .set(set), .data_in(data_in), .data(data), .cy_out(cy_out));
        end else if (OUTPUT_WIDTH == 7) begin : gen_width_7
            tinyqv_counter_7 i (.clk(clk), .rstn(rstn), .add(add), .counter(counter), .set(set), .data_in(data_in), .data(data), .cy_out(cy_out));
        end else begin : gen_unsupported
             // Fallback or error
             initial $error("Unsupported OUTPUT_WIDTH %d for tinyqv_counter", OUTPUT_WIDTH);
        end
    endgenerate
endmodule
"""
  val pw = new PrintWriter(new File(s"$outDir/tinyqv_counter.v"))
  pw.write(wrapper)
  pw.close()
}
