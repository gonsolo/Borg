// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package memory

import chisel3._
import chisel3.util._
import tinyqv.cpu.{InstrFetchIO, MemBusIO}
import borg.GpuMemIO

/** IO bundle for the SoC-level memory controller.
  *
  * Arbitrates the memory backend bus between four requestors:
  *   - CPU instruction fetch (via [[InstrFetchIO]])
  *   - CPU data read/write (loads, stores)
  *   - GPU write (autonomous tile flush)
  *   - GPU read (scanout / texel fetch)
  *
  * Physical memory pins are NOT owned here; connect a [[QspiBackend]]
  * or [[SdramBackend]] to io.backend at the top level.
  */
class MemoryControllerIO extends Bundle {
  // CPU instruction fetch interface
  val instrFetch = Flipped(new InstrFetchIO)

  // CPU data bus
  val cpuData = Flipped(new MemBusIO)

  // GPU read/write port (slave end)
  val gpuMem = Flipped(new GpuMemIO)

  // Backend interface — connect to QspiBackend or SdramBackend at top level.
  val backend = new MemBackendIO

  val debug_stall_txn = Output(Bool())
  val debug_stop_txn  = Output(Bool())
}

/** SoC-level memory controller — backend-agnostic arbiter.
  *
  * Arbitrates between four requestors:
  *   - '''Instruction fetch:''' streaming 16-bit instruction words
  *   - '''CPU data read/write:''' byte/halfword/word access for loads and stores
  *   - '''GPU write:''' autonomous SDRAM writes (tile flush)
  *   - '''GPU read:''' scanout / texel fetch from SDRAM
  *
  * The physical memory transport is provided by a separate backend module
  * ([[QspiBackend]] or [[SdramBackend]]) connected to io.backend.
  */
class MemoryController extends Module {
  val io = IO(new MemoryControllerIO)

  // Sequential State
  val instr_active       = RegInit(false.B)
  val gpu_active         = RegInit(false.B)
  val gpu_write_active   = RegInit(false.B)
  val started            = RegInit(false.B)
  val stopped            = RegInit(false.B)
  val be_write_done      = RegInit(false.B)
  val data_buf           = RegInit(VecInit(Seq.fill(4)(0.U(8.W))))
  val byte_idx           = RegInit(0.U(2.W))
  val data_txn_len       = RegInit(3.U(2.W))
  val continue_txn       = RegInit(false.B)
  val data_stall         = RegInit(false.B)

  val start_instr     = Wire(Bool())
  val start_read      = Wire(Bool())
  val start_write     = Wire(Bool())
  val start_gpu_write = Wire(Bool())
  val start_gpu_read  = Wire(Bool())
  val stop_txn    = Wire(Bool())
  val data_ready  = Wire(Bool())

  val data_txn_n = io.cpuData.writeN & io.cpuData.readN

  // Backend signals (directly wired from io.backend in wireBackend)
  val be_busy       = Wire(Bool())
  val be_data_req   = Wire(Bool())
  val be_data_ready = Wire(Bool())
  val be_data_out   = Wire(UInt(8.W))

  val is_instr  = instr_active || start_instr
  val is_gpu    = gpu_active || gpu_write_active || start_gpu_read || start_gpu_write
  val txn_len   = Mux(is_instr, 1.U(2.W), data_txn_len)
  val addr_in = WireDefault(io.cpuData.addr(24, 0))
  when(is_instr) { addr_in := Cat(0.U(1.W), io.instrFetch.instr_addr, 0.U(1.W)) }
  when(is_gpu)   { addr_in := io.gpuMem.addr }

  val stall_txn = instr_active &&
    io.instrFetch.instr_fetch_stall &&
    !be_data_ready &&
    (byte_idx === 1.U)

  // --- Core Wiring ---
  wireControlFsm()
  wireDataPath()
  wireBackend()
  wireOutputs()

  // --- Helper Methods ---

  private def assembleInstrData(): UInt = Cat(be_data_out, data_buf(0))

  private def assembleCpuDataIn(): UInt = {
    Mux(
      data_ready,
      Cat(
        be_data_out,
        data_buf(2),
        Mux(data_txn_len === 1.U, be_data_out, data_buf(1)),
        Mux(data_txn_len === 0.U, be_data_out, data_buf(0))
      ),
      data_buf.asUInt
    )
  }

  private def assembleGpuData(): UInt = Cat(be_data_out, data_buf(2), data_buf(1), data_buf(0))

  // --- Implementation Methods ---

  private def wireControlFsm(): Unit = {
    start_instr     := false.B
    start_read      := false.B
    start_write     := false.B
    start_gpu_write := false.B
    start_gpu_read  := false.B
    stop_txn        := false.B

    when(be_busy || be_write_done) {
      when(instr_active) {
        when(io.instrFetch.instr_fetch_restart && (!started || stall_txn)) {
          stop_txn := true.B
        } .elsewhen(
          (be_data_ready && byte_idx === 1.U) ||
          io.instrFetch.instr_fetch_stall
        ) {
          when(data_txn_n =/= 3.U || io.gpuMem.req || io.gpuMem.wr) { stop_txn := true.B }
        }
      } .elsewhen(
        (be_data_ready || be_data_req) &&
        byte_idx === data_txn_len &&
        !continue_txn
      ) {
        stop_txn := true.B
      }
    } .otherwise {
      // Priority chain:
      //   CPU read > CPU write > GPU write > GPU read > instr fetch
      when(io.cpuData.readN =/= 3.U) {
        start_read := true.B
      } .elsewhen(io.cpuData.writeN =/= 3.U) {
        start_write := true.B
      } .elsewhen(io.gpuMem.wr) {
        start_gpu_write := true.B
      } .elsewhen(io.gpuMem.req) {
        start_gpu_read := true.B
      } .elsewhen(io.instrFetch.instr_fetch_restart) {
        start_instr := true.B
      }
    }

    instr_active     := Mux(stop_txn, false.B, Mux(be_busy, instr_active, start_instr))
    gpu_active       := Mux(stop_txn, false.B, Mux(be_busy, gpu_active, start_gpu_read))
    gpu_write_active := Mux(stop_txn, false.B, Mux(be_busy, gpu_write_active, start_gpu_write))
    started          := start_instr
    stopped          := stop_txn
  }

