// SPDX-License-Identifier: GPL-2.0

//! DRM GEM DMA helper objects: physically contiguous buffers, as a display scans out.
//!
//! C header: [`include/drm/drm_gem_dma_helper.h`](srctree/include/drm/drm_gem_dma_helper.h)
//!
//! The C helpers allocate an object through the driver's `gem_create_object`, which here makes the
//! Rust [`Object`]; they then attach the DMA memory. Dumb buffers (KMS framebuffers, fbdev) and the
//! driver's own buffers are the same type.

use crate::{
    container_of,
    drm::{driver, gem, private::Sealed, Device, DeviceContext, Registered},
    error::from_err_ptr,
    prelude::*,
    sync::aref::ARef,
    types::Opaque,
};
use core::{marker::PhantomData, ops::Deref, ptr::NonNull};
use gem::{DriverObject, IntoGEMObject};

/// A GEM object backed by contiguous DMA memory, with the driver's data `T`.
#[repr(C)]
#[pin_data]
pub struct Object<T: DriverObject, C: DeviceContext = Registered> {
    // First, so the C helpers' kfree() of the `drm_gem_dma_object` frees the whole allocation.
    #[pin]
    obj: Opaque<bindings::drm_gem_dma_object>,
    #[pin]
    inner: T,
    _ctx: PhantomData<C>,
}

super::impl_aref_for_gem_obj! {
    impl<T, C> for Object<T, C>
    where
        T: DriverObject,
        C: DeviceContext
}

// SAFETY: All GEM objects are thread-safe.
unsafe impl<T: DriverObject, C: DeviceContext> Send for Object<T, C> {}

// SAFETY: All GEM objects are thread-safe.
unsafe impl<T: DriverObject, C: DeviceContext> Sync for Object<T, C> {}

