#!/usr/bin/env bash
# Per-module ASIC area, mapped to the real IHP SG13G2 standard-cell library.
#
# Why standalone-per-module instead of one hierarchical run: a single
# `hierarchy`-preserving synth of the whole SoC makes yosys-abc grind for
# hours on one module's combinational cone (measured: 2.5h on a single
# module, never finished). LibreLane's own flow avoids this by flattening
# everything first -- but a flattened netlist loses the hierarchy we're
# trying to measure. Synthesizing each module as its own top keeps each
# ABC invocation small, and `-fast` plus a per-module timeout bounds the
# worst case.
#
# NOTE: each number is the module's FULL SUBTREE (a parent includes its
# children). Subtract children to get a module's own contribution.
#
# Usage: scripts/module_area.sh [module ...]   (default: all interesting ones)
set -uo pipefail

LIB=$(python3 -c "
import json,sys
d=json.load(open('runs/wokwi/resolved.json'))
print(d['LIB']['nom_typ_1p20V_25C'][0])
" 2>/dev/null)
if [ ! -f "$LIB" ]; then
  echo "error: could not resolve IHP liberty from runs/wokwi/resolved.json" >&2
  echo "       run 'make gds-ihp' at least once first" >&2
  exit 1
fi

VDIR=out/hardware/borg/verilog
if [ ! -f "$VDIR/asic_files.txt" ]; then
  echo "error: $VDIR/asic_files.txt missing -- run 'make generate_verilog' first" >&2
  exit 1
fi
FILES=$(sed 's|^\.\./||' "$VDIR/asic_files.txt" | tr '\n' ' ')

MODULES=${*:-"tt_um_gonsolo_borg Hutt HuttAlu HuttDivider HuttRegFile \
  HuttDataWidthAdapter Peripherals Borg BorgCore BorgSequencer BorgFp16Fma \
  BorgBinner BorgLane BorgTileFlusher BorgTileBuffer BorgTextureUnit \
  BorgShaderDispatcher BorgDMA BorgIterator BorgRasterizer BorgGpuRegs \
  Fp16Rcp Fp16Rsq Fp16Srgb Clint MemoryController QspiController QspiBackend"}

TIMEOUT=${TIMEOUT:-600}
printf '%-26s %14s  %s\n' "MODULE" "AREA_um2" "NOTE"
for m in $MODULES; do
  log=$(mktemp)
  timeout "$TIMEOUT" yosys -p "
    read_verilog -sv $FILES
    hierarchy -top $m
    synth -flatten -top $m
    dfflibmap -liberty $LIB
    abc -fast -liberty $LIB
    opt_clean -purge
    stat -liberty $LIB
  " > "$log" 2>&1
  rc=$?
  if [ $rc -eq 124 ]; then
    printf '%-26s %14s  %s\n' "$m" "-" "TIMEOUT after ${TIMEOUT}s"
  elif [ $rc -ne 0 ]; then
    printf '%-26s %14s  %s\n' "$m" "-" "FAILED (rc=$rc)"
  else
    area=$(grep -a "Chip area for" "$log" | tail -1 | grep -aoE "[0-9]+\.[0-9]+")
    printf '%-26s %14s\n' "$m" "${area:--}"
  fi
  rm -f "$log"
done
