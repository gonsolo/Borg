// SPDX-License-Identifier: GPL-2.0 or MIT

//! Borg GPU: DRM driver. Userspace (Mesa borgvk) programs the GPU with register writes and
//! moves data in and out of the GPU memory carve-out; the kernel only validates offsets (against
//! the register map generated from borg.rdl) and sequences the accesses. The HDMI scan-out is a
//! KMS display: its descriptor, in the carve-out, points at the framebuffer.

mod reg_map;

use kernel::{
    device::Core,
    devres::Devres,
    bindings,
    drm::{self, gem::{dma, BaseObject}, ioctl, kms},
    io::{mem::IoMem, Io},
    of, platform,
    prelude::*,
    sync::aref::ARef,
    uaccess::{UserPtr, UserSlice},
    uapi,
};

const ABI: u32 = 1;
const MAX_WRITES: u32 = 4096;
const CHUNK: usize = 512;
const MAX_BO: u64 = 1 << 30;

struct BorgDrmDriver;
type BorgDrmDevice<Ctx = drm::Registered> = drm::Device<BorgDrmDriver, Ctx>;

struct BorgPlatformDriver;

#[pin_data(PinnedDrop)]
struct BorgPlatformDriverData {
    _device: ARef<BorgDrmDevice>,
}

#[pin_data]
struct BorgDeviceData {
    regs: Devres<IoMem<'static>>,
    mem: Devres<IoMem<'static>>,
    #[pin]
    display: kms::SimpleDisplay<BorgDrmDriver, BorgDisplay>,
}

// The scan-out (MigScanout): its descriptor at Borg's BORG_SCANOUT_SPI in the carve-out, and the
// DDR3's address for the CPU, as the descriptor counts from it.
const SCANOUT: usize = 0x800_0000;
const DDR3_BASE: u64 = 0x8000_0000;
const DRM_FORMAT_XRGB8888: u32 = u32::from_le_bytes(*b"XR24");

struct BorgDisplay;

impl kms::DisplayOps<BorgDrmDriver> for BorgDisplay {
    fn update(dev: &BorgDrmDevice, fb: Option<&kms::Framebuffer>) {
        // Base, pitch, width | height << 16, format (1: BGRA8 bytes, XRGB8888; 0: off).
        let desc = match fb {
            Some(f) if f.format == DRM_FORMAT_XRGB8888 => {
                [(f.dma_addr - DDR3_BASE) as u32, f.pitch, f.width | f.height << 16, 1]
            }
            _ => [0; 4],
        };
        let Some(mem) = dev.mem.try_access() else { return };
        for (i, w) in desc.iter().enumerate() {
            let _ = mem.try_write32(*w, SCANOUT + 4 * i);
        }
    }
}

#[pin_data]
struct BoData {}

impl drm::gem::DriverObject for BoData {
    type Driver = BorgDrmDriver;
    type Args = ();

    fn new<Ctx: drm::DeviceContext>(
        _dev: &BorgDrmDevice<Ctx>,
        _size: usize,
        _args: (),
    ) -> impl PinInit<Self, Error> {
        try_pin_init!(Self {})
    }
}

#[pin_data]
struct BorgDrmFileData {}

impl drm::file::DriverFile for BorgDrmFileData {
    type Driver = BorgDrmDriver;

    fn open(_dev: &drm::Device<Self::Driver>) -> Result<Pin<KBox<Self>>> {
        KBox::try_pin_init(try_pin_init!(Self {}), GFP_KERNEL)
    }
}

type BorgDrmFile = drm::file::File<BorgDrmFileData>;

impl BorgDrmFileData {
    fn info(
        ddev: &BorgDrmDevice,
        args: &mut uapi::drm_borg_info,
        _file: &BorgDrmFile,
    ) -> Result<u32> {
        args.reg_size = ddev.regs.try_access().ok_or(ENXIO)?.maxsize() as u32;
        args.mem_size = ddev.mem.try_access().ok_or(ENXIO)?.maxsize() as u32;
        args.abi = ABI;
        Ok(0)
    }

