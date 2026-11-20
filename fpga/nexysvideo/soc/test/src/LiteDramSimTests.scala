// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

package nexysvideo

import chisel3.{Bool, Bundle, Clock, ExtModule, Flipped, IO, Input, Module, Output, UInt, fromBooleanToLiteral, fromIntToLiteral, fromIntToWidth, fromLongToLiteral, fromStringToLiteral}
import chisel3.util.HasExtModulePath
import chisel3.simulator.EphemeralSimulator._
import borg.GpuMemIO
import soc.{GpuMemMig, LiteDramMigUi}
import utest._

/** `make litedram-sim`'s core (its path in LITEDRAM_SIM_V): LiteDram.yml with LiteDRAM's DDR3 model
  * in place of the PHY. */
class litedram_sim extends ExtModule with HasExtModulePath {
  addPath(sys.env("LITEDRAM_SIM_V"))
  val clk        = IO(Input(Clock()))
  val sim_trace  = IO(Input(Bool()))
  val init_done  = IO(Output(Bool()))
  val init_error = IO(Output(Bool()))
  val user_clk   = IO(Output(Clock()))
  val user_rst   = IO(Output(Bool()))

  val user_port_native_0_cmd_valid   = IO(Input(Bool()))
  val user_port_native_0_cmd_ready   = IO(Output(Bool()))
  val user_port_native_0_cmd_we      = IO(Input(Bool()))
  val user_port_native_0_cmd_addr    = IO(Input(UInt(25.W)))
  val user_port_native_0_wdata_valid = IO(Input(Bool()))
  val user_port_native_0_wdata_ready = IO(Output(Bool()))
  val user_port_native_0_wdata_data  = IO(Input(UInt(128.W)))
  val user_port_native_0_wdata_we    = IO(Input(UInt(16.W)))
  val user_port_native_0_rdata_valid = IO(Output(Bool()))
  val user_port_native_0_rdata_ready = IO(Input(Bool()))
  val user_port_native_0_rdata_data  = IO(Output(UInt(128.W)))

  val wb_ctrl_adr   = IO(Input(UInt(30.W)))
  val wb_ctrl_dat_w = IO(Input(UInt(32.W)))
  val wb_ctrl_dat_r = IO(Output(UInt(32.W)))
  val wb_ctrl_sel   = IO(Input(UInt(4.W)))
  val wb_ctrl_cyc   = IO(Input(Bool()))
  val wb_ctrl_stb   = IO(Input(Bool()))
  val wb_ctrl_we    = IO(Input(Bool()))
  val wb_ctrl_cti   = IO(Input(UInt(3.W)))
  val wb_ctrl_bte   = IO(Input(UInt(2.W)))
  val wb_ctrl_ack   = IO(Output(Bool()))
  val wb_ctrl_err   = IO(Output(Bool()))
}

class LiteDramSimRigIO extends Bundle {
  val gpu      = Flipped(new GpuMemIO)
  val wbAdr    = Input(UInt(30.W))
  val wbDatW   = Input(UInt(32.W))
  val wbStb    = Input(Bool())
  val wbWe     = Input(Bool())
  val wbAck    = Output(Bool())
  val wbDatR   = Output(UInt(32.W))
  val userRst  = Output(Bool())
  val initDone = Output(Bool())
}

/** GpuMemMig through the adapter on the generated controller and its DDR3 model, one clock. */
class LiteDramSimRig extends Module {
  val io   = IO(new LiteDramSimRigIO)
  val core = Module(new litedram_sim)
  val g    = Module(new GpuMemMig)
  val a    = Module(new LiteDramMigUi)
  core.clk       := clock
  core.sim_trace := false.B
  g.io.uiClock := clock
  g.io.uiReset := reset.asBool || core.user_rst
  g.io.gpu <> io.gpu
  a.io.ui <> g.io.mig
  a.io.initDone := core.init_done

