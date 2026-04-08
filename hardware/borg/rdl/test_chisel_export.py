#!/usr/bin/env python3
"""Generate Chisel register block and C header from borg_gpu.rdl."""

import os
import sys
import systemrdl
from peakrdl_chisel import ChiselExporter
from peakrdl_cheader.exporter import CHeaderExporter

scala_out_dir = sys.argv[1] if len(sys.argv) > 1 else "hardware/borg/src/generated/"
c_out_dir = sys.argv[2] if len(sys.argv) > 2 else "out/hardware/borg/rdl/"

rdlc = systemrdl.RDLCompiler()
rdlc.compile_file("hardware/borg/rdl/borg_gpu.rdl")
root = rdlc.elaborate()

# Chisel register block
chisel = ChiselExporter()
chisel.export(root, scala_out_dir, package_name="borg")

# C firmware header
cheader = CHeaderExporter()
cheader_file = os.path.join(c_out_dir, "borg_gpu_regs.h")
cheader.export(root, cheader_file)
print(f"Generated C header: {cheader_file}")
