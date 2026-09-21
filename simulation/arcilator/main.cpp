#include "ArcBorgSimulator.h"   // → BorgSimulatorBase.h → common_sim.h → borg_layout.h
#include "sim_app_config.h"
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <vector>
#include <fcntl.h>
#include <unistd.h>

// ---- DRAM mailbox helpers (host side of the CTS draw path) ---------------

// A float32 as the datapath word it is: the shader datapath is FP32, so the
// mailbox carries the IEEE bits unchanged.
static uint32_t f32_bits(float f) {
    uint32_t x;
    std::memcpy(&x, &f, 4);
    return x;
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

// Fill the whole texture region with white so the baked frag's
// `texel × vertex_color` modulation passes vertex color through. Texels are
// FP16 (1.0 = 0x3C00), two 32-bit words per texel -- word0 = {G, R},
// word1 = {0, B}, the layout BorgTextureUnit reads (see borg_upload_texture).
static void fill_white_texture(ArcBorgSimulator &sim) {
    for (uint32_t a = TEX_DRAM_BYTE_ADDR_FIXED;
         a + 7 < TEX_DRAM_BYTE_ADDR_FIXED + TEX_REGION_BYTES; a += 8) {
        flat_write_word(sim, a,     0x3C003C00u);
        flat_write_word(sim, a + 4, 0x00003C00u);
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
        mb_word(sim, BORG_CTS_OFF_MVP + i, f32_bits(mvp[i]));
    for (int i = 0; i < nverts * 3; i++) {
        mb_word(sim, BORG_CTS_OFF_POS   + i, f32_bits(pos[i]));
        mb_word(sim, BORG_CTS_OFF_COLOR + i, f32_bits(col[i]));
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

    // Default sized for small/legacy captures.  A full borgvk burst (2 shaders +
    // geometry + up to RX_TEX_DIM texture rows + MVP, tens of KB) takes 10 x
    // SIM_UART_CYCLES_PER_BIT sim-cycles/byte — e.g. 26 KB needs ~6.6M cycles for
    // the wire transfer, before any render time.  Override via CTS_MAX_CYCLES for
    // large captures rather than bumping the default (keeps small-test runs fast
    // to fail).
    uint64_t MAX_CYCLES = 15000000ULL;
    if (const char *ov = getenv("CTS_MAX_CYCLES")) MAX_CYCLES = strtoull(ov, nullptr, 10);
    uint64_t cycles = 0;

    // CTS_TS_RAMP=<W>: render-time stamping for the frameless-latency
    // experiment. Once the first tile of the frame reaches DRAM (render start),
    // ramp the green channel of the pushed colour (push-constant words 8..11,
    // what pushconst.frag outputs) linearly from 0 to 1 over W cycles. A shader
    // that LOADs its colour at run time then paints each tile with the value
    // current when it rendered, so the image is a per-tile render-time map.
    // Off unless the variable is set.
    uint64_t ramp_w = 0;
    if (const char *rw = getenv("CTS_TS_RAMP")) ramp_w = strtoull(rw, nullptr, 10);
    const uint32_t step_cycles = ramp_w ? 2000 : 10000;
    const uint32_t *fbw = (const uint32_t *)sim.flat->mem.data();
    const uint32_t fb0 = sim.out_base_word_buf0;
    // Buffer 1 starts one word later than frame_tile_size_words: the completion
    // marker word sits between the two buffers (see BorgSimulatorBase::step).
    const uint32_t fb1 = sim.out_base_word_buf0 + sim.frame_tile_size_words + 1;
    const uint32_t init0 = fbw[fb0], init1 = fbw[fb1];
    bool ramp_started = false;
    uint64_t ramp_t0 = 0;
    auto poke_ramp = [&]() {
        if (cycles % 2000000 == 0)
            std::cerr << "[RAMP] cycle " << cycles << " fb0=0x" << std::hex << fbw[fb0]
                      << " fb1=0x" << fbw[fb1] << std::dec << "\n";
        if (!ramp_started && (fbw[fb0] != init0 || fbw[fb1] != init1)) {
            ramp_started = true; ramp_t0 = cycles;
            std::cerr << "[RAMP] render start detected at cycle " << cycles << "\n";
        }
        double g = ramp_started ? double(cycles - ramp_t0) / double(ramp_w) : 0.0;
        if (g > 1.0) g = 1.0;
        flat_write_word(sim, BORG_PUSH_CONST_SPI + 8 * 4,  f32_bits(0.0f));
        flat_write_word(sim, BORG_PUSH_CONST_SPI + 9 * 4,  f32_bits((float)g));
        flat_write_word(sim, BORG_PUSH_CONST_SPI + 10 * 4, f32_bits(0.0f));
        flat_write_word(sim, BORG_PUSH_CONST_SPI + 11 * 4, f32_bits(1.0f));
    };

    while (!sim.step(step_cycles)) {
        cycles += step_cycles;
        if (ramp_w) poke_ramp();
        if (cycles > MAX_CYCLES) {
            std::cerr << "[CTS] Watchdog: frame not complete after "
                      << MAX_CYCLES << " cycles\n";
            if (devnull >= 0) close(devnull);
            if (pixel_fd >= 0) close(pixel_fd);
            return 2;
        }
    }
    if (devnull >= 0) { close(devnull); devnull = -1; }

    // Optional: dump the hardware perf counters borg_present() writes to a
    // DRAM scratch region right after the frame completes (see borg_driver.c
    // and sim_nanobind_wrapper.h's get_perf_counters() for the Python path).
    if (getenv("ARC_TIMING_DBG")) {
        const uint32_t *w = (const uint32_t *)sim.flat->mem.data();
        uint32_t perf_total = w[sim.out_base_word_buf0 + 300020];
        uint32_t perf_frag  = w[sim.out_base_word_buf0 + 300021];
        uint32_t perf_flush = w[sim.out_base_word_buf0 + 300022];
        uint32_t perf_stall = w[sim.out_base_word_buf0 + 300023];
        uint32_t perf_dma   = w[sim.out_base_word_buf0 + 300024];
        auto pct = [&](uint32_t v) { return perf_total ? 100.0 * v / perf_total : 0.0; };
        std::cerr << "[PERF] total=" << perf_total
                  << " frag(vert+setup+frag)=" << perf_frag << " (" << pct(perf_frag) << "%)"
                  << " flush=" << perf_flush << " (" << pct(perf_flush) << "%)"
                  << " stall=" << perf_stall << " (" << pct(perf_stall) << "%)"
                  << " dma=" << perf_dma << " (" << pct(perf_dma) << "%)\n";
    }

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
    sim.uart.set_cycles_per_bit(SIM_UART_CYCLES_PER_BIT);
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

// Draw a host-supplied mesh via the DRAM mailbox.  Geometry comes from a binary
// file written by borgvk (or any host):
//   uint32 magic = 0x42475254 ("BGRT")
//   uint32 nverts, uint32 ntris
//   float  mvp[16]
//   float  pos[nverts*3]   (clip/NDC xyz)
//   float  col[nverts*3]   (rgb 0..1)
//   uint32 idx[ntris*3]
// Invoked as: arcilator_sim --cts-draw <geom.bin> <firmware.bin> <W> <H>
static int run_cts_draw(const char *geom_file, const char *fw_path,
                        uint32_t width, uint32_t height) {
    std::ifstream f(geom_file, std::ios::binary);
    if (!f) {
        std::cerr << "[CTS] Cannot open geom file: " << geom_file << "\n";
        return 1;
    }
    uint32_t magic = 0, nverts = 0, ntris = 0;
    f.read((char *)&magic, 4);
    f.read((char *)&nverts, 4);
    f.read((char *)&ntris, 4);
    if (magic != 0x42475254u) {
        std::cerr << "[CTS] bad geom magic: " << std::hex << magic << "\n";
        return 1;
    }
    if (nverts < 1 || nverts > BORG_CTS_MAX_VERTS ||
        ntris  < 1 || ntris  > BORG_CTS_MAX_TRIS) {
        std::cerr << "[CTS] geom out of range: nverts=" << nverts
                  << " ntris=" << ntris << "\n";
        return 1;
    }
    float mvp[16];
    f.read((char *)mvp, sizeof(mvp));
    std::vector<float> pos(nverts * 3), col(nverts * 3);
    f.read((char *)pos.data(), pos.size() * 4);
    f.read((char *)col.data(), col.size() * 4);
    std::vector<uint32_t> idx32(ntris * 3);
    f.read((char *)idx32.data(), idx32.size() * 4);
    if (!f) {
        std::cerr << "[CTS] short read on geom file\n";
        return 1;
    }
    std::vector<uint8_t> idx(ntris * 3);
    for (size_t i = 0; i < idx.size(); i++)
        idx[i] = (uint8_t)idx32[i];

    ArcBorgSimulator sim(fw_path, width, height);
    sim.uart.set_cycles_per_bit(SIM_UART_CYCLES_PER_BIT);
    write_mailbox_draw(sim, pos.data(), col.data(), (int)nverts,
                       idx.data(), (int)ntris, mvp);

    int pixel_fd = dup(STDOUT_FILENO);
    return run_and_dump(sim, width, height, pixel_fd);
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
    // kernel.bin is built at CLOCK_MHZ=25 with BORG_UART_BAUD=SIM_UART_BAUD;
    // the harness must use the same cycles per bit (common_sim.h).
    sim.uart_tx.set_cycles_per_bit(SIM_UART_CYCLES_PER_BIT);
    sim.uart.set_cycles_per_bit(SIM_UART_CYCLES_PER_BIT);
    // Delay byte injection until after firmware has booted (shader modules,
    // pipeline, mailbox check, ...) and reached its first drain-loop gap-wait.
    // Measured boot takes ~2.3M cycles before the first UART poll; bytes
    // arriving before firmware is polling sit safely in the 1-deep hardware
    // RX buffer (CTS-gated, see BorgSimulatorBase::step), but the firmware's
    // own gap-sync heuristic — which assumes a genuinely idle line — misreads
    // an already-arrived burst as stale "padding" and desyncs on it if it
    // starts polling mid-burst.  8M cycles gives a solid margin over boot.
    sim.uart_tx.enqueue_gap(8000000);
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

    // CTS draw from host geometry file (borgvk sim path).
    if (argc >= 2 && strcmp(argv[1], "--cts-draw") == 0) {
        if (argc < 6) {
            std::cerr << "Usage: " << argv[0]
                      << " --cts-draw <geom.bin> <firmware.bin> <W> <H>\n";
            return 1;
        }
        uint32_t w = (uint32_t)atoi(argv[4]);
        uint32_t h = (uint32_t)atoi(argv[5]);
        return run_cts_draw(argv[2], argv[3], w, h);
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