    fn reg_writes(
        ddev: &BorgDrmDevice,
        args: &mut uapi::drm_borg_reg_writes,
        _file: &BorgDrmFile,
    ) -> Result<u32> {
        if args.count > MAX_WRITES {
            return Err(EINVAL);
        }
        let mut reader = UserSlice::new(
            UserPtr::from_addr(args.writes as usize),
            args.count as usize * 8,
        )
        .reader();
        for _ in 0..args.count {
            let offset = reader.read::<u32>()?;
            let value = reader.read::<u32>()?;
            if offset & 3 != 0 || !reg_map::contains(&reg_map::WRITABLE, offset) {
                return Err(EINVAL);
            }
            // The user copies fault, so they stay outside the Devres guard.
            ddev.regs.try_access().ok_or(ENXIO)?.try_write32(value, offset as usize)?;
        }
        Ok(0)
    }

    fn reg_read(
        ddev: &BorgDrmDevice,
        args: &mut uapi::drm_borg_reg_read,
        _file: &BorgDrmFile,
    ) -> Result<u32> {
        if args.offset & 3 != 0 || !reg_map::contains(&reg_map::READABLE, args.offset) {
            return Err(EINVAL);
        }
        let regs = ddev.regs.try_access().ok_or(ENXIO)?;
        args.value = regs.try_read32(args.offset as usize)?;
        Ok(0)
    }

    fn mem_write(
        ddev: &BorgDrmDevice,
        args: &mut uapi::drm_borg_mem,
        _file: &BorgDrmFile,
    ) -> Result<u32> {
        if args.offset & 3 != 0 || args.length & 3 != 0 {
            return Err(EINVAL);
        }
        let mut reader = UserSlice::new(
            UserPtr::from_addr(args.data as usize),
            args.length as usize,
        )
        .reader();
        let mut buf = [0u8; CHUNK];
        let mut done = 0;
        while done < args.length {
            let n = core::cmp::min(CHUNK as u32, args.length - done);
            reader.read_slice(&mut buf[..n as usize])?;
            let mem = ddev.mem.try_access().ok_or(ENXIO)?;
            for (i, w) in buf[..n as usize].chunks_exact(4).enumerate() {
                let word = u32::from_ne_bytes([w[0], w[1], w[2], w[3]]);
                mem.try_write32(word, (args.offset + done) as usize + i * 4)?;
            }
            done += n;
        }
        Ok(0)
    }

    fn gem_create(
        ddev: &BorgDrmDevice,
        args: &mut uapi::drm_borg_gem_create,
        file: &BorgDrmFile,
    ) -> Result<u32> {
        if args.size == 0 || args.size > MAX_BO {
            return Err(EINVAL);
        }
        let size = (args.size as usize + 4095) & !4095;
        let obj = dma::Object::<BoData>::new(ddev, size)?;
        args.handle = obj.create_handle(file)?;
        Ok(0)
    }

    fn gem_mmap(
        _ddev: &BorgDrmDevice,
        args: &mut uapi::drm_borg_gem_mmap,
        file: &BorgDrmFile,
    ) -> Result<u32> {
        let obj = dma::Object::<BoData>::lookup_handle(file, args.handle)?;
        args.offset = obj.create_mmap_offset()?;
        Ok(0)
    }

    fn mem_read(
        ddev: &BorgDrmDevice,
        args: &mut uapi::drm_borg_mem,
        _file: &BorgDrmFile,
    ) -> Result<u32> {
        if args.offset & 3 != 0 || args.length & 3 != 0 {
            return Err(EINVAL);
        }
        let mut writer = UserSlice::new(
            UserPtr::from_addr(args.data as usize),
            args.length as usize,
        )
        .writer();
        let mut buf = [0u8; CHUNK];
        let mut done = 0;
        while done < args.length {
            let n = core::cmp::min(CHUNK as u32, args.length - done);
            {
                let mem = ddev.mem.try_access().ok_or(ENXIO)?;
                for (i, w) in buf[..n as usize].chunks_exact_mut(4).enumerate() {
                    let word = mem.try_read32((args.offset + done) as usize + i * 4)?;
                    w.copy_from_slice(&word.to_ne_bytes());
                }
            }
            writer.write_slice(&buf[..n as usize])?;
            done += n;
        }
        Ok(0)
    }
}

