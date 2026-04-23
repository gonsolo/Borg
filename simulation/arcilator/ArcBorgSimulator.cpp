#include "arc.h"
#include "ArcBorgSimulator.h"
#include <iostream>
#include <fstream>
#include <sstream>

ArcBorgSimulator::ArcBorgSimulator(const std::string& firmware_path, uint32_t w, uint32_t h) {
    model = new tt_um_gonsolo_borg;
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
}

ArcBorgSimulator::~ArcBorgSimulator() {
    delete model;
}

void ArcBorgSimulator::clock_low() {
    model->view.clk = 0;
    model->eval();
}

void ArcBorgSimulator::clock_high() {
    model->view.clk = 1;
    model->eval();
}

uint8_t ArcBorgSimulator::get_uio_out() {
    return model->view.uio_out;
}

uint8_t ArcBorgSimulator::get_uo_out() {
    return model->view.uo_out;
}

void ArcBorgSimulator::set_uio_in(uint8_t val) {
    model->view.uio_in = val;
}

void ArcBorgSimulator::set_ui_in(uint8_t val) {
    model->view.ui_in = val;
}

int ArcBorgSimulator::get_uart_bit_pos() const {
    return 6; // uo_out >> 6
}

void ArcBorgSimulator::host_write_psram_word(uint32_t word_addr, uint32_t value) {
    uint32_t byte_addr = word_addr * 4;
    if (byte_addr + 3 < psram->mem.size()) {
        psram->mem[byte_addr] = value & 0xFF;
        psram->mem[byte_addr+1] = (value >> 8) & 0xFF;
        psram->mem[byte_addr+2] = (value >> 16) & 0xFF;
        psram->mem[byte_addr+3] = (value >> 24) & 0xFF;
    }
}

int ArcBorgSimulator::find_memory_offset(const std::string &pattern) {
    std::ifstream f("state.json");
    if (!f) return -1;
    std::string line;
    int last_offset = -1;
    bool in_name = false;
    while (std::getline(f, line)) {
        auto np = line.find("\"name\"");
        if (np != std::string::npos && line.find(pattern) != std::string::npos) {
            in_name = true;
            continue;
        }
        if (in_name) {
            auto op = line.find("\"offset\"");
            if (op != std::string::npos) {
                auto colon = line.find(':', op);
                if (colon != std::string::npos) {
                    last_offset = std::atoi(line.c_str() + colon + 1);
                }
                in_name = false;
            }
        }
    }
    return last_offset;
}

void ArcBorgSimulator::backend_reset() {
    // Reset Sequence
    model->view.clk = 0;
    model->view.rst_n = 0;
    model->view.ena = 1;
    model->view.ui_in = 0;
    model->view.uio_in = 0;

    for (int i = 0; i < 10; i++) {
        model->eval();
        model->view.clk = 1;
        model->eval();
        model->view.clk = 0;
    }
    model->view.rst_n = 1;

    // Initialize coordLut BRAMs
    {
        const uint16_t coord_lut[64] = {
            0x3800, 0x3E00, 0x4100, 0x4300, 0x4480, 0x4580, 0x4680, 0x4780,
            0x4840, 0x48C0, 0x4940, 0x49C0, 0x4A40, 0x4AC0, 0x4B40, 0x4BC0,
            0x4C20, 0x4C60, 0x4CA0, 0x4CE0, 0x4D20, 0x4D60, 0x4DA0, 0x4DE0,
            0x4E20, 0x4E60, 0x4EA0, 0x4EE0, 0x4F20, 0x4F60, 0x4FA0, 0x4FE0,
            0x5010, 0x5030, 0x5050, 0x5070, 0x5090, 0x50B0, 0x50D0, 0x50F0,
            0x5110, 0x5130, 0x5150, 0x5170, 0x5190, 0x51B0, 0x51D0, 0x51F0,
            0x5210, 0x5230, 0x5250, 0x5270, 0x5290, 0x52B0, 0x52D0, 0x52F0,
            0x5310, 0x5330, 0x5350, 0x5370, 0x5390, 0x53B0, 0x53D0, 0x53F0,
        };
        int COORD_LUT_X_OFFSET = find_memory_offset("coordLutX_ext");
        int COORD_LUT_Y_OFFSET = find_memory_offset("coordLutY_ext");
        if (COORD_LUT_X_OFFSET >= 0 && COORD_LUT_Y_OFFSET >= 0) {
            for (int i = 0; i < 64; i++) {
                *(uint16_t*)(model->storage.data() + COORD_LUT_X_OFFSET + i * 2) = coord_lut[i];
                *(uint16_t*)(model->storage.data() + COORD_LUT_Y_OFFSET + i * 2) = coord_lut[i];
            }
            std::cout << "[SIM] coordLut BRAMs initialized (X@" << COORD_LUT_X_OFFSET << ", Y@" << COORD_LUT_Y_OFFSET << ").\n";
        }
    }

    // Initialize rcpLut BRAMs
    {
        const uint16_t rcp_lut[17] = {
            0x03FF, 0x0388, 0x031C, 0x02BD, 0x0266, 0x0218, 0x01D1, 0x0191,
            0x0155, 0x011F, 0x00EC, 0x00BE, 0x0092, 0x006A, 0x0044, 0x0021, 0x0000
        };
        int RCP_LUT_A_OFFSET = find_memory_offset("rcpLutA_ext");
        int RCP_LUT_B_OFFSET = find_memory_offset("rcpLutB_ext");
        if (RCP_LUT_A_OFFSET >= 0 && RCP_LUT_B_OFFSET >= 0) {
            for (int i = 0; i < 17; i++) {
                *(uint16_t*)(model->storage.data() + RCP_LUT_A_OFFSET + i * 2) = rcp_lut[i];
                *(uint16_t*)(model->storage.data() + RCP_LUT_B_OFFSET + i * 2) = rcp_lut[i];
            }
            std::cout << "[SIM] rcpLut BRAMs initialized (A@" << RCP_LUT_A_OFFSET << ", B@" << RCP_LUT_B_OFFSET << ").\n";
        }
    }

    // Fast simulation bypasses (sim_flash_ext, sim_psram_ext) removed.
    // Simulator now operates strictly via the QSPI pin interface.
}
