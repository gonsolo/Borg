#!/usr/bin/env python3
"""Generate Chisel register blocks and C headers from all RDL files."""

import os
import sys
import systemrdl
from peakrdl_chisel import ChiselExporter
from peakrdl_cheader.exporter import CHeaderExporter

rdl_dir = os.path.dirname(os.path.abspath(__file__))

scala_out_dir = sys.argv[1] if len(sys.argv) > 1 else "hardware/borg/src/generated/"
c_out_dir = sys.argv[2] if len(sys.argv) > 2 else "out/hardware/borg/rdl/"

os.makedirs(scala_out_dir, exist_ok=True)
os.makedirs(c_out_dir, exist_ok=True)

chisel = ChiselExporter()
cheader = CHeaderExporter()

# Each peripheral is compiled independently so each gets its own
# uniquely-named Chisel companion object (e.g. BorgGpuRegs, GpioMapRegs).
peripherals = [
    {"name": "borg",  "rdl": "borg.rdl"},
    {"name": "gpio",  "rdl": "gpio.rdl"},
    {"name": "uart",  "rdl": "uart.rdl"},
    {"name": "psram", "rdl": "psram.rdl"},
]

for p in peripherals:
    rdlc = systemrdl.RDLCompiler()
    rdlc.compile_file(os.path.join(rdl_dir, p["rdl"]))
    root = rdlc.elaborate()

    for child in root.children():
        if isinstance(child, systemrdl.node.AddrmapNode):
            # Chisel register block
            chisel.export(child, scala_out_dir, package_name="borg")
            print(f"Generated Chisel: {p['name']}")

            # C header
            cheader_file = os.path.join(c_out_dir, f"{p['name']}_regs.h")
            cheader.export(child, cheader_file)
            print(f"Generated C header: {cheader_file}")

# Also compile the full SoC for the combined C header (has base addresses)
rdlc_soc = systemrdl.RDLCompiler()
for f in [os.path.join(rdl_dir, n) for n in ["gpio.rdl", "uart.rdl", "psram.rdl", "borg.rdl", "soc.rdl"]]:
    rdlc_soc.compile_file(f)
root_soc = rdlc_soc.elaborate()
for child in root_soc.children():
    if isinstance(child, systemrdl.node.AddrmapNode):
        soc_header = os.path.join(c_out_dir, "soc_regs.h")
        cheader.export(child, soc_header)
        print(f"Generated SoC C header: {soc_header}")

print(f"\nChisel output: {scala_out_dir}")
print(f"C header output: {c_out_dir}")
