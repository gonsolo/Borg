# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

# Top-level FPGA dispatcher — mirrors simulation/Makefile structure.
# Per-board build systems live in subdirectories:
#   fpga/picoice/  — pico-ice (iCE40 UP5K)
#   fpga/ulx3s/   — ULX3S (ECP5-85K, stub until hardware arrives)
#   fpga/common/  — shared host scripts and LUT files

.PHONY: all picoice ulx3s borg.bin clean triangle vkcube burn

all: picoice

# Forward render/burn targets to the active board (pico-ice for now)
triangle:
	$(MAKE) -C picoice triangle

vkcube:
	$(MAKE) -C picoice vkcube

burn:
	$(MAKE) -C picoice burn

picoice:
	$(MAKE) -C picoice

# Forward borg.bin for CI: make -C fpga borg.bin  →  picoice/borg.bin (symlink)
borg.bin: picoice/borg.bin
	@ln -sf picoice/borg.bin borg.bin

picoice/borg.bin:
	$(MAKE) -C picoice borg.bin

ulx3s:
	$(MAKE) -C ulx3s

clean:
	$(MAKE) -C picoice clean
	$(MAKE) -C ulx3s  clean
