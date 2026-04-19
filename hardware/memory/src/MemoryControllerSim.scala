// Copyright Michael Bell 2024
// Conversion to Chisel Copyright © 2026 Andreas Wendleder
// SPDX-License-Identifier: Apache-2.0

package memory

import chisel3._
import chisel3.util._
import borg.GpuReadIO
import tinyqv.cpu.{InstrFetchIO, MemBusIO}

/** Fast-simulation memory controller variant.
  *
  * Implements the same [[MemoryControllerIO]] as [[MemoryController]] but bypasses
  * QSPI serialization entirely. Uses SyncReadMem arrays that the C++ Verilator
  * model can access directly, accelerating simulation by ~25× (1 instr/2 cycles
  * vs ~50 cycles over real QSPI).
  *
  * SPI outputs are tied off — the fast sim never drives the physical bus.
  */
class MemoryControllerSim extends Module {
  val io = IO(new MemoryControllerIO)

  // Unified simulation memories — SyncReadMem so CIRCT emits external SRAM
  // modules whose backing arrays are directly accessible from C++.
  val sim_flash_ext = SyncReadMem(1048576, UInt(8.W))  // 1 MB flash/firmware
  val sim_psram_ext = SyncReadMem(8388608, UInt(8.W))  // 8 MB PSRAM (CPU read/write)

  // Separate 32-bit-wide BRAM for GPU reads — avoids multi-port contention
  // with CPU reads on sim_psram_ext.  Indexed by word address (byte_addr >> 2).
  // Only 1 MB (GPU addr is 20-bit); C++ harness packs 4 bytes per entry.
  val sim_psram_gpu = SyncReadMem(262144, UInt(32.W)) // 256K words × 32-bit = 1 MB

  // Dummy write ports to prevent CIRCT from constant-folding the memories away.
  // The never-asserted write-enable keeps the memory alive while guaranteeing
  // the synthesised logic is unchanged.
  val flash_wen = WireDefault(false.B)
  dontTouch(flash_wen)
  when(flash_wen) {
    sim_flash_ext.write(0.U(20.W), 0.U(8.W))
    sim_psram_ext.write(0.U(23.W), 0.U(8.W))
    sim_psram_gpu.write(0.U(18.W), 0.U(32.W))
  }

  // ---------------------------------------------------------------------------
  // Instruction Fetch (fast path — 1 word every 2 cycles)
  // ---------------------------------------------------------------------------
  val instr_active = RegInit(false.B)
  val started      = RegInit(false.B)
  val stopped      = RegInit(false.B)

  val instr_addr_byte = Cat(io.instrFetch.instr_addr, 0.U(1.W))
  val fetch_en_0 = (io.instrFetch.instr_fetch_restart || instr_active) &&
                   !io.instrFetch.instr_fetch_stall
  val fetch_en_1 = RegNext(fetch_en_0)

  val flash_rd0 = sim_flash_ext.read(instr_addr_byte(19, 0),       fetch_en_0)
  val flash_rd1 = sim_flash_ext.read(instr_addr_byte(19, 0) + 1.U, fetch_en_0)

  when(io.instrFetch.instr_fetch_restart) { instr_active := true.B }

  // started/stopped MUST be single-cycle pulses (matching real MemoryController).
  // The CPU uses these to toggle instr_fetch_running in an elsewhen chain.
  started := io.instrFetch.instr_fetch_restart
  stopped := false.B  // We never stop the instruction stream in sim mode

  io.instrFetch.instr_fetch_started := started
  io.instrFetch.instr_fetch_stopped := stopped

  // Self-throttle: after delivering a word, skip one cycle to prevent buffer
  // overflow without creating a combinational loop with the CPU's stall signal.
  val instr_ready_out = Wire(Bool())
  val just_delivered  = RegNext(instr_ready_out, false.B)
  instr_ready_out     := instr_active && fetch_en_1 && !just_delivered

  io.instrFetch.instr_ready := instr_ready_out
  io.instrFetch.instr_data  := Cat(flash_rd1, flash_rd0)

  // ---------------------------------------------------------------------------
  // CPU Data Path (PSRAM — fast bypass)
  // ---------------------------------------------------------------------------
  val psram_read_active  = RegInit(false.B)
  val psram_write_active = RegInit(false.B)
  dontTouch(psram_write_active)  // Keep alive for C++ write tracking
  val psram_read_data    = RegInit(0.U(32.W))

