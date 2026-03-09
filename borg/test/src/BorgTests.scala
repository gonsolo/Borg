// SPDX-FileCopyrightText: © 2025-2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package borg

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import utest._
import ujson._
import os._

object BorgTests extends TestSuite {

  // --- Float conversion helpers ---

  def floatToBits(f: Float, config: FloatConfig): BigInt = {
    if (config == FloatConfig.FP32) {
      BigInt(java.lang.Float.floatToRawIntBits(f)) & 0xffffffffL
    } else {
      val bits = java.lang.Float.floatToRawIntBits(f)
      val sign = (bits >>> 31) << 15
      var exp = ((bits >>> 23) & 0xff) - 127 + 15
      var sig = (bits >>> 13) & 0x3ff
      if (exp <= 0) { exp = 0; sig = 0 }
      else if (exp >= 31) { exp = 31; sig = 0x3ff }
      BigInt(sign | (exp << 10) | sig)
    }
  }

  def bitsToFloat(b: BigInt, config: FloatConfig): Float = {
    if (config == FloatConfig.FP32) {
      java.lang.Float.intBitsToFloat(b.toInt)
    } else {
      val bits = b.toInt
      val sign = (bits >>> 15) << 31
      var exp = ((bits >>> 10) & 0x1f)
      var sig = (bits & 0x3ff) << 13
      if (exp == 0) { /* subnormal or zero */ }
      else if (exp == 31) { exp = 255 }
      else { exp = exp - 15 + 127 }
      java.lang.Float.intBitsToFloat(sign | (exp << 23) | sig)
    }
  }

  def isClose(actual: Float, expected: Float, config: FloatConfig): Boolean = {
    val relEps = if (config == FloatConfig.FP16) 1e-3f else 1e-6f
    val tolerance = math.max(relEps * math.abs(expected), relEps)
    math.abs(actual - expected) < tolerance
  }

  // --- Low-level bus helpers ---

  def writeAddr(borg: Borg, addr: Int, bits: BigInt): Unit = {
    borg.io.address.poke(addr.U)
    borg.io.data_in.poke(bits.U)
    borg.io.data_write_n.poke(2.U)
    borg.io.data_read_n.poke(3.U)
    borg.clock.step(1)
    borg.io.data_write_n.poke(3.U)
    borg.clock.step(1)
  }

  def readAddr(borg: Borg, addr: Int, config: FloatConfig): Float = {
    borg.io.address.poke(addr.U)
    borg.io.data_read_n.poke(2.U)
    borg.io.data_write_n.poke(3.U)
    borg.clock.step(1)
    val res = bitsToFloat(borg.io.data_out.peek().litValue, config)
    borg.io.data_read_n.poke(3.U)
    res
  }

  // --- Instruction encoding ---

  sealed trait Op
  case object ADD extends Op
  case object MUL extends Op
  case class FMA(rs3: Int) extends Op

  def encodeInstruction(config: FloatConfig, op: Op, rs1: Int, rs2: Int, rd: Int): BigInt = {
    if (config.totalBits >= 32) op match {
      case ADD     => BigInt((0x00 << 25) | (rs2 << 20) | (rs1 << 15) | (rd << 7))
      case MUL     => BigInt((0x4  << 25) | (rs2 << 20) | (rs1 << 15) | (rd << 7))
      case FMA(r3) => BigInt((r3  << 27) | (rs2 << 20) | (rs1 << 15) | (rd << 7) | (1 << 2))
    } else op match {
      case ADD     => BigInt((rs2 << 8) | (rs1 << 5) | (rd << 2))
      case MUL     => BigInt((1 << 13) | (rs2 << 8) | (rs1 << 5) | (rd << 2))
      case FMA(r3) => BigInt((2 << 13) | (r3 << 11) | (rs2 << 8) | (rs1 << 5) | (rd << 2))
    }
  }

  // --- Execution helpers ---

  def resetAndWait(borg: Borg): Unit = writeAddr(borg, 60, 2)

