// Minimal standalone Verilator harness for MinimalSocRealSdramSimTop:
// preloads real OpenSBI/Linux firmware directly into SdramChipModel's
// memory via the dbg_* backdoor (bypassing FlashBootLoader's byte-serial SPI
// copy), releases reset, and watches uo_out bit 6 (the debug console UART TX,
// matching MinimalSoC's `Cat(0.U(1.W), debug_uart_txd, 0.U(6.W))`) for
// OpenSBI/Linux boot output. Unlike main_minimal_linux.cpp (idealized
// SdramBackendSim), this goes through the REAL SdramBackend/SdramController/
// SdramChipModel JEDEC-protocol co-sim.
//
// Usage: ./minimal_linux_realsdram_sim <firmware.bin> [max_cycles]

#include "../common/uart_decoder.h"
#include <VMinimalSocRealSdramSimTop.h>
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
    size_t file_size = (size_t)f.tellg();
    f.seekg(0);
    std::vector<uint8_t> raw(file_size);
    f.read((char*)raw.data(), file_size);

    // Auto-detect FlashBootLoader's wrapped format (4-byte LE length header +
    // payload) vs. a bare fw_payload.bin — see main_minimal_linux.cpp for the
    // full story (loading a wrapped file unstripped shifts the whole image 4
    // bytes from where it truly belongs, which OpenSBI's own fw_start
    // relocation sanity check correctly, if misleadingly, flags as a fault).
    size_t fw_size;
    const uint8_t* fw_data;
    uint32_t header_len = file_size >= 4
        ? (raw[0] | (raw[1] << 8) | (raw[2] << 16) | (raw[3] << 24)) : 0;
    if (file_size >= 4 && header_len == file_size - 4) {
        fw_size = file_size - 4;
        fw_data = raw.data() + 4;
        fprintf(stderr, "Loaded wrapped firmware: %zu bytes total, stripped 4-byte header, %zu bytes payload\n",
                file_size, fw_size);
    } else {
        fw_size = file_size;
        fw_data = raw.data();
        fprintf(stderr, "Loaded firmware: %zu bytes\n", fw_size);
    }
    std::vector<uint8_t> fw(fw_data, fw_data + fw_size);

    auto* top = new VMinimalSocRealSdramSimTop;

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
    uint32_t last_instr_addr = 0xFFFFFFFF;
    uint64_t last_progress_cyc = 0;
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

        if (top->dbg_instr_addr != last_instr_addr) {
            if (cyc < 20000 || cyc - last_progress_cyc > 500000) {
                fprintf(stderr, "[cyc %llu] instr fetch addr changed: 0x%x -> 0x%x\n",
                        (unsigned long long)cyc, last_instr_addr, top->dbg_instr_addr);
            }
            last_instr_addr = top->dbg_instr_addr;
            last_progress_cyc = cyc;
        }

        if (cyc - last_report >= 1'000'000) {
            fprintf(stderr,
                "... %llu cycles | mem_state=%d be_state=%d ctrl_state=%d ctrl_rdy=%d | "
                "instr(req_v=%d req_r=%d resp_v=%d addr=0x%x) data(req_v=%d req_r=%d) | "
                "last progress %llu cycles ago\n",
                (unsigned long long)cyc, top->dbg_mem_state, top->dbg_be_state, top->dbg_ctrl_state,
                top->dbg_ctrl_rdy, top->dbg_instr_req_valid, top->dbg_instr_req_ready,
                top->dbg_instr_resp_valid, top->dbg_instr_addr, top->dbg_data_req_valid,
                top->dbg_data_req_ready, (unsigned long long)(cyc - last_progress_cyc));
            last_report = cyc;
        }
    }

    fprintf(stderr, "\nDone: %llu cycles simulated, no more output expected within budget.\n",
            (unsigned long long)max_cycles);
    delete top;
    return 0;
}
