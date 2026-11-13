// SPDX-License-Identifier: GPL-2.0

//! A small part of DRM KMS: one display with one connector of a fixed mode, on the simple display
//! pipe and the atomic helpers, for framebuffers in DMA memory ([`super::gem::dma`]).
//!
//! C headers: [`include/drm/drm_simple_kms_helper.h`](srctree/include/drm/drm_simple_kms_helper.h),
//! [`include/drm/drm_mode_config.h`](srctree/include/drm/drm_mode_config.h)

use crate::{
    container_of,
    drm::{self, Device, DeviceContext},
    error::to_result,
    prelude::*,
    types::Opaque,
};
use core::{marker::PhantomData, ptr};

/// The framebuffer a display shows: its memory's bus address and layout.
pub struct Framebuffer {
    /// Bus address of the first pixel.
    pub dma_addr: u64,
    /// Bytes per row.
    pub pitch: u32,
    /// Width in pixels.
    pub width: u32,
    /// Height in pixels.
    pub height: u32,
    /// The DRM fourcc of its pixels.
    pub format: u32,
}

/// What a driver does when the display's picture changes.
pub trait DisplayOps<D: drm::Driver> {
    /// Show `fb`, or nothing for `None`.
    fn update(dev: &Device<D>, fb: Option<&Framebuffer>);
}

/// One display: a simple display pipe (plane, CRTC, encoder) and a connector with one mode.
#[repr(C)]
#[pin_data]
pub struct SimpleDisplay<D: drm::Driver, T: DisplayOps<D>> {
    #[pin]
    pipe: Opaque<bindings::drm_simple_display_pipe>,
    #[pin]
    connector: Opaque<bindings::drm_connector>,
    width: u32,
    height: u32,
    _p: PhantomData<(D, T)>,
}

// SAFETY: The C objects are only touched by the DRM core under its locks.
unsafe impl<D: drm::Driver, T: DisplayOps<D>> Send for SimpleDisplay<D, T> {}

// SAFETY: As for `Send`.
unsafe impl<D: drm::Driver, T: DisplayOps<D>> Sync for SimpleDisplay<D, T> {}

impl<D: drm::Driver, T: DisplayOps<D>> SimpleDisplay<D, T> {
    const MODE_CONFIG_FUNCS: bindings::drm_mode_config_funcs = bindings::drm_mode_config_funcs {
        fb_create: Some(bindings::drm_gem_fb_create),
        atomic_check: Some(bindings::drm_atomic_helper_check),
        atomic_commit: Some(bindings::drm_atomic_helper_commit),
        // SAFETY: All other members are optional; zero is `None`.
        ..unsafe { core::mem::zeroed() }
    };

    const CONNECTOR_FUNCS: bindings::drm_connector_funcs = bindings::drm_connector_funcs {
        reset: Some(bindings::drm_atomic_helper_connector_reset),
        fill_modes: Some(bindings::drm_helper_probe_single_connector_modes),
        destroy: Some(bindings::drm_connector_cleanup),
        atomic_duplicate_state: Some(bindings::drm_atomic_helper_connector_duplicate_state),
        atomic_destroy_state: Some(bindings::drm_atomic_helper_connector_destroy_state),
        // SAFETY: As above.
        ..unsafe { core::mem::zeroed() }
    };

    const CONNECTOR_HELPER: bindings::drm_connector_helper_funcs = bindings::drm_connector_helper_funcs {
        get_modes: Some(Self::get_modes_callback),
        // SAFETY: As above.
        ..unsafe { core::mem::zeroed() }
    };

    const PIPE_FUNCS: bindings::drm_simple_display_pipe_funcs = bindings::drm_simple_display_pipe_funcs {
        enable: Some(Self::enable_callback),
        disable: Some(Self::disable_callback),
        update: Some(Self::update_callback),
        // SAFETY: As above; without `prepare_fb` the helper pins GEM framebuffers itself.
        ..unsafe { core::mem::zeroed() }
    };

    /// A display of `width` x `height` pixels, set up by [`Self::init`].
    pub fn new(width: u32, height: u32) -> impl PinInit<Self> {
        pin_init!(Self {
            pipe <- Opaque::init_zeroed(),
            connector <- Opaque::init_zeroed(),
            width,
            height,
            _p: PhantomData,
        })
    }

