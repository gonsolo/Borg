#pragma once

#include "common_sim.h"
#include "texture_loader.h"
#include "uart_decoder.h"
#include <string>

class BorgSimulatorBase {
public:
    QSPIMemory* flash;
    QSPIMemory* psram;
    UartDecoder uart;
    bool fast_mode;
    
    uint32_t width;
    uint32_t height;
    uint32_t psram_spi_word_offset;
    uint32_t out_base_word;
    uint32_t marker_offset_word;

    BorgSimulatorBase() 
        : flash(nullptr), psram(nullptr), width(0), height(0),
          psram_spi_word_offset(0), out_base_word(0), marker_offset_word(0) {}
          
    virtual ~BorgSimulatorBase() {
        if (flash) delete flash;
        if (psram) delete psram;
    }

    // Abstract hardware interface to be implemented by verilator/arcilator backends
    virtual void clock_low() = 0;
    virtual void clock_high() = 0;
    virtual uint8_t get_uio_out() = 0;
    virtual uint8_t get_uo_out() = 0;
    virtual void set_uio_in(uint8_t val) = 0;
    virtual void set_ui_in(uint8_t val) = 0;
    virtual int get_uart_bit_pos() const = 0;

    // Optional: Backends can override this to implement fast_sim_en memory snooping
    virtual void fast_sim_snoop() {}
    
    // Shared methods (step() will be moved here in Step 3.2)
    void load_texture(const std::string& tex_path, uint32_t tex_dim = 32);
    void set_camera_angles(float rx, float ry);
    void save_ppm(const std::string& name);
    bool step(uint32_t cycles_to_run);
};
