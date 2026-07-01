#include "BorgSimulatorBase.h"
#include <cassert>
#include <iostream>

void BorgSimulatorBase::load_texture(const std::string& tex_path, uint32_t tex_dim) {
    load_texture_to_flat(flat->mem.data(), tex_path, tex_dim, TEX_DRAM_BYTE_ADDR_FIXED);
}

void BorgSimulatorBase::set_camera_angles(float rx, float ry) {
    uint32_t* flat_words = (uint32_t*)flat->mem.data();
    float* flat_floats = (float*)&flat_words[flat_spi_word_offset];
    flat_floats[2] = rx;
    flat_floats[3] = ry;
    host_write_flat_word(flat_spi_word_offset + 2, flat_words[flat_spi_word_offset + 2]);
    host_write_flat_word(flat_spi_word_offset + 3, flat_words[flat_spi_word_offset + 3]);
}

void BorgSimulatorBase::save_ppm(const std::string& name) {
    ::save_ppm(name, width, height, out_base_word, flat->mem);
}

// ── MemBackendIO handshake protocol ─────────────────────────────────────────
//
// The MemoryController exposes a flat, word-wide bus (MemBackendIO) instead of
// QSPI pins.  The simulator drives the *input* ports (dataOut, done, busy,
// accept) and reads the *output* ports (addrIn, startRead, startWrite, dataIn,
// byteEnIn, lenIn).
//
// Single-word read:
//   cycle 0: MC asserts startRead=1 with addrIn.
//   cycle 1: simulator latches addrIn, sets be_delay = BE_DELAY, sets busy=1.
//   cycle BE_DELAY: simulator drives done=1, dataOut=fetched word.
//   cycle BE_DELAY+1: done de-asserts, be_delay → -1 (idle).
//
// Single-word write:
//   cycle 0: MC asserts startWrite=1 with addrIn, dataIn, byteEnIn.
//   simulator writes the word immediately, sets be_delay = BE_DELAY.
//   (done fires BE_DELAY cycles later, same as a read.)
//
// Burst write (lenIn > 1):
//   cycle 0: startWrite latched; first word written; be_burst_rem = lenIn-1.
//   cycles 1..rem: simulator pulses accept=1 each cycle; MC advances dataIn on
//     each posedge that sees accept.  Simulator writes each word to memory.
//   After last word: be_delay = 0, done fires the next cycle.
//   During the burst be_delay stays < 0; no new transactions are accepted.
//
// BE_DELAY = 2: MemoryController parks in sWait until done arrives, so any
// value ≥ 1 is protocol-correct.  2 gives a one-cycle margin for rounding.
//
// Double-buffer DRAM layout (set by subclass constructors):
//   FRAME_STRIDE = frame_tile_size_words + 1          (tile data + one marker word)
//   buf 0 pixel data: [out_base_word_buf0 .. +tile_size)
//   buf 0 marker:      out_base_word_buf0 + frame_tile_size_words
//   buf 1 pixel data: [out_base_word_buf0 + STRIDE .. +tile_size)
//   buf 1 marker:      out_base_word_buf0 + 2*frame_tile_size_words + 1
//   Firmware writes 0x0000DEAD to the current back-buffer marker when the frame
//   is complete; step() detects this and returns true.
//
// Subclass constructor MUST set frame_tile_size_words and out_base_word_buf0
// before calling step().

static const int BE_DELAY = 2;

