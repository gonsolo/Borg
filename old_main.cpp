#include "../common/common_sim.h"
#include "Vtt_um_gonsolo_borg.h"
#include <verilated.h>

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

    std::string tex_path = app_name == "vkcube" ? "../../software/borg/borg_texture.dat" : "../../software/borg/test_texture.dat";
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
            for (int i = 2500; i < 2504; i++) {
                printf("PSRAM[%d] = 0x%08X (%d)\n", i, psram_words[i], psram_words[i]);
            }
        }

        if (cycles > 200000000) {
            std::cout << "[SIM] Timeout limit reached.\n";
            break;
        }
    }



    save_ppm(app_name, width, height, out_base_word, psram.mem);

    return 0;
}
