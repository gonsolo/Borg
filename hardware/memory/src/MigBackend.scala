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
    val app_addr = Output(UInt(29.W))
    val app_cmd = Output(UInt(3.W))
    val app_en = Output(Bool())
    val app_wdf_data = Output(UInt(128.W))
    val app_wdf_mask = Output(UInt(16.W))
    val app_wdf_wren = Output(Bool())
    val app_wdf_end = Output(Bool())

    // Mig -> Backend
    val app_rdy = Input(Bool())
    val app_wdf_rdy = Input(Bool())
    val app_rd_data = Input(UInt(128.W))
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
    
    // Back to MigBackend
    io.done := state === sDone
    io.rdata := rdataReg


}