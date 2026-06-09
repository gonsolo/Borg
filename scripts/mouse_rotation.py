#!/usr/bin/env python3
"""
Send mouse rotation to vkcube running on ULX3S via UART.

Protocol: 37-byte packets  [0xAC] [m00..m22: 9 × 4-byte LE float]
  9 floats are the column-major 3×3 rotation matrix derived from a unit
  quaternion.  Mouse X rotates around world Y; mouse Y rotates around the
  current right vector (trackball — no gimbal lock).

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


# --- Quaternion helpers (w, x, y, z) ---

def quat_from_axis_angle(ax, ay, az, angle):
    s = math.sin(angle * 0.5)
    c = math.cos(angle * 0.5)
    return (c, ax * s, ay * s, az * s)

def quat_mul(a, b):
    aw, ax, ay, az = a
    bw, bx, by, bz = b
    return (
        aw*bw - ax*bx - ay*by - az*bz,
        aw*bx + ax*bw + ay*bz - az*by,
        aw*by - ax*bz + ay*bw + az*bx,
        aw*bz + ax*by - ay*bx + az*bw,
    )

def quat_normalize(q):
    w, x, y, z = q
    n = math.sqrt(w*w + x*x + y*y + z*z)
    return (w/n, x/n, y/n, z/n)

def quat_to_col_mat3(q):
    """Column-major 3×3 rotation matrix as a 9-tuple."""
    w, x, y, z = q
    return (
        1-2*(y*y+z*z),  2*(x*y+w*z),    2*(x*z-w*y),    # col 0
        2*(x*y-w*z),    1-2*(x*x+z*z),  2*(y*z+w*x),    # col 1
        2*(x*z+w*y),    2*(y*z-w*x),    1-2*(x*x+y*y),  # col 2
    )

def apply_rotation(q, dx, dy):
    # Mouse X: rotate around world Y (always vertical on screen)
    if dx:
        dq = quat_from_axis_angle(0.0, 1.0, 0.0, dx * SENSITIVITY)
        q = quat_normalize(quat_mul(dq, q))
    # Mouse Y: rotate around the current right vector (trackball — no gimbal lock)
    if dy:
        w, x, y, z = q
        rx = 1.0 - 2.0*(y*y + z*z)
        ry = 2.0*(x*y + w*z)
        rz = 2.0*(x*z - w*y)
        dq = quat_from_axis_angle(rx, ry, rz, dy * SENSITIVITY)
        q = quat_normalize(quat_mul(dq, q))
    return q

def pack_packet(q):
    return struct.pack("<B9f", 0xAC, *quat_to_col_mat3(q))


def find_mouse():
    for path in evdev.list_devices():
        dev = evdev.InputDevice(path)
        caps = dev.capabilities()
        if evdev.ecodes.EV_REL in caps:
            rel = caps[evdev.ecodes.EV_REL]
            if evdev.ecodes.REL_X in rel and evdev.ecodes.REL_Y in rel:
                return dev
    raise RuntimeError("No relative-axis mouse found in /dev/input/event*")


def main() -> None:
    mouse = find_mouse()
    print(f"Mouse : {mouse.name}  ({mouse.path})")
    ser = serial.Serial(SERIAL_PORT, BAUD, timeout=0)
    print(f"Serial: {SERIAL_PORT} @ {BAUD} baud")
    print("Move mouse to rotate cube. Ctrl-C to quit.")

    # Initial orientation: 30° tilt around X (matches firmware default)
    q = quat_from_axis_angle(1.0, 0.0, 0.0, 0.5236)
    dx = 0.0
    dy = 0.0
    last_move    = time.monotonic()
    auto_rotating = False
    auto_dq      = quat_from_axis_angle(0.0, 1.0, 0.0, AUTO_ROTATE_SPEED)

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
                        q = apply_rotation(q, dx, dy)
                        ser.write(pack_packet(q))
                        dx = 0.0
                        dy = 0.0
                        last_move     = time.monotonic()
                        auto_rotating = False
        else:
            if not auto_rotating and time.monotonic() - last_move >= AUTO_ROTATE_DELAY:
                auto_rotating = True
            if auto_rotating:
                q = quat_normalize(quat_mul(auto_dq, q))
                ser.write(pack_packet(q))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