  core.user_port_native_0_cmd_valid   := a.io.dram.cmd.valid
  a.io.dram.cmd.ready                 := core.user_port_native_0_cmd_ready
  core.user_port_native_0_cmd_we      := a.io.dram.cmd.bits.we
  core.user_port_native_0_cmd_addr    := a.io.dram.cmd.bits.addr
  core.user_port_native_0_wdata_valid := a.io.dram.wdata.valid
  a.io.dram.wdata.ready               := core.user_port_native_0_wdata_ready
  core.user_port_native_0_wdata_data  := a.io.dram.wdata.bits.data
  core.user_port_native_0_wdata_we    := a.io.dram.wdata.bits.we
  a.io.dram.rdata.valid               := core.user_port_native_0_rdata_valid
  core.user_port_native_0_rdata_ready := a.io.dram.rdata.ready
  a.io.dram.rdata.bits                := core.user_port_native_0_rdata_data

  core.wb_ctrl_adr   := io.wbAdr
  core.wb_ctrl_dat_w := io.wbDatW
  core.wb_ctrl_sel   := "hf".U
  core.wb_ctrl_cyc   := io.wbStb
  core.wb_ctrl_stb   := io.wbStb
  core.wb_ctrl_we    := io.wbWe
  core.wb_ctrl_cti   := 0.U
  core.wb_ctrl_bte   := 0.U
  io.wbAck    := core.wb_ctrl_ack
  io.wbDatR   := core.wb_ctrl_dat_r
  io.userRst  := core.user_rst
  io.initDone := core.init_done
}

object LiteDramSimTests extends TestSuite {
  val tests = Tests {
    test("gpu_bursts_and_reads_through_litedram") {
      simulate(new LiteDramSimRig) { dut =>
        val rnd = new scala.util.Random(17)
        val ref = scala.collection.mutable.Map[Long, Int]().withDefaultValue(0)
        val g   = dut.io.gpu
        dut.reset.poke(true.B); dut.clock.step(4); dut.reset.poke(false.B)
        dut.io.wbStb.poke(false.B)
        var n = 0
        while (dut.io.userRst.peek().litToBoolean && n < 1000) { dut.clock.step(); n += 1 }
        assert(!dut.io.userRst.peek().litToBoolean)
        def wbWrite(word: Int, v: Int): Unit = {
          dut.io.wbAdr.poke(word.U); dut.io.wbDatW.poke(v.U); dut.io.wbWe.poke(true.B); dut.io.wbStb.poke(true.B)
          var t = 0
          while (!dut.io.wbAck.peek().litToBoolean) { dut.clock.step(); t += 1; assert(t < 100) }
          dut.clock.step(); dut.io.wbStb.poke(false.B); dut.clock.step()
        }
        assert(!dut.io.initDone.peek().litToBoolean)
        wbWrite(0x1000 >> 2, 0x01)  // sdram_dfii_control: SEL (also its reset value), the controller drives the DRAM
        wbWrite(0x0000 >> 2, 1)     // ddrctrl_init_done
        assert(dut.io.initDone.peek().litToBoolean)

        def await(): Unit = { var t = 0; while (!g.ready.peek().litToBoolean) { dut.clock.step(); t += 1; assert(t < 20000) } }
        var checked = 0
        for (_ <- 0 until 120) {
          val a = 0x1000L + 2 * rnd.nextInt(2048)
          if (rnd.nextInt(3) == 0) {
            val len   = 1 + rnd.nextInt(64)
            val beats = Seq.fill(len)(rnd.nextInt(1 << 16))
            for ((h, i) <- beats.zipWithIndex) { ref(a + 2 * i) = h & 0xff; ref(a + 2 * i + 1) = h >> 8 }
            var i = 0
            var t = 0
            var busy = true
            while (busy) {
              t += 1; assert(t < 20000)
              g.wr.poke(true.B); g.req.poke(false.B); g.addr.poke(a.U); g.wlen.poke(len.U); g.wdata.poke(beats(i).U)
              val acc = g.waccept.peek().litToBoolean
              busy = !g.ready.peek().litToBoolean
              dut.clock.step()
              if (acc) i += 1
            }
          } else {
            g.wr.poke(false.B); g.req.poke(true.B); g.addr.poke(a.U)
            await()
            val want = (0 until 4).map(b => ref(a + b).toLong << (8 * b)).sum
            assert(g.data.peek().litValue.toLong == want)
            checked += 1
            dut.clock.step()
          }
          g.wr.poke(false.B); g.req.poke(false.B)
          dut.clock.step()
        }
        assert(checked > 60)
      }
    }
  }
}
