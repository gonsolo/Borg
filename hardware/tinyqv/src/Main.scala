// Copyright Andreas Wendleder 2025-2026
// SPDX-License-Identifier: Apache-2.0

package tinyqv

import circt.stage.ChiselStage
import tinyqv.cpu.{TinyQVCpu, TinyQVCore, TinyQVCounter, TinyQVRegisters, TinyQVAlu, TinyQVShifter, TinyQVTime, TinyQVQspiFlash, TinyQV, LatchRegN, LatchRegP, LatchReg32N, LatchReg32P, TinyQVMemCtrl, TinyQVDecode, QspiController}
import java.io.PrintWriter
import java.io.File

object Main extends App {

  val outDir = "out/hardware/tinyqv/verilog"
  new File(outDir).mkdirs()

  val argsArray = Array("--target-dir", outDir)
  val firtoolOptsArray = Array("--split-verilog", "--lowering-options=disallowLocalVariables,disallowPackedArrays,noAlwaysComb", "--disable-all-randomization", "--strip-debug-info")

  var allAsicFiles = Set[String]()

  def emitAndCollect(gen: => chisel3.RawModule) = {
    ChiselStage.emitSystemVerilogFile(gen, argsArray, firtoolOptsArray)
    val filelistPath = s"$outDir/filelist.f"
    if (new java.io.File(filelistPath).exists()) {
      val lines = scala.io.Source.fromFile(filelistPath).getLines().toList
      allAsicFiles ++= lines.map(f => s"../$outDir/$f")
    }
  }

  emitAndCollect(new TinyQVCounter(4))
  emitAndCollect(new TinyQVCounter(5))
  emitAndCollect(new TinyQVCounter(7))
  emitAndCollect(new TinyQVRegisters())
  emitAndCollect(new TinyQVAlu())
  emitAndCollect(new TinyQVShifter())

  emitAndCollect(new TinyQVCpu(16, 4))
  emitAndCollect(new TinyQVCore())
  emitAndCollect(new TinyQVTime())
  emitAndCollect(new TinyQVQspiFlash(2, 24))
  emitAndCollect(new TinyQV())
  emitAndCollect(new LatchRegN(8))
  emitAndCollect(new LatchRegP(8))
  emitAndCollect(new LatchReg32N())
  emitAndCollect(new LatchReg32P())
  emitAndCollect(new TinyQVMemCtrl())
  emitAndCollect(new TinyQVDecode(4))
  emitAndCollect(new QspiController())
  // Removed explicit emit of UartRx and UartTx as they are now emitted through PeriUart

  val asicFilesPath = s"$outDir/asic_files.txt"
  val asicPw = new PrintWriter(new File(asicFilesPath))
  allAsicFiles.toList.sorted.foreach(asicPw.println)
  asicPw.close()

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
