// SPDX-FileCopyrightText: © 2026 Ziad Mubarak
package nexysvideo

import chisel3._
import chisel3.util._
import chisel3.experimental.{Analog, attach}

import soc.{SoCLogic, HdmiScanoutFp16, TmdsEncoder, Emit}
import borg.BorgConfig

import memory.{FlashBootLoader, MigBackend}

import _root_.circt.stage.ChiselStage

// Full Nexys Video top: Hutt CPU + Borg GPU + memory controller + UART + HDMI scan-out, on DDR3.
// Start-up order:
// 1) Clocks lock
// 2) The MIG calibrates DDR3
// 3) The flash bootloader copies the firmware from flash into DDR3 (flash clock goes through STARTUPE2)
// 4) The memory mux hands DDR3 from the bootloader to the memory controller
// 5) The CPU leaves reset and runs the firmware, which waits for drawing work from the laptop over UART
// The firmware can also reload itself over UART (warm reset): only the bootloader and CPU restart,
// and the bootloader copies the new firmware from a scratch area of DDR3 instead of from flash.
// Board version of ulx3s_top, with MigBackend + MIG in place of SdramBackend.

class nexysvideo_top(val CLOCK_MHZ: Int = 25) extends RawModule with SoCLogic {
    // GPU configuration used by vkcube demo
    override def BORG_CFG: BorgConfig = BorgConfig.Cube
    // Experiment: 64-bit Hutt with supervisor mode (the CPU Linux needs), to measure its size
    override def xlen: Int = 64
    override def hasSupervisorMode: Boolean = true
    // GPU addresses carry bit 24 like the CPU's, so both see the same memory
    override def gpuVramRegionBit: Boolean = true
    // Tells the firmware which picture the scan-out is currently showing
    override def scanoutCurBuf: Bool = scanout.io.curBuf

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
    val flash_wp_n   = IO(Output(Bool())) // flash write-protect, active low: keep 1
    val flash_hold_n = IO(Output(Bool())) // flash pause, active low: keep 1

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

    // Warm reset (firmware reload over UART): the firmware asks for it, then only the
    // bootloader and CPU restart, and the bootloader copies from the DDR3 scratch area.
    val warmRst = Wire(Bool())
    val warmBootReg = withClockAndReset(sysClock, pllRst) { RegInit(false.B) }

    // Flash bootloader: copies the firmware from flash (cold) or from DDR3 scratch (warm) into DDR3
    val flashBoot = withClockAndReset(sysClock, bootRst || warmRst) {
        Module(new FlashBootLoader())
    }
    flash_csn  := flashBoot.io.flash_csn
    flash_mosi := flashBoot.io.flash_mosi
    flashBoot.io.flash_miso := flash_miso
    flashBoot.io.warmBoot := warmBootReg // warm: copy from DDR3 scratch, cold: from flash
    flash_wp_n := true.B
    flash_hold_n := true.B
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

    // SoCLogic board members
    def soc_clk = sysClock

    // CPU held in reset until the clock is stable, the program is copied in, and the reset button is not pressed
    def soc_rst_n = pllLocked && bootDone && cpu_resetn

    // Same reset through a flip-flop on the falling edge
    lazy val soc_rst_reg_n: Bool = withClockAndReset((!sysClock.asBool).asClock, false.B) {
        RegNext(soc_rst_n)
    }

    // Inputs the CPU can read: bit 7 = UART receive (the full SoC's UART reads this bit),
    // bit 6 = 0, bits 5-1 = buttons, bit 0 = 0. 8 bits total
    def soc_ui_in = Cat(uart_tx_in, 0.U(1.W), btn, 0.U(1.W))

    // HDMI scan-out: reads the 128x128 picture from DDR3 and outputs pixels
    // Its framebuffer addresses are set by the firmware (connected after wireSoC())
    val scanout = withClockAndReset(sysClock, pllRst) {
        Module(new HdmiScanoutFp16(fbWidth = 128, fbHeight = 128, separatePixelClock = true))
    }

    // GPU memory port arbiter: Borg has priority; the scan-out gets the port only when Borg is idle.
    // scanoutOwns remembers that a scan-out read is in progress, so its ready reply goes back to the
    // scan-out even if Borg starts a request meanwhile.
    override def wireGpuMem(): Unit = {
        val gpuActive = peripherals.io.gpuMem.req || peripherals.io.gpuMem.wr
        val scanoutOwns = withClockAndReset(sysClock, pllRst) { RegInit(false.B) }

        withClockAndReset(sysClock, pllRst) {
            when(scanoutOwns) {
                when(mem.io.gpuMem.ready) { scanoutOwns := false.B }
            }.otherwise {
                when(!gpuActive && scanout.io.gpuReq) { scanoutOwns := true.B }
            }
        }

        val serveGpu = !scanoutOwns

        mem.io.gpuMem.req := Mux(serveGpu, peripherals.io.gpuMem.req,  scanout.io.gpuReq)
        mem.io.gpuMem.addr := Mux(serveGpu, peripherals.io.gpuMem.addr, scanout.io.gpuAddr)
        mem.io.gpuMem.wr := Mux(serveGpu, peripherals.io.gpuMem.wr,   false.B)
        mem.io.gpuMem.wdata := peripherals.io.gpuMem.wdata
        mem.io.gpuMem.wlen := Mux(serveGpu, peripherals.io.gpuMem.wlen, 1.U) // only Borg bursts

        peripherals.io.gpuMem.data := mem.io.gpuMem.data
        peripherals.io.gpuMem.ready := mem.io.gpuMem.ready && !scanoutOwns
        peripherals.io.gpuMem.waccept := mem.io.gpuMem.waccept && serveGpu

        scanout.io.gpuData := mem.io.gpuMem.data
        scanout.io.gpuReady := mem.io.gpuMem.ready && scanoutOwns
    }

