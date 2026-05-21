#pragma once

#include <iostream>
#include <fstream>
#include <vector>
#include <string>
#include <cstdint>
#include <cmath>
#include <cstdio>
#include <cstdlib>

// Extracts a 4-bit nibble from uio_out
inline uint8_t decode_spi_data_out(uint8_t uio_out) {
    uint8_t out = 0;
    out |= (uio_out & (1 << 1)) ? 0x01 : 0;
    out |= (uio_out & (1 << 2)) ? 0x02 : 0;
    out |= (uio_out & (1 << 4)) ? 0x04 : 0;
    out |= (uio_out & (1 << 5)) ? 0x08 : 0;
    return out;
}

// Encodes a 4-bit nibble into uio_in
inline uint8_t encode_spi_data_in(uint8_t spi_data_in) {
    uint8_t out = 0;
    out |= (spi_data_in & 0x01) ? (1 << 1) : 0;
    out |= (spi_data_in & 0x02) ? (1 << 2) : 0;
    out |= (spi_data_in & 0x04) ? (1 << 4) : 0;
    out |= (spi_data_in & 0x08) ? (1 << 5) : 0;
    return out;
}

inline bool get_spi_clk(uint8_t uio_out) {
    return (uio_out & (1 << 3)) != 0;
}

inline bool get_flash_cs(uint8_t uio_out) {
    return (uio_out & (1 << 0)) != 0;
}

inline bool get_ram_a_cs(uint8_t uio_out) {
    return (uio_out & (1 << 6)) != 0;
}

class QSPIMemory {
public:
    std::vector<uint8_t> mem;
    bool is_flash;
    bool active = false;
    uint8_t last_clk = 0;
    
    enum State { IDLE, CMD, ADDR, DUMMY, DATA_READ, DATA_WRITE };
    State state = IDLE;
    
    uint32_t addr_reg = 0;
    uint8_t cmd_reg = 0;
    int nibbles_left = 0;
    bool wrote_half_byte = false;
    bool first_data_falling_edge = false;
    uint32_t sim_cycle = 0;  // set by caller
    
    QSPIMemory(size_t size, bool flash) {
        mem.resize(size, 0);
        is_flash = flash;
    }
    
    void load_bin(const std::string& path) {
        std::ifstream f(path, std::ios::binary | std::ios::ate);
        if (!f) {
            std::cerr << "[SIM] Failed to open " << path << "\n";
            return;
        }
        size_t size = f.tellg();
        f.seekg(0, std::ios::beg);
        if (size > mem.size()) size = mem.size();
        f.read((char*)mem.data(), size);
        std::cout << "[SIM] Loaded " << size << " bytes from " << path << "\n";
    }

    uint8_t tick(bool cs, bool clk, uint8_t data_out) {
        if (cs) { // Active Low CS
            active = false;
            state = IDLE;
            return 0;
        }

        uint8_t data_in = 0;
        if (!active) {
            active = true;
            state = CMD;
            nibbles_left = 2;
            cmd_reg = 0;
            addr_reg = 0;
        }

        // Output data during DATA_READ
        if (state == DATA_READ) {
            uint8_t byte = mem[addr_reg];
            if (!wrote_half_byte) {
                data_in = byte >> 4;
            } else {
                data_in = byte & 0x0F;
            }
            if (!clk && last_clk) {
                if (first_data_falling_edge) {
                    first_data_falling_edge = false;
                } else {
                    wrote_half_byte = !wrote_half_byte;
                    if (!wrote_half_byte) {
                        addr_reg++;
                        if (addr_reg >= mem.size()) addr_reg = 0;
                    }
                }
            }
        }

        if (clk && !last_clk) { // Rising Edge logic
            if (state == CMD) {
                cmd_reg = (cmd_reg << 4) | data_out;
                nibbles_left--;
                if (nibbles_left == 0) {
                    state = ADDR;
                    addr_reg = 0;
                    nibbles_left = 6;
                }
            } else if (state == ADDR) {
                addr_reg = (addr_reg << 4) | data_out;
                nibbles_left--;
                if (nibbles_left == 0) {
                    if (cmd_reg == 0x0B) {
                        // Read: QspiCtrl uses DUMMY2(4) for flash, DUMMY1(2)+DUMMY2(4)=6 for PSRAM
                        state = DUMMY;
                        nibbles_left = is_flash ? 4 : 6;
                    } else if (cmd_reg == 0x02) {
                        state = DATA_WRITE;
                        wrote_half_byte = false;
                    }
                }
            } else if (state == DUMMY) {
                nibbles_left--;
                if (nibbles_left == 0) {
                    state = DATA_READ;
                    wrote_half_byte = false; 
                    first_data_falling_edge = true;
                }
            } else if (state == DATA_WRITE) {
                if (!wrote_half_byte) {
                    mem[addr_reg] = (data_out << 4);
                    wrote_half_byte = true;
                } else {
                    mem[addr_reg] |= data_out;
                    wrote_half_byte = false;
                    addr_reg++;
                    if (addr_reg >= mem.size()) addr_reg = 0;
                }
            }
        }

        last_clk = clk;
        return data_in;
    }
};