kernel::of_device_table!(
    OF_TABLE,
    MODULE_OF_TABLE,
    <BorgPlatformDriver as platform::Driver>::IdInfo,
    [(of::DeviceId::new(c"gonsolo,borg"), ())]
);

impl platform::Driver for BorgPlatformDriver {
    type IdInfo = ();
    type Data<'bound> = BorgPlatformDriverData;
    const OF_ID_TABLE: Option<of::IdTable<Self::IdInfo>> = Some(&OF_TABLE);

    fn probe<'bound>(
        pdev: &'bound platform::Device<Core<'_>>,
        _info: Option<&'bound Self::IdInfo>,
    ) -> impl PinInit<Self::Data<'bound>, Error> + 'bound {
        let regs = pdev.io_request_by_index(0).ok_or(ENODEV)?.iomap()?.into_devres()?;
        let mem = pdev.io_request_by_index(1).ok_or(ENODEV)?.iomap()?.into_devres()?;

        let data = try_pin_init!(BorgDeviceData {
            regs,
            mem,
            display <- kms::SimpleDisplay::new(640, 480),
        });
        let bdev = drm::UnregisteredDevice::<BorgDrmDriver>::new(pdev.as_ref(), data)?;
        bdev.display.init(&bdev, &[DRM_FORMAT_XRGB8888])?;
        let bdev = drm::driver::Registration::new_foreign_owned(bdev, pdev.as_ref(), 0)?;
        kms::SimpleDisplay::<BorgDrmDriver, BorgDisplay>::setup_console(bdev);

        dev_info!(pdev, "Borg GPU registered\n");
        Ok(BorgPlatformDriverData {
            _device: bdev.into(),
        })
    }
}

#[pinned_drop]
impl PinnedDrop for BorgPlatformDriverData {
    fn drop(self: Pin<&mut Self>) {}
}

const INFO: drm::DriverInfo = drm::DriverInfo {
    major: 1,
    minor: 0,
    patchlevel: 0,
    name: c"borg",
    desc: c"Borg GPU",
};

#[vtable]
impl drm::Driver for BorgDrmDriver {
    type Data = BorgDeviceData;
    type File = BorgDrmFileData;
    type Object<R: drm::DeviceContext> = dma::Object<BoData, R>;

    const INFO: drm::DriverInfo = INFO;
    const FEAT_RENDER: bool = true;
    const FEAT_MODESET: bool = true;
    const FBDEV_PROBE: Option<
        unsafe extern "C" fn(*mut bindings::drm_fb_helper, *mut bindings::drm_fb_helper_surface_size) -> core::ffi::c_int,
    > = Some(bindings::drm_fbdev_dma_driver_fbdev_probe);

    kernel::declare_drm_ioctls! {
        (BORG_INFO, drm_borg_info, ioctl::RENDER_ALLOW, BorgDrmFileData::info),
        (BORG_REG_WRITES, drm_borg_reg_writes, ioctl::RENDER_ALLOW, BorgDrmFileData::reg_writes),
        (BORG_REG_READ, drm_borg_reg_read, ioctl::RENDER_ALLOW, BorgDrmFileData::reg_read),
        (BORG_MEM_WRITE, drm_borg_mem, ioctl::RENDER_ALLOW, BorgDrmFileData::mem_write),
        (BORG_MEM_READ, drm_borg_mem, ioctl::RENDER_ALLOW, BorgDrmFileData::mem_read),
        (BORG_GEM_CREATE, drm_borg_gem_create, ioctl::RENDER_ALLOW, BorgDrmFileData::gem_create),
        (BORG_GEM_MMAP, drm_borg_gem_mmap, ioctl::RENDER_ALLOW, BorgDrmFileData::gem_mmap),
    }
}

kernel::module_platform_driver! {
    type: BorgPlatformDriver,
    name: "borg",
    authors: ["Andreas Wendleder"],
    description: "Borg GPU and its display",
    license: "Dual MIT/GPL",
}
