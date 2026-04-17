#!/usr/bin/env python3
"""
gen_hw_diagram.py — Borg Hardware Component Diagram Generator
==============================================================
Parses all Chisel/Scala source files under ./hardware and generates
an SVG/PNG architecture diagram showing module instantiation relationships.

Usage:
    python3 scripts/gen_hw_diagram.py [--output docs/hw_diagram] [--format svg|png|pdf]

Requirements:
    pip install graphviz
    # plus system graphviz: sudo apt install graphviz

Re-run this script whenever hardware changes to regenerate the diagram.
"""

import argparse
import re
import sys
from pathlib import Path
from collections import defaultdict

try:
    import graphviz
except ImportError:
    print("ERROR: graphviz Python package not found.")
    print("  Install with:  pip install graphviz")
    print("  System package: sudo apt install graphviz")
    sys.exit(1)

# ---------------------------------------------------------------------------
# Configuration — map subdirectory names to display groups + colours
# ---------------------------------------------------------------------------
GROUPS = {
    "soc":       {"label": "SoC (Top Level)",       "color": "#1a1a2e", "fontcolor": "#e2e8f0", "fillcolor": "#16213e"},
    "borg":      {"label": "Borg GPU",               "color": "#0f3460", "fontcolor": "#e2e8f0", "fillcolor": "#0f3460"},
    "tinyqv":    {"label": "TinyQV RISC-V CPU",     "color": "#533483", "fontcolor": "#e2e8f0", "fillcolor": "#533483"},
    "hardfloat": {"label": "Berkeley HardFloat FPU","color": "#833471", "fontcolor": "#e2e8f0", "fillcolor": "#833471"},
    "rdl":       {"label": "Register Descriptions",  "color": "#2d6a4f", "fontcolor": "#e2e8f0", "fillcolor": "#2d6a4f"},
}

NODE_COLORS = {
    "soc":       {"style": "filled,rounded", "fillcolor": "#1e3a5f", "fontcolor": "#93c5fd", "color": "#3b82f6"},
    "borg":      {"style": "filled,rounded", "fillcolor": "#1e3a5f", "fontcolor": "#6ee7b7", "color": "#10b981"},
    "tinyqv":    {"style": "filled,rounded", "fillcolor": "#2d1b69", "fontcolor": "#c4b5fd", "color": "#8b5cf6"},
    "hardfloat": {"style": "filled,rounded", "fillcolor": "#4a1841", "fontcolor": "#f9a8d4", "color": "#ec4899"},
    "rdl":       {"style": "filled,rounded", "fillcolor": "#064e3b", "fontcolor": "#6ee7b7", "color": "#10b981"},
}

# Modules we consider "top-level connectors" and want highlighted
TOP_LEVEL = {"tt_um_gonsolo_borg", "tt_um_gonsolo_borg_sim", "Project", "Borg", "TinyQV"}

# Modules that are data-types / IO bundles — skip as nodes
SKIP_PATTERNS = [
    re.compile(r".*IO$"),
    re.compile(r".*Bundle$"),
    re.compile(r"Bbox|ColorZ|Coord|Globals|RegIndices|FpuOpFlags"),
    re.compile(r"FloatConfig|SoCDecode|AddrRegion"),
]

# ---------------------------------------------------------------------------
# Parsing helpers
# ---------------------------------------------------------------------------

def is_skippable(name: str) -> bool:
    return any(p.match(name) for p in SKIP_PATTERNS)


