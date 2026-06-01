#pragma once

#include "../common/BorgSimulatorBase.h"

#include <VBorgSimTop.h>
#include <VBorgSimTop___024root.h>
#include <verilated.h>
#include <iostream>

// VerBorgSimulator drives BorgSimTop, whose MemoryController backend is a real
// Chisel SdramBackendSim (behavioral SDRAM).  The host flash/psram byte vectors
// are kept only as a staging area: at boot they are copied INTO the Chisel
// memory via the dbg_* backdoor, and on completion the framebuffer is copied
// back OUT so the existing save_ppm (which reads psram->mem) works unchanged.
//
// Address mapping (MemBackendIO 24-bit word ⇄ host byte vectors):
//   flash byte F  ⇄ SDRAM word  (F >> 1)               (boot/firmware region)
//   psram byte P  ⇄ SDRAM word  ((P >> 1) | 0x800000)  (PSRAM region, bit23=1)
class VerBorgSimulator : public BorgSimulatorBase {
public:
    VBorgSimTop* model;
    bool booted = false;
    int      cur_back_buf         = 1;  // mirrors firmware's static back_buf (starts at 1)
    uint32_t frame_tile_size_words = 0;
    uint32_t out_base_word_buf0    = 0;  // buf=0 FB base (constant after construction)

    VerBorgSimulator(const std::string& firmware_path, uint32_t w = 32, uint32_t h = 32) {
        model = new VBorgSimTop;
        flash = new QSPIMemory(1024 * 1024, true);
        psram = new QSPIMemory(8 * 1024 * 1024, false);

        width = w;
        height = h;
        psram_spi_word_offset = 0x1000 / 4;
        out_base_word = psram_spi_word_offset + (PSRAM_OUT_OFFSET / 4);
        uint32_t frame_tile_size = width * height * 2;
        marker_offset_word = out_base_word + frame_tile_size;
        frame_tile_size_words = frame_tile_size;
        out_base_word_buf0    = out_base_word;

        flash->load_bin(firmware_path);

        uint32_t* psram_init_words = (uint32_t*)psram->mem.data();
        psram_init_words[psram_spi_word_offset + 0] = width;
        psram_init_words[psram_spi_word_offset + 1] = height;

        model->dbg_we = 0; model->dbg_waddr = 0; model->dbg_wdata = 0; model->dbg_raddr = 0;
        model->clk = 0; model->rst_n = 0; model->ena = 1; model->ui_in = 0;
        for (int i = 0; i < 10; i++) { model->eval(); model->clk = 1; model->eval(); model->clk = 0; }
        // NB: keep rst_n=0 — boot() loads memory then deasserts reset.
    }

    virtual ~VerBorgSimulator() override { delete model; }

    void clock_low()  { model->clk = 0; model->eval(); }
    void clock_high() { model->clk = 1; model->eval(); }

    virtual uint8_t get_uo_out() override { return model->uo_out; }
    virtual void set_ui_in(uint8_t val) override { model->ui_in = val; }
    virtual int get_uart_bit_pos() const override { return 0; }

    void dbg_write(uint32_t word, uint16_t data) {
        model->dbg_we = 1; model->dbg_waddr = word & 0xFFFFFF; model->dbg_wdata = data;
        clock_low(); clock_high();
        model->dbg_we = 0;
    }
    uint16_t dbg_read(uint32_t word) {
        model->dbg_raddr = word & 0xFFFFFF;
        clock_low(); clock_high();
        return model->dbg_rdata;
    }

    // Copy a host byte-vector region into the Chisel SDRAM (16-bit words).
    void load_region(const std::vector<uint8_t>& src, uint32_t byteStart,
                     uint32_t byteLen, uint32_t wordBase) {
        for (uint32_t b = 0; b < byteLen; b += 2) {
            uint16_t d = (uint16_t)src[byteStart + b] | ((uint16_t)src[byteStart + b + 1] << 8);
            dbg_write(wordBase + (b >> 1), d);
        }
    }

    void boot() {
        // Firmware/boot image: flash byte F → SDRAM word F>>1.
        load_region(flash->mem, 0, 0x20000, 0);
        // Config + texture: psram byte P → SDRAM word (P>>1)|0x800000.
        load_region(psram->mem, 0, 0x20000, 0x800000);
        // Release reset — CPU now boots from the loaded image.
        model->rst_n = 1;
    }

    // Copy the framebuffer + marker region back out to psram->mem for save_ppm.
    void readback_framebuffer() {
        for (uint32_t w32 = out_base_word; w32 <= marker_offset_word + 8; w32++) {
            uint16_t lo = dbg_read((w32 * 2) | 0x800000);
            uint16_t hi = dbg_read((w32 * 2 + 1) | 0x800000);
            uint8_t* p = &psram->mem[w32 * 4];
            p[0] = lo & 0xFF; p[1] = lo >> 8; p[2] = hi & 0xFF; p[3] = hi >> 8;
        }
    }

    virtual bool step(uint32_t cycles_to_run) override {
        if (!booted) { boot(); booted = true; }
        set_ui_in(0x80); // UART RXD idle

        // FRAME_STRIDE = frame_tile_size_words + 1 (FB + 1 marker word per buffer).
        // back_buf=0 marker is at out_base_word_buf0 + frame_tile_size_words.
        // back_buf=1 marker is at out_base_word_buf0 + 2*frame_tile_size_words + 1.
        uint32_t cur_marker_off =
            cur_back_buf == 0
            ? frame_tile_size_words
            : 2 * frame_tile_size_words + 1;
        uint32_t sdram_marker_addr = ((out_base_word_buf0 + cur_marker_off) * 2) | 0x800000;

        for (uint32_t c = 0; c < cycles_to_run; c++) {
            model->dbg_raddr = sdram_marker_addr;
            clock_low(); clock_high();

            uint8_t uo_out = model->uo_out;
            if (uart.tick((uo_out >> get_uart_bit_pos()) & 1))
                std::cout << (char)uart.byte() << std::flush;

            if (model->dbg_rdata == 0x0000DEAD) {
                // Point out_base_word and marker_offset_word at the rendered buffer.
                uint32_t buf_fb_off =
                    cur_back_buf == 0 ? 0 : frame_tile_size_words + 1;
                out_base_word      = out_base_word_buf0 + buf_fb_off;
                marker_offset_word = out_base_word_buf0 + cur_marker_off;
                readback_framebuffer();
                // Clear the SDRAM marker so the firmware's done-wait loop can exit.
                dbg_write(sdram_marker_addr,     0);
                dbg_write(sdram_marker_addr + 1, 0);
                cur_back_buf ^= 1;
                return true;
            }
        }
        return false;
    }

    virtual void host_write_psram_word(uint32_t word_addr, uint32_t value) override {
        uint32_t byte_addr = word_addr * 4;
        if (byte_addr + 3 < psram->mem.size()) {
            psram->mem[byte_addr]   = value & 0xFF;
            psram->mem[byte_addr+1] = (value >> 8) & 0xFF;
            psram->mem[byte_addr+2] = (value >> 16) & 0xFF;
            psram->mem[byte_addr+3] = (value >> 24) & 0xFF;
        }
        if (booted) {
            // Push the update into the live SDRAM model so running firmware sees it.
            // psram byte P → SDRAM 16-bit word (P>>1) | 0x800000.
            uint32_t sdram_lo = (byte_addr >> 1) | 0x800000;
            dbg_write(sdram_lo,     (uint16_t)(value & 0xFFFF));
            dbg_write(sdram_lo + 1, (uint16_t)(value >> 16));
        }
    }
};
