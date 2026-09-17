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
    "software":  {"label": "Software Stack",         "color": "#b45309", "fontcolor": "#fef3c7", "fillcolor": "#78350f"},
    "soc":       {"label": "SoC (Top Level)",       "color": "#1a1a2e", "fontcolor": "#e2e8f0", "fillcolor": "#16213e"},
    "memory":    {"label": "Memory Subsystem",       "color": "#1e3a5f", "fontcolor": "#e2e8f0", "fillcolor": "#1e3a5f"},
    "borg":      {"label": "Borg GPU",               "color": "#0f3460", "fontcolor": "#e2e8f0", "fillcolor": "#0f3460"},
    "link":      {"label": "Inter-Tile Link",        "color": "#7c2d12", "fontcolor": "#e2e8f0", "fillcolor": "#7c2d12"},
    "hutt":      {"label": "Hutt RISC-V CPU",        "color": "#533483", "fontcolor": "#e2e8f0", "fillcolor": "#533483"},
    "rdl":       {"label": "Register Descriptions",  "color": "#2d6a4f", "fontcolor": "#e2e8f0", "fillcolor": "#2d6a4f"},
}

NODE_COLORS = {
    "software":  {"style": "filled,rounded", "fillcolor": "#451a03", "fontcolor": "#fcd34d", "color": "#d97706"},
    "soc":       {"style": "filled,rounded", "fillcolor": "#1e3a5f", "fontcolor": "#93c5fd", "color": "#3b82f6"},
    "memory":    {"style": "filled,rounded", "fillcolor": "#1a3a4f", "fontcolor": "#7dd3fc", "color": "#0ea5e9"},
    "borg":      {"style": "filled,rounded", "fillcolor": "#1e3a5f", "fontcolor": "#6ee7b7", "color": "#10b981"},
    "link":      {"style": "filled,rounded", "fillcolor": "#431407", "fontcolor": "#fdba74", "color": "#f97316"},
    "hutt":      {"style": "filled,rounded", "fillcolor": "#2d1b69", "fontcolor": "#c4b5fd", "color": "#8b5cf6"},
    "rdl":       {"style": "filled,rounded", "fillcolor": "#064e3b", "fontcolor": "#6ee7b7", "color": "#10b981"},
}

# Modules we consider "top-level connectors" and want highlighted. Other
# legitimate hierarchy roots (ULX3S/sim-only SoC top variants, ASIC-only
# QspiSocTop siblings, etc.) don't need to be hand-listed here -- any
# defined module nothing else instantiates is auto-detected as a root for
# reachability purposes too (see auto_roots in build_graph). This set is only
# for names that should render specially even when nothing links to them.
TOP_LEVEL = {"QspiSocTop", "Borg", "Hutt", "Borg Driver", "Hutt Firmware"}

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

    # Chisel's "cake pattern": `trait Foo { self: RawModule => ... }` mixed into a
    # concrete class via `extends RawModule with Foo`. The actual Module(new ...)
    # wiring often lives in the trait body, not the concrete class -- e.g.
    # SoCLogic/MinimalSoCLogic own the Clint/HuttDataWidthAdapter instantiations
    # that QspiSocTop, MinimalSocSimTop et al. just mix in. Without this,
    # those instantiations have no `defined` name in the same file to attribute
    # an edge to, so their targets look like orphans despite being wired.
    for m in re.finditer(
        r"trait\s+(\w+)\s*\{\s*self:\s*(?:\w+\.)*(?:Module|RawModule)",
        text,
    ):
        name = m.group(1)
        if not is_skippable(name):
            defined.add(name)

    # Also pick up top-level classes by name heuristic (class Foo extends SoCLogic)
    for m in re.finditer(r"(?:class|object)\s+(\w+)(?:\(.*?\))?", text):
        name = m.group(1)
        if name in TOP_LEVEL and name not in app_objects:
            defined.add(name)

    # Module(new Foo(...)) instantiations — also catches lambda style: () => new Foo(...)
    # and no-arg constructors like Module(new CsrFile) where ) immediately follows.
    # Deliberately NOT gated on app_objects: a file that only builds a Main/App
    # emitter (e.g. MinimalSocSimMain.scala's `new MinimalSocSimTop(...)`) or a
    # test file that defines no Module itself still tells us the instantiated
    # name is genuinely used somewhere -- see all_instantiated below. The
    # per-file `defn in f["defined"]` loop in build_graph already guarantees no
    # edge is ever drawn *from* such a file, so this can't manufacture a false
    # instantiates-by relationship, only a true instantiated-somewhere fact.
    instantiates = set()
    for m in re.finditer(r"(?:Module\s*\(\s*)?new\s+(?:[\w.]+\.)?([A-Z]\w+)\s*[({)]", text):
        name = m.group(1)
        if not is_skippable(name):
            instantiates.add(name)


    # import hutt.{Hutt, ...} — track cross-package imports
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

        data.setdefault(group, [])
        for scala in group_dir.rglob("*.scala"):
            rel_path = scala.relative_to(root)
            # hardware/borg/{src,test/src}/link/*.scala -> its own "link"
            # cluster, split out of "borg" (inter-tile credit-based link
            # fabric: BorgLinkMaster/Slave, LinkTx/Rx, CreditCounter, ...) --
            # a distinct enough subsystem to want visually separate from the
            # rest of the GPU pipeline, especially on the docs/talk slide.
            file_group = "link" if "link" in rel_path.parts else group
            result = parse_scala_file(scala)
            result["group"] = file_group
            result["rel_path"] = rel_path
            data.setdefault(file_group, []).append(result)

    return data


