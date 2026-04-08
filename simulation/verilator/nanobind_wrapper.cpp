#include <nanobind/nanobind.h>
#include <nanobind/ndarray.h>
#include <nanobind/stl/string.h>
#include "BorgSimulator.h"

namespace nb = nanobind;

class SimulatorWrapper {
    BorgSimulator* sim;
    std::vector<uint8_t> fb_rgb;
public:
    SimulatorWrapper(const std::string& firmware_path) {
        sim = new BorgSimulator(firmware_path, 32, 32);
    }
    
    ~SimulatorWrapper() {
        delete sim;
    }
    
    void load_texture(const std::string& tex_path) {
        sim->load_texture(tex_path);
    }
    
    bool step(uint32_t cycles) {
        return sim->step(cycles);
    }
    
    void set_camera_angles(float rx, float ry) {
        uint32_t* psram_words = (uint32_t*)sim->psram->mem.data();
        float* psram_floats = (float*)&psram_words[sim->psram_spi_word_offset];
        // Store rx, ry at PSRAM_BASE + 8 and PSRAM_BASE + 12
        psram_floats[2] = rx;
        psram_floats[3] = ry;
    }
    
    // Write 31 16-bit uniform values (e.g. matrices, edge constants) into PSRAM offset
    void set_uniforms(const std::vector<float>& floats) {
        // ... Wait, uniform setup in the C++ testbench was via psram writes?
        // Actually the software does it via MMIO directly, or PSRAM.
        // We can expose the ability to overwrite the PSRAM uniform page area directly.
    }
    
    // Expose the 32x32x3 RGB byte buffer as a NumPy array (zero-copy read)
    // Expose the 32x32x3 RGB byte buffer as a NumPy array
    nb::ndarray<nb::numpy, uint8_t, nb::shape<-1, -1, 3>, nb::c_contig> get_framebuffer() {
        if (fb_rgb.size() != sim->width * sim->height * 3) {
            fb_rgb.resize(sim->width * sim->height * 3);
        }
        
        uint32_t* psram_words_out = (uint32_t*)sim->psram->mem.data();
        
        for (uint32_t y = 0; y < sim->height; y++) {
            for (uint32_t x = 0; x < sim->width; x++) {
                uint32_t base = sim->out_base_word + (y * sim->width + x) * 3;
                uint16_t r_fp16 = (uint16_t)psram_words_out[base + 0];
                uint16_t g_fp16 = (uint16_t)psram_words_out[base + 1];
                uint16_t b_fp16 = (uint16_t)psram_words_out[base + 2];
                
                uint8_t r_b = std::max(0, std::min(255, (int)(fp16_to_float(r_fp16) * 255)));
                uint8_t g_b = std::max(0, std::min(255, (int)(fp16_to_float(g_fp16) * 255)));
                uint8_t b_b = std::max(0, std::min(255, (int)(fp16_to_float(b_fp16) * 255)));
                
                uint32_t out_idx = (y * sim->width + x) * 3;
                fb_rgb[out_idx + 0] = r_b;
                fb_rgb[out_idx + 1] = g_b;
                fb_rgb[out_idx + 2] = b_b;
            }
        }
        
        // Clear the DONE_MARKER so the next frame can be rendered
        psram_words_out[sim->marker_offset_word] = 0;

        size_t shape[3] = { (size_t)sim->height, (size_t)sim->width, 3 };
        
        return nb::ndarray<nb::numpy, uint8_t, nb::shape<-1, -1, 3>, nb::c_contig>(
            fb_rgb.data(), 
            3,      /* ndim */
            shape   /* shape */
        );
    }
};

NB_MODULE(borg_sim, m) {
    nb::class_<SimulatorWrapper>(m, "BorgSimulator")
        .def(nb::init<const std::string&>())
        .def("load_texture", &SimulatorWrapper::load_texture)
        .def("step", &SimulatorWrapper::step)
        .def("set_camera_angles", &SimulatorWrapper::set_camera_angles)
        .def("get_framebuffer", &SimulatorWrapper::get_framebuffer);
}
