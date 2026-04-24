#!/usr/bin/env bash
# animate_placement.sh — Render 100 color-coded placement frames + stitch into mp4.
#
# Colors match the hardware diagram from gen_hw_diagram.py:
#   GPU (Borg):     #10b981 (green)
#   CPU (TinyQV):   #8b5cf6 (purple)
#   Memory:         #0ea5e9 (sky blue)
#   Peripherals:    #3b82f6 (blue)
#   Other:          #64748b (gray)
set -euo pipefail

INPUT_ODB="runs/wokwi/27-odb-applydeftemplate/tt_um_gonsolo_borg.odb"
FINAL_ODB="runs/wokwi/28-openroad-globalplacement/tt_um_gonsolo_borg.odb"
INST_GROUPS="/tmp/inst_groups.csv"
OUT_DIR="runs/placement_frames"
FRAMES=99

if [[ ! -f "$INST_GROUPS" ]]; then
    echo "Classifying instances..."
    python3 scripts/classify_instances.py
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

echo "Rendering $FRAMES placement frames + 1 final = $((FRAMES + 1)) total"
echo "Estimated time: ~$((FRAMES * 10 / 60)) minutes"
echo ""

# Generate the Tcl coloring commands once
COLOR_TCL="/tmp/color_groups.tcl"
python3 -c "
import csv
groups = {}
with open('$INST_GROUPS') as f:
    for row in csv.reader(f):
        groups.setdefault(row[1], []).append(row[0])

# Map group -> OpenROAD highlight group index
# 0: Green
# 1: Yellow
# 2: Red
# 3: Magenta (Closest to Purple)
# 4: Blue
# 5: Cyan
group_colors = {
    'gpu':         '0',  # Green
    'cpu':         '3',  # Magenta
    'memory':      '4',  # Blue
    'peripherals': '5',  # Cyan
    'hardfloat':   '2',  # Red
    'other':       '1',  # Yellow
}

with open('$COLOR_TCL', 'w') as f:
    for group, insts in groups.items():
        idx = group_colors.get(group, '0')
        for inst in insts:
            f.write(f'gui::highlight_inst \"{inst}\" {idx}\n')
"

for i in $(seq 0 $((FRAMES - 1))); do
    overflow=$(LC_ALL=C printf "%.4f" "$(LC_ALL=C echo "0.990 - $i * 0.009081" | bc -l)")
    outfile=$(printf "%s/%04d.png" "$OUT_DIR" "$i")
    logfile=$(printf "%s/%04d.log" "$OUT_DIR" "$i")

    cat > /tmp/gpl_frame.tcl << EOF
source "$COLOR_TCL"
global_placement -density 0.6 -overflow $overflow
gui::fit
save_image -width 1920 "$outfile"
exit
EOF

    echo "Frame $i / $FRAMES  (overflow $overflow)"
    openroad -no_splash -gui -minimize -no_settings \
        -db "$INPUT_ODB" /tmp/gpl_frame.tcl >"$logfile" 2>&1
done

# Final frame: actual completed placement
outfile=$(printf "%s/%04d.png" "$OUT_DIR" "$FRAMES")
cat > /tmp/gpl_frame.tcl << EOF
source "$COLOR_TCL"
gui::fit
save_image -width 1920 "$outfile"
exit
EOF
echo "Frame $FRAMES / $FRAMES  (final placement ODB)"
openroad -no_splash -gui -minimize -no_settings \
    -db "$FINAL_ODB" /tmp/gpl_frame.tcl >/dev/null 2>&1

rm -f /tmp/gpl_frame.tcl

echo ""
echo "Stitching into placement_animation.mp4 at 10fps ..."
ffmpeg -y -framerate 10 -i "$OUT_DIR/%04d.png" \
    -c:v libx264 -pix_fmt yuv420p placement_animation.mp4

echo "Done → placement_animation.mp4"