# ---------------------------------------------------------------------------
# Graph building
# ---------------------------------------------------------------------------

def build_graph(hw_data: dict, groups: set[str] | None = None) -> graphviz.Digraph:
    """`groups`, if given, restricts the diagram to just those GROUPS keys
    (e.g. {"borg"} for a single-cluster diagram with no cross-group edges --
    used for the docs/talk slide). None (the default) renders everything."""
    active_groups = set(GROUPS.keys()) if groups is None else set(groups)

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
        # Single-cluster diagrams (docs/talk's slide embed) already say what
        # they are via the one cluster label -- an identical outer title
        # would just be redundant.
        label=(""
               if groups is not None and len(active_groups) == 1
               else r"Borg GPU — Hardware Component Interaction\n(auto-generated by scripts/gen_hw_diagram.py)"),
        labelloc="t",
        labeljust="c",
    )

    # Build lookup: module_name -> group. Anything outside active_groups is
    # simply never added here, so it can never appear as a node or an edge
    # endpoint below -- no separate filtering pass needed elsewhere.
    module_to_group: dict[str, str] = {}
    all_defined: dict[str, set] = defaultdict(set)

    for group, files in hw_data.items():
        if group not in active_groups:
            continue
        for f in files:
            for name in f["defined"]:
                module_to_group[name] = group
                all_defined[group].add(name)

    # Add RDL pseudo-nodes
    rdl_root = Path("hardware/rdl")
    rdl_files = list(rdl_root.glob("*.rdl")) if rdl_root.exists() and "rdl" in active_groups else []
    rdl_nodes = [p.stem for p in rdl_files if p.stem != "generate"]
    for rn in rdl_nodes:
        lbl = rn.replace("_", " ").title() + "\n.rdl"
        module_to_group[rn] = "rdl"
        all_defined["rdl"].add(rn)

    # Add Software pseudo-nodes
    if "software" in active_groups:
        module_to_group["Borg Driver"] = "software"
        all_defined["software"].add("Borg Driver")
        module_to_group["Hutt Firmware"] = "software"
        all_defined["software"].add("Hutt Firmware")

    # Pre-compute edges so we can filter orphan nodes
    # (done early; draw_edges() below re-uses this set)
    edges: set[tuple[str, str, str]] = set()

    for group, files in hw_data.items():
        if group not in active_groups:
            continue
        for f in files:
            for defn in f["defined"]:
                # defn itself might be excluded even though its file's group
                # is active (SKIP_PATTERNS etc.), or -- when filtering to a
                # subset of groups -- might be a trait/class whose group
                # isn't in active_groups' scan at all; either way, if it's
                # not a real node here, it can't be a real edge source
                # (dot.edge() would otherwise auto-create a phantom node for
                # it, as happened for "SoCLogic" wiring up "Borg" with only
                # --groups borg selected).
                if defn not in module_to_group:
                    continue
                for inst in f["instantiates"]:
                    if inst != defn and inst in module_to_group:
                        edges.add((defn, inst, "instantiates"))



    for consumer, rdl_file in [
        ("Borg", "borg"),
        ("Peripherals", "soc"),
        ("Project", "soc"),
        ("Peripherals", "gpio"),
        ("Peripherals", "uart"),
        ("MemoryController", "dram"),
    ]:
        if rdl_file in rdl_nodes and consumer in module_to_group:
            edges.add((rdl_file, consumer, "generated regs"))

    for sw, rdl_file in [
        ("Borg Driver", "borg"),
        ("Borg Driver", "dram"),
        ("Hutt Firmware", "soc"),
        ("Hutt Firmware", "gpio"),
        ("Hutt Firmware", "uart"),
    ]:
        if rdl_file in rdl_nodes and sw in module_to_group:
            edges.add((sw, rdl_file, "software uses regs"))
    if "soc" in rdl_nodes and "Project" in module_to_group:
        edges.add(("soc", "Project", "generated regs"))

    # Every raw `new Foo(...)` seen anywhere, regardless of whether the file it
    # appeared in defines a Module itself (a test file, or a Main/App emitter
    # like MinimalSocSimMain.scala). This can't manufacture a false "used by"
    # edge (see the comment above instantiates' regex in parse_scala_file), but
    # it *is* proof `Foo` is genuinely instantiated somewhere -- e.g. Papers'
    # test harnesses (ColorQuantizeHarness, QspiBackendHarness, ...) are only
    # ever built from test files that don't themselves extend Module, so they'd
    # otherwise show zero edges and read as dead code needing "wiring or
    # removal" despite being real, exercised hardware.
    all_instantiated: set[str] = set()
    for group, files in hw_data.items():
        for f in files:
            all_instantiated |= f["instantiates"]

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

    # Roots aren't just the hand-maintained TOP_LEVEL set (which drifts --
    # e.g. "Project" hasn't existed as a class in years, and it never listed
    # the ULX3S/sim-harness top variants like MinimalSocSimTop or
    # QspiSocTop's siblings). Any defined module nothing else ever
    # instantiates is *by construction* the top of its own hierarchy --
    # exactly what a Main/App emitter's sole top-level argument is -- so treat
    # it as a valid BFS seed too, instead of flagging it "unreachable."
    instantiated_targets = {dst for _, dst, kind in edges if kind == "instantiates"}
    auto_roots = {
        name
        for group_names in all_defined.values()
        for name in group_names
        if name not in instantiated_targets
    }

    reachable: set[str] = set()
    queue = list((TOP_LEVEL | auto_roots) & module_to_group.keys())
    reachable.update(queue)
    while queue:
        node = queue.pop()
        for neighbour in adj.get(node, []):
            if neighbour not in reachable:
                reachable.add(neighbour)
                queue.append(neighbour)

    # Create subgraphs (clusters) per group
    for group, group_cfg in GROUPS.items():
        if group not in active_groups:
            continue
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
                is_orphan = name not in connected and name not in all_instantiated
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
        "generated regs": {"color": "#10b981", "penwidth": "1.4", "arrowhead": "diamond", "style": "dashed"},
        "software uses regs": {"color": "#d97706", "penwidth": "1.4", "arrowhead": "open", "style": "dashed"},
    }

    for (src, dst, kind) in sorted(edges):
        style = edge_styles.get(kind, {})
        dot.edge(src, dst, **style)

    # Force hierarchy layout (user request: soc > gpu/cpu > fpu). These two
    # names ("Project", the FPU pipeline's old "MulAddRecFN" from the since-
    # removed hardware/hardfloat/ vendor dir -- FP16 FMA is now inline in
    # BorgFp16Fma.scala) haven't existed as classes for a while; dot.edge()
    # auto-creates a node for any name it's given, so referencing them here
    # was silently drawing two stray, unstyled default-look boxes with
    # nothing else pointing at them. QspiSocTop is the real ASIC top.
    if "QspiSocTop" in module_to_group:
        dot.edge("QspiSocTop", "Borg", style="invis", weight="100")
        dot.edge("QspiSocTop", "Hutt", style="invis", weight="100")

    # (Legend removed as per user request)

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
    parser.add_argument(
        "--groups",
        default=None,
        help=f"Comma-separated subset of {sorted(GROUPS.keys())} to render "
             "(default: all). E.g. --groups borg for just the GPU cluster, "
             "no cross-cluster edges to anything excluded.",
    )
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

    groups = None
    if args.groups:
        groups = {g.strip() for g in args.groups.split(",") if g.strip()}
        unknown = groups - GROUPS.keys()
        if unknown:
            print(f"ERROR: unknown group(s) {sorted(unknown)}; valid: {sorted(GROUPS.keys())}")
            sys.exit(1)

    print(f"📐  Building diagram ...")
    dot = build_graph(hw_data, groups=groups)
    dot.format = args.format

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    rendered = dot.render(str(output_path), cleanup=True, view=args.view)
    print(f"✅  Diagram written to: {rendered}")
    print(f"\nTip: Re-run this script any time hardware changes:")
    print(f"  python3 scripts/gen_hw_diagram.py")


if __name__ == "__main__":
    main()
