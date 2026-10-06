# Shader compilation on the board (2026-10-06)

The unmodified `cube.c` on borgvk renders the cube on the ULX3S with **borgvk compiling its two shaders
on Hutt itself** (SPIR-V to NIR to borgc): the image has no shader cache (`/etc/cube.shaders` is absent).
Same bitstream, kernel and Rust DRM driver as `../selfhosted_vkcube`.

| Part | Version |
|---|---|
| mesa (borgvk, borgc) | `422d09793ed` on branch `borgc-small-nir` (`-Dborg-small-nir`) |
| Binary | `vkcube-borg-full`, 3.8 MB stripped (`make -C software/vkcube COMPILER=full`, Rust std from `software/vkcube/rust/setup.sh`) |
| Image | `make -C software/rootfs rootfs VKCUBE_COMPILER=full`, payload 12,418,536 bytes (flash limit 12,582,908) |

Result (`boot.log`, `frame.ppm`, `frame_x3.png`):
- borgvk prints `captured vertex shader (333 bytes)` and `captured fragment shader (282 bytes)`, `vkcube-borg` exits 0.
- The frame equals the frame of the same binary under `qemu-riscv64` (Hutt's ISA) with `direct_sim`: 0 of 16,384 pixels differ.
- It differs from the cache-based frame of `../selfhosted_vkcube` in 8 pixels, by at most 8 of 255: the small
  NIR build (no `nir_opt_algebraic`) fuses three multiply-add pairs into `FMADD` (one rounding), the full build does not.

Timing: `/proc/uptime` reads 71.04 s before and 199.25 s after `vkcube-borg` (both runs identical). The kernel
timebase is declared at twice the core clock, so kernel time runs at half speed: the run is about 4.3 minutes
of real time (not measured with an external clock). Shader compilation itself is about 9.5 M instructions
(`qemu-riscv64`, `-one-insn-per-tb`), 3.4 M for cube.vert and 6.0 M for cube.frag.
