#!/usr/bin/env python3
"""
gen_hw_diagram.py — Borg Hardware Diagram Generator (pure SVG, no graphviz)

Produces a 4-column table-style diagram: tight rectangles, no floating layout.
Re-run whenever hardware changes:
    python3 scripts/gen_hw_diagram.py
"""

import argparse
import subprocess
import sys
from pathlib import Path
from textwrap import dedent

# ── Column definitions ───────────────────────────────────────────────────────

COLUMNS = [
    {
        "label": "Software",
        "fill":  "#d0e8e8",
        "nodes": [
            "Borg Driver",
            "Hutt Firmware",
        ],
    },
    {
        "label": "SoC · CPU · Memory",
        "fill":  "#dde8f4",
        "nodes": [
            "Project",
            "Hutt",
            "Peripherals",
            "MemoryController",
            "SdramBackend",
        ],
    },
    {
        "label": "GPU",
        "fill":  "#e4e0f4",
        "nodes": [
            "Borg",
            "BorgCommandFIFO",
            "BorgCore",
            "BorgSequencer",
        ],
    },
    {
        "label": "GPU Pipeline",
        "fill":  "#ddf0e4",
        "nodes": [
            "BorgBinner",
            "BorgTileFlusher",
            "BorgRasterizer",
            "BorgTextureUnit",
            "BorgTileBuffer",
            "BorgDMA",
            "BorgIterator",
            "BorgShaderDispatcher",
        ],
    },
]

# Arrows: (source_node, target_node, optional_label)
# Only draw arrows that cross column boundaries left-to-right.
ARROWS = [
    ("Borg Driver",      "Peripherals",      ""),
    ("Hutt Firmware",    "Hutt",             ""),
    ("Peripherals",      "Borg",             ""),
    ("MemoryController", "Borg",             ""),
    ("Borg",             "BorgBinner",       ""),
    ("Borg",             "BorgTileFlusher",  ""),
    ("BorgSequencer",    "BorgRasterizer",   ""),
    ("BorgCore",         "BorgShaderDispatcher", ""),
]

# ── Visual style ─────────────────────────────────────────────────────────────

BG_COLOR     = "#C8E8E8"   # poster monoLight
HDR_FILL     = "#085868"   # poster monoDark (teal)
HDR_TEXT     = "#ffffff"
NODE_FILL    = "#ffffff"
NODE_TEXT    = "#082830"
NODE_BORDER  = "#085868"
ARROW_COLOR  = "#085868"
LABEL_COLOR  = "#2E6878"   # poster monoMid

# ── Dimensions (SVG user units, emitted as "pt" so inkscape scales correctly) ─

PAD       = 50    # outer canvas padding
COL_GAP   = 60    # horizontal gap between columns
HDR_H     = 88    # header box height
NODE_H    = 74    # node box height
NODE_GAP  = 10    # vertical gap between nodes
HDR_FONT  = 32    # header label font size
NODE_FONT = 28    # node label font size
ARR_FONT  = 20    # arrow label font size
RADIUS    = 8     # box corner radius
ARROW_W   = 3     # arrow stroke width
BORDER_W  = 2     # node border width


# ── Geometry helpers ─────────────────────────────────────────────────────────

def column_height(col):
    n = len(col["nodes"])
    return HDR_H + NODE_GAP + n * NODE_H + (n - 1) * NODE_GAP


def compute_canvas(col_width):
    n_cols  = len(COLUMNS)
    max_h   = max(column_height(c) for c in COLUMNS)
    canvas_w = 2 * PAD + n_cols * col_width + (n_cols - 1) * COL_GAP
    canvas_h = 2 * PAD + max_h
    return canvas_w, canvas_h


def col_x(col_idx, col_width):
    return PAD + col_idx * (col_width + COL_GAP)


def node_y(col_idx, node_idx):
    """Top-left Y of a node box."""
    return PAD + HDR_H + NODE_GAP + node_idx * (NODE_H + NODE_GAP)


def node_cx(col_idx, col_width):
    return col_x(col_idx, col_width) + col_width // 2


def node_cy(col_idx, node_idx):
    return node_y(col_idx, node_idx) + NODE_H // 2


def find_node(name):
    """Return (col_idx, node_idx) for a named node."""
    for ci, col in enumerate(COLUMNS):
        for ni, node in enumerate(col["nodes"]):
            if node == name:
                return ci, ni
    return None, None


# ── SVG primitives ───────────────────────────────────────────────────────────

def rect(x, y, w, h, fill, stroke=None, stroke_w=BORDER_W, rx=RADIUS):
    s = stroke or fill
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" '
            f'rx="{rx}" ry="{rx}" '
            f'fill="{fill}" stroke="{s}" stroke-width="{stroke_w}"/>')


def text(x, y, content, size, color, anchor="middle", weight="normal"):
    return (f'<text x="{x}" y="{y}" '
            f'font-family="Helvetica Neue,Helvetica,Arial,sans-serif" '
            f'font-size="{size}" font-weight="{weight}" '
            f'fill="{color}" text-anchor="{anchor}" '
            f'dominant-baseline="central">'
            f'{content}</text>')


