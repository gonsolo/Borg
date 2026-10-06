# Self-hosted vkcube on the ULX3S: evidence

The unmodified Vulkan-Tools `cube.c` (with `software/vkcube/headless.patch`) running on Linux 7.2.9
on Hutt, through borgvk and the Rust DRM render node, rendered by the Borg on the same ULX3S.
Captured on 2026-10-06 from the debug UART at 115200 baud, from reset to the end of the frame.

| File | What it is |
|---|---|
| `boot.log` | The UART capture from the OpenSBI banner: kernel messages, `borg_drm_test`, `vkcube-borg`. The frame's hex lines are cut out (3073 lines). The console has no timestamps. |
| `frame.ppm` | The frame decoded from the hex with `scripts/uart_ppm_od.py` (128 x 128, P6). |
| `frame_x3.png` | The same frame enlarged three times. |

The frame equals the frame that borgvk's host sink (`BORGVK_HW=1` against `direct_sim --raw`) renders from the same
shaders: 0 of 16,384 pixels differ.

## What was built

| Part | Version |
|---|---|
| Borg repo | `f8adf805` on `feat/drm-on-board` (built from `bcdf7acf..79b8bfae`) |
| mesa (borgvk, borgc) | `6a0d8a437f0` on `borgvk-drm-sink` |
| Boot image (OpenSBI + Linux + initramfs) | `fw_payload.bin`, sha256 `e1858d1b29c9754128a3ba95d6806d81511b75637c1dae184639318b78e018e0` |
| FPGA bitstream | `fpga/ulx3s/borg-minimal-linux-borg.bit`, sha256 `fe845c21842897027d71f7dbe0fc5b523dacafa422b97e76f2de8f09f9d662f6`, built 2026-10-06 |
| Shader cache | `software/vkcube/cube.shaders` (two blobs from borgc, recorded on the host) |

## What it does not show

- Shaders are compiled on the host; the board loads them from the cache file.
- The picture leaves the board as hex over the UART; HDMI is not used.
- The driver validates only offsets and alignment (no register whitelist yet).
- The boot-to-frame time was not measured with a clock on the board; it took about 8 minutes of wall time
  to the end of `vkcube-borg` and about 8 more for the hex dump.
- The FPGA runs the `Tiny` Borg configuration (4 lanes, no blend, stencil, compute or MSAA).

## Clean-checkout rebuild (2026-10-06)

Rebuilt from a fresh clone: the bitstream is byte-identical (same sha256), Fmax 19.96 MHz at 18.75 MHz, 84% LUT.
The boot payload was rebuilt too (12,140,008 bytes, not byte-identical to the flashed one). Booted on the board:
`BORG_DRM_TEST PASS`, `vkcube-borg end: 0`, and the frame equals `frame.ppm` byte for byte. Log: `boot_clean.log`.
