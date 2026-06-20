#!/usr/bin/env python3
"""
gen_seq_diagram.py — BorgSequencer State Machine Diagram Generator
==================================================================
Parses BorgSequencer.scala and generates an SVG state diagram and a 
markdown table of state descriptions.

Usage:
    python3 scripts/gen_seq_diagram.py
"""

import re
import sys
from pathlib import Path

try:
    import graphviz
except ImportError:
    print("ERROR: graphviz Python package not found. Run: pip install graphviz")
    sys.exit(1)

def parse_sequencer():
    src_path = Path("hardware/borg/src/BorgSequencer.scala")
    if not src_path.exists():
        print(f"Error: {src_path} not found.")
        sys.exit(1)
        
    text = src_path.read_text()
    
    # Extract the state enum list
    states = []
    m1 = re.search(r'val \((.*?)\) = states\.take\(16\)', text, re.DOTALL)
    if m1:
        states.extend([s.strip() for s in m1.group(1).split('::') if s.strip() != 'Nil'])
    m2 = re.search(r'val \((.*?)\) = states\.drop\(16\)', text, re.DOTALL)
    if m2:
        states.extend([s.strip() for s in m2.group(1).split('::') if s.strip() != 'Nil'])
        
    if not states:
        print("Error: Could not find state list.")
        sys.exit(1)
        
    # Extract comments/descriptions from the wireFsm index
    descriptions = {}
    fsm_match = re.search(r'private def wireFsm\(\): Unit = \{(.*?)\n  \}', text, re.DOTALL)
    if fsm_match:
        fsm_body = fsm_match.group(1)
        lines = fsm_body.split('\n')
        current_comment = []
        for line in lines:
            line = line.strip()
            if line.startswith('//'):
                cmt = line[2:].strip()
                if not cmt.startswith('---') and not cmt.startswith('==='):
                    current_comment.append(cmt)
            elif line.startswith('is('):
                m = re.search(r'is\((s\w+)\)', line)
                if m:
                    state_name = m.group(1)
                    if current_comment:
                        descriptions[state_name] = " ".join(current_comment)
                    else:
                        descriptions[state_name] = ""
                current_comment = []
    
    # Extract transitions from handle methods
    transitions = []
    dma_targets = []
    
    for state in states:
        handle_name = "handle" + state[1:]
        search_str = f"private def {handle_name}(): Unit = {{"
        idx = text.find(search_str)
        if idx != -1:
            # find the closing brace that is unindented "  }"
            end_idx = text.find("\n  }\n", idx)
            if end_idx != -1:
                body = text[idx:end_idx]
                # direct transitions
                for m in re.finditer(r'state\s*:=\s*(s\w+)', body):
                    target = m.group(1)
                    transitions.append((state, target, ""))
                # indirect DMA transitions
                for m in re.finditer(r'nextAfterDMA\s*:=\s*(s\w+)', body):
                    dma_targets.append((state, m.group(1)))
                
    # Add DMA return transitions
    for src, target in dma_targets:
        transitions.append(('sWaitDMA', target, f"from {src}"))

    return states, descriptions, transitions

