# Linux on Hutt on the Nexys Video

Plan as of 2026-11-13. Two goals:

1. 64-bit Linux on Hutt on the Nexys Video, everything on the board, no host needed.
2. The best vkcube frame rate, with the Borg GPU doing the rendering.

Goal 1 is the end state for both: CPU and GPU share the board's DDR3, so Vulkan memory is board
memory and nothing crosses a link per frame. The USB host path (mode 8 in
[Test and demo modes](B14_test_modes.md)) stays for CTS runs and as the loader, but gets no
board-side device heap.

## Where things stand

On the ULX3S (mode 7, 18.75 MHz) Linux 7.2.9 boots on RV64 Hutt with Sv39 to a busybox shell,
and the unmodified cube.c ran through borgvk and the Rust DRM driver: about 8 minutes per
vkcube run, about 4 with the shaders compiled on Hutt (`docs/evidence/selfhosted_*`).

| Piece | State |
|---|---|
| Hutt | RV64 M and S mode, A and M extensions, Sv39 with a 16-entry TLB, instruction cache (512 one-word lines, 32 MB reach), no data cache, no compressed instructions |
| `MinimalSoC` | 32 MB RAM at 0, CLINT at `0x02000000` (timer only), UART transmit only, Borg at `0x08000C00`; one interrupt, the timer |
| OpenSBI | `software/opensbi/platform/borg`, rv64ima, one hart, console through `borg_putc` |
| Linux | `borg_rv64_defconfig` (+ `borg_rv64_rust.frag` for the DRM driver), `borg.dts` for the ULX3S, initramfs from `software/rootfs`, `console=hvc0` |
| DRM driver | Rust, `software/linux/overlay/drivers/gpu/drm/borg`, render-only: no KMS |
| Nexys link top | `NexysLinkTop`: Borg `Default` (93k LUTs), MIG, `GpuMemMig`, `MigScanout`, `HostLink` over USB; 74.6% LUTs, 4.7% block RAM |
| Ziad's `NexysVideoTop` | RV64 Hutt with S mode + Borg `Cube`: 79% LUTs at 25 MHz, `MigBackend` (16 bits per DDR3 transaction), 32 MB, flash boot; no Linux boot yet |

## Phases

Each phase ends with a check on the board or in `link_sim`, and its commits are reviewed before
the next one starts.

### L1: the SoC top

`NexysLinuxTop`, from `NexysLinkTop`: RV64 Hutt with S mode and its instruction cache, the
CLINT, a UART, Borg `Default`, `MigScanout`, and the MIG as the one main memory for CPU, GPU,
scan-out and `HostLink`. `HostLink` loads memory and holds Hutt in reset until a register
releases it. Ziad's files stay as they are.

Check: synthesis fits and meets timing at 25 MHz; a small bare-metal program loaded over USB
prints on the UART.

### L2: a shell

A Nexys device tree and OpenSBI platform (25 MHz timebase, the Nexys memory map, the UART);
the payload (OpenSBI, kernel, device tree, initramfs) goes into DDR3 over `HostLink`, about
15 MB/s. Boot in `link_sim` first, which plays the MIG, then on the board.

Check: a busybox shell on the UART.

### L3: a fast CPU

The data path goes through a data cache (or at least whole 16-byte DDR3 lines, as `GpuMemMig`
reads them), and RAM grows from 32 MB to 512 MB: the address decode, the backend's address
width and the instruction cache's reach. This phase decides how fast Linux and Mesa run on the
board, and with them goal 2 on this path.

Check: boot time and a CPU benchmark before and after; the Hutt test suites.

### L4: Borg under Linux

The DRM driver on the Nexys memory map, borgvk built for riscv64 (its DRM link exists), vkcube
rendering through it.

Check: `borg_drm_test`, then vkcube frames read back and compared with the golden image.

### L5: HDMI from Linux

KMS in the DRM driver: `MigScanout` reads 4x4-tiled images (a descriptor bit), so the
swapchain image is shown where Borg rendered it, double-buffered. vkcube with `--wsi display`.

Check: the cube on the monitor; frames per second.

### L6: no host

UART receive and a serial console, then boot from an SD card (SPI mode: controller, a small
boot ROM, the Linux MMC driver). Ethernet later, for ssh and an NFS root.

Check: power on, Linux and vkcube with only the SD card.

## Frame rate (goal 2)

vkcube records its command buffers once; a frame updates a uniform buffer, submits and
presents, so the GPU should set the rate if L3 makes the CPU fast enough. Today a 256x256 frame
takes 3.77M GPU cycles, 75% of them fragment work: 6.6 fps at 25 MHz at most. Levers after L5,
measured one at a time: a second fragment core (room comes from moving Borg's memories into
block RAM), Borg's clock on the Artix-7, the memory path (19% stalls with one cached line), and
the resolution.

## Risks

- CPU speed: the ULX3S runs took minutes with every load and store uncached; L3 is the answer.
- Area: Borg `Default` plus RV64 Hutt is estimated at 85 to 90% of the LUTs; a second core needs
  the block RAM move first.
- Interrupts: only the timer today; the UART, SD card and Ethernet need an interrupt controller
  or polling drivers.
