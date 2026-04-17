// Copyright Andreas Wendleder 2025-2026
// SPDX-License-Identifier: Apache-2.0

package tinyqv

import tinyqv.cpu.{TinyQVCpu, TinyQVCore, TinyQVCounter, TinyQVRegisters,
  TinyQVAlu, TinyQVShifter, TinyQVTime, TinyQV,
  TinyQVDecode, QspiController}
import java.io.{PrintWriter, File}

object Main extends App {
  val outDir = "out/hardware/tinyqv/verilog"
  new File(outDir).mkdirs()

  val allAsicFiles = collection.mutable.Set[String]()

  Seq[() => chisel3.RawModule](
    () => new TinyQVCounter(4),
    () => new TinyQVCounter(5),
    () => new TinyQVCounter(7),
    () => new TinyQVRegisters(),
    () => new TinyQVAlu(),
    () => new TinyQVShifter(),
    () => new TinyQVCpu(16, 4),
    () => new TinyQVCore(),
    () => new TinyQVTime(),
    () => new TinyQV(),

    () => new TinyQVDecode(4),
    () => new QspiController(),
  ).foreach(gen => borg.Emit.emitAndCollect(gen(), outDir, allAsicFiles))

  val pw = new PrintWriter(new File(s"$outDir/asic_files.txt"))
  allAsicFiles.toList.sorted.foreach(pw.println)
  pw.close()

  // Parameterised counter wrapper (Verilog, not expressible in Chisel)
  val wrapper = """|module tinyqv_counter #(parameter OUTPUT_WIDTH=4) (
                   |    input clk,
                   |    input rstn,
                   |    input add,
                   |    input [2:0] counter,
                   |    input set,
                   |    input [3:0] data_in,
                   |    output [OUTPUT_WIDTH-1:0] data,
                   |    output cy_out
                   |);
                   |    generate
                   |        if (OUTPUT_WIDTH == 4) begin : gen_width_4
                   |            tinyqv_counter_4 i (.clk(clk), .rstn(rstn), .add(add), .counter(counter), .set(set), .data_in(data_in), .data(data), .cy_out(cy_out));
                   |        end else if (OUTPUT_WIDTH == 5) begin : gen_width_5
                   |            tinyqv_counter_5 i (.clk(clk), .rstn(rstn), .add(add), .counter(counter), .set(set), .data_in(data_in), .data(data), .cy_out(cy_out));
                   |        end else if (OUTPUT_WIDTH == 7) begin : gen_width_7
                   |            tinyqv_counter_7 i (.clk(clk), .rstn(rstn), .add(add), .counter(counter), .set(set), .data_in(data_in), .data(data), .cy_out(cy_out));
                   |        end else begin : gen_unsupported
                   |            initial $$error("Unsupported OUTPUT_WIDTH %d for tinyqv_counter", OUTPUT_WIDTH);
                   |        end
                   |    endgenerate
                   |endmodule
                   |""".stripMargin
  val pw2 = new PrintWriter(new File(s"$outDir/tinyqv_counter.v"))
  pw2.write(wrapper)
  pw2.close()
}