    val uo_out_val = wireSoC()
    uart_rx_out := uo_out_val(6)

    // Scan-out picture settings, written by the firmware into registers that wireSoC() creates
    scanout.io.frontBuf := fbSelectReg // which of the two pictures to show
    scanout.io.fbBase   := scanoutFbBase0 // memory address of picture 0
    scanout.io.fbBase1  := scanoutFbBase1 // memory address of picture 1

    // Warm-reset counter: when the firmware requests a warm reset (warmReset, created by wireSoC()),
    // remember that this is a warm boot and hold the bootloader (and so the CPU) in reset for 16 cycles.
    val warmRstCtr = withClockAndReset(sysClock, pllRst) { RegInit(0.U(5.W)) }
    withClockAndReset(sysClock, pllRst) {
        when(warmReset) {
            warmBootReg := true.B // the next boot copies from DDR3 scratch, not flash
            warmRstCtr := 16.U   // start counting down
        } .elsewhen(warmRstCtr =/= 0.U) {
            warmRstCtr := warmRstCtr - 1.U
        }
    }
    warmRst := warmRstCtr =/= 0.U   // reset is held while the counter is not 0

    // Memory mux: requests into MigBackend
    // Before bootDone the bootloader owns it, after it the memory controller. 
    backend.io.backend.addrIn := Mux(bootDone, mem.io.backend.addrIn, flashBoot.io.backend.addrIn)
    backend.io.backend.dataIn := Mux(bootDone, mem.io.backend.dataIn, flashBoot.io.backend.dataIn)
    backend.io.backend.byteEnIn := Mux(bootDone, mem.io.backend.byteEnIn, flashBoot.io.backend.byteEnIn)
    backend.io.backend.lenIn := Mux(bootDone, mem.io.backend.lenIn, flashBoot.io.backend.lenIn)
    backend.io.backend.startRead := Mux(bootDone, mem.io.backend.startRead, flashBoot.io.backend.startRead)
    backend.io.backend.startWrite := Mux(bootDone, mem.io.backend.startWrite, flashBoot.io.backend.startWrite)
    
    // Replies to the memory controller: it only sees them after bootDone, otherwise 0
    mem.io.backend.dataOut := Mux(bootDone, backend.io.backend.dataOut, 0.U)
    mem.io.backend.done := Mux(bootDone, backend.io.backend.done, false.B)
    mem.io.backend.busy := Mux(bootDone, backend.io.backend.busy, false.B)
    mem.io.backend.accept := Mux(bootDone, backend.io.backend.accept, false.B)

    // Replies to the bootloader: it only sees done/busy/accept before bootDone.
    // dataOut needs no mux: it's only used when done is 1, and done only reaches the owner.
    // (The bootloader reads dataOut during a warm reload, copying from DDR3 scratch.)
    flashBoot.io.backend.dataOut := backend.io.backend.dataOut
    flashBoot.io.backend.done := Mux(!bootDone, backend.io.backend.done, false.B)
    flashBoot.io.backend.busy := Mux(!bootDone, backend.io.backend.busy, false.B)
    flashBoot.io.backend.accept := Mux(!bootDone, backend.io.backend.accept, false.B)

    // HDMI clock: 125 MHz, a pixel every 5th cycle
    val hdmiClock = clkGen.io.clk125
    val hdmiRst   = pllRst

    // Pixel tick: 1 on every 5th cycle = 25 MHz pixel rate
    val hdmiCount = withClockAndReset(hdmiClock, hdmiRst) { RegInit(0.U(3.W)) }
    val hdmiTick25 = (hdmiCount === 4.U)
    withClockAndReset(hdmiClock, hdmiRst) {
        when(hdmiTick25) { hdmiCount := 0.U } .otherwise { hdmiCount := hdmiCount + 1.U }
    }