#include "../../software/borg/borg_math.h"
#include "../../software/borg/borg_layout.h"

// fp16_to_float is now in borg_math.h

// ── Flat MemBackendIO memory access ─────────────────────────────────────────
// The verilator sim drives the MemoryController backend bus directly (no QSPI).
// The 24-bit word address selects flash vs PSRAM exactly as QspiBackend does:
//   QspiBackend: byteAddr = Cat(addrIn, 0)  → addr_in(24) = addrIn bit 23
//   QspiController: flash when addr_in(24)==0, PSRAM otherwise
// Per-chip byte address = (addrIn & 0x7FFFFF) << 1.  The flash/psram vectors
// are indexed by this byte address, identical to the old QSPIMemory model, so
// firmware load and framebuffer readback continue to work unchanged.
inline uint16_t flat_read16(QSPIMemory* flash, QSPIMemory* psram, uint32_t addrIn) {
    bool is_psram = (addrIn >> 23) & 1;
    uint32_t spi_byte = (addrIn & 0x7FFFFF) << 1;
    QSPIMemory* m = is_psram ? psram : flash;
    if (spi_byte + 1 >= m->mem.size()) return 0;
    return (uint16_t)m->mem[spi_byte] | ((uint16_t)m->mem[spi_byte + 1] << 8);
}

inline void flat_write16(QSPIMemory* flash, QSPIMemory* psram,
                         uint32_t addrIn, uint16_t data, uint8_t byteEn) {
    bool is_psram = (addrIn >> 23) & 1;
    uint32_t spi_byte = (addrIn & 0x7FFFFF) << 1;
    QSPIMemory* m = is_psram ? psram : flash;
    if (spi_byte + 1 >= m->mem.size()) return;
    if (byteEn & 0x1) m->mem[spi_byte]     = data & 0xFF;
    if (byteEn & 0x2) m->mem[spi_byte + 1] = (data >> 8) & 0xFF;
}

inline void save_ppm(const std::string& app_name, uint32_t width, uint32_t height, uint32_t out_base_word, const std::vector<uint8_t>& mem) {
    char ppm_name[256];
    snprintf(ppm_name, sizeof(ppm_name), "%s_00.ppm", app_name.c_str());
    std::ofstream out(ppm_name);
    out << "P3\n" << width << " " << height << "\n255\n";

    uint32_t* psram_words_out = (uint32_t*)mem.data();

    for (uint32_t y = 0; y < height; y++) {
        for (uint32_t x = 0; x < width; x++) {
            // Step 25.4.2 Option A: tiled framebuffer layout, 2 words/pixel.
            // BorgTileFlusher writes each pixel as 4 halfwords at byte offsets:
            //   +0: r, +2: g, +4: b, +6: z   (matches the ULX3S HDMI scanout).
            // So the two 32-bit words are:
            //   word_off+0 = {g[31:16], r[15:0]}
            //   word_off+1 = {z[31:16], b[15:0]}
            uint32_t tiles_per_row = width >> 2;
            uint32_t tile_index    = (y >> 2) * tiles_per_row + (x >> 2);
            uint32_t tile_idx      = (x & 3) | ((y & 3) << 2);
            uint32_t word_off      = out_base_word + tile_index * 32 + tile_idx * 2;
            uint32_t lo            = psram_words_out[word_off + 0];  // {g, r}
            uint32_t hi            = psram_words_out[word_off + 1];  // {z, b}
            uint16_t r = (uint16_t)(lo & 0xFFFF);
            uint16_t g = (uint16_t)(lo >> 16);
            uint16_t b = (uint16_t)(hi & 0xFFFF);

            int r_b = std::max(0, std::min(255, (int)(fp16_to_float(r) * 255)));
            int g_b = std::max(0, std::min(255, (int)(fp16_to_float(g) * 255)));
            int b_b = std::max(0, std::min(255, (int)(fp16_to_float(b) * 255)));

            out << r_b << " " << g_b << " " << b_b << " ";
        }
        out << "\n";
    }
    std::cout << "[SIM] Saved " << ppm_name << "\n";
}