impl<T: DriverObject, C: DeviceContext> Object<T, C>
where
    T::Args: Default,
{
    const VTABLE: bindings::drm_gem_object_funcs = bindings::drm_gem_object_funcs {
        free: Some(Self::free_callback),
        open: Some(super::open_callback::<T>),
        close: Some(super::close_callback::<T>),
        print_info: Some(Self::print_info_callback),
        export: None,
        pin: None,
        unpin: None,
        get_sg_table: Some(Self::get_sg_table_callback),
        vmap: Some(Self::vmap_callback),
        vunmap: None,
        mmap: Some(Self::mmap_callback),
        status: None,
        rss: None,
        #[allow(unused_unsafe, reason = "Safe since Rust 1.82.0")]
        // SAFETY: `drm_gem_dma_vm_ops` is a valid, static const on the C side.
        vm_ops: unsafe { &raw const bindings::drm_gem_dma_vm_ops },
        evict: None,
    };

    fn as_raw_dma(&self) -> *mut bindings::drm_gem_dma_object {
        self.obj.get()
    }

    /// Allocates an object of `size` bytes of contiguous memory.
    pub fn new(dev: &Device<T::Driver, C>, size: usize) -> Result<ARef<Self>> {
        // SAFETY: `dev` is a valid DRM device; the helper calls `create_object_callback`.
        let dma = from_err_ptr(unsafe { bindings::drm_gem_dma_create(dev.as_raw(), size) })?;
        // SAFETY: `dma` is the `obj` of a `Self` made by `create_object_callback`, with the
        // reference the helper returned, which the `ARef` takes over.
        Ok(unsafe {
            ARef::from_raw(NonNull::new_unchecked(
                container_of!(Opaque::cast_from(dma), Self, obj).cast_mut(),
            ))
        })
    }

    /// The memory's address on the bus, as a device sees it.
    pub fn dma_addr(&self) -> u64 {
        // SAFETY: The helper set `dma_addr` before the object was handed out.
        unsafe { (*self.as_raw_dma()).dma_addr as u64 }
    }

    /// The memory's kernel virtual address.
    pub fn vaddr(&self) -> *mut core::ffi::c_void {
        // SAFETY: As for `dma_addr`.
        unsafe { (*self.as_raw_dma()).vaddr }
    }

    extern "C" fn create_object_callback(
        dev: *mut bindings::drm_device,
        size: usize,
    ) -> *mut bindings::drm_gem_object {
        // SAFETY: The C helper passes the driver's own, valid device.
        let dev: &Device<T::Driver, C> = unsafe { Device::from_raw(dev) };
        let new = KBox::try_pin_init(
            try_pin_init!(Self {
                obj <- Opaque::init_zeroed(),
                inner <- T::new(dev, size, Default::default()),
                _ctx: PhantomData::<C>,
            }),
            GFP_KERNEL,
        );
        let Ok(new) = new else {
            return ENOMEM.to_ptr();
        };
        // SAFETY: The object is pinned for its lifetime; the C side frees it in `free_callback`.
        let new = KBox::into_raw(unsafe { Pin::into_inner_unchecked(new) });
        // SAFETY: `new` is valid; the helper keeps `funcs` when it is set.
        unsafe {
            let base = &raw mut (*(*new).obj.get()).base;
            (*base).funcs = &Self::VTABLE;
            base
        }
    }

    extern "C" fn free_callback(obj: *mut bindings::drm_gem_object) {
        // SAFETY: `obj` is the `base` of the `drm_gem_dma_object` in a `Self`.
        unsafe {
            let dma = container_of!(obj, bindings::drm_gem_dma_object, base);
            let this = container_of!(Opaque::cast_from(dma), Self, obj).cast_mut();
            core::ptr::drop_in_place(&raw mut (*this).inner);
            // Frees the memory, releases the GEM object and kfree()s `dma`, the allocation's start.
            bindings::drm_gem_dma_free(dma);
        }
    }

    extern "C" fn print_info_callback(
        p: *mut bindings::drm_printer,
        indent: core::ffi::c_uint,
        obj: *const bindings::drm_gem_object,
    ) {
        // SAFETY: `obj` is the `base` of a `drm_gem_dma_object`.
        unsafe {
            let dma = container_of!(obj, bindings::drm_gem_dma_object, base);
            bindings::drm_gem_dma_print_info(dma, p, indent);
        }
    }

    extern "C" fn get_sg_table_callback(obj: *mut bindings::drm_gem_object) -> *mut bindings::sg_table {
        // SAFETY: As for `print_info_callback`.
        unsafe { bindings::drm_gem_dma_get_sg_table(container_of!(obj, bindings::drm_gem_dma_object, base)) }
    }

    extern "C" fn vmap_callback(obj: *mut bindings::drm_gem_object, map: *mut bindings::iosys_map) -> core::ffi::c_int {
        // SAFETY: As for `print_info_callback`.
        unsafe { bindings::drm_gem_dma_vmap(container_of!(obj, bindings::drm_gem_dma_object, base), map) }
    }

    extern "C" fn mmap_callback(obj: *mut bindings::drm_gem_object, vma: *mut bindings::vm_area_struct) -> core::ffi::c_int {
        // SAFETY: As for `print_info_callback`.
        unsafe { bindings::drm_gem_dma_mmap(container_of!(obj, bindings::drm_gem_dma_object, base), vma) }
    }
}

impl<T: DriverObject, C: DeviceContext> Deref for Object<T, C> {
    type Target = T;

    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

impl<T: DriverObject, C: DeviceContext> Sealed for Object<T, C> {}

impl<T: DriverObject, C: DeviceContext> gem::IntoGEMObject for Object<T, C> {
    fn as_raw(&self) -> *mut bindings::drm_gem_object {
        // SAFETY: `self.obj` is a valid, initialized `drm_gem_dma_object`.
        unsafe { &raw mut (*self.obj.get()).base }
    }

    unsafe fn from_raw<'a>(obj: *mut bindings::drm_gem_object) -> &'a Self {
        // SAFETY: The caller guarantees `obj` is the `base` of the `drm_gem_dma_object` of a `Self`.
        unsafe {
            let obj = Opaque::cast_from(container_of!(obj, bindings::drm_gem_dma_object, base));
            &*container_of!(obj, Self, obj)
        }
    }
}

impl<T: DriverObject, C: DeviceContext> driver::AllocImpl for Object<T, C>
where
    T::Args: Default,
{
    type Driver = T::Driver;

    const ALLOC_OPS: driver::AllocOps = driver::AllocOps {
        gem_create_object: Some(Self::create_object_callback),
        prime_handle_to_fd: None,
        prime_fd_to_handle: None,
        gem_prime_import: None,
        gem_prime_import_sg_table: Some(bindings::drm_gem_dma_prime_import_sg_table),
        dumb_create: Some(bindings::drm_gem_dma_dumb_create),
        dumb_map_offset: None,
    };
}
