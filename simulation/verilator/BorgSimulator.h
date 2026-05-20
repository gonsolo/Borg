#pragma once

#include "../common/BorgSimulatorBase.h"

#include <VBorgSimTop.h>
#include <VBorgSimTop___024root.h>
#include <verilated.h>

class VerBorgSimulator : public BorgSimulatorBase {
public:
    VBorgSimTop* model;

    VerBorgSimulator(const std::string& firmware_path, uint32_t w = 32, uint32_t h = 32) {
        model = new VBorgSimTop;
        flash = new QSPIMemory(1024 * 1024, true); // 1MB flash
        psram = new QSPIMemory(8 * 1024 * 1024, false); // 8MB PSRAM

        width = w;
        height = h;
        psram_spi_word_offset = 0x1000 / 4;
        out_base_word = psram_spi_word_offset + (PSRAM_OUT_OFFSET / 4);

        // Step 25.4.2 Option A: tiled layout = 2 PSRAM words per pixel (lo=R|G, hi=B|Z).
        uint32_t frame_tile_size = width * height * 2;   // words
        marker_offset_word = out_base_word + frame_tile_size;

        flash->load_bin(firmware_path);

        uint32_t* psram_init_words = (uint32_t*)psram->mem.data();
        psram_init_words[psram_spi_word_offset + 0] = width;
        psram_init_words[psram_spi_word_offset + 1] = height;

        // Reset Sequence — drive backend inputs to a known state first.
        model->backend_done = 0;
        model->backend_busy = 0;
        model->backend_dataOut = 0;
        model->clk = 0;
        model->rst_n = 0;
        model->ena = 1;
        model->ui_in = 0;

        for (int i = 0; i < 10; i++) {
            model->eval();
            model->clk = 1;
            model->eval();
            model->clk = 0;
        }
        model->rst_n = 1;
    }

    virtual ~VerBorgSimulator() override {
        delete model;
    }

    virtual void clock_low() override {
        model->clk = 0;
        model->eval();
    }

    virtual void clock_high() override {
        model->clk = 1;
        model->eval();
    }

    virtual uint8_t get_uo_out() override {
        return model->uo_out;
    }

    virtual void set_ui_in(uint8_t val) override {
        model->ui_in = val;
    }

    virtual int get_uart_bit_pos() const override {
        return 0; // uo_out >> 0
    }

    // ── Flat MemBackendIO bus ──
    virtual uint32_t get_backend_addrIn()     override { return model->backend_addrIn; }
    virtual bool     get_backend_startRead()  override { return model->backend_startRead; }
    virtual bool     get_backend_startWrite() override { return model->backend_startWrite; }
    virtual uint16_t get_backend_dataIn()     override { return model->backend_dataIn; }
    virtual uint8_t  get_backend_byteEnIn()   override { return model->backend_byteEnIn; }
    virtual void set_backend_dataOut(uint16_t v) override { model->backend_dataOut = v; }
    virtual void set_backend_done(bool v)        override { model->backend_done = v; }
    virtual void set_backend_busy(bool v)        override { model->backend_busy = v; }

    virtual void host_write_psram_word(uint32_t word_addr, uint32_t value) override {
        uint32_t byte_addr = word_addr * 4;
        if (byte_addr + 3 < psram->mem.size()) {
            psram->mem[byte_addr] = value & 0xFF;
            psram->mem[byte_addr+1] = (value >> 8) & 0xFF;
            psram->mem[byte_addr+2] = (value >> 16) & 0xFF;
            psram->mem[byte_addr+3] = (value >> 24) & 0xFF;
        }
    }
};