  val is_psram_addr = io.cpuData.addr(24, 23) === 2.U

  // Isolated read ports to avoid FIRRTL synchronous-memory trapping
  val psram_addr_wire = io.cpuData.addr(22, 0)
  val r0 = sim_psram_ext(psram_addr_wire)
  val r1 = sim_psram_ext(psram_addr_wire + 1.U)
  val r2 = sim_psram_ext(psram_addr_wire + 2.U)
  val r3 = sim_psram_ext(psram_addr_wire + 3.U)

  // Capture memory value on cycle 2 to compensate for inferred clock enable
  val read_latch = RegNext(
    io.cpuData.readN =/= 3.U && !psram_read_active && is_psram_addr, false.B
  )
  when(read_latch) { psram_read_data := Cat(r3, r2, r1, r0) }

  when(io.cpuData.readN =/= 3.U && !psram_read_active && is_psram_addr) {
    psram_read_active := true.B
  } .elsewhen(io.cpuData.writeN =/= 3.U && !psram_write_active && is_psram_addr) {
    psram_write_active := true.B
    val w_addr = io.cpuData.addr(22, 0)
    val w_data = io.cpuData.dataOut
    when(io.cpuData.writeN === 2.U) {  // Word
      sim_psram_ext(w_addr + 2.U) := w_data(23, 16)
      sim_psram_ext(w_addr + 3.U) := w_data(31, 24)
    }
    when(io.cpuData.writeN === 1.U || io.cpuData.writeN === 2.U) {  // Halfword or Word
      sim_psram_ext(w_addr + 1.U) := w_data(15, 8)
    }
    // Byte, Halfword, Word all write byte 0
    sim_psram_ext(w_addr) := w_data(7, 0)
  }

  // Clear active flags once the CPU drops the request
  when(psram_read_active  && io.cpuData.readN  === 3.U) { psram_read_active  := false.B }
  when(psram_write_active && io.cpuData.writeN === 3.U) { psram_write_active := false.B }

  io.cpuData.ready   := psram_read_active || psram_write_active
  io.cpuData.dataIn := psram_read_data

  // ---------------------------------------------------------------------------
  // GPU Read Port — fast bypass (Step 19.2)
  //
  // The rasterizer's sTexFetch holds req=true across two consecutive reads with
  // different addresses. After each read completes (ready pulse), we auto-clear
  // pending so the next cycle re-latches the (now-updated) address.
  // This matches the real MemoryController where gpu_active goes false after
  // each completed SPI transaction.
  // ---------------------------------------------------------------------------
  val gpu_read_pending = RegInit(false.B)
  val gpu_data_valid   = RegInit(false.B)
  val gpu_addr_reg     = RegInit(0.U(20.W))  // Must match GpuReadIO.addr width

  // Latch address on new request
  when(io.gpuRead.req && !gpu_read_pending) {
    gpu_addr_reg     := io.gpuRead.addr
    gpu_read_pending := true.B
    gpu_data_valid   := false.B
  }

  // SyncReadMem has 1-cycle read latency: at T+1 gpu_addr_reg is stable,
  // read fires; at T+2 data is valid on the output.
  when(gpu_read_pending && !gpu_data_valid) {
    gpu_data_valid := true.B
  }

  // Single 32-bit read from dedicated GPU BRAM (word-addressed, combinational)
  val gpu_word = sim_psram_gpu(gpu_addr_reg(19, 2))

  // Drive outputs — combinational data, ready gated by data_valid.
  // At T+2 both are correct: data_valid=true and gpu_word holds T+1’s read.
  io.gpuRead.data  := gpu_word
  io.gpuRead.ready := gpu_data_valid

  // Auto-clear after data delivered so back-to-back reads re-latch the address.
  when(gpu_data_valid || (gpu_read_pending && !io.gpuRead.req)) {
    gpu_read_pending := false.B
    gpu_data_valid   := false.B
  }

  // ---------------------------------------------------------------------------
  // SPI outputs — tied off; fast sim bypasses QSPI entirely
  // ---------------------------------------------------------------------------
  io.qspiPins.dataOut     := 0.U
  io.qspiPins.dataOe      := 0.U
  io.qspiPins.clkOut      := false.B
  io.qspiPins.flashSelect := false.B
  io.qspiPins.ramASelect  := false.B
  io.qspiPins.ramBSelect  := false.B

  io.debug_stall_txn := false.B
  io.debug_stop_txn  := false.B
}