def parse_scala_file(path: Path) -> dict:
    """Extract class/object definitions and Module(new ...) instantiations."""
    text = path.read_text(errors="replace")

    # Detect Scala App objects — these are build drivers, not hardware modules
    app_objects: set[str] = set()
    for m in re.finditer(r"(?:class|object)\s+(\w+)(?:\(.*?\))?\s+extends\s+App\b", text):
        app_objects.add(m.group(1))

    # Classes / objects that extend Module or RawModule (skip App objects)
    defined = set()
    for m in re.finditer(
        r"(?:class|object)\s+(\w+)(?:\(.*?\))?\s+extends\s+(?:\w+\.)*(?:Module|RawModule)",
        text,
    ):
        name = m.group(1)
        if not is_skippable(name) and name not in app_objects:
            defined.add(name)

    # Also pick up top-level classes by name heuristic (class Foo extends SoCLogic)
    for m in re.finditer(r"(?:class|object)\s+(\w+)(?:\(.*?\))?", text):
        name = m.group(1)
        if name in TOP_LEVEL and name not in app_objects:
            defined.add(name)

    # Module(new Foo(...)) instantiations — also catches lambda style: () => new Foo(...)
    # and no-arg constructors like Module(new CsrFile) where ) immediately follows.
    # Skip App-only files (e.g. Main.scala) — their lambda lists are emitter config, not hierarchy
    instantiates = set()
    if defined or not app_objects:
        for m in re.finditer(r"(?:Module\s*\(\s*)?new\s+(?:[\w.]+\.)?([A-Z]\w+)\s*[({)]", text):
            name = m.group(1)
            if not is_skippable(name):
                instantiates.add(name)


    # import tinyqv.cpu.{TinyQV, ...} — track cross-package imports
    imports = set()
    for m in re.finditer(r"import\s+([\w.]+)\.\{([^}]+)\}", text):
        pkg = m.group(1)
        symbols = [s.strip().split(" ")[-1] for s in m.group(2).split(",")]
        for sym in symbols:
            if sym and not is_skippable(sym):
                imports.add((pkg, sym))
    for m in re.finditer(r"import\s+([\w.]+)\.(\w+)\b", text):
        sym = m.group(2)
        if not is_skippable(sym):
            imports.add((m.group(1), sym))

    return {"defined": defined, "instantiates": instantiates, "imports": imports, "path": path}


def discover_hardware(root: Path) -> dict:
    """Walk ./hardware and parse every .scala file. Returns structured info."""
    data = {}  # group_name -> list of file results

    for group_dir in sorted(root.iterdir()):
        if not group_dir.is_dir():
            continue
        group = group_dir.name
        if group not in GROUPS:
            continue

        data[group] = []
        for scala in group_dir.rglob("*.scala"):
            result = parse_scala_file(scala)
            result["group"] = group
            result["rel_path"] = scala.relative_to(root)
            data[group].append(result)

    return data


# ---------------------------------------------------------------------------
# Graph building
# ---------------------------------------------------------------------------

