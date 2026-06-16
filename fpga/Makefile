# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later

# Top-level FPGA dispatcher.
# Per-board build systems live in subdirectories:
#   fpga/ulx3s/   — ULX3S (ECP5-85K)
#   fpga/common/  — shared LUT files

.PHONY: all ulx3s clean

all: ulx3s

ulx3s:
	$(MAKE) -C ulx3s

clean:
	$(MAKE) -C ulx3s clean