def generate_diagram(states, descriptions, transitions):
    dot = graphviz.Digraph(
        name="BorgSequencer FSM",
        format="svg",
        node_attr={"shape": "box", "style": "filled,rounded", "fontname": "Helvetica", "fontsize": "12"},
        edge_attr={"fontname": "Helvetica", "fontsize": "10"}
    )
    
    dot.attr(rankdir="TB", bgcolor="#0d1117", fontcolor="#e2e8f0", 
             label=r"BorgSequencer 33-State FSM\n(Pass 1: Geometry & Binning | Pass 2: Tile Render)")

    pass1_states = states[:16]
    pass2_states = states[16:]

    with dot.subgraph(name="cluster_pass1") as p1:
        p1.attr(label="Pass 1: Geometry & Setup", color="#3b82f6", fontcolor="#93c5fd", style="rounded")
        for s in pass1_states:
            color = "#1e3a8a" if s != 'sIdle' else "#065f46"
            p1.node(s, s, fillcolor=color, fontcolor="#e2e8f0")

    with dot.subgraph(name="cluster_pass2") as p2:
        p2.attr(label="Pass 2: Tile Rendering", color="#10b981", fontcolor="#6ee7b7", style="rounded")
        for s in pass2_states:
            color = "#064e3b" if s != 'sDone' else "#7f1d1d"
            p2.node(s, s, fillcolor=color, fontcolor="#e2e8f0")

    # Add edges
    # deduplicate edges
    seen_edges = set()
    for src, dst, label in transitions:
        if (src, dst) not in seen_edges:
            dot.edge(src, dst, label=label, color="#94a3b8", fontcolor="#94a3b8")
            seen_edges.add((src, dst))

    output_path = Path("docs/seq_diagram")
    rendered = dot.render(str(output_path), cleanup=True)
    print(f"Generated diagram: {rendered}")

def update_markdown(states, descriptions):
    md_path = Path("docs/07_tbr.md")
    if not md_path.exists():
        return
        
    content = md_path.read_text()
    
    # Generate table
    table = "## Sequencer FSM States\n\n"
    table += "![BorgSequencer FSM](seq_diagram.svg)\n\n"
    table += "|State|Pass|Description|\n"
    table += "|---|---|---|\n"
    seen_states = set()
    for i, s in enumerate(states):
        if s in seen_states:
            continue
        seen_states.add(s)
        pass_name = "Pass 1" if i < 16 else "Pass 2"
        desc = descriptions.get(s, "").strip()
        if not desc and s == 'sIdle': desc = "Wait for MMIO trigger to begin rendering."
        if not desc and s == 'sWaitDMA': desc = "Wait for DMA transfer to complete."
        if not desc and s == 'sRunVert': desc = "Trigger vertex shader execution on BorgCore at PC=0"
        if not desc and s == 'sRunSetup': desc = "Trigger setup shader execution on BorgCore at PC=0"
        if not desc and s == 'sBinTri': desc = "Trigger BorgBinner for this triangle"
        if not desc and s == 'sStoreSetup': desc = "Store 31 uniform values to DRAM setup store"
        if not desc and s == 'sWaitBinCount': desc = "Latch the count data for the tile"
        if not desc and s == 'sClearTile': desc = "Pulse tileCtrlClear for 16-cycle BRAM clear sequence"
        if not desc and s == 'sWaitBinEntry': desc = "Wait for DMA snoop to capture binEntryData"
        if not desc and s == 'sEnqueueTile': desc = "Enqueue tile coordinates for rasterizer"
        if not desc and s == 'sIteratePixels': desc = "Start rasterizer iteration over pixels"
        if not desc and s == 'sWaitRast': desc = "Wait for tileComplete (all pixels shaded)"
        if not desc and s == 'sWaitFlush': desc = "Trigger flusher: writes tile SRAM -> DRAM"
        if not desc and s == 'sWaitFlushSync': desc = "Wait for flusher to finish"
        if not desc and s == 'sDone': desc = "Sequencer complete — pulse done for one cycle, return to idle"
        
        table += f"|`{s}`|{pass_name}|{desc}|\n"
        
    table += "\n"
    
    # Replace existing table if we added it before, or insert it.
    if "## Sequencer FSM States" in content:
        content = re.sub(r'## Sequencer FSM States.*?## Hardware Components', table + '## Hardware Components', content, flags=re.DOTALL)
    else:
        # Insert before Hardware Components
        content = content.replace("## Hardware Components", table + "## Hardware Components")
        
    md_path.write_text(content)
    print(f"Updated {md_path}")

if __name__ == "__main__":
    s, d, t = parse_sequencer()
    generate_diagram(s, d, t)
    update_markdown(s, d)

