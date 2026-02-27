package borg

import chisel3.*
import chisel3.util.log2Up

class FNFromRecFNWrapperIO(inputWidth: UInt, outputWidth: UInt) extends Bundle {
  val in = Input(inputWidth)
  val out = Output(outputWidth)
}

// Wrapper module to emit the fNFromRecFN utility as a standalone component
class FNFromRecFNWrapper(expWidth: Int, sigWidth: Int) extends RawModule {
  // The input is the RecFN format: 1 (sign) + (expWidth + 2) + (sigWidth - 1)
  val recFNWidth = 1 + expWidth + 2 + sigWidth - 1
  // The output is the standard FN format: 1 (sign) + expWidth + (sigWidth - 1)
  val fnWidth = 1 + expWidth + sigWidth - 1

  val io = IO(new FNFromRecFNWrapperIO(UInt(recFNWidth.W), UInt(fnWidth.W)))

  // Instantiate the logic from the utility object
  io.out := fNFromRecFN(expWidth, sigWidth, io.in)
}