  private def wireDataPath(): Unit = {
    when(start_instr || start_read || start_write || start_gpu_read || start_gpu_write) {
      byte_idx := 0.U
    } .otherwise {
      when(be_data_ready || be_data_req) {
        byte_idx := Mux(
          byte_idx === txn_len, 0.U, byte_idx + 1.U
        )
      }
    }

    when(be_data_ready) {
      data_buf(byte_idx) := be_data_out
    } .elsewhen(io.cpuData.writeN =/= 3.U && (data_stall || start_write)) {
      for (i <- 0 until 4) {
        data_buf(i) := io.cpuData.dataOut(i * 8 + 7, i * 8)
      }
    } .elsewhen(start_gpu_write) {
      // Load GPU write data into buffer
      for (i <- 0 until 4) {
        data_buf(i) := io.gpuMem.wdata(i * 8 + 7, i * 8)
      }
    }

    be_write_done := be_data_req && byte_idx === data_txn_len

    when(start_gpu_read) {
      data_txn_len := 3.U   // reads: 4 bytes (2 SDRAM words)
    } .elsewhen(start_gpu_write) {
      data_txn_len := 1.U   // writes: 2 bytes (1 SDRAM word per transaction)
    } .elsewhen(start_read || start_write) {
      data_txn_len := Cat(data_txn_n(1), data_txn_n(1) | data_txn_n(0))
    }

    when(continue_txn) {
      when(
        (be_data_req   && byte_idx + 1.U === data_txn_len) ||
        (be_data_ready && byte_idx       === data_txn_len)
      ) {
        data_stall := true.B
      } .elsewhen(
        data_stall &&
        byte_idx === 0.U &&
        ((io.cpuData.readN =/= 3.U && !data_ready) || io.cpuData.writeN =/= 3.U)
      ) {
        data_stall   := false.B
        continue_txn := io.cpuData.dataContinue
      }
    } .otherwise {
      data_stall := false.B
      when(start_gpu_read || start_gpu_write) {
        continue_txn := false.B
      } .elsewhen(start_write || start_read) {
        continue_txn := io.cpuData.dataContinue
      }
    }
  }

  private def wireBackend(): Unit = {
    // Arbiter → Backend
    io.backend.addrIn     := addr_in
    // On a start_write/start_gpu_write cycle, BOTH byte_idx AND
    // data_buf hold stale values (register writes take effect next cycle).
    // Bypass everything and present byte 0 directly from the data source.
    io.backend.dataIn := Mux(start_write,
      io.cpuData.dataOut(7, 0),
      Mux(start_gpu_write,
        io.gpuMem.wdata(7, 0),
        data_buf(
          byte_idx + Mux(io.backend.dataReq, 1.U, 0.U)
        )
      )
    )
    io.backend.startRead  := start_read || start_instr || start_gpu_read
    io.backend.startWrite := start_write || start_gpu_write
    io.backend.stallTxn   := stall_txn || data_stall
    io.backend.stopTxn    := stop_txn

    // Backend → Arbiter
    be_data_out   := io.backend.dataOut
    be_data_req   := io.backend.dataReq
    be_data_ready := io.backend.dataReady
    be_busy       := io.backend.busy
  }

  private def wireOutputs(): Unit = {
    // CPU Instruction Fetch Outputs
    io.instrFetch.instr_fetch_started := started
    io.instrFetch.instr_fetch_stopped := stopped
    io.instrFetch.instr_data          := assembleInstrData()
    io.instrFetch.instr_ready         := instr_active && be_data_ready && byte_idx === 1.U

    // CPU Data Bus Outputs
    data_ready := !instr_active && !gpu_active && !gpu_write_active && (
      (be_data_ready && byte_idx === data_txn_len) ||
      (io.cpuData.writeN =/= 3.U && ((data_stall && byte_idx === 0.U) || start_write))
    )
    io.cpuData.ready  := data_ready
    io.cpuData.dataIn := assembleCpuDataIn()

    // GPU Port
    // ready pulses on read completion OR write completion.
    // Write uses combinational be_data_req (not registered be_write_done) because
    // stop_txn clears gpu_write_active in the same cycle be_write_done is set,
    // so the registered version arrives 1 cycle too late.
    val gpuReadDone  = gpu_active && be_data_ready && byte_idx === data_txn_len
    val gpuWriteDone = gpu_write_active && be_data_req && byte_idx === data_txn_len
    io.gpuMem.ready := gpuReadDone || gpuWriteDone
    io.gpuMem.data  := assembleGpuData()

    io.debug_stall_txn := stall_txn
    io.debug_stop_txn  := stop_txn
  }
}
