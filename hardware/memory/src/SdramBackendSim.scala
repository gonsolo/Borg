// SdramModel.scala — Chisel behavioral SDRAM model for simulation tests.
//
// Provides SdramBackendSim: a drop-in replacement for SdramBackend whose
// SdramController is replaced by a SyncReadMem-backed model.
//
// The byte-serial protocol (MemBackendIO) is implemented here directly,
// mirroring SdramBackend's FSM but reading/writing a Chisel SyncReadMem.
//
// Usage:
//   simulate(new SdramBackendSim()) { dut => ... }
//
// The io bundle is identical to SdramBackendIO so existing test code
// (SdramReadPathTests) needs only `new SdramBackend()` → `new SdramBackendSim()`.

package memory

import chisel3._
import chisel3.util._

/** Behavioral SDRAM — wraps MemBackendIO, backed by SyncReadMem.
  *
  * @param words    Number of 16-bit words in the simulated SDRAM (default 4096 = 8 KB)
  * @param rdDelay  Cycles between startRead and first dataReady (simulates CAS latency)
  * @param wrDelay  Cycles between startWrite+full word and dataReq (write ack)
  */
class SdramBackendSimIO extends Bundle {
  val backend          = Flipped(new MemBackendIO)
  val debug_be_state   = Output(UInt(4.W))
  val debug_ctrl_state = Output(UInt(3.W))
  val debug_ctrl_rdy   = Output(Bool())
  val debug_readWord   = Output(UInt(16.W))
}

