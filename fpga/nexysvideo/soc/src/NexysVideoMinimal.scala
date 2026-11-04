// SPDX-FileCopyrightText: © 2026 Ziad Mubarak
package nexysvideo

import chisel3._
import chisel3.util._
import chisel3.experimental.{Analog, attach}

import soc.{MinimalSoCLogic, HdmiScanoutFp16, TmdsEncoder}

import memory.{FlashBootLoader, MigBackend}

// Minimal Nexys Video top: Hutt CPU + memory controller + UART + HDMI scan-out, on DDR3. No Borg GPU.
// Start-up order:
// 1. Clocks lock
// 2. The MIG calibrates DDR3
// 3. The flash bootloader copies the program from flash into DDR3 (flash clock goes through STARTUPE2)
// 4. The memory mux hands DDR3 from the bootloader to the memory controller
// 5. The CPU leaves reset and runs the program
// Board version of Andreas's ulx3s_minimal_top, with MigBackend + MIG in place of SdramBackend


class nexysvideo_minimal_top(val CLOCK_MHZ: Int = 25, fbSize: Int = 32) extends RawModule with MinimalSoCLogic {
    //Clock and Reset
    val clk = IO(Input(Clock()))
    val cpu_resetn = IO(Input(Bool()))

    // DDR3
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

    // HDMI
    val hdmi_tx_clk_p = IO(Output(Bool()))
    val hdmi_tx_clk_n = IO(Output(Bool()))
    val hdmi_tx_p = IO(Output(UInt(3.W)))
    val hdmi_tx_n = IO(Output(UInt(3.W)))

    // Flash (SPI)
    val flash_csn  = IO(Output(Bool())) // chip select, active low
    val flash_mosi = IO(Output(Bool())) // FPGA -> flash data
    val flash_miso = IO(Input(Bool())) // flash -> FPGA data

    // UART 
    val uart_rx_out = IO(Output(Bool())) // FPGA sends to the PC
    val uart_tx_in  = IO(Input(Bool())) // FPGA receives from the PC

    // LEDs and buttons
    val led = IO(Output(UInt(8.W)))
    val btn = IO(Input(UInt(5.W))) // centre, up, down, left, right

    val clkGen = Module(new Clocks)
    clkGen.io.clk100 := clk
    clkGen.io.rst := !cpu_resetn

    val sysClock  = clkGen.io.clk25 // SoC clock: CPU, memory, bootloader
    val pllLocked = clkGen.io.locked
    val pllRst    = !pllLocked

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

    val backend = withClockAndReset(sysClock, pllRst) {
        Module(new MigBackend)
    }
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

    // Hold the bootloader until DDR3 is calibrated 
    // init_calib_complete comes from the MIG's clock: 2 flip-flops onto sysClock
    val calibrated = withClockAndReset(sysClock, pllRst) {
        RegNext(RegNext(mig.init_calib_complete, false.B), false.B)
    }
    val bootRst = pllRst || !calibrated

    // Flash bootloader: copies the program from flash into DDR3
    val flashBoot = withClockAndReset(sysClock, bootRst) {
        Module(new FlashBootLoader())
    }
    flash_csn  := flashBoot.io.flash_csn
    flash_mosi := flashBoot.io.flash_mosi
    flashBoot.io.flash_miso := flash_miso
    flashBoot.io.warmBoot   := false.B // cold boot from flash only
    val bootDone = flashBoot.io.boot_done

    // STARTUPE2: the bootloader's clock out to the flash clock pin
    val startup = Module(new STARTUPE2)
    startup.USRCCLKO  := flashBoot.io.spi_clk
    startup.USRCCLKTS := false.B 
    startup.CLK       := false.B
    startup.GSR       := false.B
    startup.GTS       := false.B
    startup.KEYCLEARB := true.B
    startup.PACK      := false.B
    startup.USRDONEO  := true.B
    startup.USRDONETS := true.B

    // MinimalSoCLogic board members
    def soc_clk = sysClock

    // CPU held in reset until the clock is stable, the program is copied in, and the reset button is not pressed
    def soc_rst_n = pllLocked && bootDone && cpu_resetn

    // Same reset through a flip-flop on the falling edge
    lazy val soc_rst_reg_n: Bool = withClockAndReset((!sysClock.asBool).asClock, false.B) {
        RegNext(soc_rst_n)
    }

    // Inputs the CPU can read: bit 0 = UART receive, bits 1-5 = buttons, 8 bits total
    def soc_ui_in = Cat(0.U(2.W), btn, uart_tx_in)

    // HDMI scan-out: reads the 32x32 picture from DDR3 and outputs pixels
    val scanout = withClockAndReset(sysClock, pllRst) {
        Module(new HdmiScanoutFp16(fbWidth = fbSize, fbHeight = fbSize, separatePixelClock = true, rgb565Store = false, doubleBuffer = true))  
    }
    scanout.io.frontBuf := false.B   
    val fbAddr = 0x100000 // where the picture lives in memory 
    scanout.io.fbBase  := fbAddr.U
    scanout.io.fbBase1 := fbAddr.U

    // No Borg - the scan-out owns the memory controller's GPU port 
    override def wireGpuMem(): Unit = {
        mem.io.gpuMem.req   := scanout.io.gpuReq
        mem.io.gpuMem.addr  := scanout.io.gpuAddr
        mem.io.gpuMem.wr    := false.B // the scan-out only reads
        mem.io.gpuMem.wdata := 0.U
        mem.io.gpuMem.wlen  := 1.U
        scanout.io.gpuData  := mem.io.gpuMem.data
        scanout.io.gpuReady := mem.io.gpuMem.ready
    }

    val uo_out_val = wireSoC()
    uart_rx_out := uo_out_val(6)

    // Memory mux: requests into MigBackend
    backend.io.backend.addrIn := Mux(bootDone, mem.io.backend.addrIn, flashBoot.io.backend.addrIn)
    backend.io.backend.dataIn := Mux(bootDone, mem.io.backend.dataIn, flashBoot.io.backend.dataIn)
    backend.io.backend.byteEnIn := Mux(bootDone, mem.io.backend.byteEnIn, flashBoot.io.backend.byteEnIn)
    backend.io.backend.lenIn := Mux(bootDone, mem.io.backend.lenIn, 1.U)
    backend.io.backend.startRead := Mux(bootDone, mem.io.backend.startRead, false.B)
    backend.io.backend.startWrite := Mux(bootDone, mem.io.backend.startWrite, flashBoot.io.backend.startWrite)
    
    // Replies to the memory controller: it only sees them after bootDone, otherwise 0
    mem.io.backend.dataOut := Mux(bootDone, backend.io.backend.dataOut, 0.U)
    mem.io.backend.done := Mux(bootDone, backend.io.backend.done, false.B)
    mem.io.backend.busy := Mux(bootDone, backend.io.backend.busy, false.B)
    mem.io.backend.accept := Mux(bootDone, backend.io.backend.accept, false.B)

    // Replies to the bootloader: it only sees done/busy/accept before bootDone 
    // dataOut needs no mux: the bootloader never reads, and data means nothing without done
    flashBoot.io.backend.dataOut := backend.io.backend.dataOut
    flashBoot.io.backend.done := Mux(!bootDone, backend.io.backend.done, false.B)
    flashBoot.io.backend.busy := Mux(!bootDone, backend.io.backend.busy, false.B)
    flashBoot.io.backend.accept := Mux(!bootDone, backend.io.backend.accept, false.B)
}
