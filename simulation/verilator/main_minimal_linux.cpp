// Minimal standalone Verilator harness for MinimalSocSimTop: preloads real
// OpenSBI/Linux firmware directly into the behavioral SDRAM via the dbg_*
// backdoor (bypassing FlashBootLoader's byte-serial SPI copy, which real
// hardware already showed completing — boot_done=1), releases reset, and
// watches uo_out bit 6 (the debug console UART TX, matching MinimalSoC's
// `Cat(0.U(1.W), debug_uart_txd, 0.U(6.W))`) for OpenSBI/Linux boot output.
//
// Usage: ./minimal_linux_sim <firmware.bin> [max_cycles] [trace_start] [trace_end]
// Passing trace_start/trace_end dumps an FST waveform (waveform.fst) for that
// cycle window — useful for post-mortem inspection with gtkwave or a VCD/FST
// text dump, without paying the size/speed cost of tracing the whole run.

#include "../common/uart_decoder.h"
#include <VMinimalSocSimTop.h>
#include <verilated.h>
#include <verilated_fst_c.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <vector>

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <firmware.bin> [max_cycles] [trace_start] [trace_end]\n", argv[0]);
        return 1;
    }
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);

    std::string fw_path = argv[1];
    uint64_t max_cycles = argc > 2 ? strtoull(argv[2], nullptr, 10) : 20'000'000ULL;
    uint64_t trace_start = argc > 3 ? strtoull(argv[3], nullptr, 10) : (uint64_t)-1;
    uint64_t trace_end   = argc > 4 ? strtoull(argv[4], nullptr, 10) : (uint64_t)-1;

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
    // payload) vs. a bare fw_payload.bin, so this harness can accept either
    // without silently loading firmware 4 bytes offset from where it truly
    // belongs (that offset is exactly what a real FlashBootLoader would have
    // stripped before ever touching SDRAM — feeding it in unstripped once
    // caused OpenSBI's own fw_start relocation sanity check to correctly, but
    // misleadingly, fail).
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

    auto* top = new VMinimalSocSimTop;

    VerilatedFstC* tfp = nullptr;
    if (trace_start != (uint64_t)-1) {
        tfp = new VerilatedFstC;
        top->trace(tfp, 99);
        tfp->open("waveform.fst");
        fprintf(stderr, "Tracing enabled: cycles [%llu, %llu] -> waveform.fst\n",
                (unsigned long long)trace_start, (unsigned long long)trace_end);
    }
    uint64_t vtime = 0;

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
        bool tracing = tfp && cyc >= trace_start && cyc <= trace_end;
        top->clk = 0; top->eval();
        if (tracing) { tfp->dump(vtime++); }
        top->clk = 1; top->eval();
        if (tracing) { tfp->dump(vtime++); }

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
            fprintf(stderr, "... %llu cycles simulated | pc=0x%llx mtime=0x%llx mtimecmp=0x%llx irq=%d\n",
                    (unsigned long long)cyc, (unsigned long long)top->dbg_pc,
                    (unsigned long long)top->dbg_mtime, (unsigned long long)top->dbg_mtimecmp,
                    (int)top->dbg_timer_irq);
            last_report = cyc;
        }

        // Log every mtimecmp write (CLINT.mtimecmpWriteSeq incrementing) so we
        // can see the complete history of timer re-arms and find the LAST one
        // before the timer permanently stops being rearmed.
        static uint32_t last_mtimecmp_seq = 0xFFFFFFFFu;
        if (top->dbg_mtimecmp_write_seq != last_mtimecmp_seq) {
            last_mtimecmp_seq = top->dbg_mtimecmp_write_seq;
            fprintf(stderr, "[MTIMECMP-WRITE cyc %llu seq=%u] mtime=0x%llx mtimecmp=0x%llx\n",
                    (unsigned long long)cyc, top->dbg_mtimecmp_write_seq,
                    (unsigned long long)top->dbg_mtime, (unsigned long long)top->dbg_mtimecmp);
        }
    }

    fprintf(stderr, "\nDone: %llu cycles simulated, no more output expected within budget.\n",
            (unsigned long long)max_cycles);

    if (tfp) { tfp->close(); delete tfp; }
    delete top;
    return 0;
}
