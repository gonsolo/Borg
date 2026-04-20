#pragma once

#include "../common/BorgSimulatorBase.h"

#include <Vtt_um_gonsolo_borg.h>
#include <Vtt_um_gonsolo_borg___024root.h>
#include <verilated.h>

class VerBorgSimulator : public BorgSimulatorBase {
public:
    Vtt_um_gonsolo_borg* model;

    // Convenience accessor for the Chisel flash SyncReadMem array
    auto& flash_arr()  { return model->rootp->tt_um_gonsolo_borg__DOT__uo_out_val_memSim__DOT__sim_flash_ext_ext__DOT__Memory; }

    VerBorgSimulator(const std::string& firmware_path, bool fast_mode_val = false, uint32_t w = 32, uint32_t h = 32) {
        fast_mode = fast_mode_val;
        model = new Vtt_um_gonsolo_borg;
        flash = new QSPIMemory(1024 * 1024, true); // 1MB flash
        psram = new QSPIMemory(8 * 1024 * 1024, false); // 8MB PSRAM
        
        width = w;
        height = h;
        psram_spi_word_offset = 0x1000 / 4;
        out_base_word = psram_spi_word_offset + (PSRAM_OUT_OFFSET / 4);
        
        uint32_t frame_fb_size = width * height * 3;
        uint32_t frame_zb_size = width * height;
        marker_offset_word = out_base_word + frame_fb_size + frame_zb_size;
        
        flash->load_bin(firmware_path);
        
        uint32_t* psram_init_words = (uint32_t*)psram->mem.data();
        psram_init_words[psram_spi_word_offset + 0] = width;
        psram_init_words[psram_spi_word_offset + 1] = height;

        // STEP 1: Only load firmware into the Chisel flash array.
        // Data (PSRAM) still goes through the C++ QSPI model.
        if (fast_mode) {
            for (size_t i = 0; i < flash->mem.size() && i < flash_arr().size(); i++) {
                flash_arr()[i] = flash->mem[i];
            }
            std::cout << "[SIM] Fast mode: firmware loaded into Chisel flash array ("
                      << flash->mem.size() << " bytes). Data via QSPI.\n";
        }

        // Reset Sequence
        model->clk = 0;
        model->rst_n = 0;
        model->ena = 1;
        model->ui_in = 0;
        model->uio_in = 0;

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
    
    virtual uint8_t get_uio_out() override {
        return model->uio_out;
    }
    
    virtual uint8_t get_uo_out() override {
        return model->uo_out;
    }
    
    virtual void set_uio_in(uint8_t val) override {
        model->uio_in = val;
    }
    
    virtual void set_ui_in(uint8_t val) override {
        model->ui_in = val;
    }
    
    virtual int get_uart_bit_pos() const override {
        return 0; // uo_out >> 0
    }
};
