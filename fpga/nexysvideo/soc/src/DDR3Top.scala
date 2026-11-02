// SPDX-FileCopyrightText: © 2026 Ziad Mubarak
package nexysvideo

import chisel3._
import chisel3.util._

import chisel3.experimental.{Analog, attach}

import _root_.circt.stage.ChiselStage
import soc.Emit

import memory.MigBackend

class DDR3Top extends RawModule {
    val clk = IO(Input(Clock()))
    val cpu_resetn = IO(Input(Bool()))
    val led = IO(Output(UInt(4.W)))

    val ddr3_dq      = IO(Analog(16.W)) // data, both directions
    val ddr3_dqs_p   = IO(Analog(2.W)) // data strobes, both directions
    val ddr3_dqs_n   = IO(Analog(2.W)) // wire pair, opposite of ddr3_dqs_p 
    val ddr3_addr    = IO(Output(UInt(15.W)))
    val ddr3_ba      = IO(Output(UInt(3.W))) // bank address
    val ddr3_ras_n   = IO(Output(Bool()))
    val ddr3_cas_n   = IO(Output(Bool()))
    val ddr3_we_n    = IO(Output(Bool()))
    val ddr3_reset_n = IO(Output(Bool()))
    val ddr3_ck_p    = IO(Output(UInt(1.W))) // DDR3 clock
    val ddr3_ck_n    = IO(Output(UInt(1.W)))
    val ddr3_cke     = IO(Output(UInt(1.W))) // clock enable
    val ddr3_dm      = IO(Output(UInt(2.W))) // data mask
    val ddr3_odt     = IO(Output(UInt(1.W)))

    val clkGen = Module(new Clocks)
    clkGen.io.clk100 := clk
    clkGen.io.rst := !cpu_resetn

    val mig = Module(new mig_7series_0)
    mig.sys_clk_i := clkGen.io.clk200
    mig.sys_rst := clkGen.io.locked

    mig.app_sr_req := false.B
    mig.app_ref_req := false.B
    mig.app_zq_req := false.B

    // DDR3 pins: board pin <- MIG pin 
    ddr3_addr    := mig.ddr3_addr
    ddr3_ba      := mig.ddr3_ba
    ddr3_ras_n   := mig.ddr3_ras_n
    ddr3_cas_n   := mig.ddr3_cas_n
    ddr3_we_n    := mig.ddr3_we_n
    ddr3_reset_n := mig.ddr3_reset_n
    ddr3_ck_p    := mig.ddr3_ck_p
    ddr3_ck_n    := mig.ddr3_ck_n
    ddr3_cke     := mig.ddr3_cke
    ddr3_dm      := mig.ddr3_dm
    ddr3_odt     := mig.ddr3_odt

    // two way pins
    attach(ddr3_dq,    mig.ddr3_dq)
    attach(ddr3_dqs_p, mig.ddr3_dqs_p)
    attach(ddr3_dqs_n, mig.ddr3_dqs_n)

    withClockAndReset(clkGen.io.clk25, !clkGen.io.locked) {
        val backend = Module(new MigBackend)
        val tester = Module(new DDR3Tester)

        tester.io.mem <> backend.io.backend

        backend.io.ui_clk := mig.ui_clk
        backend.io.ui_rst := mig.ui_clk_sync_rst

        mig.app_addr := backend.io.mig.app_addr
        mig.app_cmd := backend.io.mig.app_cmd
        mig.app_en := backend.io.mig.app_en
        mig.app_wdf_data := backend.io.mig.app_wdf_data
        mig.app_wdf_mask := backend.io.mig.app_wdf_mask
        mig.app_wdf_wren := backend.io.mig.app_wdf_wren
        mig.app_wdf_end := backend.io.mig.app_wdf_end

        backend.io.mig.app_rdy := mig.app_rdy
        backend.io.mig.app_wdf_rdy := mig.app_wdf_rdy
        backend.io.mig.app_rd_data := mig.app_rd_data
        backend.io.mig.app_rd_data_valid := mig.app_rd_data_valid
        backend.io.mig.init_calib_complete := mig.init_calib_complete

        led := Cat(tester.io.finished && !tester.io.pass, tester.io.pass, tester.io.finished, mig.init_calib_complete)
     }
}

object DDR3TopMain extends App{
    val targetDir = "out/nexysvideo_ddr3/verilog"
    Emit.cleanTargetDir(targetDir)

    ChiselStage.emitSystemVerilogFile(
        gen = new DDR3Top,
        args = Array("--target-dir", targetDir),
        firtoolOpts = Emit.firtoolOpts
    )
}