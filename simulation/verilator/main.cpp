#include "BorgSimulator.h"
#include "sim_app_config.h"
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <vector>
#include <fcntl.h>
#include <unistd.h>

// Run until the completion marker (or watchdog), then decode the RGB565 tiled
// framebuffer to RGB888 and write it to pixel_fd.  Returns 0 on success.
static int run_and_dump(VerBorgSimulator &sim, uint32_t width, uint32_t height,
                        int pixel_fd)
{
    int devnull = open(getenv("CTS_DBG") ? "/dev/stderr" : "/dev/null", O_WRONLY);
    if (devnull >= 0) dup2(devnull, STDOUT_FILENO);

    // Default sized for small/legacy captures.  A full borgvk burst (2 shaders +
    // geometry + up to RX_TEX_DIM texture rows + MVP, tens of KB) takes 10 x
    // SIM_UART_CYCLES_PER_BIT sim-cycles/byte — e.g. 26 KB needs ~6.6M cycles for
    // the wire transfer, before any render time.  Override via CTS_MAX_CYCLES for
    // large captures rather than bumping the default (keeps small-test runs fast
    // to fail).
    uint64_t MAX_CYCLES = 50000000ULL;
    if (const char *ov = getenv("CTS_MAX_CYCLES")) MAX_CYCLES = strtoull(ov, nullptr, 10);
    uint64_t cycles = 0;
    while (!sim.step(10000)) {
        cycles += 10000;
        if (cycles > MAX_CYCLES) {
            std::cerr << "[CTS] Watchdog: frame not complete after "
                      << MAX_CYCLES << " cycles\n";
            if (devnull >= 0) close(devnull);
            if (pixel_fd >= 0) close(pixel_fd);
            return 2;
        }
    }
    if (devnull >= 0) { close(devnull); devnull = -1; }

    const uint32_t *words = (const uint32_t *)sim.flat->mem.data();
    uint32_t base = sim.out_base_word;
    std::vector<uint8_t> rgb_buf(width * height * 3);
    for (uint32_t y = 0; y < height; y++) {
        for (uint32_t x = 0; x < width; x++) {
            uint32_t tiles_per_row = width >> 2;
            uint32_t tile_index   = (y >> 2) * tiles_per_row + (x >> 2);
            uint32_t tile_idx     = (x & 3) | ((y & 3) << 2);
            uint32_t word_off     = base + tile_index * 8 + (tile_idx >> 1);
            uint32_t word         = words[word_off];
            uint16_t px           = (tile_idx & 1) ? (uint16_t)(word >> 16)
                                                   : (uint16_t)(word & 0xFFFF);
            uint8_t r = (uint8_t)(((px >> 11) & 0x1F) << 3);
            uint8_t g = (uint8_t)(((px >>  5) & 0x3F) << 2);
            uint8_t b = (uint8_t)(( px        & 0x1F) << 3);
            r |= r >> 5; g |= g >> 6; b |= b >> 5;
            size_t off = ((size_t)y * width + x) * 3;
            rgb_buf[off] = r; rgb_buf[off+1] = g; rgb_buf[off+2] = b;
        }
    }
    size_t total = rgb_buf.size(), written = 0;
    while (written < total && pixel_fd >= 0) {
        ssize_t n = write(pixel_fd, rgb_buf.data() + written, total - written);
        if (n <= 0) break;
        written += (size_t)n;
    }
    if (pixel_fd >= 0) close(pixel_fd);
    return 0;
}

// CTS headless UART mode: pre-queue the byte stream then run to completion.
// Invoked as: verilator_sim --cts-uart <uart.bin> <firmware.bin> <W> <H>
static int run_cts(const char *uart_file, const char *fw_path,
                   uint32_t width, uint32_t height)
{
    Verilated::commandArgs(0, (char **)nullptr);
    VerBorgSimulator sim(fw_path, width, height);

    std::ifstream f(uart_file, std::ios::binary | std::ios::ate);
    if (!f) {
        std::cerr << "[CTS] Cannot open uart file: " << uart_file << "\n";
        return 1;
    }
    std::streamsize sz = f.tellg();
    f.seekg(0);
    std::vector<uint8_t> uart_bytes((size_t)sz);
    f.read((char *)uart_bytes.data(), sz);
    // kernel.bin is built at CLOCK_MHZ=25 with BORG_UART_BAUD=SIM_UART_BAUD;
    // the harness must use the same cycles per bit (common_sim.h).
    sim.uart_tx.set_cycles_per_bit(SIM_UART_CYCLES_PER_BIT);
    sim.uart.set_cycles_per_bit(SIM_UART_CYCLES_PER_BIT);
    // Delay byte injection until after firmware has booted and reached its
    // first drain-loop gap-wait — see arcilator/main.cpp's twin of this for
    // why: an already-arrived burst is misread as stale "padding" by the
    // gap-sync heuristic if it starts polling mid-burst.  8M cycles covers
    // Verilator's boot time (SDRAM model adds latency beyond raw instruction
    // cycles) with a solid margin.
    sim.uart_tx.enqueue_gap(8000000);
    sim.uart_tx.enqueue(uart_bytes.data(), (size_t)sz);

    int pixel_fd = dup(STDOUT_FILENO);
    return run_and_dump(sim, width, height, pixel_fd);
}

