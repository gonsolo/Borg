#!/usr/bin/env python3
"""
Send mouse rotation to vkcube running on ULX3S via UART.

Protocol: 9-byte packets  [0xAB] [ry: 4-byte LE float] [rx: 4-byte LE float]
  ry = Y-axis rotation (0 .. 2π)
  rx = X-axis tilt    (-1.4 .. 1.4 rad)

Requirements:
  pip install evdev pyserial
  sudo usermod -aG input $USER   (then re-login, or run with sudo)

Usage:
  python3 scripts/mouse_rotation.py [/dev/ttyUSB0]
"""

import sys
import math
import select
import struct
import time
import serial
import evdev

SERIAL_PORT        = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB0"
BAUD               = 115200
SENSITIVITY        = 0.005   # radians per raw mouse count
AUTO_ROTATE_DELAY  = 5.0     # seconds of inactivity before auto-rotate
AUTO_ROTATE_SPEED  = 0.03    # radians per 50ms tick (~1 rev / 10 s)
SELECT_TIMEOUT     = 0.05    # seconds between auto-rotate ticks


def find_mouse():
    for path in evdev.list_devices():
        dev = evdev.InputDevice(path)
        caps = dev.capabilities()
        if evdev.ecodes.EV_REL in caps:
            rel = caps[evdev.ecodes.EV_REL]
            if evdev.ecodes.REL_X in rel and evdev.ecodes.REL_Y in rel:
                return dev
    raise RuntimeError("No relative-axis mouse found in /dev/input/event*")


def pack_packet(ry: float, rx: float) -> bytes:
    return struct.pack("<Bff", 0xAB, ry, rx)


def main() -> None:
    mouse = find_mouse()
    print(f"Mouse : {mouse.name}  ({mouse.path})")
    ser = serial.Serial(SERIAL_PORT, BAUD, timeout=0)
    print(f"Serial: {SERIAL_PORT} @ {BAUD} baud")
    print("Move mouse to rotate cube. Ctrl-C to quit.")

    ry: float = 0.0
    rx: float = 0.5236   # 30° initial tilt (matches firmware default)
    TWO_PI = 2.0 * math.pi
    dx = 0.0
    dy = 0.0
    last_move = time.monotonic()
    auto_rotating = False

    while True:
        readable, _, _ = select.select([mouse.fd], [], [], SELECT_TIMEOUT)

        if readable:
            for event in mouse.read():
                if event.type == evdev.ecodes.EV_REL:
                    if event.code == evdev.ecodes.REL_X:
                        dx += event.value
                    elif event.code == evdev.ecodes.REL_Y:
                        dy += event.value
                elif event.type == evdev.ecodes.EV_SYN and event.code == evdev.ecodes.SYN_REPORT:
                    if dx != 0.0 or dy != 0.0:
                        ry = (ry + dx * SENSITIVITY) % TWO_PI
                        rx = max(-1.4, min(1.4, rx + dy * SENSITIVITY))
                        ser.write(pack_packet(ry, rx))
                        dx = 0.0
                        dy = 0.0
                        last_move = time.monotonic()
                        auto_rotating = False
        else:
            # No mouse event this tick — advance auto-rotation if idle long enough.
            if not auto_rotating and time.monotonic() - last_move >= AUTO_ROTATE_DELAY:
                auto_rotating = True
            if auto_rotating:
                ry = (ry + AUTO_ROTATE_SPEED) % TWO_PI
                ser.write(pack_packet(ry, rx))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
