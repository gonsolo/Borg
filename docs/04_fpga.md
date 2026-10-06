# Running on an FPGA

The **ULX3S** (Lattice ECP5-85K) is the primary FPGA development and demo target
for the Borg SoC.

---

## ULX3S (Lattice ECP5-85K) — Primary Target

The ULX3S board carries a Lattice ECP5-85K FPGA with 84K LUTs, 32 Mb SDRAM,
and HDMI output. It is the main bring-up and integration target for the full
Borg SoC.

### Build and Upload

The ULX3S build uses Yosys for synthesis and nextpnr-ecp5 for place-and-route:

```bash
cd fpga/ulx3s
make load    # Synth + P&R + load bitstream to SRAM (openFPGALoader)
make flash   # Write bitstream to config flash (survives power cycle)
make tio     # Open serial console on /dev/ttyUSB0
```

There are also lightweight targets for fast iteration without the full ~10 min
synthesis:

```bash
make minimal-boot   # Build + flash minimal FlashBootLoader test (Hutt + UART only)
```

Layered bring-up bitstreams in `fpga/ulx3s/debug/` isolate individual subsystems
(UART, SDRAM, HDMI) without paying the full SoC synthesis cost.

### Clock Domains

| Domain | Frequency | Used for |
|--------|-----------|----------|
| SoC clock | 25 MHz | Hutt CPU, MemoryController, Borg peripheral |
| HDMI pixel clock | 125 MHz | TMDS serialiser |

### RV64 Linux with a tiny Borg (`borg-minimal-linux-borg`)

`make generate_verilog_ulx3s_minimal_linux_borg`; `BorgConfig.Tiny` (FP32, 4 lanes, 1024 bin
tiles, no MSAA, blend, stencil, compute, depth flush or perf counters) beside the RV64
Hutt, scanout off, GPU memory at SDRAM 16 MB and up (the VRAM region bit). nextpnr:
69,948 / 83,640 LUTs (83%), 75 MULT18X18D, 7 DP16KD. The PLL gives 18.75 MHz; Fmax 19.96 MHz. One lane renders the cube wrong (derivatives need the 2x2 quad), and
four lanes at 25 MHz reach only 15 to 18 MHz. The limiter is the lane's integer ALU fed
straight from the register BRAM, not the FMA (`fmaStages=4` changes nothing).

Host-free frame: `make -C software/borg replay` links the captured borgvk burst into the
firmware; the simulator (`make -C simulation/verilator minimal-linux-borg-sim`) renders it
bit-exact against `simulation/golden/vkcube_cts_uart_00.ppm`. `scripts/uart_ppm.py` turns the
UART dump (or a `DUMP_*` simulator dump) into a picture.
On the ULX3S the same firmware prints the frame over the UART and it is identical to the golden
(0 of 49,152 values differ), with no host computer. Below a 25 MHz PFD the ECP5 PLL needs the
loop-filter attributes (`ICP_CURRENT` etc., in `Ecp5PllPrim`) or it never locks.

Under Linux: the same replay runs as a static userspace program (`replay_linux`, in the initramfs)
that maps the Borg registers and its 5 MB at SDRAM 16 MB through `/dev/mem`, at the addresses the
firmware uses. The device tree reserves that memory (`no-map`) and describes the Borg at
`0x08000C00`. The frame over the UART again equals the golden; boot to the picture takes about 3
minutes. A tick of the 18.75 MHz core clock at 100 Hz (187k cycles) stalled the boot after the
clocksource switch, so the tree declares a 37.5 MHz timebase (tick every 375k cycles; kernel time
runs at half speed).

Through the DRM driver: a Rust render-only DRM driver (`software/linux/overlay/drivers/gpu/drm/borg`,
Linux 7.2.9, built by `software/linux/rust-build.sh` with the flake's LLVM 21 toolchain) binds the
Borg node and exposes `/dev/dri/renderD128`. Its uAPI (`borg_drm.h`) is five ioctls: info, register
writes, register read, GPU-memory write and read; the kernel only bounds- and alignment-checks.
`replay_drm` runs `borg_core.c` (the host build) with its register and memory hooks turned into
these ioctls, so the frame no longer touches `/dev/mem`. On the board the frame equals the golden
(0 of 16,384 pixels differ) and `Memory:` reports 17,260K of 32,768K available (14.5 MB reserved,
5 MB of it the GPU region). The driver is developed on QEMU (`software/linux/tests/qemu-borg.sh`,
a fake Borg made of reserved RAM) and then run on the board.

Two things only the board showed: Hutt has no compressed instructions and LLVM 21 keeps `zca` in
the Rust target even with `-c`, so the Rust build passes `-Ctarget-feature=-zca`; and user copies
must not happen under the `Devres` (RCU) guard, where a page fault returns `EFAULT`.

Mesa on the board (`software/vkcube`): `make -C software/vkcube` builds the unmodified
`Vulkan-Tools/cube/cube.c` (plus `headless.patch`, which gives the "display" platform a headless
surface and lets `cube_functions.h` take `vk_icdGetInstanceProcAddr` instead of `dlopen`) linked
statically with borgvk for Hutt's ISA (rv64ima, lp64 soft-float, no C) against the rootfs's musl:
no loader, no window system, no libdrm, no C++ and no Rust (`-Dborg-compiler=cache`,
`-Dutil-without-cpp`, `-Dborg-without-libdrm`). The two cube shaders come from a cache file
(`BORGVK_SHADER_CACHE`, recorded on the host with `BORGVK_SHADER_CACHE_RECORD`), so the board
needs no SPIR-V to NIR to borgc step. 2.9 MB of code, 0.5 MB of data. borgvk's `BORGVK_HW` sink
runs the wire stream through `borg_core` in the process; its register and memory accesses are the
render node's ioctls (`software/borg/borg_hw.c`), or on a host a `direct_sim --raw` process. The
same binary runs under `qemu-riscv64` on a CPU without C, F and D (`run-qemu.py`) and its frame
equals the x86 sink's frame pixel for pixel. The golden of step 5 (`vkcube_cts_uart_00.ppm`) is an
older capture without the texture; the reference for the board is this sink's frame.

Self-hosted vkcube on the board (2026-10-06): the initramfs's `init` runs `vkcube-borg --wsi display
--c 1` with `BORGVK_HW=1`, borgvk's sink on the DRM render node, and prints the frame as hex on the
debug UART (`scripts/uart_ppm_od.py` decodes it). Linux 7.2.9 with the Rust driver, Mesa/borgvk,
the unmodified `cube.c` and the Borg run on the one ULX3S with no computer attached to it but
the serial monitor; the 128 x 128 frame equals the host sink's frame pixel for pixel (0 of 16,384
differ). `Memory:` reports 13,956K of 32,768K available (the 3.4 MB binary is unpacked into RAM). The
whole boot to the frame takes about 15 minutes at the 18.75 MHz core clock; the run of `vkcube-borg`
itself is a few minutes of that (the console has no timestamps; a timed run is still to do).
