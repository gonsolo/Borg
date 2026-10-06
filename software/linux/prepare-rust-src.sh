#!/bin/sh
# Writable copy of $LINUX_SRC with the Borg DRM driver overlaid and hooked into the build.
# usage: prepare-rust-src.sh <dest>
set -e
dest=$1
[ -n "$dest" ] && [ -n "$LINUX_SRC" ] || { echo "usage: LINUX_SRC=... $0 <dest>"; exit 1; }
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$dest"
cp -a --reflink=auto --no-preserve=ownership "$LINUX_SRC"/. "$dest"/
chmod -R u+w "$dest"
cp -r "$here"/overlay/. "$dest"/
grep -q borg "$dest/drivers/gpu/drm/Kconfig" || sed -i 's|^source "drivers/gpu/drm/tyr/Kconfig"|&\nsource "drivers/gpu/drm/borg/Kconfig"|' "$dest/drivers/gpu/drm/Kconfig"
grep -q borg "$dest/drivers/gpu/drm/Makefile" || sed -i 's|^obj-$(CONFIG_DRM_TYR) += tyr/|&\nobj-$(CONFIG_DRM_BORG) += borg/|' "$dest/drivers/gpu/drm/Makefile"
grep -q borg_drm "$dest/rust/uapi/uapi_helper.h" || sed -i 's|^#include <uapi/drm/drm.h>|&\n#include <uapi/drm/borg_drm.h>|' "$dest/rust/uapi/uapi_helper.h"
