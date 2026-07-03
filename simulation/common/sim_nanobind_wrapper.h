// simulation/common/sim_nanobind_wrapper.h
//
// Shared nanobind SimulatorWrapper.  Include this from a backend-specific
// nanobind_wrapper.cpp after defining three macros:
//
//   #define SIM_INCLUDE "ArcBorgSimulator.h"   // header for the backend class
//   #define SIM_TYPE    ArcBorgSimulator        // the backend class name
//   #define NB_MODULE_NAME arc_sim              // Python extension module name
//
// The #include SIM_INCLUDE / NB_MODULE(NB_MODULE_NAME, m) expansions are
// standard C preprocessor behaviour.

#pragma once
#include <nanobind/nanobind.h>
#include <nanobind/ndarray.h>
#include <nanobind/stl/string.h>
#include <cstring>
#include <vector>

#include SIM_INCLUDE  // expands to the backend header defined by the includer

namespace nb = nanobind;

class SimulatorWrapper {
    SIM_TYPE* sim;
    std::vector<uint8_t> fb_rgb;
public:
    SimulatorWrapper(const std::string& firmware_path,
                     uint32_t width = 32, uint32_t height = 32) {
        sim = new SIM_TYPE(firmware_path, width, height);
    }
    ~SimulatorWrapper() { delete sim; }

    bool step(uint32_t cycles)               { return sim->step(cycles); }
    uint32_t width()  const { return sim->width; }
    uint32_t height() const { return sim->height; }

    // Inject raw bytes into the UART RXD line (as if sent by the firmware's
    // serial peer — borgvk in production, test harness in CI).
    void uart_inject(nb::bytes data) {
        sim->uart_tx.enqueue(
            reinterpret_cast<const uint8_t*>(data.c_str()), data.size());
    }
    // Insert `cycles` idle cycles into the uart_tx stream before queued bytes.
    // Use once at startup (~3.5M cycles) so the firmware finishes booting before
    // the first borgvk packet arrives.
    void uart_inject_gap(uint32_t cycles) {
        sim->uart_tx.enqueue_gap(cycles);
    }
    // Set the RXD bit period (sim cycles/bit) — must match the firmware's own
    // UART_BAUD divisor (a function of the CLOCK_MHZ it was built with).
    // Call once, before any uart_inject(), and before the firmware boots.
    // Also updates the debug-print TX decoder so boot/status messages stay
    // readable (functionally independent of the RXD injection path).
    void uart_set_cycles_per_bit(int cycles_per_bit) {
        sim->uart_tx.set_cycles_per_bit(cycles_per_bit);
        sim->uart.set_cycles_per_bit(cycles_per_bit);
    }

    nb::ndarray<nb::numpy, uint8_t, nb::shape<-1, -1, 3>, nb::c_contig>
    get_framebuffer() {
        if (fb_rgb.size() != sim->width * sim->height * 3)
            fb_rgb.resize(sim->width * sim->height * 3);

        uint32_t* flat_words = (uint32_t*)sim->flat->mem.data();
        for (uint32_t y = 0; y < sim->height; y++) {
            for (uint32_t x = 0; x < sim->width; x++) {
                // RGB565 tiled framebuffer (tile stride 32 bytes = 8 words,
                // two pixels per 32-bit word).
                uint32_t tiles_per_row = sim->width >> 2;
                uint32_t tile_index = (y >> 2) * tiles_per_row + (x >> 2);
                uint32_t tile_idx   = (x & 3) | ((y & 3) << 2);
                uint32_t word_off   = sim->out_base_word + tile_index * 8 + (tile_idx >> 1);
                uint32_t word = flat_words[word_off];
                uint16_t px = (tile_idx & 1) ? (uint16_t)(word >> 16)
                                             : (uint16_t)(word & 0xFFFF);
                int r5 = (px >> 11) & 0x1F;
                int g6 = (px >> 5)  & 0x3F;
                int b5 =  px        & 0x1F;
                uint8_t r_b = (uint8_t)((r5 << 3) | (r5 >> 2));
                uint8_t g_b = (uint8_t)((g6 << 2) | (g6 >> 4));
                uint8_t b_b = (uint8_t)((b5 << 3) | (b5 >> 2));
                uint32_t out_idx = (y * sim->width + x) * 3;
                fb_rgb[out_idx + 0] = r_b;
                fb_rgb[out_idx + 1] = g_b;
                fb_rgb[out_idx + 2] = b_b;
            }
        }
        // Clear the DONE_MARKER so the next frame can be rendered.
        flat_words[sim->marker_offset_word] = 0;

        size_t shape[3] = { (size_t)sim->height, (size_t)sim->width, 3 };
        return nb::ndarray<nb::numpy, uint8_t, nb::shape<-1, -1, 3>, nb::c_contig>(
            fb_rgb.data(), 3, shape);
    }

    // Hardware perf-counter snapshot written by borg_present() at
    // DRAM_OUT(300020..300024) — see borg_driver.c.
    // (total, frag[vert+setup+frag], flush, stall, dma).
    nb::tuple get_perf_counters() {
        const uint32_t* w = (const uint32_t*)sim->flat->mem.data();
        uint32_t b = sim->out_base_word_buf0;
        return nb::make_tuple(w[b + 300020], w[b + 300021], w[b + 300022],
                               w[b + 300023], w[b + 300024]);
    }
};

// NB_MODULE takes the module name as a raw token; the preprocessor expands
// NB_MODULE_NAME before NB_MODULE sees it, so this works correctly.
NB_MODULE(NB_MODULE_NAME, m) {
    nb::class_<SimulatorWrapper>(m, "BorgSimulator")
        .def(nb::init<const std::string&, uint32_t, uint32_t>(),
             nb::arg("firmware_path"), nb::arg("width") = 32, nb::arg("height") = 32)
        .def("step",                &SimulatorWrapper::step)
        .def("uart_inject",         &SimulatorWrapper::uart_inject)
        .def("uart_inject_gap",     &SimulatorWrapper::uart_inject_gap)
        .def("uart_set_cycles_per_bit", &SimulatorWrapper::uart_set_cycles_per_bit)
        .def("get_framebuffer",     &SimulatorWrapper::get_framebuffer)
        .def("get_perf_counters",   &SimulatorWrapper::get_perf_counters)
        .def_prop_ro("width",       &SimulatorWrapper::width)
        .def_prop_ro("height",      &SimulatorWrapper::height);
}