  def startAndWaitForHalt(borg: Borg): Unit = {
    writeAddr(borg, 60, 1)
    var status: BigInt = 0
    do {
      borg.io.address.poke(16.U)
      borg.io.data_read_n.poke(2.U)
      borg.io.data_write_n.poke(3.U)
      borg.clock.step(1)
      status = borg.io.data_out.peek().litValue
    } while ((status & 2) == 0)
    borg.io.data_read_n.poke(3.U)
  }

  def assertResult(actual: Float, expected: Float, config: FloatConfig, label: String): Unit = {
    println(f"Check: $label -> Actual: $actual%8.2f (Exp: $expected%8.2f)")
    if (expected.isInfinite || expected.isNaN) {
      utest.assert(actual.isInfinite || actual.isNaN)
    } else {
      utest.assert(isClose(actual, expected, config))
    }
  }

  // --- Test runners ---

  def runTest(borg: Borg, config: FloatConfig, op: Op, a: Float, b: Float, c: Float = 0f): Unit = {
    resetAndWait(borg)

    // Load operands
    writeAddr(borg, 0, floatToBits(a, config))
    writeAddr(borg, 4, floatToBits(b, config))
    val (instr, rdAddr, expected, label) = op match {
      case ADD =>
        (encodeInstruction(config, ADD, rs1 = 0, rs2 = 1, rd = 2),
          8, a + b, f"$a%8.2f + $b%8.2f")
      case MUL =>
        (encodeInstruction(config, MUL, rs1 = 0, rs2 = 1, rd = 2),
          8, a * b, f"$a%8.2f * $b%8.2f")
      case fma @ FMA(_) =>
        writeAddr(borg, 12, floatToBits(c, config))
        (encodeInstruction(config, FMA(rs3 = 3), rs1 = 0, rs2 = 1, rd = 2),
          8, a * b + c, f"$a%8.2f * $b%8.2f + $c%8.2f")
    }

    // Write instruction and halt
    writeAddr(borg, 32, instr)
    writeAddr(borg, 36, 0)

    startAndWaitForHalt(borg)
    assertResult(readAddr(borg, rdAddr, config), expected, config, label)
  }

  // --- Test suites ---

  val FP16_MAX = 65504f

  def runBatch(config: FloatConfig, op: Op, pairs: Seq[(Float, Float)]): Unit = {
    val tag = s"${config.getClass.getSimpleName.replace("$", "")} ${op.getClass.getSimpleName.replace("$", "")}"
    simulate(new Borg(config)) { borg =>
      println(s"\n--- Starting $tag Batch ---")
      pairs.foreach { case (a, b) =>
        op match {
          case ADD => runTest(borg, config, ADD, a, b)
          case MUL =>
            if (config != FloatConfig.FP16 || math.abs(a * b) <= FP16_MAX)
              runTest(borg, config, MUL, a, b)
          case FMA(_) =>
            Seq(1.0f, -0.5f).foreach { c =>
              if (config != FloatConfig.FP16 || math.abs(a * b + c) <= FP16_MAX)
                runTest(borg, config, FMA(3), a, b, c)
            }
        }
      }
      println(s"--- $tag Tests Passed ---\n")
    }
  }

  val tests = Tests {
    val projectRoot = sys.env.get("PROJECT_ROOT").map(os.Path(_)).getOrElse(os.pwd)
    val jsonFile = projectRoot / "data" / "test_cases.json"
    val data = ujson.read(os.read(jsonFile))
    val pairs = data("pairs").arr.map(p => (p(0).num.toFloat, p(1).num.toFloat)).toSeq

    utest.test("fp32_add_test")  { runBatch(FloatConfig.FP32, ADD, pairs) }
    utest.test("fp16_add_test")  { runBatch(FloatConfig.FP16, ADD, pairs) }
    utest.test("fp32_mul_test")  { runBatch(FloatConfig.FP32, MUL, pairs) }
    utest.test("fp16_mul_test")  { runBatch(FloatConfig.FP16, MUL, pairs) }
    utest.test("fp32_fma_test")  { runBatch(FloatConfig.FP32, FMA(3), pairs) }
    utest.test("fp16_fma_test")  { runBatch(FloatConfig.FP16, FMA(3), pairs) }
  }
}
