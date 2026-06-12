#pragma once

// Full-SoC verilator harness WITH the HDMI scanout in the loop.  Boots the same
// firmware as VerBorgSimulator (CPU + Borg render the framebuffer into the
// behavioral SDRAM), then — instead of reading the framebuffer back through the
// SDRAM backdoor — captures the image the way the MONITOR sees it: it runs the
// 640×480 VGA raster and records the scanout's RGB8 output (disp_*).  This goes
// through the scanout's own fbBase / fill FSM / FP16→RGB8 / display read, so
// display-side bugs (e.g. a stale scanout base) appear in the captured frame.

#include "../common/BorgSimulatorBase.h"
#include <VBorgHdmiSimTop.h>
#include <verilated.h>
#include <fstream>
#include <iostream>
#include <vector>

class VerBorgHdmiSimulator : public BorgSimulatorBase {
public:
    VBorgHdmiSimTop* model;
    bool booted = false;
    int      cur_back_buf          = 1;
    uint32_t frame_tile_size_words = 0;
    uint32_t out_base_word_buf0    = 0;

    VerBorgHdmiSimulator(const std::string& firmware_path, uint32_t w = 128, uint32_t h = 128) {
        model = new VBorgHdmiSimTop;
        flash = new QSPIMemory(1024 * 1024, true);
        psram = new QSPIMemory(8 * 1024 * 1024, false);

        width = w; height = h;
        psram_spi_word_offset = 0x1000 / 4;
        out_base_word = psram_spi_word_offset + (PSRAM_OUT_OFFSET / 4);
        uint32_t frame_tile_size = width * height * 2;
        marker_offset_word = out_base_word + frame_tile_size;
        frame_tile_size_words = frame_tile_size;
        out_base_word_buf0    = out_base_word;

        flash->load_bin(firmware_path);
        uint32_t* pw = (uint32_t*)psram->mem.data();
        pw[psram_spi_word_offset + 0] = width;
        pw[psram_spi_word_offset + 1] = height;

        model->dbg_we = 0; model->dbg_waddr = 0; model->dbg_wdata = 0; model->dbg_raddr = 0;
        model->clk = 0; model->rst_n = 0; model->ena = 1; model->ui_in = 0;
        for (int i = 0; i < 10; i++) { model->eval(); model->clk = 1; model->eval(); model->clk = 0; }
    }
    virtual ~VerBorgHdmiSimulator() override { delete model; }

    void clock_low()  { model->clk = 0; model->eval(); }
    void clock_high() { model->clk = 1; model->eval(); }
    virtual uint8_t get_uo_out() override { return model->uo_out; }
    virtual void set_ui_in(uint8_t v) override { model->ui_in = v; }
    virtual int get_uart_bit_pos() const override { return 0; }

    void dbg_write(uint32_t word, uint16_t data) {
        model->dbg_we = 1; model->dbg_waddr = word & 0xFFFFFF; model->dbg_wdata = data;
        clock_low(); clock_high(); model->dbg_we = 0;
    }
    uint16_t dbg_read(uint32_t word) {
        model->dbg_raddr = word & 0xFFFFFF; clock_low(); clock_high();
        return model->dbg_rdata;
    }
    void load_region(const std::vector<uint8_t>& src, uint32_t bStart, uint32_t bLen, uint32_t wBase) {
        for (uint32_t b = 0; b < bLen; b += 2) {
            uint16_t d = (uint16_t)src[bStart + b] | ((uint16_t)src[bStart + b + 1] << 8);
            dbg_write(wBase + (b >> 1), d);
        }
    }
    void boot() {
        load_region(flash->mem, 0, 0x20000, 0);
        load_region(psram->mem, 0, 0x20000, 0x800000);
        model->rst_n = 1;
    }
    virtual void host_write_psram_word(uint32_t word_addr, uint32_t value) override {
        uint32_t ba = word_addr * 4;
        if (ba + 3 < psram->mem.size()) {
            psram->mem[ba]=value&0xFF; psram->mem[ba+1]=(value>>8)&0xFF;
            psram->mem[ba+2]=(value>>16)&0xFF; psram->mem[ba+3]=(value>>24)&0xFF;
        }
        if (booted) {
            uint32_t lo = (ba >> 1) | 0x800000;
            dbg_write(lo, (uint16_t)(value & 0xFFFF));
            dbg_write(lo + 1, (uint16_t)(value >> 16));
        }
    }

    // Run until the firmware writes its DONE_MARKER (one frame rendered to SDRAM).
    bool render_one_frame(uint64_t max_cycles) {
        if (!booted) { boot(); booted = true; }
        set_ui_in(0x80);
        uint32_t cur_marker_off = cur_back_buf == 0 ? frame_tile_size_words
                                                    : 2 * frame_tile_size_words + 1;
        uint32_t marker_addr = ((out_base_word_buf0 + cur_marker_off) * 2) | 0x800000;
        for (uint64_t c = 0; c < max_cycles; c++) {
            model->dbg_raddr = marker_addr; clock_low(); clock_high();
            uint8_t uo = model->uo_out;
            if (uart.tick((uo >> get_uart_bit_pos()) & 1)) std::cout << (char)uart.byte() << std::flush;
            if (model->dbg_rdata == 0x0000DEAD) { cur_back_buf ^= 1; return true; }
        }
        return false;
    }

    // Run the VGA raster and capture the scanout's displayed RGB into a
    // width×height image (the centered active window).  `frames` lets the
    // scanout BRAM converge; the last frame is kept.
    void capture_display(const std::string& name, int frames = 4) {
        const int FBW = width, FBH = height;
        const int startX = (640 - FBW) / 2, startY = (480 - FBH) / 2;
        std::vector<uint8_t> img(FBW * FBH * 3, 0);
        set_ui_in(0x80);
        for (int f = 0; f < frames; f++) {
            for (int i = 0; i < 800 * 525; i++) {
                clock_low(); clock_high();
                if (model->disp_de) {
                    int x = (int)model->disp_hcnt - startX;
                    int y = (int)model->disp_vcnt - startY;
                    if (x >= 0 && x < FBW && y >= 0 && y < FBH) {
                        img[(y*FBW+x)*3+0] = model->disp_r;
                        img[(y*FBW+x)*3+1] = model->disp_g;
                        img[(y*FBW+x)*3+2] = model->disp_b;
                    }
                }
            }
        }
        std::string path = name + "_hdmi.ppm";
        std::ofstream f(path);
        f << "P3\n" << FBW << " " << FBH << "\n255\n";
        for (int y = 0; y < FBH; y++)
            for (int x = 0; x < FBW; x++)
                f << (int)img[(y*FBW+x)*3+0] << " " << (int)img[(y*FBW+x)*3+1]
                  << " " << (int)img[(y*FBW+x)*3+2] << "\n";
        std::cout << "[HDMI-SIM] displayed frame written: " << path << "\n";
    }
};