// Push-constant staging test (Step 50 item 13).
//
// Invoked as: verilator_sim --push-const-test <firmware.bin>
//
// Narrow on purpose: this proves the 0xB2 transport and the firmware's
// staging arithmetic, NOT that a compiled shader reads the value back. The
// full chain additionally needs a borgc-compiled shader that actually does a
// push-constant LOAD, which no content in the tree does yet.
//
// Two packets rather than one, because the interesting bugs live in the
// offset path: a full-range push, then a short push at a non-zero offset.
// Asserting that the second landed ONLY in its window catches both an offset
// that is ignored (would overwrite from word 0) and a length that is not
// clamped (would run past its window).
//
// The packets are built here rather than read from a fixture file so there is
// one source of truth for the format in this test; the format itself is
// cross-checked against borgvk's sender and the firmware's parser by the
// constants in each (132 B, 32 words, marker 0xB2).
static int run_push_const_test(const char *fw_path)
{
    Verilated::commandArgs(0, (char **)nullptr);
    // Framebuffer size is irrelevant here (nothing renders), but the
    // constructor needs one; 32x32 keeps the SDRAM model's init cheap.
    VerBorgSimulator sim(fw_path, 32, 32);

    const uint32_t MAXW = BORG_PUSH_CONST_MAX_WORDS;
    const int PKT = 1 + 1 + 1 + (int)MAXW * 4 + 1;

    // Expected DRAM image, maintained alongside the packets we send.
    std::vector<uint32_t> expect(MAXW);

    auto build = [&](uint32_t off_w, uint32_t n_w, uint32_t seed) {
        std::vector<uint8_t> p((size_t)PKT, 0);
        p[0] = 0xB2;
        p[1] = (uint8_t)off_w;
        p[2] = (uint8_t)n_w;
        for (uint32_t i = 0; i < n_w; i++) {
            uint32_t v = seed + i;
            p[3 + i * 4 + 0] = (uint8_t)(v & 0xFF);
            p[3 + i * 4 + 1] = (uint8_t)((v >> 8) & 0xFF);
            p[3 + i * 4 + 2] = (uint8_t)((v >> 16) & 0xFF);
            p[3 + i * 4 + 3] = (uint8_t)((v >> 24) & 0xFF);
            expect[off_w + i] = v;   // last write wins, as on the device
        }
        uint8_t csum = 0;
        for (int i = 1; i < PKT - 1; i++) csum ^= p[(size_t)i];
        p[(size_t)PKT - 1] = csum;
        return p;
    };

    std::vector<uint8_t> a = build(0, MAXW, 0xA5A50000u);   // full range
    std::vector<uint8_t> b = build(8, 4,    0xB2B20000u);   // window at word 8

    std::vector<uint8_t> stream;
    stream.insert(stream.end(), a.begin(), a.end());
    stream.insert(stream.end(), b.begin(), b.end());

    // Same baud/boot-gap handling as run_cts() above -- see its comments for
    // why the gap is required rather than merely helpful.
    sim.uart_tx.set_cycles_per_bit(SIM_UART_CYCLES_PER_BIT);
    sim.uart.set_cycles_per_bit(SIM_UART_CYCLES_PER_BIT);
    sim.uart_tx.enqueue_gap(8000000);
    sim.uart_tx.enqueue(stream.data(), stream.size());

    // 8M boot gap + wire time for 264 bytes (tiny at SIM_UART_CYCLES_PER_BIT)
    // plus firmware processing. 16M leaves ample margin, and nothing here
    // waits on a rendered frame.
    const uint64_t MAX_CYCLES = 16000000ULL;
    for (uint64_t c = 0; c < MAX_CYCLES; c += 100000)
        sim.step(100000);

    // flat word index == SPI byte address / 4 (see BorgSimulator's
    // out_base_word derivation), and a 32-bit word is two SDRAM halfwords.
    uint32_t base_w32 = BORG_PUSH_CONST_SPI / 4;
    int bad = 0;
    for (uint32_t i = 0; i < MAXW; i++) {
        uint32_t w32 = base_w32 + i;
        uint16_t lo = sim.dbg_read((w32 * 2) | 0x800000);
        uint16_t hi = sim.dbg_read((w32 * 2 + 1) | 0x800000);
        uint32_t got = (uint32_t)lo | ((uint32_t)hi << 16);
        if (got != expect[i]) {
            std::cerr << "[PUSH] word " << i << " (SPI 0x" << std::hex
                      << (BORG_PUSH_CONST_SPI + i * 4) << "): got 0x" << got
                      << ", expected 0x" << expect[i] << std::dec << "\n";
            bad++;
        }
    }

    if (bad) {
        std::cerr << "[PUSH] FAIL: " << bad << " of " << MAXW
                  << " staged words wrong\n";
        return 1;
    }
    std::cout << "[PUSH] PASS: " << MAXW
              << " words staged at SPI 0x" << std::hex << BORG_PUSH_CONST_SPI
              << std::dec << ", including a 4-word window at offset 8\n";
    return 0;
}

