# QSPI CPU SoC tests

cocotb tests for `QspiSocTop` (hardware/soc/src/QspiSocTop.scala): the Hutt
CPU booting over QSPI, `MemoryController`, and Borg as an MMIO peripheral,
simulated at RTL with Icarus Verilog.

```sh
make test-cocotb-soc-core-rtl   # from the repo root: CPU core tests (test.py)
make test-cocotb-soc-borg-rtl   # Borg peripheral tests (user_peripherals/borg/test.py)
make -C test/soc rv32boot       # real RV32 firmware through the QSPI flash protocol
```

The Verilog comes from `make generate_verilog` (out/hardware/borg/verilog/),
whose `soc_files.txt` lists the sources. The waveform is written to `tb.vcd`.
