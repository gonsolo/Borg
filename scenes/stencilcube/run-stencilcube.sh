#!/usr/bin/env bash
# Show the stencil scene on the ULX3S: resets the board, forces a fresh upload and
# runs vkcube-stencil (vkcube with cull NONE and a two-sided stencil test, see
# stencilcube.patch -- the cube is seen from the inside) through borgvk. Needs the
# "stencil" bitstream (BORG_ULX_CFG=stencil) and a firmware with the 0xB4 handler.
#
# Usage: scenes/stencilcube/run-stencilcube.sh [seconds]   (default 45)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -z "${IN_NIX_SHELL:-}" ]]; then
  exec direnv exec /home/gonsolo/work/Borg bash "$0" "$@"
fi
rm -f /tmp/borgvk_ttyUSB0_setup
openFPGALoader -b ulx3s -r >/dev/null 2>&1
sleep 3
export BORGVK_FORCE_UPLOAD=1
export VKCUBE="$HERE/vkcube-stencil"
exec bash "$HERE/../../fpga/ulx3s/run-vkcube.sh" "${1:-45}"
