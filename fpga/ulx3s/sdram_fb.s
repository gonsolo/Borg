# sdram_fb.s — Paint SDRAM Framebuffer Red
# Framebuffer at SDRAM address 0x00100000 (1MB offset, avoids firmware code at 0x0)
# 64×64 pixels × 2 bytes/pixel (RGB565) = 8192 bytes
# Uses sh (16-bit store) to write one SDRAM word at a time,
# matching SdramBackend's 16-bit write granularity.
.section .text
.global _start
_start:
    li   t0, 0x00100000     # FB start in SDRAM (1MB offset)
    li   t1, 0x00102000     # FB end: 0x100000 + 8192
    li   a0, 0xF800          # One red RGB565 pixel (16-bit)

fill_loop:
    sh   a0, 0(t0)
    addi t0, t0, 2
    bltu t0, t1, fill_loop

halt:
    j    halt
