# Golden Images

Reference frames for the cycle-accurate simulation regression suite. Each golden
is compared by `scripts/compare_ppm.py` with `--max-diff 1 --max-fail-pixels 2`
(allows ±1 rounding in FP16→byte conversion, up to 2 pixels).

| File | App | Frame | What it exercises |
|------|-----|-------|-------------------|
| `vkcube_cts_uart_00.ppm` | cts-uart | 0 | Full TBR path driven by a captured borgvk UART burst (0xAD MVP / 0xAE geometry / 0xAF texture / 0xB0 shaders) replayed via `--cts-uart`: BorgBinner Pass 1 + BorgSequencer Pass 2, GPU vertex transform (seq_vert_shader) including perspective divide, texture fetch via BorgTextureUnit, sRGB-converted output, double-buffered PSRAM layout. |

The old `triangle_00.ppm`/`vkcube_00.ppm` goldens (baked app-config demos) were
removed once firmware content stopped being baked in — see `borg_kernel.c`; all
geometry/shaders/textures now arrive from borgvk at runtime, so `vkcube_cts_uart_00.ppm`
(a real captured borgvk burst) is the only render regression golden left.

## Updating a golden

Run the simulation to produce the new PPM, then verify the change is intentional
(not a regression) before committing:

```bash
# Re-generate the golden (arcilator is faster):
make -C simulation/arcilator cts-uart-golden

# Inspect the diff:
python3 scripts/compare_ppm.py simulation/golden/vkcube_cts_uart_00.ppm <old_golden>
```

## fpga/

The `fpga/` subdirectory holds FPGA-captured frames (read back over serial from
the ULX3S HDMI output). These are kept for visual reference only and are not
compared automatically by the test suite.
