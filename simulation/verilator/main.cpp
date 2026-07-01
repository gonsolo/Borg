#include "BorgSimulator.h"
#include "sim_app_config.h"
#include <cstdint>
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

    const uint64_t MAX_CYCLES = 50000000ULL;
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
    // kernel.bin is built at CLOCK_MHZ=25 (matching ULX3S) so the borgvk UART
    // drain loop's software polling has enough cycles/bit margin — see
    // borg_kernel.c and simulation/common/uart_tx.h.  115200 baud @ 25 MHz ≈
    // 217 sim-cycles/bit; must match the firmware's own UART_BAUD divisor.
    sim.uart_tx.set_cycles_per_bit(217);
    // The SDRAM model adds per-access latency beyond raw instruction cycles, so
    // use a wider gap than arcilator's to ensure the firmware has finished
    // booting and is waiting in its drain-loop before the first byte arrives.
    sim.uart_tx.enqueue_gap(3500000);
    sim.uart_tx.enqueue(uart_bytes.data(), (size_t)sz);

    int pixel_fd = dup(STDOUT_FILENO);
    return run_and_dump(sim, width, height, pixel_fd);
}

int main(int argc, char** argv) {
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
    const uint64_t MAX_CYCLES_PER_FRAME = 12000000ULL;
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
