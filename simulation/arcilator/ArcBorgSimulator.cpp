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
int     ArcBorgSimulator::get_uart_bit_pos() const { return 6; }

uint32_t ArcBorgSimulator::get_backend_addrIn()     { return model->view.be_addrIn; }
bool     ArcBorgSimulator::get_backend_startRead()  { return model->view.be_startRead; }
bool     ArcBorgSimulator::get_backend_startWrite() { return model->view.be_startWrite; }
uint16_t ArcBorgSimulator::get_backend_dataIn()     { return model->view.be_dataIn; }
uint8_t  ArcBorgSimulator::get_backend_byteEnIn()   { return model->view.be_byteEnIn; }
uint8_t  ArcBorgSimulator::get_backend_lenIn()      { return model->view.be_lenIn; }
void ArcBorgSimulator::set_backend_dataOut(uint16_t v) { model->view.be_dataOut = v; }
void ArcBorgSimulator::set_backend_done(bool v)        { model->view.be_done = v; }
void ArcBorgSimulator::set_backend_busy(bool v)        { model->view.be_busy = v; }
void ArcBorgSimulator::set_backend_accept(bool v)      { model->view.be_accept = v; }

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
    // The FRCP/FRSQ/FSRGB LUTs live per-lane in BorgLane.  Arcilator ignores the
    // RTL's $readmemh, so we poke them from C++.  The unified SIMT config has 4
    // lanes (lanes_0..3), each with its own A/B copy of every LUT — all must be
    // loaded or that lane's FRCP returns garbage (black/hung frame).  A struct-path
    // mismatch here is a loud compile error, not a silent runtime hang.
    // (coordLutX/Y were replaced by combinational pixelToFP16Half — no load needed.)
    uint16_t rcp[17], frsq[34], srgb[256];
    int nr = load_hex_into("../../hardware/borg/src/rcp_lut.hex",  rcp,  17);
    int nf = load_hex_into("../../hardware/borg/src/frsq_lut.hex", frsq, 34);
    int ns = load_hex_into("../../hardware/borg/src/srgb_lut.hex", srgb, 256);

    auto loadLane = [&](auto& lane) {
        for (int i = 0; i < nr; i++) { lane.rcpLutA_ext.words[i].data  = rcp[i];  lane.rcpLutB_ext.words[i].data  = rcp[i];  }
        for (int i = 0; i < nf; i++) { lane.frsqLutA_ext.words[i].data = frsq[i]; lane.frsqLutB_ext.words[i].data = frsq[i]; }
        for (int i = 0; i < ns; i++) { lane.srgbLutA_ext.words[i].data = srgb[i]; lane.srgbLutB_ext.words[i].data = srgb[i]; }
    };
    auto& core = model->view.internal.uo_out_val_peripherals.borg.core;
    loadLane(core.lanes_0);
    loadLane(core.lanes_1);
    loadLane(core.lanes_2);
    loadLane(core.lanes_3);
    fprintf(stderr, "[SIM] Loaded LUTs ×4 lanes: rcp=%d frsq=%d srgb=%d\n", nr, nf, ns);
}

bool ArcBorgSimulator::step(uint32_t cycles_to_run) {
    if (!booted) { model->view.rst_n = 1; booted = true; }
    return BorgSimulatorBase::step(cycles_to_run);
}
