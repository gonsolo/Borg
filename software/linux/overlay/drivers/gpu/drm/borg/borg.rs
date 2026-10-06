// SPDX-License-Identifier: GPL-2.0 or MIT

//! Borg GPU: render-only DRM driver. Userspace (Mesa borgvk) programs the GPU with register
//! writes and moves data in and out of the GPU memory carve-out; the kernel only validates
//! offsets and sequences the accesses.

use kernel::{
    device::Core,
    devres::Devres,
    drm::{self, ioctl},
    io::{mem::IoMem, Io},
    of, platform,
    prelude::*,
    sync::aref::ARef,
    uaccess::{UserPtr, UserSlice},
    uapi,
};

const ABI: u32 = 1;
const MAX_WRITES: u32 = 4096;

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
        let regs = ddev.regs.try_access().ok_or(ENXIO)?;
        let mut reader = UserSlice::new(
            UserPtr::from_addr(args.writes as usize),
            args.count as usize * 8,
        )
        .reader();
        for _ in 0..args.count {
            let offset = reader.read::<u32>()?;
            let value = reader.read::<u32>()?;
            if offset & 3 != 0 {
                return Err(EINVAL);
            }
            regs.try_write32(value, offset as usize)?;
        }
        Ok(0)
    }

    fn reg_read(
        ddev: &BorgDrmDevice,
        args: &mut uapi::drm_borg_reg_read,
        _file: &BorgDrmFile,
    ) -> Result<u32> {
        if args.offset & 3 != 0 {
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
        let mem = ddev.mem.try_access().ok_or(ENXIO)?;
        let mut reader = UserSlice::new(
            UserPtr::from_addr(args.data as usize),
            args.length as usize,
        )
        .reader();
        for i in 0..args.length / 4 {
            let word = reader.read::<u32>()?;
            mem.try_write32(word, (args.offset + i * 4) as usize)?;
        }
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
        let mem = ddev.mem.try_access().ok_or(ENXIO)?;
        let mut writer = UserSlice::new(
            UserPtr::from_addr(args.data as usize),
            args.length as usize,
        )
        .writer();
        for i in 0..args.length / 4 {
            let word = mem.try_read32((args.offset + i * 4) as usize)?;
            writer.write_slice(&word.to_ne_bytes())?;
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

        let data = try_pin_init!(BorgDeviceData { regs, mem });
        let bdev = drm::UnregisteredDevice::<BorgDrmDriver>::new(pdev.as_ref(), data)?;
        let bdev = drm::driver::Registration::new_foreign_owned(bdev, pdev.as_ref(), 0)?;

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
    type Object<R: drm::DeviceContext> = drm::gem::shmem::Object<BoData, R>;

    const INFO: drm::DriverInfo = INFO;
    const FEAT_RENDER: bool = true;

    kernel::declare_drm_ioctls! {
        (BORG_INFO, drm_borg_info, ioctl::RENDER_ALLOW, BorgDrmFileData::info),
        (BORG_REG_WRITES, drm_borg_reg_writes, ioctl::RENDER_ALLOW, BorgDrmFileData::reg_writes),
        (BORG_REG_READ, drm_borg_reg_read, ioctl::RENDER_ALLOW, BorgDrmFileData::reg_read),
        (BORG_MEM_WRITE, drm_borg_mem, ioctl::RENDER_ALLOW, BorgDrmFileData::mem_write),
        (BORG_MEM_READ, drm_borg_mem, ioctl::RENDER_ALLOW, BorgDrmFileData::mem_read),
    }
}

kernel::module_platform_driver! {
    type: BorgPlatformDriver,
    name: "borg",
    authors: ["Andreas Wendleder"],
    description: "Borg GPU render node",
    license: "Dual MIT/GPL",
}
