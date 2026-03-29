#include <iostream>
#include <fstream>
#include <vector>
#include <string>
#include <cstdint>
#include <cmath>
#include <cstdio>
#include <cstdlib>

#include "Vtt_um_gonsolo_borg.h"
#include <verilated.h>
// Extracts a 4-bit nibble from uio_out
uint8_t decode_spi_data_out(uint8_t uio_out) {
    uint8_t out = 0;
    out |= (uio_out & (1 << 1)) ? 0x01 : 0;
    out |= (uio_out & (1 << 2)) ? 0x02 : 0;
    out |= (uio_out & (1 << 4)) ? 0x04 : 0;
    out |= (uio_out & (1 << 5)) ? 0x08 : 0;
    return out;
}

// Encodes a 4-bit nibble into uio_in
uint8_t encode_spi_data_in(uint8_t spi_data_in) {
    uint8_t out = 0;
    out |= (spi_data_in & 0x01) ? (1 << 1) : 0;
    out |= (spi_data_in & 0x02) ? (1 << 2) : 0;
    out |= (spi_data_in & 0x04) ? (1 << 4) : 0;
    out |= (spi_data_in & 0x08) ? (1 << 5) : 0;
    return out;
}

bool get_spi_clk(uint8_t uio_out) {
    return (uio_out & (1 << 3)) != 0;
}

bool get_flash_cs(uint8_t uio_out) {
    return (uio_out & (1 << 0)) != 0;
}