    // Screen timing: 640x480 at 60 Hz
    val hCount = withClockAndReset(hdmiClock, hdmiRst) { RegInit(0.U(10.W)) }
    val vCount = withClockAndReset(hdmiClock, hdmiRst) { RegInit(0.U(10.W)) }
    val hTotal = 800.U;  val vTotal = 525.U
    val hActive = 640.U; val vActive = 480.U
    val hFront = 16.U;   val hSync = 96.U
    val vFront = 10.U;   val vSync = 2.U
    withClockAndReset(hdmiClock, hdmiRst) {
        when(hdmiTick25) {
            when(hCount === hTotal - 1.U) {
                hCount := 0.U
                when(vCount === vTotal - 1.U) { vCount := 0.U }
                .otherwise { vCount := vCount + 1.U }
            } .otherwise { hCount := hCount + 1.U }
        }
    }
    val de    = (hCount < hActive) && (vCount < vActive) // inside the visible area
    val hsync = (hCount >= (hActive + hFront)) && (hCount < (hActive + hFront + hSync))
    val vsync = (vCount >= (vActive + vFront)) && (vCount < (vActive + vFront + vSync))

    // Scan-out screen side: which pixel, and its clock
    scanout.io.hCount := hCount
    scanout.io.vCount := vCount
    scanout.io.de     := de
    scanout.io.tick25 := hdmiTick25
    scanout.io.pixClk.get := hdmiClock
    scanout.io.pixRst.get := hdmiRst

    // Boot delay: keep the scan-out off for ~42 ms after the CPU is released.
    // Once on, the scan-out uses memory almost non-stop, and the CPU's instruction fetch has the
    // lowest priority, so the program needs a head start. The count starts at bootDone, not at
    // clock lock, because on this board DDR3 calibration + boot can take longer than 42 ms
    // (counting from clock lock let the scan-out start before the CPU and starve it).
    val scanoutRst       = pllRst || !bootDone
    val scanoutBootDelay = withClockAndReset(sysClock, scanoutRst) { RegInit(0.U(21.W)) }
    val scanoutReady     = scanoutBootDelay(20)
    withClockAndReset(sysClock, scanoutRst) {
        when(!scanoutReady) { scanoutBootDelay := scanoutBootDelay + 1.U }
    }
    scanout.io.enable := scanoutReady

    // TMDS encoders: 8-bit colour -> 10-bit HDMI code (blue also carries hsync/vsync)
    val encB = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
    encB.io.en := hdmiTick25; encB.io.data := scanout.io.blue
    encB.io.c  := Cat(vsync, hsync); encB.io.de := de
    val encG = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
    encG.io.en := hdmiTick25; encG.io.data := scanout.io.green
    encG.io.c  := 0.U; encG.io.de := de
    val encR = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsEncoder) }
    encR.io.en := hdmiTick25; encR.io.data := scanout.io.red
    encR.io.c  := 0.U; encR.io.de := de

    // The encoder takes 2 cycles, so the serializers load 2 cycles after the tick
    val hdmiTick25D1 = withClockAndReset(hdmiClock, hdmiRst) { RegNext(hdmiTick25, false.B) }
    val hdmiTick25D2 = withClockAndReset(hdmiClock, hdmiRst) { RegNext(hdmiTick25D1, false.B) }

    // Serializers (Xilinx ODDR): 10 bits out one at a time
    val serB = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
    serB.io.en := hdmiTick25D2; serB.io.tmds := encB.io.tmds
    val serG = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
    serG.io.en := hdmiTick25D2; serG.io.tmds := encG.io.tmds
    val serR = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
    serR.io.en := hdmiTick25D2; serR.io.tmds := encR.io.tmds
    val serClk = withClockAndReset(hdmiClock, hdmiRst) { Module(new TmdsSerializer) }
    serClk.io.en := hdmiTick25D2; serClk.io.tmds := "b0000011111".U // HDMI clock pattern

    // Wire-pair buffers onto the HDMI pins 
    val bufB = Module(new OBUFDS); bufB.I := serB.io.out
    val bufG = Module(new OBUFDS); bufG.I := serG.io.out
    val bufR = Module(new OBUFDS); bufR.I := serR.io.out
    val bufClk = Module(new OBUFDS); bufClk.I := serClk.io.out
    hdmi_tx_clk_p := bufClk.O
    hdmi_tx_clk_n := bufClk.OB
    hdmi_tx_p := Cat(bufR.O, bufG.O, bufB.O)
    hdmi_tx_n := Cat(bufR.OB, bufG.OB, bufB.OB)

    // LEDs (same layout as ulx3s_top)
    // LD7: clocks stable (pllLocked)
    // LD6: program copied from flash into DDR3 (bootDone)
    // LD5-LD2: bootloader step, as a 4-bit number (stays at the first step until DDR3 is calibrated)
    // LD1: memory busy (MigBackend busy)
    // LD0: UART transmit line (flickers when the CPU sends text)
    led := Cat(pllLocked, bootDone, flashBoot.io.debug_state, backend.io.backend.busy, uo_out_val(6))
}

object NexysVideoTopMain extends App {
    val targetDir = "out/nexysvideo/verilog"
    Emit.cleanTargetDir(targetDir)

    ChiselStage.emitSystemVerilogFile(
        gen = new nexysvideo_top(),
        args = Array("--target-dir", targetDir),
        firtoolOpts = Emit.firtoolOpts
    )
}