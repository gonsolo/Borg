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
    return 0; // uo_out >> 0
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
    auto load_hex = [&](const std::string& path, int offset1, int offset2, int depth) {
        if (offset1 < 0 || offset2 < 0) return;
        std::ifstream f(path);
        if (!f) {
            std::cerr << "Warning: Could not open " << path << "\n";
            return;
        }
        std::string line;
        int i = 0;
        while (std::getline(f, line) && i < depth) {
            if (line.empty()) continue;
            uint16_t val = std::stoi(line, nullptr, 16);
            *(uint16_t*)(model->storage.data() + offset1 + i * 2) = val;
            *(uint16_t*)(model->storage.data() + offset2 + i * 2) = val;
            i++;
        }
        std::cout << "[SIM] Loaded " << i << " entries from " << path << " (offsets " << offset1 << ", " << offset2 << ").\n";
    };

    int COORD_LUT_X_OFFSET = find_memory_offset("coordLutX_ext");
    int COORD_LUT_Y_OFFSET = find_memory_offset("coordLutY_ext");
    load_hex("../../hardware/borg/src/coord_lut.hex", COORD_LUT_X_OFFSET, COORD_LUT_Y_OFFSET, 512);

    int RCP_LUT_A_OFFSET = find_memory_offset("rcpLutA_ext");
    int RCP_LUT_B_OFFSET = find_memory_offset("rcpLutB_ext");
    load_hex("../../hardware/borg/src/rcp_lut.hex", RCP_LUT_A_OFFSET, RCP_LUT_B_OFFSET, 17);

    // Fast simulation bypasses (sim_flash_ext, sim_psram_ext) removed.
    // Simulator now operates strictly via the QSPI pin interface.
}
