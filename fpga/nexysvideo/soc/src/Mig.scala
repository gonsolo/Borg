// SPDX-FileCopyrightText: © 2026 Ziad Mubarak
package nexysvideo

import chisel3._
import chisel3.experimental.Analog

class mig_7series_0 extends ExtModule{
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

    // Clock and reset in
    val sys_clk_i = IO(Input(Clock())) // 200 MHz from Clocks
    val sys_rst   = IO(Input(Bool())) // active low: 0 = reset

    // User interface: the MIG receives these from MigBackend
    val app_addr     = IO(Input(UInt(29.W))) // start address of the 128-bit block
    val app_cmd      = IO(Input(UInt(3.W))) // 0 = write, 1 = read
    val app_en       = IO(Input(Bool())) // read/write command is valid
    val app_wdf_data = IO(Input(UInt(128.W))) // 128-bit block of write data
    val app_wdf_mask = IO(Input(UInt(16.W))) // 1 per byte: 1 = leave byte unchanged
    val app_wdf_wren = IO(Input(Bool())) // write data is valid
    val app_wdf_end  = IO(Input(Bool())) // last piece of write data
    val app_sr_req   = IO(Input(Bool()))       
    val app_ref_req  = IO(Input(Bool()))        
    val app_zq_req   = IO(Input(Bool()))       

    // User interface: the MIG sends these (to MigBackend)
    val app_rd_data         = IO(Output(UInt(128.W))) // 128-bit block read from memory
    val app_rd_data_valid   = IO(Output(Bool())) // app_rd_data is valid this cycle
    val app_rd_data_end     = IO(Output(Bool())) // last piece of read data 
    val app_rdy             = IO(Output(Bool())) // ready to accept a command
    val app_wdf_rdy         = IO(Output(Bool())) // ready to accept write data
    val app_sr_active       = IO(Output(Bool()))      
    val app_ref_ack         = IO(Output(Bool()))       
    val app_zq_ack          = IO(Output(Bool()))      
    val init_calib_complete = IO(Output(Bool())) // calibration finished, ready to use

    // MIG's clock and status out
    val ui_clk          = IO(Output(Clock())) // 100 MHz, the user interface's clock
    val ui_clk_sync_rst = IO(Output(Bool())) // reset that goes with ui_clk
    val device_temp     = IO(Output(UInt(12.W))) // chip temperature (unused)
}

