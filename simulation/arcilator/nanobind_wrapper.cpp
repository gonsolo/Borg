#include <nanobind/nanobind.h>
#include <nanobind/ndarray.h>
#include <nanobind/stl/string.h>
#include "ArcBorgSimulator.h"

namespace nb = nanobind;

class SimulatorWrapper {
    ArcBorgSimulator* sim;
    std::vector<uint8_t> fb_rgb;
    bool fast_mode;
    bool reset_done;
public:
    SimulatorWrapper(const std::string& firmware_path, bool fast_mode = true) : fast_mode(fast_mode), reset_done(false) {
        sim = new ArcBorgSimulator(firmware_path, fast_mode);
    }
    
    ~SimulatorWrapper() {
        delete sim;
    }
    
    void load_texture(const std::string& tex_path, uint32_t tex_dim = 32) {
        sim->load_texture(tex_path, tex_dim);
    }
    
    bool step(uint32_t cycles) {
        if (!reset_done) {
            sim->backend_reset();
            reset_done = true;
        }
        return sim->step(cycles);
    }
    
    void set_camera_angles(float rx, float ry) {
        sim->set_camera_angles(rx, ry);
    }

    uint32_t width()  const { return sim->width; }
    uint32_t height() const { return sim->height; }

    nb::ndarray<nb::numpy, uint8_t, nb::shape<-1, -1, 3>, nb::c_contig> get_framebuffer() {
        if (fb_rgb.size() != sim->width * sim->height * 3) {
            fb_rgb.resize(sim->width * sim->height * 3);
        }

        // In fast mode, arcilator modifies sim_psram_ext directly. Copy it back to C++ model.
        int PSRAM_OFFSET = sim->find_memory_offset("sim_psram_ext");
        if (PSRAM_OFFSET >= 0) {
            for (size_t i = 0; i < sim->psram->mem.size(); i++) {
                sim->psram->mem[i] = *(sim->get_storage_ptr() + PSRAM_OFFSET + i);
            }
        }

        uint32_t* psram_words = (uint32_t*)sim->psram->mem.data();
        
        for (uint32_t y = 0; y < sim->height; y++) {
            for (uint32_t x = 0; x < sim->width; x++) {
                uint32_t base = sim->out_base_word + (y * sim->width + x) * 3;
                uint16_t r_fp16 = (uint16_t)psram_words[base + 0];
                uint16_t g_fp16 = (uint16_t)psram_words[base + 1];
                uint16_t b_fp16 = (uint16_t)psram_words[base + 2];
                
                uint8_t r_b = std::max(0, std::min(255, (int)(::fp16_to_float(r_fp16) * 255)));
                uint8_t g_b = std::max(0, std::min(255, (int)(::fp16_to_float(g_fp16) * 255)));
                uint8_t b_b = std::max(0, std::min(255, (int)(::fp16_to_float(b_fp16) * 255)));
                
                uint32_t out_idx = (y * sim->width + x) * 3;
                fb_rgb[out_idx + 0] = r_b;
                fb_rgb[out_idx + 1] = g_b;
                fb_rgb[out_idx + 2] = b_b;
            }
        }
        
        // Clear the DONE_MARKER so the next frame can be rendered
        psram_words[sim->marker_offset_word] = 0;
        
        // Write the cleared marker back to sim_psram_ext in fast mode
        if (PSRAM_OFFSET >= 0) {
            uint32_t marker_byte_addr = sim->marker_offset_word * 4;
            *(sim->get_storage_ptr() + PSRAM_OFFSET + marker_byte_addr + 0) = 0;
            *(sim->get_storage_ptr() + PSRAM_OFFSET + marker_byte_addr + 1) = 0;
            *(sim->get_storage_ptr() + PSRAM_OFFSET + marker_byte_addr + 2) = 0;
            *(sim->get_storage_ptr() + PSRAM_OFFSET + marker_byte_addr + 3) = 0;
        }

        size_t shape[3] = { (size_t)sim->height, (size_t)sim->width, 3 };
        
        return nb::ndarray<nb::numpy, uint8_t, nb::shape<-1, -1, 3>, nb::c_contig>(
            fb_rgb.data(), 
            3,      /* ndim */
            shape   /* shape */
        );
    }
};

NB_MODULE(arc_sim, m) {
    nb::class_<SimulatorWrapper>(m, "BorgSimulator")
        .def(nb::init<const std::string&, bool>(), nb::arg("firmware_path"), nb::arg("fast_mode") = true)
        .def("load_texture", &SimulatorWrapper::load_texture, nb::arg("tex_path"), nb::arg("tex_dim") = 32)
        .def("step", &SimulatorWrapper::step)
        .def("set_camera_angles", &SimulatorWrapper::set_camera_angles)
        .def("get_framebuffer", &SimulatorWrapper::get_framebuffer)
        .def_prop_ro("width",  &SimulatorWrapper::width)
        .def_prop_ro("height", &SimulatorWrapper::height);
}
