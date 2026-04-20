#pragma once
#include "../common/BorgSimulatorBase.h"
#include <string>

struct tt_um_gonsolo_borg;

class ArcBorgSimulator : public BorgSimulatorBase {
public:
    tt_um_gonsolo_borg* model;
    
    ArcBorgSimulator(const std::string& firmware_path, bool fast_mode_val = true, uint32_t w = 32, uint32_t h = 32);
    virtual ~ArcBorgSimulator() override;
    
    virtual void clock_low() override;
    virtual void clock_high() override;
    virtual uint8_t get_uio_out() override;
    virtual uint8_t get_uo_out() override;
    virtual void set_uio_in(uint8_t val) override;
    virtual void set_ui_in(uint8_t val) override;
    int get_uart_bit_pos() const override;
    virtual void host_write_psram_word(uint32_t word_addr, uint32_t value) override;

    uint8_t* get_storage_ptr();
    int ext_psram_offset = -1;
    void backend_reset();
    int find_memory_offset(const std::string& pattern);
};