bool BorgSimulatorBase::step(uint32_t cycles_to_run) {
    assert(frame_tile_size_words > 0 &&
           "subclass constructor must set frame_tile_size_words before step()");

    uint32_t* flat_words = (uint32_t*)flat->mem.data();

    uint32_t cur_marker_off =
        cur_back_buf == 0
        ? frame_tile_size_words
        : 2 * frame_tile_size_words + 1;
    uint32_t cur_marker_word = out_base_word_buf0 + cur_marker_off;

    for (uint32_t c = 0; c < cycles_to_run; c++) {
        // Gated by uart_cts (sampled from get_uo_out()'s rts bit at the end of
        // the previous cycle) so a new byte never starts while UartRx.scala is
        // still parked in FSM_READY holding an unread one — otherwise the byte
        // is silently dropped (the receiver is deaf to the wire in that state).
        uint8_t rxd_bit = uart_tx.tick(uart_cts);
        set_ui_in((uint8_t)(0x00 | (rxd_bit << 7)));
        // --- Drive backend INPUT ports so they are stable across the posedge.
        //     During a burst: accept is pulsed every cycle until all words are
        //     consumed; the model advances its dataIn on each posedge that sees
        //     accept=true.  done fires once after the last word is written.
        bool in_burst = (be_burst_rem > 0);
        bool drive_done = (be_delay == 0 && !in_burst);
        set_backend_accept(in_burst);
        set_backend_done(drive_done);
        set_backend_dataOut(drive_done ? be_data : 0);
        set_backend_busy(be_delay >= 0 || in_burst);

        // Two-phase eval: clock_high() is the posedge that advances the design
        // and at which the MemoryController samples done/dataOut/accept.
        clock_low();
        clock_high();

        // --- After the posedge: handle burst streaming, then retire/latch.
        if (in_burst) {
            // The model has advanced dataIn in response to accept.  Write the
            // current word and decrement the burst counter.
            flat_write16(flash, flat, be_burst_waddr, get_backend_dataIn(), 3);
            be_burst_waddr++;
            be_burst_rem--;
            if (be_burst_rem == 0) {
                be_delay = 0;  // fire done next cycle
            }
            // be_delay stays < 0 while streaming; do NOT check for new transactions.
        } else {
            if (be_delay == 0)      be_delay = -1;   // done consumed → idle
            else if (be_delay > 0)  be_delay--;

            if (be_delay < 0) {
                uint32_t a = get_backend_addrIn();
                if (get_backend_startRead()) {
                    be_data  = flat_read16(flash, flat, a);
                    be_delay = BE_DELAY;
                } else if (get_backend_startWrite()) {
                    int len = get_backend_lenIn();
                    flat_write16(flash, flat, a, get_backend_dataIn(), get_backend_byteEnIn());
                    if (len > 1) {
                        // Burst: stream remaining len-1 words via accept/dataIn.
                        be_burst_waddr = a + 1;
                        be_burst_rem   = len - 1;
                        // be_delay stays -1; burst drives accept immediately next cycle.
                    } else {
                        be_data  = 0;
                        be_delay = BE_DELAY;
                    }
                }
            }
        }

        fast_sim_snoop(); // Subclass hook for writing to memory arrays in fast mode

        // UART Decode
        uint8_t uo_out = get_uo_out();
        if (uart.tick((uo_out >> get_uart_bit_pos()) & 1)) {
            std::cout << (char)uart.byte() << std::flush;
        }
        // uo_out := Fill(4, Cat(uart_rts, uart_txd)) (PeriUart.scala) — rts sits
        // at the bit immediately above txd in every 2-bit replica.
        uart_cts = !((uo_out >> (get_uart_bit_pos() + 1)) & 1);

        static const bool uart_dbg = getenv("CTS_UART_DBG") != nullptr;
        if (uart_dbg) {
            static uint64_t dbgcyc = 0;
            static uint64_t mem_activity = 0;
            dbgcyc++;
            if (get_backend_startRead() || get_backend_startWrite()) mem_activity++;
            if (dbgcyc % 500000 == 0) {
                std::cerr << "[UARTDBG] cyc=" << dbgcyc
                          << " cts=" << uart_cts
                          << " tx_empty=" << uart_tx.empty()
                          << " mem_activity=" << mem_activity
                          << " uo_out=0x" << std::hex << (int)uo_out << std::dec
                          << std::endl;
            }
        }

        // Check completion marker in the current back-buffer's slot.
        if (flat_words[cur_marker_word] == 0x0000DEAD) {
            uint32_t buf_fb_off = cur_back_buf == 0 ? 0 : frame_tile_size_words + 1;
            out_base_word      = out_base_word_buf0 + buf_fb_off;
            marker_offset_word = cur_marker_word;
            flat_words[cur_marker_word] = 0;  // clear for firmware's done-wait loop
            cur_back_buf ^= 1;
            return true;
        }
    }
    return false;
}
