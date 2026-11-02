// SPDX-FileCopyrightText: © 2026 Ziad Mubarak
// Adapter from MemBackendIO to the Xilinx MIG's user interface.

// Two modules, because two clocks:
//   MigBackend: runs on the SoC clock (25 MHz). Faces MemoryController
//               through MemBackendIO, exactly like SdramBackend.
//   MigUiDriver: runs on the MIG's ui_clk (100 MHz). Performs one 16-bit
//                read or write through the MIG user interface.
//
// The MIG's user interface only runs on its own ui_clk, which is unrelated to
// the SoC clock, so requests must cross between the two clock domains.

// Clock domain crossing (request/done handshake):
// - One request in flight, so the request is held stable on the SoC side.
// - Only `req` and `done` cross, each through a 2-flip-flop synchroniser.
// - Multi-bit values are read only after the matching bit arrives.
// - The two clocks are declared asynchronous in the .xdc

package memory

import chisel3._
import chisel3.util._

class MigUiIO extends Bundle {
    // MIG user interface signals
    
    // Backend -> MIG
    val app_addr = Output(UInt(29.W))  // start address of the 128-bit block
    val app_cmd = Output(UInt(3.W)) // 0 = write, 1 = read
    val app_en = Output(Bool()) //  read/write command is valid
    val app_wdf_data = Output(UInt(128.W)) // 128-bit block of write data
    val app_wdf_mask = Output(UInt(16.W)) // mask for the 16 bytes, mask bit = 0: write, mask bit = 1: leave byte in memory as it was
    val app_wdf_wren = Output(Bool()) // write data is valid
    val app_wdf_end = Output(Bool()) // last piece of write data

    // Mig -> Backend
    val app_rdy = Input(Bool()) // MIG ready to accept command
    val app_wdf_rdy = Input(Bool()) // MIG ready to accept write data
    val app_rd_data = Input(UInt(128.W)) // 128 bit block read from memory
    val app_rd_data_valid = Input(Bool()) // app_rd_data holds read data this cycle
    val init_calib_complete = Input(Bool()) // MIG finished start-up calibration, ready to use
}

class MigUiDriverIO extends Bundle {
    val start = Input(Bool()) 
    val isWrite = Input(Bool())
    val addr = Input(UInt(24.W))
    val wdata = Input(UInt(16.W))
    val rdata = Output(UInt(16.W))
    val done = Output(Bool())
    val mig = new MigUiIO
}

class ToggleSyncIO extends Bundle {
  val in    = Input(Bool())    // toggle from the other clock domain
  val pulse = Output(Bool())   // 1 for one cycle per flip
}

class MigBackendIO extends Bundle {
    val backend =  Flipped(new MemBackendIO)
    val mig = new MigUiIO
    val ui_clk = Input(Clock())
    val ui_rst = Input(Bool())
}

class MigUiDriver extends Module {
    val io = IO(new MigUiDriverIO)

    // States
    // sIdle: wait for start, save the request
    // sData: (write) offer the 128-bit data + mask until app_wdf_rdy
    // sCmd:  offer the command + address until app_rdy
    // sRead: (read) wait for app_rd_data_valid. keep our 16 bits
    // sDone: pulse done for one cycle
    val sIdle :: sData :: sCmd :: sRead :: sDone :: Nil = Enum(5)
    val state = RegInit(sIdle)

    // Saved copy of the request
    val isWriteReg = RegInit(false.B)
    val addrReg = RegInit(0.U(24.W))
    val wdataReg = RegInit(0.U(16.W))
    // Result of the read, returned to MigBackend
    val rdataReg = RegInit(0.U(16.W))

    io.mig.app_en := state === sCmd
    io.mig.app_wdf_wren := state === sData
    io.mig.app_wdf_end := state === sData
    io.mig.app_cmd := Mux(isWriteReg, 0.U, 1.U)
    
    io.done := state === sDone
    io.rdata := rdataReg

    io.mig.app_addr := Cat(addrReg(23, 3), 0.U(3.W)) 
    io.mig.app_wdf_data := Fill(8, wdataReg)
    val wordIdx = addrReg(2, 0)
    val writeEn = "b11".U(16.W) << (wordIdx * 2.U)
    io.mig.app_wdf_mask := (~writeEn)(15, 0)    

