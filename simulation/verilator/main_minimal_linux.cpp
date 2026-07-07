// Minimal standalone Verilator harness for MinimalSocSimTop: preloads real
// OpenSBI/Linux firmware directly into the behavioral SDRAM via the dbg_*
// backdoor (bypassing FlashBootLoader's byte-serial SPI copy, which real
// hardware already showed completing — boot_done=1), releases reset, and
// watches uo_out bit 6 (the debug console UART TX, matching MinimalSoC's
// `Cat(0.U(1.W), debug_uart_txd, 0.U(6.W))`) for OpenSBI/Linux boot output.
//
// Usage: ./minimal_linux_sim <firmware.bin> [max_cycles]

#include "../common/uart_decoder.h"
#include <VMinimalSocSimTop.h>
#include <verilated.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <vector>

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <firmware.bin> [max_cycles]\n", argv[0]);
        return 1;
    }
    Verilated::commandArgs(argc, argv);

    std::string fw_path = argv[1];
    uint64_t max_cycles = argc > 2 ? strtoull(argv[2], nullptr, 10) : 20'000'000ULL;

    std::ifstream f(fw_path, std::ios::binary | std::ios::ate);
    if (!f) {
        fprintf(stderr, "Cannot open firmware file: %s\n", fw_path.c_str());
        return 1;
    }
    size_t fw_size = (size_t)f.tellg();
    f.seekg(0);
    std::vector<uint8_t> fw(fw_size);
    f.read((char*)fw.data(), fw_size);
    fprintf(stderr, "Loaded firmware: %zu bytes\n", fw_size);

    auto* top = new VMinimalSocSimTop;

    top->dbg_we = 0; top->dbg_waddr = 0; top->dbg_wdata = 0; top->dbg_raddr = 0;
    top->clk = 0; top->rst_n = 0; top->ena = 1; top->ui_in = 0;
    for (int i = 0; i < 10; i++) { top->eval(); top->clk = 1; top->eval(); top->clk = 0; }

    // Preload: flash byte F -> SDRAM word F>>1 (2 bytes per 16-bit word),
    // matching FlashBootLoader's own byte-serial copy target layout exactly.
    fprintf(stderr, "Preloading firmware into SDRAM via debug backdoor...\n");
    uint32_t evenLen = (uint32_t)((fw_size + 1) & ~1u);
    for (uint32_t b = 0; b < evenLen; b += 2) {
        uint16_t lo = fw[b];
        uint16_t hi = (b + 1 < fw_size) ? fw[b + 1] : 0;
        uint16_t d = lo | (hi << 8);
        top->dbg_we = 1; top->dbg_waddr = (b >> 1) & 0xFFFFFF; top->dbg_wdata = d;
        top->clk = 0; top->eval(); top->clk = 1; top->eval();
    }
    top->dbg_we = 0;
    fprintf(stderr, "Preload done (%u words). Releasing reset.\n", evenLen >> 1);

    top->rst_n = 1;

    UartDecoder dec;
    dec.set_cycles_per_bit(217);  // 25 MHz / 115200 baud

    std::string line_buf;
    uint64_t last_report = 0;
    for (uint64_t cyc = 0; cyc < max_cycles; cyc++) {
        top->clk = 0; top->eval();
        top->clk = 1; top->eval();

        uint8_t txd = (top->uo_out >> 6) & 1;
        if (dec.tick(txd)) {
            uint8_t c = dec.byte();
            putchar(c);
            fflush(stdout);
            if (c == '\n') {
                if (!line_buf.empty()) fprintf(stderr, "[UART] %s\n", line_buf.c_str());
                line_buf.clear();
            } else if (c >= 0x20 && c < 0x7f) {
                line_buf += (char)c;
            }
        }

        if (cyc - last_report >= 2'000'000) {
            fprintf(stderr, "... %llu cycles simulated\n", (unsigned long long)cyc);
            last_report = cyc;
        }
    }

    fprintf(stderr, "\nDone: %llu cycles simulated, no more output expected within budget.\n",
            (unsigned long long)max_cycles);
    delete top;
    return 0;
}
