#!/usr/bin/env bash
# Show the blend scene on the ULX3S: resets the board, forces a fresh upload and
# runs vkcube-blend (vkcube with red-tinted constant-colour blending, see
# blendcube.patch) through borgvk. Needs the "blend" bitstream
# (BORG_ULX_CFG=blend) and a firmware with the 0xB3 handler on the board.
#
# Usage: scenes/blendcube/run-blendcube.sh [seconds]   (default 45)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# run-vkcube.sh resolves its library paths through pkg-config, which only points
# at the Nix store inside the dev shell; in a plain shell it picks the system
# glibc and the Nix-built vkcube dies with "undefined symbol: __pointer_chk_guard".
if [[ -z "${IN_NIX_SHELL:-}" ]]; then
  exec direnv exec /home/gonsolo/work/Borg bash "$0" "$@"
fi
rm -f /tmp/borgvk_ttyUSB0_setup
openFPGALoader -b ulx3s -r >/dev/null 2>&1
sleep 3
export BORGVK_FORCE_UPLOAD=1
export VKCUBE="$HERE/vkcube-blend"
exec bash "$HERE/../../fpga/ulx3s/run-vkcube.sh" "${1:-45}"
