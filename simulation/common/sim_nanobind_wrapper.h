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

    void load_texture(const std::string& tex_path, uint32_t tex_dim = 32) {
        sim->load_texture(tex_path, tex_dim);
    }
    bool step(uint32_t cycles)               { return sim->step(cycles); }
    void set_camera_angles(float rx, float ry) { sim->set_camera_angles(rx, ry); }
    uint32_t width()  const { return sim->width; }
    uint32_t height() const { return sim->height; }

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
};

// NB_MODULE takes the module name as a raw token; the preprocessor expands
// NB_MODULE_NAME before NB_MODULE sees it, so this works correctly.
NB_MODULE(NB_MODULE_NAME, m) {
    nb::class_<SimulatorWrapper>(m, "BorgSimulator")
        .def(nb::init<const std::string&, uint32_t, uint32_t>(),
             nb::arg("firmware_path"), nb::arg("width") = 32, nb::arg("height") = 32)
        .def("load_texture",        &SimulatorWrapper::load_texture,
             nb::arg("tex_path"), nb::arg("tex_dim") = 32)
        .def("step",                &SimulatorWrapper::step)
        .def("set_camera_angles",   &SimulatorWrapper::set_camera_angles)
        .def("get_framebuffer",     &SimulatorWrapper::get_framebuffer)
        .def_prop_ro("width",       &SimulatorWrapper::width)
        .def_prop_ro("height",      &SimulatorWrapper::height);
}
