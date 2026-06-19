#!/usr/bin/env python3
# SPDX-FileCopyrightText: © 2026 Andreas Wendleder
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Serial firmware reload for the ULX3S — no reflash, no FPGA reconfiguration, so
# the HDMI monitor never loses sync.
#
# Streams a raw firmware image (software/borg/vkcube.bin) to the running firmware
# over /dev/ttyUSB0.  The firmware's 0xB1 handler writes it into SDRAM scratch and
# pulses a warm reset; the bootloader then copies scratch → 0 and the CPU reboots
# with a fresh instruction cache, while the video clock domain keeps running.
#
# Wire format:  0xB1 | size(4 B LE) | image[size] | xor8(image)
#
# Usage:  reload.py [PORT] [IMAGE]
#   PORT  default /dev/ttyUSB0
#   IMAGE default ../../software/borg/vkcube.bin (relative to this script)

import os
import struct
import sys
import time

import serial

MARKER = 0xB1
BAUD = 115200


def main() -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    port = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB0"
    image = (sys.argv[2] if len(sys.argv) > 2
             else os.path.join(here, "..", "..", "..", "software", "borg", "vkcube.bin"))

    with open(image, "rb") as f:
        img = f.read()
    size = len(img)
    if size == 0:
        print(f"reload: {image} is empty", file=sys.stderr)
        return 1

    xsum = 0
    for b in img:
        xsum ^= b

    pkt = bytes([MARKER]) + struct.pack("<I", size) + img + bytes([xsum & 0xFF])

    # Don't toggle DTR/RTS (not wired to reset, but avoid surprises).
    ser = serial.Serial()
    ser.port = port
    ser.baudrate = BAUD
    ser.timeout = 2
    ser.rtscts = False
    ser.dsrdtr = False
    ser.open()

    time.sleep(0.1)            # let the line settle so the firmware re-syncs to a boundary
    ser.reset_input_buffer()
    t_start = time.time()
    ser.write(pkt)
    ser.flush()
    dt = time.time() - t_start
    print(f"reload: sent {size} bytes (+5 hdr +1 csum) to {port} in {dt:.2f}s "
          f"(xor=0x{xsum & 0xFF:02x})")

    # Best-effort confirmation read.  If tio (or anything) already holds the port
    # the read raises — that's fine, the bytes are already sent and the firmware's
    # "FW: reload OK, warm reset" shows up in that other reader.  Never fail the
    # tool over the readback.
    try:
        deadline = time.time() + 4
        while time.time() < deadline:
            line = ser.readline()
            if line:
                sys.stdout.write(line.decode(errors="replace"))
                sys.stdout.flush()
                if b"reload OK" in line or b"B1:" in line:
                    break
    except serial.SerialException:
        print("reload: (confirmation goes to your tio session — see it there)")
    finally:
        ser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
