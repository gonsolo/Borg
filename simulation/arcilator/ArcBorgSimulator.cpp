#include "arc.h"
#include "ArcBorgSimulator.h"
#include <iostream>
#include <fstream>

ArcBorgSimulator::ArcBorgSimulator(const std::string& firmware_path, uint32_t w, uint32_t h) {
    model = new BorgArcSimTop;
    flash = new QSPIMemory(1024 * 1024, true);
    psram = new QSPIMemory(8 * 1024 * 1024, false);

    width = w;
    height = h;
    psram_spi_word_offset = 0x1000 / 4;
    out_base_word = psram_spi_word_offset + (PSRAM_OUT_OFFSET / 4);
    uint32_t frame_tile_size = width * height * 2;
    marker_offset_word    = out_base_word + frame_tile_size;
    frame_tile_size_words = frame_tile_size;
    out_base_word_buf0    = out_base_word;

    flash->load_bin(firmware_path);

    uint32_t* psram_init_words = (uint32_t*)psram->mem.data();
    psram_init_words[psram_spi_word_offset + 0] = width;
    psram_init_words[psram_spi_word_offset + 1] = height;

    // Reset sequence — leave rst_n=0 until first step() call.
    model->view.clk   = 0;
    model->view.rst_n = 0;
    model->view.ena   = 1;
    model->view.ui_in = 0;
    model->eval();

    // Arcilator does not process the RTL's $readmemh LUT init — load them here.
    load_luts();
}

ArcBorgSimulator::~ArcBorgSimulator() { delete model; }

void ArcBorgSimulator::clock_low()  { model->view.clk = 0; model->eval(); }
void ArcBorgSimulator::clock_high() { model->view.clk = 1; model->eval(); }

uint8_t ArcBorgSimulator::get_uo_out()       { return model->view.uo_out; }
void    ArcBorgSimulator::set_ui_in(uint8_t v) { model->view.ui_in = v; }
int     ArcBorgSimulator::get_uart_bit_pos() const { return 0; }

uint32_t ArcBorgSimulator::get_backend_addrIn()     { return model->view.be_addrIn; }
bool     ArcBorgSimulator::get_backend_startRead()  { return model->view.be_startRead; }
bool     ArcBorgSimulator::get_backend_startWrite() { return model->view.be_startWrite; }
uint16_t ArcBorgSimulator::get_backend_dataIn()     { return model->view.be_dataIn; }
uint8_t  ArcBorgSimulator::get_backend_byteEnIn()   { return model->view.be_byteEnIn; }
void ArcBorgSimulator::set_backend_dataOut(uint16_t v) { model->view.be_dataOut = v; }
void ArcBorgSimulator::set_backend_done(bool v)        { model->view.be_done = v; }
void ArcBorgSimulator::set_backend_busy(bool v)        { model->view.be_busy = v; }

// Arcilator does NOT honor the RTL's $readmemh / loadMemoryFromFileInline, so
// the coord/reciprocal LUTs (initialized from .hex in BorgCore) come up all-zero.
// All-zero coordLut makes every pixel's r30/r31 = 0, so the rasterizer evaluates
// every edge at the origin and classifies all pixels "outside" (black frame).
// We replicate the RTL's init here by loading the same .hex files into the
// arcilator-observed memories.  (Verilator gets this for free via $readmemh.)
static int load_hex_into(const char* path, uint16_t* out, int max_entries) {
    std::ifstream f(path);
    if (!f) { fprintf(stderr, "[SIM] WARNING: could not open LUT %s\n", path); return 0; }
    std::string line;
    int i = 0;
    while (std::getline(f, line) && i < max_entries) {
        if (line.empty()) continue;
        out[i++] = (uint16_t)std::stoul(line, nullptr, 16);
    }
    return i;
}

void ArcBorgSimulator::load_luts() {
    auto& core = model->view.internal.uo_out_val_peripherals.borg.core;
    uint16_t coord[512];
    int nc = load_hex_into("../../hardware/borg/src/coord_lut.hex", coord, 512);
    for (int i = 0; i < nc; i++) {
        core.coordLutX_ext.words[i].data = coord[i];
        core.coordLutY_ext.words[i].data = coord[i];
    }
    uint16_t rcp[17];
    int nr = load_hex_into("../../hardware/borg/src/rcp_lut.hex", rcp, 17);
    for (int i = 0; i < nr; i++) {
        core.rcpLutA_ext.words[i].data = rcp[i];
        core.rcpLutB_ext.words[i].data = rcp[i];
    }
    fprintf(stderr, "[SIM] Loaded LUTs: coord=%d rcp=%d entries\n", nc, nr);
}

bool ArcBorgSimulator::step(uint32_t cycles_to_run) {
    if (!booted) { model->view.rst_n = 1; booted = true; }
    return BorgSimulatorBase::step(cycles_to_run);
}
