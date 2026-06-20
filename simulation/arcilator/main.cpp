#include "ArcBorgSimulator.h"
#include "sim_app_config.h"
#include <cstring>
#include <fstream>
#include <iostream>
#include <vector>
#include <fcntl.h>
#include <unistd.h>

// CTS headless mode: run one frame, dump raw RGB888 pixels to stdout.
// Invoked as: arcilator_sim --cts-uart <uart_bytes.bin> <firmware.bin> <W> <H>
// The uart_bytes.bin file is the exact byte stream borgvk would send over serial
// (0xB0 shader upload, 0xAD MVP, 0xAE geometry, etc.).  The sim queues them as
// UART TX before the first clock cycle, then runs until the completion marker.
static int run_cts(const char *uart_file, const char *fw_path,
                   uint32_t width, uint32_t height)
{
    ArcBorgSimulator sim(fw_path, width, height);

    // Pre-queue all UART bytes so the firmware receives them from cycle 0.
    std::ifstream f(uart_file, std::ios::binary | std::ios::ate);
    if (!f) {
        std::cerr << "[CTS] Cannot open uart file: " << uart_file << "\n";
        return 1;
    }
    std::streamsize sz = f.tellg();
    f.seekg(0);
    std::vector<uint8_t> uart_bytes((size_t)sz);
    f.read((char *)uart_bytes.data(), sz);
    // Delay byte injection until after the firmware's first drain-loop gap-wait.
    // The firmware discards bytes arriving before the gap-wait finds GAP_CYCLES
    // (7500) of idle. At 4 MHz the firmware boots in ~100K cycles; 700K gives a
    // wide margin so that when bytes start arriving the firmware is already in
    // Step 2 (waiting for the first marker byte, 2M+ cycle window).
    sim.uart_tx.enqueue_gap(700000);
    sim.uart_tx.enqueue(uart_bytes.data(), (size_t)sz);

    // Firmware UART output goes to stdout inside BorgSimulatorBase.  Save the
    // real stdout (pipe to borgvk, or terminal/file in standalone mode), then
    // redirect fd 1 → /dev/null for the duration of the sim so firmware prints
    // don't pollute the pixel stream.  Pixels are written to the saved fd via
    // write(2) afterwards, bypassing stdio buffering.
    int pixel_fd = dup(STDOUT_FILENO);
    int devnull  = open("/dev/null", O_WRONLY);
    if (devnull >= 0) dup2(devnull, STDOUT_FILENO);

    // Run until frame complete (or watchdog).
    const uint64_t MAX_CYCLES = 15000000ULL;
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

    // Decode RGB565 tiled framebuffer → raw RGB888, write to stdout (fd 1 = pipe/file).
    const uint32_t *words = (const uint32_t *)sim.psram->mem.data();
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

int main(int argc, char **argv) {
    // CTS headless mode.
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

    // Normal interactive mode.
    if (argc < 3) {
        std::cerr << "Usage: " << argv[0] << " <firmware.bin> <app_name>\n";
        return 1;
    }
    std::string firmware_path = argv[1];
    std::string app_name = argv[2];
    uint32_t width = 32;
    uint32_t height = 32;

    ArcBorgSimulator sim(firmware_path, width, height);

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
                return 2;
            }
            if (total_cycles % 5000000 == 0) {
                std::cout << "[SIM] " << (total_cycles / 1000000) << "M cycles (frame " << (frame+1) << ")\n";
            }
        }
        uint64_t frame_cycles = total_cycles - frame_start;
        std::cout << "[SIM] Frame " << (frame+1) << " done: " << frame_cycles << " cycles"
                  << "  (est fps @ 4MHz: " << (4000000.0 / frame_cycles) << ")\n";
    }
    sim.save_ppm(app_name);
    return 0;
}
