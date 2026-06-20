#include "ArcBorgSimulator.h"   // → BorgSimulatorBase.h → common_sim.h → borg_layout.h
#include "sim_app_config.h"
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <vector>
#include <fcntl.h>
#include <unistd.h>

// ---- fp16 + DRAM mailbox helpers (host side of the CTS draw path) --------

// Round-to-nearest-even float → IEEE-754 half (fp16).
static uint16_t f32_to_f16(float f) {
    uint32_t x;
    std::memcpy(&x, &f, 4);
    uint32_t sign = (x >> 16) & 0x8000u;
    int32_t  exp  = (int32_t)((x >> 23) & 0xFF) - 127 + 15;
    uint32_t mant = x & 0x7FFFFFu;
    if (exp <= 0) {
        if (exp < -10) return (uint16_t)sign;           // underflow → ±0
        mant |= 0x800000u;
        uint32_t shift = (uint32_t)(14 - exp);
        uint32_t half = (mant + (1u << (shift - 1))) >> shift;
        return (uint16_t)(sign | half);
    }
    if (exp >= 0x1F) return (uint16_t)(sign | 0x7C00u); // overflow → ±inf
    uint16_t h = (uint16_t)(sign | ((uint32_t)exp << 10) | (mant >> 13));
    if (mant & 0x1000u) h++;                             // round to nearest even
    return h;
}

// Write one 32-bit word into DRAM at an SPI byte address (little-endian),
// matching the firmware's DRAM_OUT_RAW word access.
static void flat_write_word(ArcBorgSimulator &sim, uint32_t spi_byte, uint32_t v) {
    uint8_t *m = sim.flat->mem.data();
    m[spi_byte+0] = v & 0xFF;        m[spi_byte+1] = (v >> 8)  & 0xFF;
    m[spi_byte+2] = (v >> 16) & 0xFF; m[spi_byte+3] = (v >> 24) & 0xFF;
}
static void mb_word(ArcBorgSimulator &sim, uint32_t word_idx, uint32_t v) {
    flat_write_word(sim, BORG_CTS_MAILBOX_SPI + word_idx * 4, v);
}

// Fill the whole texture region with white (fp16 1.0 = 0x3C00) so the baked
// frag's `texel × vertex_color` modulation passes vertex color through.
static void fill_white_texture(ArcBorgSimulator &sim) {
    uint8_t *m = sim.flat->mem.data();
    // White (R=G=B=1.0=0x3C00) per 6-byte texel so frag texel×color = color.
    for (uint32_t a = TEX_DRAM_BYTE_ADDR_FIXED;
         a + 5 < TEX_DRAM_BYTE_ADDR_FIXED + TEX_REGION_BYTES; a += 6) {
        m[a]=0x00; m[a+1]=0x3C; m[a+2]=0x00;
        m[a+3]=0x3C; m[a+4]=0x00; m[a+5]=0x3C;
    }
}

// Write a draw command (positions, per-vertex colors, indices, MVP) into the
// DRAM mailbox and lay down a white texture.  pos/col are float xyz/rgb.
static void write_mailbox_draw(ArcBorgSimulator &sim,
                               const float *pos, const float *col, int nverts,
                               const uint8_t *idx, int ntris,
                               const float mvp[16]) {
    fill_white_texture(sim);
    mb_word(sim, BORG_CTS_OFF_NVERTS, (uint32_t)nverts);
    mb_word(sim, BORG_CTS_OFF_NTRIS,  (uint32_t)ntris);
    for (int i = 0; i < 16; i++)
        mb_word(sim, BORG_CTS_OFF_MVP + i, f32_to_f16(mvp[i]));
    for (int i = 0; i < nverts * 3; i++) {
        mb_word(sim, BORG_CTS_OFF_POS   + i, f32_to_f16(pos[i]));
        mb_word(sim, BORG_CTS_OFF_COLOR + i, f32_to_f16(col[i]));
    }
    for (int i = 0; i < ntris * 3; i++)
        mb_word(sim, BORG_CTS_OFF_IDX + i, (uint32_t)idx[i]);
    // Magic written LAST so a reader never sees a half-built command.
    mb_word(sim, BORG_CTS_OFF_MAGIC, BORG_CTS_MAGIC);
}

// Run until the completion marker (or watchdog), then decode the RGB565 tiled
// framebuffer to RGB888 and write it to `pixel_fd`.  Returns 0 on success.
static int run_and_dump(ArcBorgSimulator &sim, uint32_t width, uint32_t height,
                        int pixel_fd) {
    int devnull = open(getenv("CTS_DBG") ? "/dev/stderr" : "/dev/null", O_WRONLY);
    if (devnull >= 0) dup2(devnull, STDOUT_FILENO);

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

// Checkpoint test: render a hardcoded RGB triangle via the DRAM mailbox.
// arcilator_sim --cts-tri <firmware.bin> <W> <H>
static int run_cts_tri(const char *fw_path, uint32_t width, uint32_t height) {
    ArcBorgSimulator sim(fw_path, width, height);
    // NDC triangle with red/green/blue corners (Vulkan y-down screen space).
    const float pos[9] = {
        -0.9f, -0.9f, 0.5f,
         0.9f, -0.9f, 0.5f,
         0.0f,  0.9f, 0.5f,
    };
    const float col[9] = {
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f,
    };
    const uint8_t idx[3] = {0, 2, 1};  // reversed winding (cull test)
    const float identity[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    write_mailbox_draw(sim, pos, col, 3, idx, 1, identity);

    if (getenv("CTS_DBG")) {
        const uint32_t *w = (const uint32_t *)(sim.flat->mem.data() + BORG_CTS_MAILBOX_SPI);
        std::cerr << "[CTS] mailbox after write: magic=" << std::hex << w[BORG_CTS_OFF_MAGIC]
                  << " nverts=" << std::dec << w[BORG_CTS_OFF_NVERTS]
                  << " pos0=" << std::hex << w[BORG_CTS_OFF_POS] << std::dec << "\n";
    }

    int pixel_fd = dup(STDOUT_FILENO);
    int rc = run_and_dump(sim, width, height, pixel_fd);
    if (getenv("CTS_DBG")) {
        const uint32_t *w = (const uint32_t *)(sim.flat->mem.data() + BORG_CTS_MAILBOX_SPI);
        std::cerr << "[CTS] mailbox after run:   magic=" << std::hex << w[BORG_CTS_OFF_MAGIC]
                  << std::dec << "\n";
    }
    return rc;
}

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

    // Save the real stdout (pipe to borgvk, or terminal/file standalone); pixels
    // are written there by run_and_dump after redirecting fd 1 → /dev/null.
    int pixel_fd = dup(STDOUT_FILENO);
    return run_and_dump(sim, width, height, pixel_fd);
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

    // CTS mailbox checkpoint test: hardcoded RGB triangle, no UART.
    if (argc >= 2 && strcmp(argv[1], "--cts-tri") == 0) {
        if (argc < 5) {
            std::cerr << "Usage: " << argv[0]
                      << " --cts-tri <firmware.bin> <W> <H>\n";
            return 1;
        }
        uint32_t w = (uint32_t)atoi(argv[3]);
        uint32_t h = (uint32_t)atoi(argv[4]);
        return run_cts_tri(argv[2], w, h);
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
