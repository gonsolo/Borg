# HDMI output of the Borg frame (2026-10-07)

The cube that Borg renders under Linux on the ULX3S is shown on a monitor over HDMI by the board itself.
Same image as `../selfhosted_compile` (kernel, Rust DRM driver, borgvk compiling its shaders on Hutt), flash
payload unchanged. Only the bitstream differs.

| Part | Version |
|---|---|
| Bitstream | `borg-minimal-linux-borg-hdmi.bit`, sha256 `79bdef237411033d8708928cc5db0c5346ed1cb2b9e6cea017cdf511bc745e6a`, loaded into SRAM (`openFPGALoader -b ulx3s`), flash not written |
| Scanout | `HdmiScanoutFp16`, RGB565, 128 x 128 frame from SDRAM (commits `288f340f`, `ea02a7ab`) |

Result:
- `hdmi_cube.jpg`: photo of the monitor, the textured cube in the 128 x 128 window (phone photo, not a capture).
- `boot.log`: UART log up to the end of `vkcube-borg` (`borg_drm_test` PASS, shaders compiled, `vkcube-borg` ran).
  The screen stayed black until the first Borg write, as designed.

Not shown by this run:
- The frame is **not** compared pixel by pixel here. The hex dump after `vkcube-borg` was too slow to finish
  (see below) and was stopped. The pixel-exact check (0 of 16,384 differ from `direct_sim`) is the one in
  `../selfhosted_compile`, with the bitstream without scanout.
- Format: 128 x 128 RGB565, not the 800 x 480 output target.

Slowdown with scanout on (measured, cause not yet):
- `vkcube-borg` takes 253 s of kernel time with scanout (`/proc/uptime` 71.04 s to 324.07 s) against 128 s without
  (71.04 s to 199.25 s). Kernel time runs at half speed.
- The UART hex dump afterwards ran at about 15 bytes/s, far slower than without scanout.
- Likely the scanout's SDRAM reads taking the CPU's memory bandwidth; not verified.
