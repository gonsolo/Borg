#pragma once

#include "../common/common_sim.h"
#ifdef FAST_MEM
#include <Vtt_um_gonsolo_borg_sim.h>
#include <Vtt_um_gonsolo_borg_sim___024root.h>
#else
#include <Vtt_um_gonsolo_borg.h>
#endif
#include <verilated.h>

class BorgSimulator {
public:
#ifdef FAST_MEM
    Vtt_um_gonsolo_borg_sim* model;
#else
    Vtt_um_gonsolo_borg* model;
#endif
    QSPIMemory* flash;
    QSPIMemory* psram;
    
    uint32_t width;
    uint32_t height;
    uint32_t psram_spi_word_offset;
    uint32_t out_base_word;
    uint32_t marker_offset_word;
    
    BorgSimulator(const std::string& firmware_path, uint32_t w = 32, uint32_t h = 32) {
#ifdef FAST_MEM
        model = new Vtt_um_gonsolo_borg_sim;
#else
        model = new Vtt_um_gonsolo_borg;
#endif
        flash = new QSPIMemory(1024 * 1024, true); // 1MB flash
        psram = new QSPIMemory(8 * 1024 * 1024, false); // 8MB PSRAM
        
        width = w;
        height = h;
        psram_spi_word_offset = 0x1000 / 4;
        out_base_word = psram_spi_word_offset + (128 / 4);
        
        uint32_t frame_fb_size = width * height * 3;
        uint32_t frame_zb_size = width * height;
        marker_offset_word = out_base_word + frame_fb_size + frame_zb_size;
        
        flash->load_bin(firmware_path);
        
        uint32_t* psram_init_words = (uint32_t*)psram->mem.data();
        psram_init_words[psram_spi_word_offset + 0] = width;
        psram_init_words[psram_spi_word_offset + 1] = height;

#ifdef FAST_MEM
        // Copy initialized firmware into fast-mem unified array (lower 256KB)
        for (size_t i = 0; i < flash->mem.size() && i < 262144; i++) {
            model->rootp->tt_um_gonsolo_borg_sim__DOT__uo_out_val_i_tinyqv__DOT__memSim__DOT__sim_mem_ext__DOT__Memory[i] = flash->mem[i];
        }
        
        // Copy initialized PSRAM setup into the fast-mem unified array (upper 256KB offset)
        for (size_t i = 0; i < psram->mem.size() && i < 262144; i++) {
            model->rootp->tt_um_gonsolo_borg_sim__DOT__uo_out_val_i_tinyqv__DOT__memSim__DOT__sim_mem_ext__DOT__Memory[0x40000 + i] = psram->mem[i];
        }
#endif

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
            
            uint32_t TEX_PSRAM_OFFSET = 4200;
            uint32_t tex_base = psram_spi_word_offset + TEX_PSRAM_OFFSET;

            uint32_t* psram_init_words = (uint32_t*)psram->mem.data();
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

#ifdef FAST_MEM
            // Also copy the texture to the simulator's internal PSRAM array (offset 256KB)
            for (size_t i = 0; i < 262144; i++) {
                model->rootp->tt_um_gonsolo_borg_sim__DOT__uo_out_val_i_tinyqv__DOT__memSim__DOT__sim_mem_ext__DOT__Memory[0x40000 + i] = psram->mem[i];
            }
#endif
        }
    }
    
    // Returns true when a frame completed (0xDEAD marker found), false if still busy
    bool step(uint32_t cycles_to_run) {
        
#ifdef FAST_MEM
        // Fast mode: read directly from the Verilator C array
        auto& psram_arr = model->rootp->tt_um_gonsolo_borg_sim__DOT__uo_out_val_i_tinyqv__DOT__memSim__DOT__sim_mem_ext__DOT__Memory;
        
        // Extract 32-bit word from 4 bytes in the array (with 256KB offset)
        uint32_t marker_byte_idx = 0x40000 + marker_offset_word * 4;
        uint32_t marker_val = psram_arr[marker_byte_idx] | (psram_arr[marker_byte_idx+1] << 8) |
                              (psram_arr[marker_byte_idx+2] << 16) | (psram_arr[marker_byte_idx+3] << 24);
        
        if (marker_val == 0x0000DEAD) {
            psram_arr[marker_byte_idx] = 0;
            psram_arr[marker_byte_idx+1] = 0;
            psram_arr[marker_byte_idx+2] = 0;
            psram_arr[marker_byte_idx+3] = 0;
        }
#else
        uint32_t* psram_words = (uint32_t*)psram->mem.data();
        if (psram_words[marker_offset_word] == 0x0000DEAD) {
            psram_words[marker_offset_word] = 0;
        }
#endif
        
        uint8_t prev_uio_out = 0xFF;

        for (uint32_t c = 0; c < cycles_to_run; c++) {
            // Phase 1 (Clock Low)
            model->clk = 0;
            model->eval();

            uint8_t uio_out = model->uio_out;
            uint8_t uo_out  = model->uo_out;
            
#ifndef FAST_MEM
            bool spi_clk = get_spi_clk(uio_out);
            bool flash_cs = get_flash_cs(uio_out);
            bool ram_a_cs = get_ram_a_cs(uio_out);
            
            uint8_t mosi = decode_spi_data_out(uio_out);

            uint8_t f_data = flash->tick(flash_cs, spi_clk, mosi);
            uint8_t r_data = psram->tick(ram_a_cs, spi_clk, mosi);
            uint8_t miso = !flash_cs ? f_data : (!ram_a_cs ? r_data : 0);
            model->uio_in = encode_spi_data_in(miso);
#endif

            // Phase 2 (Clock High)
            model->clk = 1;
            model->eval();

            uio_out = model->uio_out;
            uo_out  = model->uo_out;
            
#ifndef FAST_MEM
            spi_clk = get_spi_clk(uio_out);
            flash_cs = get_flash_cs(uio_out);
            ram_a_cs = get_ram_a_cs(uio_out);
            mosi = decode_spi_data_out(uio_out);

            f_data = flash->tick(flash_cs, spi_clk, mosi);
            r_data = psram->tick(ram_a_cs, spi_clk, mosi);
            miso = !flash_cs ? f_data : (!ram_a_cs ? r_data : 0);
            model->uio_in = encode_spi_data_in(miso);
#endif

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

#ifdef FAST_MEM
            marker_val = psram_arr[marker_byte_idx] | (psram_arr[marker_byte_idx+1] << 8) |
                         (psram_arr[marker_byte_idx+2] << 16) | (psram_arr[marker_byte_idx+3] << 24);
            if (marker_val == 0x0000DEAD) {
                return true; // Frame rendered successfully!
            }
#else
            if (psram_words[marker_offset_word] == 0x0000DEAD) {
                return true; // Frame rendered successfully!
            }
#endif
        }
        return false; // Did not finish frame yet
    }
    
    void save_ppm(const std::string& name) {
#ifdef FAST_MEM
        auto& psram_arr = model->rootp->tt_um_gonsolo_borg_sim__DOT__uo_out_val_i_tinyqv__DOT__memSim__DOT__sim_mem_ext__DOT__Memory;
        std::vector<uint8_t> tmp_mem(262144);
        for(size_t i=0; i<262144; i++) tmp_mem[i] = psram_arr[0x40000 + i];
        ::save_ppm(name, width, height, out_base_word, tmp_mem);
#else
        ::save_ppm(name, width, height, out_base_word, psram->mem);
#endif
    }
};
