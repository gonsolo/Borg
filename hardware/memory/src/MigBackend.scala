// SPDX-FileCopyrightText: © 2026 Ziad Mubarak
// Adapter from MemBackendIO to the Xilinx MIG's user interface.

// Two modules, because two clocks:
//   MigBackend: runs on the SoC clock (25 MHz). Faces MemoryController
//                 through MemBackendIO, exactly like SdramBackend.
//   MigUiDriver: runs on the MIG's ui_clk (100 MHz). Performs one 16-bit
//                 read or write through the MIG user interface.
//
// The MIG's user interface only runs on its own ui_clk, which is unrelated to
// the SoC clock, so requests must cross between the two clock domains.
//
// Clock domain crossing (request/done handshake):
// - One request in flight, so the request is held stable on the SoC side.
// - Only `req` and `done` cross, each through a 2-flip-flop synchroniser.
// - Multi-bit values are read only after the matching bit arrives.
// - The two clocks are declared asynchronous in the .xdc

package memory

import chisel3._
import chisel3.util._

class MigUiIO extends Bundle {
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
    val app_rd_data_valid = Input(Bool()) 
    val init_calib_complete = Input(Bool())
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