def arrow(x1, y1, x2, y2, color=ARROW_COLOR, w=ARROW_W):
    """Orthogonal arrow: horizontal then vertical then horizontal."""
    mid_x = (x1 + x2) // 2
    path = f"M{x1},{y1} H{mid_x} V{y2} H{x2}"
    return (f'<path d="{path}" fill="none" stroke="{color}" '
            f'stroke-width="{w}" '
            f'marker-end="url(#arrowhead)"/>')


# ── Main SVG generation ───────────────────────────────────────────────────────

def generate_svg(col_width=None):
    # Default column width: fill a ~3:1 canvas
    if col_width is None:
        max_h = max(column_height(c) for c in COLUMNS)
        total_h = max_h + 2 * PAD
        target_w = int(total_h * 3.2)
        n = len(COLUMNS)
        col_width = (target_w - 2 * PAD - (n - 1) * COL_GAP) // n

    canvas_w, canvas_h = compute_canvas(col_width)

    lines = []

    # SVG header (use "pt" units — identical to graphviz output, so
    # the poster's \includegraphics[width=\linewidth] scales correctly)
    lines.append(
        f'<svg width="{canvas_w}pt" height="{canvas_h}pt" '
        f'viewBox="0 0 {canvas_w} {canvas_h}" '
        f'xmlns="http://www.w3.org/2000/svg">'
    )

    # Arrowhead marker
    lines.append(dedent(f"""\
        <defs>
          <marker id="arrowhead" markerWidth="8" markerHeight="6"
                  refX="7" refY="3" orient="auto">
            <polygon points="0 0, 8 3, 0 6"
                     fill="{ARROW_COLOR}"/>
          </marker>
        </defs>"""))

    # Background
    lines.append(rect(0, 0, canvas_w, canvas_h, BG_COLOR, stroke=BG_COLOR))

    # ── Columns ───────────────────────────────────────────────────────────────
    for ci, col in enumerate(COLUMNS):
        cx = col_x(ci, col_width)
        ch = column_height(col)

        # Column background panel (full column height)
        lines.append(rect(cx, PAD, col_width, ch,
                          fill=col["fill"], stroke=NODE_BORDER))

        # Header box (dark teal)
        lines.append(rect(cx, PAD, col_width, HDR_H,
                          fill=HDR_FILL, stroke=HDR_FILL))
        lines.append(text(cx + col_width // 2,
                          PAD + HDR_H // 2,
                          col["label"], HDR_FONT, HDR_TEXT, weight="bold"))

        # Node boxes
        for ni, node_name in enumerate(col["nodes"]):
            nx = cx
            ny = node_y(ci, ni)
            lines.append(rect(nx + BORDER_W, ny, col_width - 2 * BORDER_W,
                              NODE_H, fill=NODE_FILL, stroke=NODE_BORDER))
            lines.append(text(nx + col_width // 2,
                              ny + NODE_H // 2,
                              node_name, NODE_FONT, NODE_TEXT))

    # ── Arrows ────────────────────────────────────────────────────────────────
    for src_name, dst_name, label in ARROWS:
        src_ci, src_ni = find_node(src_name)
        dst_ci, dst_ni = find_node(dst_name)
        if src_ci is None or dst_ci is None:
            continue
        if src_ci >= dst_ci:
            continue  # only left-to-right arrows

        # Exit from right edge of source node, enter left edge of target node
        x1 = col_x(src_ci, col_width) + col_width
        y1 = node_cy(src_ci, src_ni)
        x2 = col_x(dst_ci, col_width)
        y2 = node_cy(dst_ci, dst_ni)

        lines.append(arrow(x1, y1, x2, y2))

        if label:
            lx = (x1 + x2) // 2
            ly = min(y1, y2) - 8
            lines.append(text(lx, ly, label, ARR_FONT, LABEL_COLOR))

    lines.append("</svg>")
    return "\n".join(lines)


# ── Entry point ───────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Generate Borg hardware architecture diagram (pure SVG)")
    parser.add_argument("--output", default="docs/hw_diagram",
                        help="Output file base path (no extension)")
    parser.add_argument("--format", choices=["svg", "pdf", "png"], default="svg",
                        help="Output format (default: svg)")
    parser.add_argument("--col-width", type=int, default=None,
                        help="Override column width in SVG units")
    args = parser.parse_args()

    svg = generate_svg(args.col_width)
    svg_path = Path(args.output + ".svg")
    svg_path.write_text(svg)
    print(f"✅  Diagram written to: {svg_path}")

    if args.format in ("pdf", "png"):
        out_path = Path(args.output + "." + args.format)
        result = subprocess.run(
            ["inkscape", f"--export-type={args.format}",
             f"--export-filename={out_path}", str(svg_path)],
            capture_output=True, text=True
        )
        if result.returncode == 0:
            print(f"✅  Converted to: {out_path}")
        else:
            print(f"❌  inkscape conversion failed:\n{result.stderr}", file=sys.stderr)
            sys.exit(1)

    print("\nTip: Re-run this script any time hardware changes:")
    print("  python3 scripts/gen_hw_diagram.py")


if __name__ == "__main__":
    main()