    switch(state) {
        is(sIdle) {
            when(io.start) {
                isWriteReg := io.isWrite
                addrReg := io.addr
                wdataReg := io.wdata 
                when(io.isWrite){
                    state := sData
                }.otherwise{
                    state := sCmd
                }
            }
        }
        is(sData) {
            when(io.mig.app_wdf_rdy) {
                state := sCmd
            }
        }
        is(sCmd) {
            when(io.mig.app_rdy) {
                when(isWriteReg) {
                    state := sDone 
                }.otherwise{
                    state := sRead
                }
            }
        }
        is(sRead) {
            when(io.mig.app_rd_data_valid) {
                rdataReg := (io.mig.app_rd_data >> (wordIdx * 16.U))(15, 0)
                state := sDone
            }
        }
        is(sDone) {
            state := sIdle
        }
    }

}   

// Safely receives a bit from another clock domain that flips once per
// event, and outputs a one-cycle pulse on this clock for each flip
// s1, s2 = 2 flip-flop synchroniser
// s3 = previous s2, to detect the flip
class ToggleSync extends Module {
    val io = IO(new ToggleSyncIO)

    val s1 = RegNext(io.in, false.B)
    val s2 = RegNext(s1, false.B)
    val s3 = RegNext(s2, false.B)
    io.pulse := s2 =/= s3
}

// Adapter from MemBackendIO to the Xilinx MIG
// Runs on the SoC clock
class MigBackend extends Module {
    val io = IO(new MigBackendIO)

    val sIdle :: sWait :: sLoad :: sDone :: Nil = Enum(4)
    val state = RegInit(sIdle)

    val isWriteReg = RegInit(false.B)
    val addrReg = RegInit(0.U(24.W))
    val wdataReg = RegInit(0.U(16.W))
    val reqToggle = RegInit(false.B)
    val rdataReg = RegInit(0.U(16.W))
    val lenReg = RegInit(0.U(7.W))
    val beatReg = RegInit(0.U(7.W))

    val (driver, doneToggle) = withClockAndReset(io.ui_clk, io.ui_rst) {
        val reqSync = Module(new ToggleSync)
        reqSync.io.in := reqToggle

        val driver = Module(new MigUiDriver)
        driver.io.start := reqSync.io.pulse
        driver.io.isWrite := isWriteReg
        driver.io.addr := addrReg
        driver.io.wdata := wdataReg

        io.mig <> driver.io.mig

        val doneToggle = RegInit(false.B)
        when(driver.io.done) { 
            doneToggle := !doneToggle
        }

        (driver, doneToggle) 
    }

    val doneSync = Module(new ToggleSync)
    doneSync.io.in := doneToggle

    val calibrated = RegNext(RegNext(io.mig.init_calib_complete, false.B), false.B)

    val acceptW = WireDefault(false.B)

    io.backend.busy := state =/= sIdle || !calibrated
    io.backend.done := state === sDone
    io.backend.dataOut := rdataReg
    io.backend.accept := acceptW 

    switch(state) {
        is(sIdle) {
            when(io.backend.startRead || io.backend.startWrite) {
                addrReg := io.backend.addrIn
                wdataReg := io.backend.dataIn
                isWriteReg := io.backend.startWrite
                lenReg := Mux(io.backend.startWrite, io.backend.lenIn, 1.U)
                beatReg := 0.U
                reqToggle := !reqToggle
                state := sWait
            }
        }
        is(sWait) {
            when(doneSync.io.pulse) {
                when(beatReg + 1.U < lenReg) {
                    acceptW := true.B
                    addrReg := addrReg + 1.U
                    beatReg := beatReg + 1.U
                    state := sLoad
                }.otherwise {
                    rdataReg := driver.io.rdata
                    state := sDone
                }
            }
        }
        is(sLoad) {
            wdataReg := io.backend.dataIn
            reqToggle := !reqToggle
            state := sWait
        }
        is(sDone) {
            state := sIdle
        }
    }
}