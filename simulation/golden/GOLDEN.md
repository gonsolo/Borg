# Golden Images

Reference frames for the cycle-accurate simulation regression suite. Each golden
is compared by `scripts/compare_ppm.py` with `--max-diff 1 --max-fail-pixels 2`
(allows ±1 rounding in the colour conversion, up to 2 pixels).

| File | App | Frame | What it exercises |
|------|-----|-------|-------------------|
| `vkcube_cts_uart_baked_00.ppm` | cts-uart-baked | 0 | Same scene with the capture's 0xB0 shader uploads stripped (`borgvk_capture_noshader.bin`), so the firmware runs its baked borgc shaders. Identical to `vkcube_cts_uart_00.ppm` while the baked shaders match what borgvk uploads. |
| `vkcube_cts_uart_00.ppm` | cts-uart | 0 | Full TBR path driven by a captured borgvk UART burst (0xAD MVP / 0xAE geometry / 0xAF texture / 0xB0 shaders) replayed via `--cts-uart`: BorgBinner Pass 1 + BorgSequencer Pass 2, GPU vertex transform (seq_vert_shader) including perspective divide, texture fetch via BorgTextureUnit, sRGB-converted output, double-buffered PSRAM layout. |

The old `triangle_00.ppm`/`vkcube_00.ppm` goldens (baked app-config demos) were
removed once firmware content stopped being baked in — see `borg_kernel.c`; all
geometry/shaders/textures now arrive from borgvk at runtime.

`borgvk_capture.bin` is one frame of the unmodified Vulkan-Tools vkcube as borgvk
puts it on the wire (current capture: FP32 datapath, 2026-09-17, 26388 B):

```bash
BORGVK_SIM=/bin/true BORGVK_SIM_FW=software/borg/kernel.bin \
BORGVK_SIM_DUMP=simulation/golden/borgvk_capture.bin \
VK_DRIVER_FILES=mesa/build-borg/src/borg/vulkan/borg_devenv_icd.x86_64.json \
  Vulkan-Tools/build/cube/vkcube --c 1
```

(with the library path set up as in `fpga/ulx3s/run-vkcube.sh`). A new capture
changes the MVP (a different frame), so regenerate the goldens with it and
look at the images before committing.

## Updating a golden

Run the simulation to produce the new PPM, then verify the change is intentional
(not a regression) before committing:

```bash
# Re-generate the golden (verilator is faster: ~1 min vs ~5 min):
make -C simulation/verilator cts-uart-golden

# Inspect the diff:
python3 scripts/compare_ppm.py simulation/golden/vkcube_cts_uart_00.ppm <old_golden>
```

## fpga/

The `fpga/` subdirectory holds FPGA-captured frames (read back over serial from
the ULX3S HDMI output). These are kept for visual reference only and are not
compared automatically by the test suite.
