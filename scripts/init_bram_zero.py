#!/usr/bin/env python3
"""Patch generated BRAM .sv files with zero-fill initial blocks.

Icarus Verilog initialises SyncReadMem arrays to X.  If the valid bit
stored in the tag BRAM is X, isHit becomes X, which propagates through
the Verilog ternary in the InstrCache state register, corrupting the
FSM and preventing QSPI from ever starting.

This post-processor inserts an 'initial' block that sets every word in
each *Mem_*.sv memory to zero.  Synthesis tools ignore initial blocks
inside translate_off/translate_on guards, so the patch has no effect on
the synthesised netlist.
"""
import re
import sys
from pathlib import Path

verilog_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("out/hardware/borg/verilog")

for sv_file in sorted(verilog_dir.glob("*Mem_*.sv")):
    text = sv_file.read_text()
    if "MEM_INIT" in text:
        continue  # already patched
    m = re.search(r'(  reg \[.*?\] Memory\[0:(\d+)\];)', text)
    if not m:
        continue
    decl, last_idx = m.group(1), m.group(2)
    init_block = (
        "  // synthesis translate_off\n"
        "  initial begin : MEM_INIT\n"
        "    integer _i;\n"
        f"    for (_i = 0; _i <= {last_idx}; _i = _i + 1) Memory[_i] = '0;\n"
        "  end\n"
        "  // synthesis translate_on"
    )
    patched = text.replace(decl, decl + "\n" + init_block, 1)
    sv_file.write_text(patched)
    print(f"  patched {sv_file.name}")
