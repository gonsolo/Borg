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
    
    uint32_t width;
    uint32_t height;
    uint32_t psram_spi_word_offset;
    uint32_t out_base_word;
    uint32_t marker_offset_word;

    // Flat MemBackendIO handshake state (persists across step() chunks).
    // be_delay: -1 = idle; counts down to 0, at which point `done` is pulsed.
    int      be_delay = -1;
    uint16_t be_data  = 0;   // captured read result presented on `done`

    // Burst write state: be_burst_rem > 0 means we are streaming words.
    // accept is pulsed each cycle while burst is active; the model advances
    // its dataIn register and we read/write each word to memory.
    int      be_burst_rem  = 0;
    uint32_t be_burst_waddr = 0;  // next burst write word address

    // Double-buffer tracking: mirrors firmware's static back_buf (starts at 1).
    // frame_tile_size_words and out_base_word_buf0 must be set by the subclass
    // constructor before step() is called.
    int      cur_back_buf          = 1;
    uint32_t frame_tile_size_words = 0;
    uint32_t out_base_word_buf0    = 0;

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
    virtual uint8_t get_uo_out() = 0;
    virtual void set_ui_in(uint8_t val) = 0;
    virtual int get_uart_bit_pos() const = 0;

    // Legacy QSPI pin interface — kept (non-pure) so the arcilator backend,
    // which still overrides these, continues to compile.  The verilator
    // backend no longer uses them (it drives the flat MemBackendIO bus below).
    virtual uint8_t get_uio_out() { return 0; }
    virtual void set_uio_in(uint8_t /*val*/) {}

    // Flat MemBackendIO interface — the verilator sim exposes the
    // MemoryController backend bus directly (no QSPI).  Backend-output ports
    // (driven by the MemoryController) are read; backend-input ports are set.
    // Default no-op so backends that don't use them still compile.
    virtual uint32_t get_backend_addrIn()     { return 0; }
    virtual bool     get_backend_startRead()  { return false; }
    virtual bool     get_backend_startWrite() { return false; }
    virtual uint16_t get_backend_dataIn()     { return 0; }
    virtual uint8_t  get_backend_byteEnIn()   { return 0; }
    virtual uint8_t  get_backend_lenIn()      { return 1; }
    virtual void set_backend_dataOut(uint16_t /*v*/) {}
    virtual void set_backend_done(bool /*v*/)        {}
    virtual void set_backend_busy(bool /*v*/)        {}
    virtual void set_backend_accept(bool /*v*/)      {}

    // Optional: Backends can override this to implement custom memory snooping
    virtual void fast_sim_snoop() {}
    virtual void host_write_psram_word(uint32_t word_addr, uint32_t value) {}
    
    // Shared methods (step() will be moved here in Step 3.2)
    void load_texture(const std::string& tex_path, uint32_t tex_dim = 32);
    void set_camera_angles(float rx, float ry);
    void save_ppm(const std::string& name);
    virtual bool step(uint32_t cycles_to_run);

    // Print the SDRAM bandwidth-demand histogram (read/write beats by region),
    // read from the RTL beat-counters.  No-op unless the backend exposes them.
    virtual void report_bandwidth() {}
};
