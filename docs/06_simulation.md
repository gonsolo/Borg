# Simulation

Two cycle-accurate simulators: the **direct simulator**, Borg alone driven by the
host, for the driver, the CTS and the vkcube demo; and the **Linux boot
harness**, the RV64 SoC on Verilator.

## Direct simulator

`simulation/direct` models Borg alone: no CPU, no firmware, no UART. The GPU's
RTL (`BorgDirectSimTop`) is compiled with Arcilator, and a small C++ host
gives borgvk the device (`--raw`): register writes, reads and polls, and the
device memory the driver maps. borgvk builds the render lists itself.

- **Build**: `make -C simulation/direct` builds `direct_sim`;
  `make -C simulation/direct direct_sim_v` the same harness on Verilator, for
  `BORG_TRACE` printfs (Arcilator drops them).
- **vkcube**: `scripts/vkcube_host.sh` runs the unmodified Khronos vkcube on
  borgvk and `direct_sim` in an X window (Xvfb without a display).
  `make -C simulation/direct golden` checks its first frame bit-exact against
  `simulation/golden/vkcube_00.ppm`.
- **CTS**: `scripts/cts_par.sh` runs dEQP-VK cases on many instances in
  parallel, see [The Software Driver](03_software_driver.md).
- **Speed**: roughly 140,000 to 230,000 cycles per second per instance.

## Linux Boot Harness

A separate Verilator harness
(`simulation/verilator/main_minimal_linux_realsdram.cpp`, target
`MinimalSocRealSdramSimTop` — Hutt + CLINT only, no Borg/HDMI) boots
OpenSBI + Linux against the real (not idealized) SdramBackend/
SdramController/SdramChipModel JEDEC co-sim, for hardware-faithful
Linux bring-up debugging. It supports checkpointing
(`--save-at CYCLE --save-path PATH` / `--load PATH --start-cycle CYCLE`)
so a multi-billion-cycle boot doesn't need to be re-simulated from cycle
zero on every run, plus a family of harness-only debug probes (register
writeback tracing, task_struct field reads via a debug memory backdoor,
per-syscall entry tracing) that don't require a Chisel rebuild to add or
change. This is what found and confirmed the CSPRNG entropy-seeding fix
described in `docs/A3_hutt_cpu.md`. `make -C simulation/verilator
minimal-linux-sim` and `minimal-linux-borg-sim` build the idealized-SDRAM
variants, the latter with a small Borg.
