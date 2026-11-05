# Test and demo modes

The ways Borg runs under Mesa, from all-simulated to all-hardware. Each mode puts the same
pieces, borgvk (Mesa), the Borg DRM driver, Linux and the GPU, in different places. Status as
of 2026-11-05.

| # | Mesa and the driver | GPU | Link | Status |
|---|---|---|---|---|
| 1 | host Mesa, no kernel driver | `direct_sim` (Arcilator) | pipe | works: CTS, corpus, vkcube golden |
| 2 | host Mesa, no kernel driver | `direct_sim_v` (Verilator) | pipe | works: slower, the one with `BORG_TRACE` printfs |
| 3 | User Mode Linux: Mesa, DRM driver | `direct_sim` | register region forwarded (`logic_iomem`) | planned |
| 4 | User Mode Linux: Mesa, DRM driver | `direct_sim_v` | as 3 | planned, free with 3 |
| 5 | RISC-V Linux in QEMU: Mesa, DRM driver | `direct_sim --bridge` | QEMU device model | planned (demo mode 2) |
| 6 | RISC-V Linux in QEMU: Mesa, DRM driver | `direct_sim_v --bridge` | as 5 | planned, free with 5 |
| 7 | Linux on Hutt: Mesa, DRM driver | ULX3S | on chip | worked 2026-10-06; RV64 with Sv39 no longer fits next to the FP32 Borg on the ECP5-85K |
| 8 | host Mesa | ULX3S, Borg only | USB UART | needs a link (the serial path went 2026-11-04) |
| 9 | RISC-V Linux in QEMU: Mesa, DRM driver | ULX3S, Borg only | USB UART | needs a link |
| 10 | host Mesa | NiteFury II, Borg only | PCIe | planned |
| 11 | host Linux: DRM driver as a PCIe driver, host Mesa | NiteFury II, Borg only | PCIe | planned |
| 12 | Linux on Hutt: Mesa, DRM driver | NiteFury II | on chip | planned; no display, frames read back over PCIe |

The two simulators run the same Chisel; `direct_sim` and `direct_sim_v` speak one protocol
(register writes, reads and polls, memory writes and reads, start), so a mode built for one runs
on the other.

**User Mode Linux (3, 4)** runs the DRM driver in a process on the host, its register region
forwarded to the simulator: the fast tier for the kernel driver. Open: whether the Rust driver
builds for UML in our kernel version.

**QEMU (5, 6)** is demo mode 2: the unmodified cube.c on riscv64 Mesa with `--wsi display`.

**ULX3S (7 to 9)** is demo mode 3. Mode 7 comes back when the SoC fits again. Modes 8 and 9 need
a host-to-board link speaking the simulator's protocol; the USB UART runs at a few Mbit/s, enough
for correctness, slow for the CTS.

**NiteFury II (10 to 12)** is an Artix-7 XC7A200T on an M.2 card with PCIe Gen2 x4 and 512 MB of
DDR3. PCIe makes hardware runs fast enough for the CTS; mode 11 tests the DRM driver with a real
kernel on real hardware behind a real bus, and comes first. The 200T (about 134k 6-input LUTs)
may fit RV64 with Sv39 next to Borg again, for mode 12. Needed: a DDR3 memory backend
(LiteDRAM or MIG), a PCIe bridge to Borg's registers and memory, a PCIe probe in the DRM driver
(11) or a VFIO mapping (10), and a toolchain for the PCIe block (openXC7's support to be checked,
else Vivado). The Nexys Video port (same FPGA) is a starting point. A QEMU-over-PCIe mode, as 9,
would need a host bridge process; 11 covers it.

Below Mesa: Chisel unit tests, cocotb SoC tests, the `MinimalSocSimTop` Linux boot harness
([Simulation](06_simulation.md)), and the wafer.space link tests on RTL, the synthesis netlist
and the final netlist ([ASIC](05_asic.md)). The chip itself, once made, runs as 8 and 9 do.
