#pragma once
#include "../common/BorgSimulatorBase.h"
#include <string>

struct BorgArcSimTop;

class ArcBorgSimulator : public BorgSimulatorBase {
public:
  BorgArcSimTop *model;
  bool booted = false;

  ArcBorgSimulator(const std::string &firmware_path, uint32_t w = 32,
                   uint32_t h = 32);
  virtual ~ArcBorgSimulator() override;

  virtual void clock_low() override;
  virtual void clock_high() override;
  virtual uint8_t get_uo_out() override;
  virtual void set_ui_in(uint8_t val) override;
  int get_uart_bit_pos() const override;

  virtual bool step(uint32_t cycles_to_run) override;

  // Load coord/reciprocal LUTs into the model — arcilator does not honor the
  // RTL's $readmemh init, so without this every pixel's r30/r31 reads 0 and the
  // rasterizer classifies all pixels "outside" (black frame).
  void load_luts();

  // Flat MemBackendIO — driven by BorgSimulatorBase::step()
  virtual uint32_t get_backend_addrIn()     override;
  virtual bool     get_backend_startRead()  override;
  virtual bool     get_backend_startWrite() override;
  virtual uint16_t get_backend_dataIn()     override;
  virtual uint8_t  get_backend_byteEnIn()   override;
  virtual void set_backend_dataOut(uint16_t v) override;
  virtual void set_backend_done(bool v)        override;
  virtual void set_backend_busy(bool v)        override;
};
