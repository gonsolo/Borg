// SPDX-FileCopyrightText: © 2026 Ziad Mubarak
package nexysvideo

import chisel3._
import chisel3.util._

import memory.MemBackendIO

// Board test: writes a pattern to DDR3 through MigBackend, reads it back,
// and reports pass/fail on LEDs.

class DDR3TesterIO extends Bundle {
    val mem = new MemBackendIO

    val finished = Output(Bool())
    val pass = Output(Bool())
}

class DDR3Tester extends Module {
    val io = IO(new DDR3TesterIO)

    val sWaitReady :: sWrite :: sWriteWait :: sRead :: sReadWait :: sFinish :: Nil = Enum(6)
    val state = RegInit(sWaitReady)

    val addr = RegInit(0.U(24.W))
    val failed = RegInit(false.B)

    val numWords = 1024

    // the value each address should hold
    // takes bottom 16 bits of the address and mixes them with A5A5 using XOR
    // every address gets a different value, none are 0
    def pattern(a: UInt): UInt = a(15, 0) ^ "hA5A5".U 

    // Requests to MigBackend
    io.mem.addrIn     := addr
    io.mem.dataIn     := pattern(addr)
    io.mem.byteEnIn   := 3.U                
    io.mem.lenIn      := 1.U                
    io.mem.startWrite := state === sWrite && !io.mem.busy
    io.mem.startRead  := state === sRead  && !io.mem.busy

    // LEDs
    io.finished := state === sFinish
    io.pass     := state === sFinish && !failed

    switch(state) {
        is(sWaitReady) {
            when(!io.mem.busy) {
                state := sWrite
            }
        }
        is(sWrite) {
            when(!io.mem.busy) {
                state := sWriteWait
            }
        }
        is(sWriteWait) {
            when(io.mem.done) {
                when(addr === (numWords - 1).U) {
                    addr := 0.U
                    state := sRead
                }.otherwise {
                    addr := addr + 1.U
                    state := sWrite
                }
            }
        }
        is(sRead) {
            when(!io.mem.busy) {
                state := sReadWait
            }
        }
        is(sReadWait) {
            when(io.mem.done) {
                when(io.mem.dataOut =/= pattern(addr)) {
                    failed := true.B
                }
                when(addr === (numWords - 1).U) {
                    state := sFinish
                }.otherwise {
                    addr := addr + 1.U
                    state := sRead
                }
            }
        }
        is(sFinish) {
        }
    }
}