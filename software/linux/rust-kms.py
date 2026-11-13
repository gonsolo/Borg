#!/usr/bin/env python3
"""Hook the overlay's Rust DRM KMS pieces (rust/kernel/drm/kms.rs, gem/dma.rs) into a kernel source
tree: their modules, their C headers for bindgen, and a driver's modeset features. Idempotent.

    rust-kms.py <kernel source>
"""
import sys

src = sys.argv[1]


def insert(path, anchor, text, after=True):
    f = f"{src}/{path}"
    t = open(f).read()
    if text in t:
        return
    assert t.count(anchor) == 1, f"{path}: {anchor!r}"
    t = t.replace(anchor, anchor + text if after else text + anchor)
    open(f, "w").write(t)


insert("rust/kernel/drm/mod.rs", "pub mod ioctl;\n", "#[cfg(CONFIG_DRM_KMS_HELPER)]\npub mod kms;\n")
insert("rust/kernel/drm/gem/mod.rs", "pub mod shmem;\n", "\n#[cfg(CONFIG_DRM_GEM_DMA_HELPER)]\npub mod dma;\n")
insert("rust/bindings/bindings_helper.h", "#include <drm/drm_ioctl.h>\n",
       "".join(f"#include <drm/{h}.h>\n" for h in [
           "clients/drm_client_setup", "drm_atomic_helper", "drm_connector", "drm_edid", "drm_fb_dma_helper",
           "drm_fb_helper", "drm_fbdev_dma", "drm_framebuffer", "drm_fourcc", "drm_gem_dma_helper", "drm_gem_framebuffer_helper",
           "drm_mode_config", "drm_modeset_helper_vtables", "drm_probe_helper", "drm_simple_kms_helper"]))
insert("rust/kernel/drm/driver.rs",
       "pub(crate) const FEAT_RENDER: u32 = bindings::drm_driver_feature_DRIVER_RENDER;\n",
       "pub(crate) const FEAT_MODESET: u32 = bindings::drm_driver_feature_DRIVER_MODESET;\n"
       "pub(crate) const FEAT_ATOMIC: u32 = bindings::drm_driver_feature_DRIVER_ATOMIC;\n")
insert("rust/kernel/drm/driver.rs", "    const FEAT_RENDER: bool = false;\n",
       "\n    /// Sets the `DRIVER_MODESET` and `DRIVER_ATOMIC` features: the driver has a display\n"
       "    /// ([`drm::kms`]).\n"
       "    const FEAT_MODESET: bool = false;\n\n"
       "    /// The fbdev emulation's probe, for a console on the display.\n"
       "    const FBDEV_PROBE: Option<\n"
       "        unsafe extern \"C\" fn(\n"
       "            *mut bindings::drm_fb_helper,\n"
       "            *mut bindings::drm_fb_helper_surface_size,\n"
       "        ) -> core::ffi::c_int,\n"
       "    > = None;\n")
insert("rust/kernel/drm/device.rs",
       "        if T::FEAT_RENDER {\n            features |= drm::driver::FEAT_RENDER;\n        }\n",
       "\n        if T::FEAT_MODESET {\n"
       "            features |= drm::driver::FEAT_MODESET | drm::driver::FEAT_ATOMIC;\n        }\n")
f = f"{src}/rust/kernel/drm/device.rs"
t = open(f).read()
if "fbdev_probe: T::FBDEV_PROBE," not in t:
    assert t.count("        fbdev_probe: None,\n") == 1
    open(f, "w").write(t.replace("        fbdev_probe: None,\n", "        fbdev_probe: T::FBDEV_PROBE,\n"))
