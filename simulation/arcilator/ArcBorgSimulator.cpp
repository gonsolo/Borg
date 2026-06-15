#include "arc.h"
#include "ArcBorgSimulator.h"

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

void ArcBorgSimulator::load_luts() {
    // The FRCP/FRSQ/FSRGB LUTs live per-lane in BorgLane.  They were dual-copy
    // SyncReadMem (observed as rcpLutA_ext/rcpLutB_ext etc.) and had to be poked
    // from C++ because arcilator ignored the SyncReadMem $readmemh.
    //
    // dca2fb9 merged each A/B pair into a single async-read `Mem`.  arcilator's
    // --observe-memories only exposes *sequential* memories, so the async Mem LUTs
    // are now inlined — there is no *_ext handle to poke.  Whether arcilator honors
    // the inlined Mem's $readmemh init is verified by the triangle/vkcube goldens;
    // if a lane's FRCP returns garbage, this is where to reinstate a load path.
    // (coordLutX/Y were replaced by combinational pixelToFP16Half — no load needed.)
}

bool ArcBorgSimulator::step(uint32_t cycles_to_run) {
    if (!booted) { model->view.rst_n = 1; booted = true; }
    return BorgSimulatorBase::step(cycles_to_run);
}
