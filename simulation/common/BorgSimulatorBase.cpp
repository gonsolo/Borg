#include "BorgSimulatorBase.h"
#include <iostream>

void BorgSimulatorBase::load_texture(const std::string& tex_path, uint32_t tex_dim) {
    load_texture_to_psram(psram->mem.data(), tex_path, tex_dim, TEX_PSRAM_BYTE_ADDR_FIXED);
}

void BorgSimulatorBase::set_camera_angles(float rx, float ry) {
    uint32_t* psram_words = (uint32_t*)psram->mem.data();
    float* psram_floats = (float*)&psram_words[psram_spi_word_offset];
    psram_floats[2] = rx;
    psram_floats[3] = ry;
    host_write_psram_word(psram_spi_word_offset + 2, psram_words[psram_spi_word_offset + 2]);
    host_write_psram_word(psram_spi_word_offset + 3, psram_words[psram_spi_word_offset + 3]);
}

void BorgSimulatorBase::save_ppm(const std::string& name) {
    ::save_ppm(name, width, height, out_base_word, psram->mem);
}

bool BorgSimulatorBase::step(uint32_t cycles_to_run) {
    uint32_t* psram_words = (uint32_t*)psram->mem.data();

    if (psram_words[marker_offset_word] == 0x0000DEAD) {
        psram_words[marker_offset_word] = 0;
    }

    set_ui_in(0x80); // Hold UART RXD (ui_in(7)) high (idle)

    for (uint32_t c = 0; c < cycles_to_run; c++) {
        // Phase 1 (Clock Low)
        clock_low();

        uint8_t uio_out = get_uio_out();
        bool spi_clk = get_spi_clk(uio_out);
        bool flash_cs = get_flash_cs(uio_out);
        bool ram_a_cs = get_ram_a_cs(uio_out);
        uint8_t mosi = decode_spi_data_out(uio_out);

        uint8_t f_data = flash->tick(flash_cs, spi_clk, mosi);
        uint8_t r_data = psram->tick(ram_a_cs, spi_clk, mosi);
        uint8_t miso = !flash_cs ? f_data : (!ram_a_cs ? r_data : 0);
        set_uio_in(encode_spi_data_in(miso));

        // Phase 2 (Clock High)
        clock_high();

        uio_out = get_uio_out();
        spi_clk = get_spi_clk(uio_out);
        flash_cs = get_flash_cs(uio_out);
        ram_a_cs = get_ram_a_cs(uio_out);
        mosi = decode_spi_data_out(uio_out);

        f_data = flash->tick(flash_cs, spi_clk, mosi);
        r_data = psram->tick(ram_a_cs, spi_clk, mosi);
        miso = !flash_cs ? f_data : (!ram_a_cs ? r_data : 0);
        set_uio_in(encode_spi_data_in(miso));

        fast_sim_snoop(); // Subclass hook for writing to memory arrays in fast mode

        // UART Decode
        uint8_t uo_out = get_uo_out();
        if (uart.tick((uo_out >> get_uart_bit_pos()) & 1)) {
            std::cout << (char)uart.byte() << std::flush;
        }

        // Check completion marker
        if (psram_words[marker_offset_word] == 0x0000DEAD) {
            return true;
        }
    }
    return false;
}