int main(int argc, char** argv) {
    if (argc >= 3 && strcmp(argv[1], "--push-const-test") == 0)
        return run_push_const_test(argv[2]);

    // CTS headless UART mode — same protocol as arcilator_sim --cts-uart.
    if (argc >= 2 && strcmp(argv[1], "--cts-uart") == 0) {
        if (argc < 6) {
            std::cerr << "Usage: " << argv[0]
                      << " --cts-uart <uart.bin> <firmware.bin> <W> <H>\n";
            return 1;
        }
        uint32_t w = (uint32_t)atoi(argv[4]);
        uint32_t h = (uint32_t)atoi(argv[5]);
        return run_cts(argv[2], argv[3], w, h);
    }

    if (argc < 3) {
        std::cerr << "Usage: " << argv[0] << " <firmware.bin> <app_name> [width] [height]\n";
        return 1;
    }
    Verilated::commandArgs(argc, argv);
    std::string firmware_path = argv[1];
    std::string app_name = argv[2];

    uint32_t width  = argc > 3 ? std::atoi(argv[3]) : 32;
    uint32_t height = argc > 4 ? std::atoi(argv[4]) : 32;

    VerBorgSimulator sim(firmware_path, width, height);

    AppConfig cfg = get_app_config(app_name);
    sim.load_texture(cfg.tex_path, cfg.tex_dim);
    if (cfg.has_camera)
        sim.set_camera_angles(cfg.cam_angle_x, cfg.cam_angle_y);

    std::cout << "[SIM] Starting simulation...\n";

    const int NUM_FRAMES = cfg.num_frames;
    uint64_t MAX_CYCLES_PER_FRAME = 12000000ULL;
    if (const char* envMax = std::getenv("MAX_CYCLES_PER_FRAME")) {
        MAX_CYCLES_PER_FRAME = strtoull(envMax, nullptr, 10);
    }
    uint64_t total_cycles = 0;
    for (int frame = 0; frame < NUM_FRAMES; frame++) {
        uint64_t frame_start = total_cycles;
        while (!sim.step(100000)) {
            total_cycles += 100000;
            if (total_cycles - frame_start > MAX_CYCLES_PER_FRAME) {
                std::cerr << "[SIM] ERROR: frame " << (frame + 1) << " exceeded "
                          << (MAX_CYCLES_PER_FRAME / 1000000) << "M cycles with no completion "
                          << "marker — aborting (likely a render hang).\n";
                sim.save_ppm(app_name);
                sim.report_bandwidth();
                return 2;
            }
            if (total_cycles % 5000000 == 0)
                std::cout << "[SIM] " << (total_cycles / 1000000) << "M cycles (frame " << (frame+1) << ")\n";
        }
        uint64_t frame_cycles = total_cycles - frame_start;
        std::cout << "[SIM] Frame " << (frame+1) << " done: " << frame_cycles << " cycles"
                  << "  (est fps @ 25MHz: " << (25000000.0 / frame_cycles) << ")\n";
    }
    sim.save_ppm(app_name);
    sim.report_bandwidth();
    return 0;
}
