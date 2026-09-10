#!/usr/bin/env bash
# Launch the video-autoplay-patched Papers (see patches/papers-video-autoplay.patch)
# in F5 presentation mode against talk_video.pdf. See patches/README.md for how
# this build was produced and how to reproduce it.
#
# Usage: ./run-papers-video.sh [pdf]   (default: talk_video.pdf)
set -euo pipefail
cd "$(dirname "$0")"

PREFIX="$HOME/.local/opt/papers-video"
PDF="${1:-talk_video.pdf}"

if [[ ! -x "$PREFIX/bin/papers" ]]; then
  echo "patched papers not found at $PREFIX -- see patches/README.md to build it" >&2
  exit 1
fi

exec env \
  LD_LIBRARY_PATH="$PREFIX/lib" \
  GSETTINGS_SCHEMA_DIR="$PREFIX/share/glib-2.0/schemas" \
  XDG_DATA_DIRS="$PREFIX/share:${XDG_DATA_DIRS:-/usr/share}" \
  "$PREFIX/bin/papers" --presentation "$PDF"