class SdramBackendSim(
  words:   Int = 4096,
  rdDelay: Int = 4,
  wrDelay: Int = 2
) extends Module {

  val io = IO(new SdramBackendSimIO)

  // ── Backing store ─────────────────────────────────────────────────────────
  val mem       = SyncReadMem(words, UInt(16.W))
  val wordBits  = log2Up(words)

  // ── FSM state encoding (matches SdramBackend for debug_be_state compat) ──
  val sIdle   = 0.U(4.W)
  val sWrReq  = 4.U(4.W)   // emit dataReq, waiting for byte 1
  val sWrWord = 5.U(4.W)   // latch byte 1, issue write
  val sWrWait = 6.U(4.W)   // wait for (simulated) write completion
  val sWrAck  = 9.U(4.W)   // ack the halfword; continue or stop
  val sRdReq  = 1.U(4.W)   // issue read
  val sRdWait = 2.U(4.W)   // wait for read data
  val sReadB  = 3.U(4.W)   // present bytes one at a time

  val state      = RegInit(sIdle)
  val counter    = RegInit(0.U(8.W))
  val byteAddr   = RegInit(0.U(25.W))
  val lane0      = RegInit(false.B)   // true when starting on an odd byte address
  val writeWord  = RegInit(0.U(16.W))
  val readWord   = RegInit(0.U(16.W))
  val rdByteIdx  = RegInit(0.U(1.W))
  // Captures the arbiter's one-cycle stopTxn pulse fired in sWrWait so that
  // sWrAck (one cycle later) can honor it.  Without this the backend loops
  // forever generating phantom halfwords on any multi-halfword CPU write.
  val wrStopPending = RegInit(false.B)

  // ── Defaults ──────────────────────────────────────────────────────────────
  io.backend.dataOut   := 0.U
  io.backend.dataReq   := false.B
  io.backend.dataReady := false.B
  io.backend.busy      := (state =/= sIdle)

  io.debug_be_state   := state
  io.debug_ctrl_state := 0.U
  io.debug_ctrl_rdy   := false.B
  io.debug_readWord   := readWord

  // SyncReadMem read port — we issue the read address combinatorially;
  // the result is available on the NEXT clock edge.
  val rdAddrWire = WireDefault(0.U(wordBits.W))
  val rdData     = mem.read(rdAddrWire)

  // ── FSM ───────────────────────────────────────────────────────────────────
  switch(state) {

    // ── Idle: wait for startWrite or startRead ──────────────────────────────
    is(sIdle) {
      when(io.backend.startWrite) {
        byteAddr  := io.backend.addrIn
        lane0     := io.backend.addrIn(0).asBool
        // First byte goes to low lane (even addr) or high lane (odd addr)
        writeWord := Mux(io.backend.addrIn(0),
          Cat(io.backend.dataIn, 0.U(8.W)),   // odd: first byte is high
          Cat(0.U(8.W), io.backend.dataIn))   // even: first byte is low
        state := sWrReq
      }.elsewhen(io.backend.startRead) {
        byteAddr  := io.backend.addrIn
        lane0     := io.backend.addrIn(0).asBool
        rdByteIdx := 0.U
        counter   := 0.U
        state     := sRdReq
      }
    }

    // ── Write: emit dataReq (byte 0 consumed, send byte 1) ─────────────────
    is(sWrReq) {
      io.backend.dataReq := true.B
      state := sWrWord
    }

    // ── Write: latch byte 1, commit word to SyncReadMem ────────────────────
    is(sWrWord) {
      val b1   = io.backend.dataIn
      val word = Mux(!lane0,
        Cat(b1, writeWord(7, 0)),     // even start: byte1 → high lane
        Cat(writeWord(15, 8), b1))    // odd start:  byte1 → low lane
      writeWord := word
      mem.write(byteAddr(wordBits, 1), word)   // word address = byteAddr >> 1
      counter := 0.U
      state   := sWrWait
    }

    // ── Write: simulate write latency, then ack ─────────────────────────────
    is(sWrWait) {
      counter := counter + 1.U
      when(counter >= wrDelay.U) {
        io.backend.dataReq := true.B   // signals halfword write complete to arbiter
        // Sample stopTxn at the same cycle dataReq pulses — the arbiter's
        // end-of-txn stop is combinational from be_data_req and gone next cycle.
        wrStopPending := io.backend.stopTxn
        state := sWrAck
      }
    }

    // ── Write ack: continue with next halfword or stop. Mirrors
    //    SdramBackend.sWrAck so multi-byte CPU writes (e.g. 4-byte `sw`)
    //    don't drop the second halfword on the floor.
    is(sWrAck) {
      when(wrStopPending || io.backend.stopTxn) {
        wrStopPending := false.B
        state := sIdle
      }.otherwise {
        byteAddr  := byteAddr + 2.U
        // Continuation halfwords are always word-aligned: byte 0 → low lane.
        writeWord := Cat(0.U(8.W), io.backend.dataIn)
        lane0     := false.B
        state     := sWrReq
      }
    }

    // ── Read: issue address to SyncReadMem; wait rdDelay cycles ────────────
    is(sRdReq) {
      rdAddrWire := byteAddr(wordBits, 1)
      counter    := 0.U
      state      := sRdWait
    }

    is(sRdWait) {
      rdAddrWire := byteAddr(wordBits, 1)   // keep addr stable during delay
      counter    := counter + 1.U
      when(counter >= rdDelay.U) {
        readWord := rdData              // latch SyncReadMem output
        state    := sReadB
      }
      when(io.backend.stopTxn) { state := sIdle }
    }

    // ── Read: present bytes one at a time ───────────────────────────────────
    is(sReadB) {
      val sendLow = (rdByteIdx === 0.U) === !lane0
      io.backend.dataReady := true.B
      io.backend.dataOut   := Mux(sendLow, readWord(7, 0), readWord(15, 8))

      when(io.backend.stopTxn) {
        state := sIdle
      }.otherwise {
        when(rdByteIdx === 1.U) {
          // Fetch next word (sequential reads)
          byteAddr  := byteAddr + 2.U
          rdByteIdx := 0.U
          counter   := 0.U
          state     := sRdReq
        }.otherwise {
          rdByteIdx := 1.U
        }
      }
    }
  }
}
