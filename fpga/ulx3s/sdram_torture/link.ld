/* SPDX-FileCopyrightText: © 2026 Andreas Wendleder */
/* SPDX-License-Identifier: CERN-OHL-S-2.0 */

/* FlashBootLoader copies the firmware payload straight to SDRAM address 0
 * and jumps there in M-mode -- same layout OpenSBI itself uses on this
 * platform (FW_TEXT_START=0x0). Test region + stack live far above our
 * own tiny code/data footprint (see sdram_torture.c's TEST_BASE). */

ENTRY(_start)

MEMORY
{
  RAM (rwx) : ORIGIN = 0x0, LENGTH = 0x100000  /* 1 MiB -- code/data only */
}

SECTIONS
{
  . = 0x0;
  .text.start : { *(.text.start) } > RAM
  .text : { *(.text) *(.text.*) } > RAM
  .rodata : { *(.rodata) *(.rodata.*) } > RAM
  . = ALIGN(8);
  __global_pointer$ = . + 0x800;
  .data : { *(.data) *(.data.*) *(.sdata) *(.sdata.*) } > RAM
  . = ALIGN(8);
  __bss_start = .;
  .bss : { *(.bss) *(.bss.*) *(.sbss) *(.sbss.*) *(COMMON) } > RAM
  . = ALIGN(8);
  __bss_end = .;
  . = ALIGN(16);
  . = . + 0x8000; /* 32 KiB stack */
  _stack_top = .;
}