    /// Sets up KMS on `dev` with this display showing `formats`; before `dev` is registered.
    pub fn init<C: DeviceContext>(&self, dev: &Device<D, C>, formats: &'static [u32]) -> Result {
        let raw = dev.as_raw();
        // SAFETY: `raw` is a valid device that is not registered yet; the pipe and connector live
        // as long as the device's data, which holds `self`.
        unsafe {
            to_result(bindings::drmm_mode_config_init(raw))?;
            let mc = &raw mut (*raw).mode_config;
            (*mc).min_width = 1;
            (*mc).min_height = 1;
            (*mc).max_width = self.width;
            (*mc).max_height = self.height;
            (*mc).funcs = &Self::MODE_CONFIG_FUNCS;

            let conn = self.connector.get();
            to_result(bindings::drm_connector_init(
                raw,
                conn,
                &Self::CONNECTOR_FUNCS,
                bindings::DRM_MODE_CONNECTOR_HDMIA as i32,
            ))?;
            (*conn).helper_private = (&Self::CONNECTOR_HELPER as *const bindings::drm_connector_helper_funcs).cast();

            to_result(bindings::drm_simple_display_pipe_init(
                raw,
                self.pipe.get(),
                &Self::PIPE_FUNCS,
                formats.as_ptr(),
                formats.len() as u32,
                ptr::null(),
                conn,
            ))?;
            bindings::drm_mode_config_reset(raw);
        }
        Ok(())
    }

    /// A console on the display (fbdev emulation); after `dev` is registered.
    pub fn setup_console(dev: &Device<D>) {
        // SAFETY: `dev` is registered and its driver has an `fbdev_probe`.
        unsafe { bindings::drm_client_setup(dev.as_raw(), ptr::null()) };
    }

    extern "C" fn get_modes_callback(conn: *mut bindings::drm_connector) -> core::ffi::c_int {
        // SAFETY: `conn` is the `connector` of a `Self`.
        unsafe {
            let this = &*container_of!(Opaque::cast_from(conn), Self, connector);
            let n = bindings::drm_add_modes_noedid(conn, this.width, this.height);
            bindings::drm_set_preferred_mode(conn, this.width as i32, this.height as i32);
            n
        }
    }

    /// The pipe's device and current framebuffer.
    ///
    /// # Safety
    ///
    /// `pipe` is the `pipe` of a `Self`, called from a pipe callback.
    unsafe fn show(pipe: *mut bindings::drm_simple_display_pipe, on: bool) {
        // SAFETY: Per the function's contract, the pipe and its plane state are valid.
        unsafe {
            let dev: &Device<D> = Device::from_raw((*pipe).crtc.dev);
            let state = (*pipe).plane.state;
            let fb = if on && !state.is_null() { (*state).fb } else { ptr::null_mut() };
            if fb.is_null() {
                T::update(dev, None);
                return;
            }
            let fb_info = Framebuffer {
                dma_addr: bindings::drm_fb_dma_get_gem_addr(fb, state, 0) as u64,
                pitch: (*fb).pitches[0],
                width: (*fb).width,
                height: (*fb).height,
                format: (*(*fb).format).format,
            };
            T::update(dev, Some(&fb_info));
        }
    }

    extern "C" fn enable_callback(
        pipe: *mut bindings::drm_simple_display_pipe,
        _crtc_state: *mut bindings::drm_crtc_state,
        _plane_state: *mut bindings::drm_plane_state,
    ) {
        // SAFETY: Called by the simple pipe helper for this pipe.
        unsafe { Self::show(pipe, true) };
    }

    extern "C" fn disable_callback(pipe: *mut bindings::drm_simple_display_pipe) {
        // SAFETY: As for `enable_callback`.
        unsafe { Self::show(pipe, false) };
    }

    extern "C" fn update_callback(
        pipe: *mut bindings::drm_simple_display_pipe,
        _old_state: *mut bindings::drm_plane_state,
    ) {
        // SAFETY: As for `enable_callback`.
        unsafe { Self::show(pipe, true) };
    }
}
