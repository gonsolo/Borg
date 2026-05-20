#!/usr/bin/env python3
"""
classify_instances.py — Classify flattened gate-level instances into
their original Chisel module groups by analyzing net names,
then propagate classifications through the netlist graph.

Outputs /tmp/inst_groups.csv for the color-coded animation renderer.
"""
import csv
from collections import Counter, defaultdict

# Colors from gen_hw_diagram.py NODE_COLORS
COLORS = {
    "cpu":         "#8b5cf6",   # purple (hutt)
    "gpu":         "#10b981",   # green  (borg)
    "memory":      "#0ea5e9",   # blue   (memory subsystem)
    "peripherals": "#3b82f6",   # blue   (soc)
    "hardfloat":   "#ec4899",   # pink   (hardfloat FPU)
    "other":       "#64748b",   # gray
}

def classify_by_net(nets_str):
    """Classify an instance based on ALL its connected net names."""
    nets = nets_str.lower()
    if 'borg' in nets or 'rasterizer' in nets or 'tilebuffer' in nets:
        return "gpu"
    if 'hardfloat' in nets or 'muladdrec' in nets or 'recfn' in nets:
        return "hardfloat"
    if '_cpu' in nets or 'instrfetch' in nets or 'hutt' in nets:
        return "cpu"
    if 'qspi' in nets or 'psram' in nets or '_mem_' in nets or 'q_ctrl' in nets:
        return "memory"
    if 'uart' in nets or 'gpio' in nets or 'peripheral' in nets:
        return "peripherals"
    return None

def main():
    # Phase 1: direct classification from net names
    groups = {}
    # Build adjacency: for each instance, track its neighbor instances
    inst_neighbors = defaultdict(set)
    all_insts = set()

    with open("/tmp/inst_all_nets.csv") as f:
        reader = csv.reader(f)
        next(reader)
        for row in reader:
            inst = row[0]
            nets_str = row[1] if len(row) > 1 else ""
            all_insts.add(inst)

            g = classify_by_net(nets_str)
            if g:
                groups[inst] = g

    # Build neighbor graph from net connections (more efficiently)
    # Re-read to build net-to-inst mapping
    net_to_insts = defaultdict(list)
    with open("/tmp/inst_all_nets.csv") as f:
        reader = csv.reader(f)
        next(reader)
        for row in reader:
            inst = row[0]
            nets_str = row[1] if len(row) > 1 else ""
            for n in nets_str.split("|"):
                if n and n not in ("clk", "rst_n", "VDD", "VSS"):
                    net_to_insts[n].append(inst)

    # Build adjacency (skip large fanout nets like clk/reset)
    for net, insts in net_to_insts.items():
        if len(insts) > 100:  # skip high-fanout nets
            continue
        for i in range(len(insts)):
            for j in range(i+1, len(insts)):
                inst_neighbors[insts[i]].add(insts[j])
                inst_neighbors[insts[j]].add(insts[i])

    phase1 = len(groups)
    print(f"Phase 1 (net-name heuristic): {phase1} classified")

    # Phase 2: propagate — majority vote from neighbors
    for iteration in range(10):
        changed = 0
        unclassified = [i for i in all_insts if i not in groups]
        for inst in unclassified:
            neighbor_groups = Counter()
            for neighbor in inst_neighbors.get(inst, []):
                if neighbor in groups:
                    neighbor_groups[groups[neighbor]] += 1
            if neighbor_groups:
                top_group, top_count = neighbor_groups.most_common(1)[0]
                total = sum(neighbor_groups.values())
                if top_count / total >= 0.5:
                    groups[inst] = top_group
                    changed += 1
        print(f"  Round {iteration+1}: +{changed} ({len(groups)}/{len(all_insts)})")
        if changed == 0:
            break

    # Stats
    counts = Counter(groups.values())
    unclassified = len(all_insts) - len(groups)
    print(f"\nFinal classification:")
    for g, c in counts.most_common():
        print(f"  {g:15s}: {c:6d}  ({COLORS.get(g, '#888')})")
    print(f"  {'unclassified':15s}: {unclassified:6d}")
    print(f"  {'total':15s}: {len(all_insts):6d}")

    # Assign remaining to "other"
    for inst in all_insts:
        if inst not in groups:
            groups[inst] = "other"

    # Write instance-to-group CSV
    with open("/tmp/inst_groups.csv", "w") as f:
        for inst in sorted(groups.keys()):
            f.write(f"{inst},{groups[inst]}\n")

    print(f"\nWrote /tmp/inst_groups.csv ({len(groups)} entries)")

if __name__ == "__main__":
    main()
