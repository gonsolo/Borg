#include "BorgSimulatorBase.h"
#include <iostream>

void BorgSimulatorBase::load_texture(const std::string& tex_path, uint32_t tex_dim) {
    load_texture_to_psram(psram->mem.data(), tex_path, tex_dim, TEX_PSRAM_BYTE_ADDR_FIXED);
}

void BorgSimulatorBase::set_camera_angles(float rx, float ry) {
    uint32_t* psram_words = (uint32_t*)psram->mem.data();
    float* psram_floats = (float*)&psram_words[psram_spi_word_offset];
    psram_floats[2] = rx;
    psram_floats[3] = ry;
    host_write_psram_word(psram_spi_word_offset + 2, psram_words[psram_spi_word_offset + 2]);
    host_write_psram_word(psram_spi_word_offset + 3, psram_words[psram_spi_word_offset + 3]);
}

void BorgSimulatorBase::save_ppm(const std::string& name) {
    ::save_ppm(name, width, height, out_base_word, psram->mem);
}

// Backend handshake latency (cycles from observing a start pulse to driving
// `done`).  MemoryController parks in its sWait state until `done` arrives, so
// any value >= 1 is correct; 2 gives a small safety margin.
static const int BE_DELAY = 2;

bool BorgSimulatorBase::step(uint32_t cycles_to_run) {
    uint32_t* psram_words = (uint32_t*)psram->mem.data();

    // FRAME_STRIDE = frame_tile_size_words + 1.
    // back_buf=0 marker: out_base_word_buf0 + frame_tile_size_words.
    // back_buf=1 marker: out_base_word_buf0 + 2*frame_tile_size_words + 1.
    uint32_t cur_marker_off =
        cur_back_buf == 0
        ? frame_tile_size_words
        : 2 * frame_tile_size_words + 1;
    uint32_t cur_marker_word = out_base_word_buf0 + cur_marker_off;

    set_ui_in(0x80); // Hold UART RXD (ui_in(7)) high (idle)

    for (uint32_t c = 0; c < cycles_to_run; c++) {
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
            flat_write16(flash, psram, be_burst_waddr, get_backend_dataIn(), 3);
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
                    be_data  = flat_read16(flash, psram, a);
                    be_delay = BE_DELAY;
                } else if (get_backend_startWrite()) {
                    int len = get_backend_lenIn();
                    flat_write16(flash, psram, a, get_backend_dataIn(), get_backend_byteEnIn());
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

        // Check completion marker in the current back-buffer's slot.
        if (psram_words[cur_marker_word] == 0x0000DEAD) {
            uint32_t buf_fb_off = cur_back_buf == 0 ? 0 : frame_tile_size_words + 1;
            out_base_word      = out_base_word_buf0 + buf_fb_off;
            marker_offset_word = cur_marker_word;
            psram_words[cur_marker_word] = 0;  // clear for firmware's done-wait loop
            cur_back_buf ^= 1;
            return true;
        }
    }
    return false;
}
