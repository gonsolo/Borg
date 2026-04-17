#pragma once

#include "../common/common_sim.h"

#include <Vtt_um_gonsolo_borg.h>
#include <Vtt_um_gonsolo_borg___024root.h>
#include <verilated.h>

class BorgSimulator {
public:
    // Texture placed BEFORE framebuffer in PSRAM, so TEX_CONFIG.base_addr
    // always fits in 16 bits.  No resolution ceiling from texture addressing.
    static constexpr uint32_t DEFAULT_WIDTH  = 32;
    static constexpr uint32_t DEFAULT_HEIGHT = 32;
    Vtt_um_gonsolo_borg* model;
    QSPIMemory* flash;
    QSPIMemory* psram;
    bool fast_mode;
    
    uint32_t width;
    uint32_t height;
    uint32_t psram_spi_word_offset;
    uint32_t out_base_word;
    uint32_t marker_offset_word;

    // Convenience accessor for the Chisel flash SyncReadMem array
    auto& flash_arr()  { return model->rootp->tt_um_gonsolo_borg__DOT__uo_out_val_memSim__DOT__sim_flash_ext_ext__DOT__Memory; }
    
    BorgSimulator(const std::string& firmware_path, bool fast_mode_val = false, uint32_t w = DEFAULT_WIDTH, uint32_t h = DEFAULT_HEIGHT) {
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
    
    ~BorgSimulator() {
        delete model;
        delete flash;
        delete psram;
    }
    
    void load_texture(const std::string& tex_path) {
        std::ifstream tex_f(tex_path, std::ios::binary);
        if (tex_f) {
            std::vector<uint8_t> tex_data(32 * 32 * 6); // 32x32 RGB FP16
            tex_f.read((char*)tex_data.data(), tex_data.size());
            
            // Texture lives at TEX_PSRAM_BYTE_ADDR_FIXED (defined in
            // borg_layout.h), BEFORE the framebuffer, so it always fits
            // in the 16-bit TEX_CONFIG.base_addr register.
            uint32_t tex_byte_base = TEX_PSRAM_BYTE_ADDR_FIXED;

            // Store texels in packed 2-word (8-byte) format at the PSRAM byte
            // address the GPU sTexFetch hardware will read:
            //   Word 0: { G[15:0], R[15:0] }  (little-endian in PSRAM bytes)
            //   Word 1: { pad[15:0], B[15:0] }
            uint8_t* pmem = psram->mem.data();
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    int src_idx = y * 32 + x;
                    int dst_idx = morton_encode(x, y);

                    uint16_t r = tex_data[(src_idx * 3 + 0) * 2] | (tex_data[(src_idx * 3 + 0) * 2 + 1] << 8);
                    uint16_t g = tex_data[(src_idx * 3 + 1) * 2] | (tex_data[(src_idx * 3 + 1) * 2 + 1] << 8);
                    uint16_t b = tex_data[(src_idx * 3 + 2) * 2] | (tex_data[(src_idx * 3 + 2) * 2 + 1] << 8);

                    // Word 0: { G, R } — little-endian: byte[0..1]=R, byte[2..3]=G
                    uint32_t word0 = (uint32_t)r | ((uint32_t)g << 16);
                    // Word 1: { pad, B } — little-endian: byte[0..1]=B, byte[2..3]=0
                    uint32_t word1 = (uint32_t)b;

                    uint32_t byte_addr = tex_byte_base + dst_idx * 8;
                    pmem[byte_addr + 0] = word0 & 0xFF;
                    pmem[byte_addr + 1] = (word0 >> 8) & 0xFF;
                    pmem[byte_addr + 2] = (word0 >> 16) & 0xFF;
                    pmem[byte_addr + 3] = (word0 >> 24) & 0xFF;
                    pmem[byte_addr + 4] = word1 & 0xFF;
                    pmem[byte_addr + 5] = (word1 >> 8) & 0xFF;
                    pmem[byte_addr + 6] = (word1 >> 16) & 0xFF;
                    pmem[byte_addr + 7] = (word1 >> 24) & 0xFF;
                }
            }
            std::cout << "[SIM] Texture loaded at PSRAM byte 0x" << std::hex << tex_byte_base << std::dec << ".\n";
            // No Chisel PSRAM sync needed — data goes through QSPI in step 1
        }
    }
    
    void set_camera_angles(float rx, float ry) {
        uint32_t* psram_words = (uint32_t*)psram->mem.data();
        float* psram_floats = (float*)&psram_words[psram_spi_word_offset];
        psram_floats[2] = rx;
        psram_floats[3] = ry;
        // No Chisel PSRAM sync needed — data goes through QSPI in step 1
    }
    
    // Returns true when a frame completed (0xDEAD marker found), false if still busy
    bool step(uint32_t cycles_to_run) {
        
        uint32_t* psram_words = (uint32_t*)psram->mem.data();

        // Marker always checked in C++ QSPI model (data path is QSPI)
        if (psram_words[marker_offset_word] == 0x0000DEAD) {
            psram_words[marker_offset_word] = 0;
        }
        
        // Assert pin 7 of user inputs to tell the SoC to mux instruction fetch to memSim
        model->ui_in = fast_mode ? 0x80 : 0x00;

        for (uint32_t c = 0; c < cycles_to_run; c++) {
            // Phase 1 (Clock Low)
            model->clk = 0;
            model->eval();

            uint8_t uio_out = model->uio_out;
            uint8_t uo_out  = model->uo_out;
            
            bool spi_clk = get_spi_clk(uio_out);
            bool flash_cs = get_flash_cs(uio_out);
            bool ram_a_cs = get_ram_a_cs(uio_out);
            
            uint8_t mosi = decode_spi_data_out(uio_out);

            uint8_t f_data = flash->tick(flash_cs, spi_clk, mosi);
            uint8_t r_data = psram->tick(ram_a_cs, spi_clk, mosi);
            uint8_t miso = !flash_cs ? f_data : (!ram_a_cs ? r_data : 0);
            model->uio_in = encode_spi_data_in(miso);

            // Phase 2 (Clock High)
            model->clk = 1;
            model->eval();

            uio_out = model->uio_out;
            uo_out  = model->uo_out;
            
            spi_clk = get_spi_clk(uio_out);
            flash_cs = get_flash_cs(uio_out);
            ram_a_cs = get_ram_a_cs(uio_out);
            mosi = decode_spi_data_out(uio_out);

            f_data = flash->tick(flash_cs, spi_clk, mosi);
            r_data = psram->tick(ram_a_cs, spi_clk, mosi);
            miso = !flash_cs ? f_data : (!ram_a_cs ? r_data : 0);
            model->uio_in = encode_spi_data_in(miso);

            static uint8_t last_write_n = 3;
            if (fast_mode) {
                uint32_t addr = model->rootp->tt_um_gonsolo_borg__DOT__uo_out_val_i_memReal_io_instrFetch_instr_addr_i_tinyqv__DOT__cpu__DOT__data_addr_reg;
                uint8_t write_n = model->rootp->tt_um_gonsolo_borg__DOT__uo_out_val_i_memReal_io_instrFetch_instr_addr_i_tinyqv__DOT__cpu__DOT__data_write_n_reg;
                
                if (write_n != 3) {
                    uint32_t data = model->rootp->tt_um_gonsolo_borg__DOT__uo_out_val_i_memReal_io_instrFetch_instr_addr_i_tinyqv__DOT__cpu__DOT__data_out_reg;
                    
                    if ((addr >> 23) == 2) {
                        uint32_t psram_addr = addr & 0x7FFFFF;
                        uint8_t* pmem = psram->mem.data();
                        if (psram_addr < psram->mem.size() - 3) {
                            if (write_n == 2) {
                                pmem[psram_addr + 2] = (data >> 16) & 0xFF;
                                pmem[psram_addr + 3] = (data >> 24) & 0xFF;
                            }
                            if (write_n == 1 || write_n == 2) {
                                pmem[psram_addr + 1] = (data >> 8) & 0xFF;
                            }
                            pmem[psram_addr + 0] = data & 0xFF;
                        }
                    }
                }
                last_write_n = write_n;
            }

            // UART TX Decode (4 MHz / 115200 Baud = ~35 cycles per bit)
            static uint8_t last_uart = 1;
            static int uart_bits_received = 0;
            static int uart_cycles_waited = 0;
            static uint8_t uart_byte = 0;
            static bool uart_receiving = false;
            
            uint8_t uart_txd = (uo_out >> 0) & 1;
            
            if (!uart_receiving) {
                if (last_uart == 1 && uart_txd == 0) {
                    uart_receiving = true;
                    uart_cycles_waited = -17; // Wait ~0.5 bits to sample middle of bit 0
                    uart_bits_received = 0;
                    uart_byte = 0;
                }
            } else {
                uart_cycles_waited++;
                if (uart_cycles_waited == 35) {
                    uart_cycles_waited = 0;
                    if (uart_bits_received < 8) {
                        uart_byte |= (uart_txd << uart_bits_received);
                        uart_bits_received++;
                    } else { // 8 bits received. Stop bit is next, but we don't strictly need to check it
                        std::cout << (char)uart_byte << std::flush;
                        uart_receiving = false;
                    }
                }
            }
            last_uart = uart_txd;

            // Marker always in C++ QSPI model
            if (psram_words[marker_offset_word] == 0x0000DEAD) {
                return true; // Frame rendered successfully!
            }
        }
        return false; // Did not finish frame yet
    }
    
    void save_ppm(const std::string& name) {
        // Framebuffer always in C++ QSPI model for step 1
        ::save_ppm(name, width, height, out_base_word, psram->mem);
    }
};
