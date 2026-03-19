// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Nibble-serial multiplier-accumulator for MulAddRecFN.
// Replaces the combinational sigWidth × sigWidth multiplier with a
// multi-cycle version that processes 4 bits of operand A per cycle.

package borg

import chisel3._
import chisel3.util._

/** Nibble-serial multiplier: computes (a * b) + c over multiple cycles.
  *
  * Processes 4 bits of `a` per cycle against the full `b`, accumulating
  * partial products. Total cycles = ceil(sigWidth / 4) + 1 (for the add).
  *
  * @param sigWidth significand width (e.g. 11 for FP16)
  */
class NibbleSerialMulAddIO(sigWidth: Int) extends Bundle {
  val mulAddWidth = sigWidth * 2 + 1
  val a      = Input(UInt(sigWidth.W))
  val b      = Input(UInt(sigWidth.W))
  val c      = Input(UInt((sigWidth * 2).W))
  val valid  = Input(Bool())
  val result = Output(UInt(mulAddWidth.W))
  val ready  = Output(Bool())
}

class NibbleSerialMulAdd(val sigWidth: Int) extends Module {
  val mulAddWidth = sigWidth * 2 + 1

  val io = IO(new NibbleSerialMulAddIO(sigWidth))

  // Number of nibble cycles needed for the multiply
  val numNibbles = (sigWidth + 3) / 4  // ceil(sigWidth / 4)

  // State
  val idle :: multiplying :: adding :: Nil = Enum(3)
  val state = RegInit(idle)
  val nibbleCount = RegInit(0.U(log2Ceil(numNibbles + 1).W))
  val accumulator = RegInit(0.U((sigWidth * 2 + 4).W))
  val aReg = RegInit(0.U(sigWidth.W))
  val bReg = RegInit(0.U(sigWidth.W))
  val cReg = RegInit(0.U((sigWidth * 2).W))

  io.ready := state === idle
  io.result := accumulator(mulAddWidth - 1, 0)

  switch(state) {
    is(idle) {
      when(io.valid) {
        aReg := io.a
        bReg := io.b
        cReg := io.c
        accumulator := 0.U
        nibbleCount := 0.U
        state := multiplying
      }
    }
    is(multiplying) {
      // Extract current nibble from aReg
      val shift = nibbleCount << 2
      val nibble = (aReg >> shift)(3, 0)

      // Partial product: nibble * bReg, shifted left by (nibbleCount * 4)
      val partialProduct = (nibble * bReg).asUInt
      val shifted = (partialProduct << shift)(sigWidth * 2 + 3, 0)

      accumulator := accumulator + shifted

      when(nibbleCount === (numNibbles - 1).U) {
        state := adding
      }.otherwise {
        nibbleCount := nibbleCount + 1.U
      }
    }
    is(adding) {
      // Add the aligned C operand to the product
      accumulator := accumulator +& cReg
      state := idle
    }
  }
}