bool get_ram_a_cs(uint8_t uio_out) {
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
    
    QSPIMemory(size_t size, bool flash) {
        mem.resize(size, 0);
        is_flash = flash;
    }
    
    void load_bin(const std::string& path) {
        std::ifstream f(path, std::ios::binary | std::ios::ate);
        if (!f) {
            std::cerr << "Failed to open " << path << "\n";
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
            if (is_flash) {
                state = ADDR;
                nibbles_left = 6;
                addr_reg = 0;
            } else {
                state = CMD;
                nibbles_left = 2;
                cmd_reg = 0;
            }
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
                    if (is_flash) {
                        state = DUMMY;
                        nibbles_left = 6;
                    } else {
                        if (cmd_reg == 0x0B) {
                            state = DUMMY;
                            nibbles_left = 4;
                        } else if (cmd_reg == 0x02) {
                            state = DATA_WRITE;
                            wrote_half_byte = false;
                        }
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

static uint16_t morton_interleave(uint16_t x) {
    uint32_t val = x;
    val = (val | (val << 8)) & 0x00FF00FF;
    val = (val | (val << 4)) & 0x0F0F0F0F;
    val = (val | (val << 2)) & 0x33333333;
    val = (val | (val << 1)) & 0x55555555;
    return val;
}

static uint32_t morton_encode(uint16_t x, uint16_t y) {
    return morton_interleave(x) | (morton_interleave(y) << 1);
}

int main(int argc, char** argv) {
    if (argc < 3) {
        std::cerr << "Usage: " << argv[0] << " <firmware.bin> <app_name>\n";
        return 1;
    }
    Verilated::commandArgs(argc, argv);
    std::string firmware_path = argv[1];
    std::string app_name = argv[2];

    Vtt_um_gonsolo_borg* model = new Vtt_um_gonsolo_borg;

    QSPIMemory flash(1024 * 1024, true); // 1MB flash
    flash.load_bin(firmware_path);

    // 8MB PSRAM mirroring setup
    QSPIMemory psram(8 * 1024 * 1024, false);

    uint32_t width = 32;
    uint32_t height = 32;
    
    // PSRAM byte mapping: the hardware maps PSRAM starting at offset 0x1000.
    // So PSRAM_IO_SPI_ADDR = 0x1000 (4096 bytes, which is 1024 words).
    uint32_t psram_spi_word_offset = 0x1000 / 4;
    
    uint32_t* psram_init_words = (uint32_t*)psram.mem.data();
    psram_init_words[psram_spi_word_offset + 0] = width;
    psram_init_words[psram_spi_word_offset + 1] = height;

    std::string tex_path = app_name == "vkcube" ? "../fpga/firmware/borg_texture.dat" : "../fpga/firmware/test_texture.dat";
    std::ifstream tex_f(tex_path, std::ios::binary);
    if (tex_f) {
        std::vector<uint8_t> tex_data(32 * 32 * 6); // 32x32 RGB FP16
        tex_f.read((char*)tex_data.data(), tex_data.size());
        
        uint32_t TEX_PSRAM_OFFSET = 4200;
        // In render.py: PSRAM_IO_SPI_ADDR + (TEX_PSRAM_OFFSET + dst_idx * ...) * 4
        uint32_t tex_base = psram_spi_word_offset + TEX_PSRAM_OFFSET;

        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int src_idx = y * 32 + x;
                int dst_idx = morton_encode(x, y);

                uint16_t r = tex_data[(src_idx * 3 + 0) * 2] | (tex_data[(src_idx * 3 + 0) * 2 + 1] << 8);
                uint16_t g = tex_data[(src_idx * 3 + 1) * 2] | (tex_data[(src_idx * 3 + 1) * 2 + 1] << 8);
                uint16_t b = tex_data[(src_idx * 3 + 2) * 2] | (tex_data[(src_idx * 3 + 2) * 2 + 1] << 8);

                psram_init_words[tex_base + dst_idx * 3 + 0] = r;
                psram_init_words[tex_base + dst_idx * 3 + 1] = g;
                psram_init_words[tex_base + dst_idx * 3 + 2] = b;
            }
        }
        std::cout << "[SIM] Texture loaded.\n";
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

    std::cout << "[SIM] Starting simulation...\n";

    uint64_t cycles = 0;
    bool done = false;
    
    // PSRAM_OUT_OFFSET = 128 (bytes). So byte base = 0x1000 + 128 = 4224. Words = 1056.
    uint32_t out_base_word = psram_spi_word_offset + (128 / 4);
    // Frame stride and sizes in words
    uint32_t frame_fb_size = width * height * 3;
    uint32_t frame_zb_size = width * height;
    uint32_t marker_offset_word = out_base_word + frame_fb_size + frame_zb_size;
    
    uint8_t prev_uio_out = 0xFF;

    while (!done) {
        // Phase 1 (Clock Low)
        model->clk = 0;
        model->eval();

        uint8_t uio_out = model->uio_out;
        uint8_t uo_out  = model->uo_out;
        
        static uint8_t prev_uo_out = 0xFF;
        if (uo_out != prev_uo_out) {
            prev_uo_out = uo_out;
        }

        if (uio_out != prev_uio_out) {
            prev_uio_out = uio_out;
        }

        bool clk = get_spi_clk(uio_out);
        uint8_t data_out = decode_spi_data_out(uio_out);

        uint8_t f_data = flash.tick(get_flash_cs(uio_out), clk, data_out);
        uint8_t r_data = psram.tick(get_ram_a_cs(uio_out), clk, data_out);
        uint8_t m_data = !get_flash_cs(uio_out) ? f_data : (!get_ram_a_cs(uio_out) ? r_data : 0);
        model->uio_in = encode_spi_data_in(m_data);

        // Phase 2 (Clock High)
        model->clk = 1;
        model->eval();

        uio_out = model->uio_out;
        clk = get_spi_clk(uio_out);
        data_out = decode_spi_data_out(uio_out);

        f_data = flash.tick(get_flash_cs(uio_out), clk, data_out);
        r_data = psram.tick(get_ram_a_cs(uio_out), clk, data_out);
        m_data = !get_flash_cs(uio_out) ? f_data : (!get_ram_a_cs(uio_out) ? r_data : 0);
        model->uio_in = encode_spi_data_in(m_data);

        static uint8_t last_uart = 1;
        static int uart_bits_received = 0;
        static int uart_cycles_waited = 0;
        static uint8_t uart_byte = 0;
        static bool uart_receiving = false;
        
        uint8_t uart_txd = (model->uo_out >> 6) & 1;
        
        if (!uart_receiving) {
            if (last_uart == 1 && uart_txd == 0) {
                uart_receiving = true;
                uart_cycles_waited = 0;
                uart_bits_received = 0;
                uart_byte = 0;
            }
        } else {
            uart_cycles_waited++;
            if (uart_cycles_waited == 52) { 
                uart_byte |= (uart_txd << uart_bits_received);
                uart_bits_received++;
                uart_cycles_waited = 52 - 35; 
            } else if (uart_bits_received > 0 && uart_cycles_waited == 35) {
                if (uart_bits_received < 8) {
                    uart_byte |= (uart_txd << uart_bits_received);
                    uart_bits_received++;
                    uart_cycles_waited = 0;
                } else {
                    std::cout << (char)uart_byte << std::flush;
                    uart_receiving = false;
                }
            }
        }
        last_uart = uart_txd;

        cycles++;

        if (cycles % 1000000 == 0) std::cout << "[SIM] " << (cycles / 1000000) << " million cycles\n" << std::flush;

        // Check completion marker
        uint32_t* psram_words = (uint32_t*)psram.mem.data();
        if (psram_words[marker_offset_word] == 0x0000DEAD) {
            done = true;
            std::cout << "[SIM] Frame complete! DONE_MARKER detected.\n";
            std::cout << "Total Sim Cycles:  " << cycles << " cycles.\n";
        }

        if (cycles > 200000000) {
            std::cout << "[SIM] Timeout limit reached.\n";
            break;
        }
    }

    auto fp16_to_float = [](uint16_t h) -> float {
        int sign = (h >> 15) & 1;
        int exp = (h >> 10) & 0x1F;
        int mant = h & 0x3FF;
        if (exp == 0 && mant == 0) return 0.0f;
        if (exp == 31) return (mant == 0) ? INFINITY : NAN;
        if (exp == 0) return (sign ? -1.0f : 1.0f) * std::pow(2.0f, -14.0f) * (mant / 1024.0f);
        return (sign ? -1.0f : 1.0f) * std::pow(2.0f, exp - 15.0f) * (1.0f + mant / 1024.0f);
    };

    char ppm_name[256];
    snprintf(ppm_name, sizeof(ppm_name), "%s_00.ppm", app_name.c_str());
    std::ofstream out(ppm_name);
    out << "P3\n" << width << " " << height << "\n255\n";

    uint32_t* psram_words_out = (uint32_t*)psram.mem.data();

    for (uint32_t y = 0; y < height; y++) {
        for (uint32_t x = 0; x < width; x++) {
            uint32_t base = out_base_word + (y * width + x) * 3;
            uint16_t r = psram_words_out[base + 0];
            uint16_t g = psram_words_out[base + 1];
            uint16_t b = psram_words_out[base + 2];
            
            int r_b = std::max(0, std::min(255, (int)(fp16_to_float(r) * 255)));
            int g_b = std::max(0, std::min(255, (int)(fp16_to_float(g) * 255)));
            int b_b = std::max(0, std::min(255, (int)(fp16_to_float(b) * 255)));
            
            out << r_b << " " << g_b << " " << b_b << " ";
        }
        out << "\n";
    }
    std::cout << "[SIM] Saved " << ppm_name << "\n";

    return 0;
}
