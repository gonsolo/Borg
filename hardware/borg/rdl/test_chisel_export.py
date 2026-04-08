#!/usr/bin/env python3
"""Generate Chisel register block and C header from borg_gpu.rdl."""

import systemrdl
from peakrdl_chisel import ChiselExporter
from peakrdl_cheader.exporter import CHeaderExporter

rdlc = systemrdl.RDLCompiler()
rdlc.compile_file("hardware/borg/rdl/borg_gpu.rdl")
root = rdlc.elaborate()

# Chisel register block
chisel = ChiselExporter()
chisel.export(root, "hardware/borg/rdl/generated/", package_name="borg")

# C firmware header
cheader = CHeaderExporter()
cheader.export(root, "hardware/borg/rdl/generated/borg_gpu_regs.h")
print(f"Generated C header: hardware/borg/rdl/generated/borg_gpu_regs.h")
