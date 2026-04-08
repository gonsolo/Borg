#!/usr/bin/env python3
"""Test the PeakRDL-chisel exporter on borg_gpu.rdl."""

import sys
import systemrdl
from peakrdl_chisel import ChiselExporter

rdlc = systemrdl.RDLCompiler()
rdlc.compile_file("hardware/borg/rdl/borg_gpu.rdl")
root = rdlc.elaborate()

exporter = ChiselExporter()
exporter.export(root, "hardware/borg/rdl/generated/", package_name="borg")