def build_graph(hw_data: dict) -> graphviz.Digraph:
    dot = graphviz.Digraph(
        name="Borg Hardware Architecture",
        comment="Auto-generated from Chisel source — do not edit manually",
        format="svg",
    )
    dot.attr(
        rankdir="TB",
        bgcolor="#0d1117",
        fontname="Helvetica Neue,Helvetica,Arial,sans-serif",
        fontcolor="#e2e8f0",
        fontsize="13",
        pad="0.6",
        splines="ortho",
        nodesep="0.5",
        ranksep="0.8",
        label=r"Borg GPU — Hardware Component Interaction\n(auto-generated by scripts/gen_hw_diagram.py)",
        labelloc="t",
        labeljust="c",
    )

    # Build lookup: module_name -> group
    module_to_group: dict[str, str] = {}
    all_defined: dict[str, set] = defaultdict(set)

    for group, files in hw_data.items():
        for f in files:
            for name in f["defined"]:
                module_to_group[name] = group
                all_defined[group].add(name)

    # Add RDL pseudo-nodes
    rdl_root = Path("hardware/rdl")
    rdl_files = list(rdl_root.glob("*.rdl")) if rdl_root.exists() else []
    rdl_nodes = [p.stem for p in rdl_files if p.stem != "generate"]
    for rn in rdl_nodes:
        lbl = rn.replace("_", " ").title() + "\n.rdl"
        module_to_group[rn] = "rdl"
        all_defined["rdl"].add(rn)

    # Pre-compute edges so we can filter orphan nodes
    # (done early; draw_edges() below re-uses this set)
    edges: set[tuple[str, str, str]] = set()

    for group, files in hw_data.items():
        for f in files:
            for defn in f["defined"]:
                for inst in f["instantiates"]:
                    if inst != defn and inst in module_to_group:
                        edges.add((defn, inst, "instantiates"))

    for group, files in hw_data.items():
        for f in files:
            for defn in f["defined"]:
                for (pkg, sym) in f["imports"]:
                    if sym in module_to_group and sym != defn:
                        edge = (defn, sym, "uses")
                        rev = (sym, defn, "uses")
                        if edge not in edges and rev not in edges:
                            if module_to_group.get(sym) != group:
                                edges.add(edge)

    for consumer, rdl_file in [
        ("Borg", "borg"),
        ("Peripherals", "soc"),
        ("Project", "soc"),
        ("Peripherals", "gpio"),
        ("Peripherals", "uart"),
        ("MemoryController", "psram"),
    ]:
        if rdl_file in rdl_nodes and consumer in module_to_group:
            edges.add((rdl_file, consumer, "generated regs"))
    if "soc" in rdl_nodes and "Project" in module_to_group:
        edges.add(("soc", "Project", "generated regs"))

    # Only render nodes that participate in at least one edge (skip orphans)
    connected: set[str] = set()
    for src, dst, _ in edges:
        connected.add(src)
        connected.add(dst)
    # Always include explicit top-level modules even if somehow unconnected
    connected |= TOP_LEVEL & module_to_group.keys()

    # BFS from all top-level nodes to find modules reachable from the top of the hierarchy
    # Build adjacency (directed: parent -> child)
    adj: dict[str, set[str]] = defaultdict(set)
    for src, dst, _ in edges:
        adj[src].add(dst)
        adj[dst].add(src)  # treat as undirected for reachability from top

    reachable: set[str] = set()
    queue = list(TOP_LEVEL & module_to_group.keys())
    reachable.update(queue)
    while queue:
        node = queue.pop()
        for neighbour in adj.get(node, []):
            if neighbour not in reachable:
                reachable.add(neighbour)
                queue.append(neighbour)

    # Create subgraphs (clusters) per group
    for group, group_cfg in GROUPS.items():
        nc = NODE_COLORS[group]
        with dot.subgraph(name=f"cluster_{group}") as sg:
            sg.attr(
                label=group_cfg["label"],
                fontname="Helvetica Neue,Helvetica,Arial,sans-serif",
                fontcolor=group_cfg["fontcolor"],
                fontsize="14",
                style="filled,rounded",
                fillcolor="#161b22",
                color=group_cfg["color"],
                penwidth="2",
                labelloc="b",  # label at bottom — avoids visual overlap with incoming arrowheads
            )
            for name in sorted(all_defined[group]):
                is_top = name in TOP_LEVEL
                is_orphan = name not in connected
                is_floating = connected and name in connected and name not in reachable
                if is_orphan:
                    # Red — no edges at all, needs wiring or removal
                    node_attrs = {
                        "style": "filled,dashed",
                        "fillcolor": "#3b0a0a",
                        "fontcolor": "#ff6b6b",
                        "color": "#ef4444",
                        "penwidth": "2",
                        "shape": "box",
                    }
                    label = f"⚠ {name}"
                elif is_floating:
                    # Red — has edges but not reachable from any top cell
                    node_attrs = {
                        "style": "filled,dashed",
                        "fillcolor": "#3b0a0a",
                        "fontcolor": "#ff6b6b",
                        "color": "#ef4444",
                        "penwidth": "2",
                        "shape": "box",
                    }
                    label = f"◈ {name}"
                else:
                    node_attrs = dict(nc)
                    node_attrs["shape"] = "box3d" if is_top else "box"
                    if is_top:
                        node_attrs["penwidth"] = "3"
                    label = name
                node_attrs["fontname"] = "Helvetica Neue,Helvetica,Arial,sans-serif"
                node_attrs["fontsize"] = "11" if not is_top else "12"
                node_attrs["margin"] = "0.2,0.1"
                sg.node(name, label=label, **node_attrs)


    # (edges already computed above before node rendering)

    # Draw edges
    edge_styles = {
        "instantiates": {"color": "#3b82f6", "penwidth": "1.8", "arrowhead": "vee", "style": "solid"},
        "uses":         {"color": "#6b7280", "penwidth": "1.2", "arrowhead": "open", "style": "dashed"},
        "generated regs": {"color": "#10b981", "penwidth": "1.4", "arrowhead": "diamond", "style": "dashed"},
    }

    for (src, dst, kind) in sorted(edges):
        style = edge_styles.get(kind, {})
        dot.edge(src, dst, **style)

    # Legend
    with dot.subgraph(name="cluster_legend") as leg:
        leg.attr(label="Legend", fontcolor="#94a3b8", fontsize="11",
                 style="filled", fillcolor="#161b22", color="#374151")
        fn = "Helvetica Neue,Helvetica,Arial,sans-serif"
        leg.node("leg_top", "Top-Level", shape="box3d", style="filled,rounded",
                 fillcolor="#1e3a5f", fontcolor="#93c5fd", color="#3b82f6",
                 fontsize="10", fontname=fn)
        leg.node("leg_inst", "Module A", shape="box", style="filled,rounded",
                 fillcolor="#1e3a5f", fontcolor="#93c5fd", color="#3b82f6",
                 fontsize="10", fontname=fn)
        leg.node("leg_inst2", "Module B", shape="box", style="filled,rounded",
                 fillcolor="#1e3a5f", fontcolor="#93c5fd", color="#3b82f6",
                 fontsize="10", fontname=fn)
        leg.node("leg_inst3", "Module C", shape="box", style="filled,rounded",
                 fillcolor="#1e3a5f", fontcolor="#93c5fd", color="#3b82f6",
                 fontsize="10", fontname=fn)
        leg.node("leg_inst4", "Module D", shape="box", style="filled,rounded",
                 fillcolor="#1e3a5f", fontcolor="#93c5fd", color="#3b82f6",
                 fontsize="10", fontname=fn)
        leg.node("leg_float", "◈ Floating island\n(not reachable from top)", shape="box",
                 style="filled,dashed", fillcolor="#3b0a0a", fontcolor="#ff6b6b",
                 color="#ef4444", penwidth="2", fontsize="10", fontname=fn)
        leg.node("leg_orphan", "⚠ Orphan\n(no edges)", shape="box",
                 style="filled,dashed", fillcolor="#3b0a0a", fontcolor="#ff6b6b",
                 color="#ef4444", penwidth="2", fontsize="10", fontname=fn)
        leg.edge("leg_inst", "leg_inst2", label="instantiates", style="solid",
                 color="#3b82f6", arrowhead="vee", fontsize="9", fontcolor="#94a3b8",
                 fontname=fn)
        leg.edge("leg_inst2", "leg_inst3", label="uses", style="dashed",
                 color="#6b7280", arrowhead="open", fontsize="9", fontcolor="#94a3b8",
                 fontname=fn)
        leg.edge("leg_inst3", "leg_inst4", label="generated regs", style="dashed",
                 color="#10b981", arrowhead="diamond", fontsize="9", fontcolor="#94a3b8",
                 fontname=fn)

    return dot


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Generate Borg hardware architecture diagram")
    parser.add_argument("--hw-root", default="hardware", help="Path to hardware root (default: hardware)")
    parser.add_argument("--output", default="docs/hw_diagram", help="Output file base (no extension)")
    parser.add_argument("--format", choices=["svg", "png", "pdf"], default="svg", help="Output format")
    parser.add_argument("--view", action="store_true", help="Open the diagram after generation")
    parser.add_argument("--list-modules", action="store_true", help="Print discovered modules and exit")
    args = parser.parse_args()

    hw_root = Path(args.hw_root)
    if not hw_root.is_dir():
        print(f"ERROR: Hardware root not found: {hw_root}")
        print(f"  Run from the repo root, or pass --hw-root <path>")
        sys.exit(1)

    print(f"🔍  Scanning {hw_root.resolve()} ...")
    hw_data = discover_hardware(hw_root)

    total_files = sum(len(v) for v in hw_data.values())
    total_modules = sum(len(f["defined"]) for files in hw_data.values() for f in files)
    print(f"   Found {total_files} Scala files across {len(hw_data)} groups, {total_modules} modules")

    if args.list_modules:
        for group, files in hw_data.items():
            print(f"\n  [{group}]")
            for f in files:
                if f["defined"]:
                    print(f"    {f['rel_path']}")
                    for m in sorted(f["defined"]):
                        print(f"      - {m}")
                        if f["instantiates"]:
                            for inst in sorted(f["instantiates"]):
                                print(f"          → {inst}")
        return

    print(f"📐  Building diagram ...")
    dot = build_graph(hw_data)
    dot.format = args.format

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    rendered = dot.render(str(output_path), cleanup=True, view=args.view)
    print(f"✅  Diagram written to: {rendered}")
    print(f"\nTip: Re-run this script any time hardware changes:")
    print(f"  python3 scripts/gen_hw_diagram.py")


if __name__ == "__main__":
    main()
