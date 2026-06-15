# Golden Images

Reference frames for the cycle-accurate simulation regression suite. Each golden
is compared by `scripts/compare_ppm.py` with `--max-diff 1 --max-fail-pixels 2`
(allows ±1 rounding in FP16→byte conversion, up to 2 pixels).

| File | App | Frame | What it exercises |
|------|-----|-------|-------------------|
| `triangle_00.ppm` | triangle | 0 | BorgCore FP16 FMA pipeline, BorgRasterizer edge-function coverage, BorgTileBuffer Z-test, PSRAM flush via BorgTileFlusher. Single RGB-interpolated triangle. |
| `vkcube_00.ppm` | vkcube | 0 | Full TBR path: BorgBinner Pass 1 + BorgSequencer Pass 2, GPU vertex transform (seq_vert_shader) including perspective divide, LunarG-logo texture fetch via BorgTextureUnit, sRGB-converted output, double-buffered PSRAM layout. |

## Updating a golden

Run the simulation to produce the new PPM, then verify the change is intentional
(not a regression) before committing:

```bash
# Re-generate a golden (arcilator is faster):
make -C simulation/arcilator vkcube_headless
cp simulation/arcilator/build/vkcube_00.ppm simulation/golden/vkcube_00.ppm

# Inspect the diff:
python3 scripts/compare_ppm.py simulation/golden/vkcube_00.ppm <old_golden>
```

## fpga/

The `fpga/` subdirectory holds FPGA-captured frames (read back over serial from
the ULX3S HDMI output). These are kept for visual reference only and are not
compared automatically by the test suite.
