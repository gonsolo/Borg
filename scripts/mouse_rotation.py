#!/usr/bin/env python3
"""
Send mouse rotation to vkcube running on ULX3S via UART.

Protocol: 37-byte packets  [0xAC] [m00..m22: 9 × 4-byte LE float]
  Column-major 3×3 rotation matrix derived from a unit quaternion (the FPGA
  CPU has no soft-float, so the matrix is computed here).  The firmware
  rejects any packet with an out-of-range entry (corruption/mis-sync guard).
  Mouse X rotates around world Y; mouse Y rotates around the current right
  vector (trackball — no gimbal lock).

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

DEBUG              = "--debug" in sys.argv
SERIAL_PORT        = next((a for a in sys.argv[1:] if not a.startswith("-")), "/dev/ttyUSB0")
BAUD               = 115200
SENSITIVITY        = 0.005   # radians per raw count (mouse); touchpad uses same but ABS range is ~5000 units
AUTO_ROTATE_DELAY  = 5.0     # seconds of inactivity before auto-rotate
AUTO_ROTATE_RATE   = 0.6     # radians/second (~1 rev / 10 s), time-based
# Tick fast during auto-rotate so packets stream densely (every ~4 ms).  The
# firmware drains UART once per frame with only a ~12 ms catch window; a sparse
# (50 ms) sender phase-locks into that window's gaps and stalls.  A dense stream
# guarantees a 0xAC is always in flight when the firmware drains.
SELECT_TIMEOUT     = 0.004   # seconds between poll/auto-rotate ticks


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
    # [0xAC][m00..m22] — firmware sanity-checks each entry and uses the matrix directly.
    return struct.pack("<B9f", 0xAC, *quat_to_col_mat3(q))


def find_input_device():
    """Return (device, is_touchpad).

    Prefer a genuine external mouse (REL axes, no sibling Touchpad device).
    Fall back to an ABS multitouch touchpad.  Touchpad-emulation "Mouse"
    nodes share a base name with a "Touchpad" node — we skip those because
    their REL stream doesn't fire from normal finger movement.
    """
    all_devs = [evdev.InputDevice(p) for p in evdev.list_devices()]
    touchpad_bases = {
        dev.name.replace(" Touchpad", "").replace(" touchpad", "")
        for dev in all_devs
        if "touchpad" in dev.name.lower()
    }

    touchpad = None
    for dev in all_devs:
        caps = dev.capabilities()
        if evdev.ecodes.EV_REL in caps:
            rel = caps[evdev.ecodes.EV_REL]
            if evdev.ecodes.REL_X in rel and evdev.ecodes.REL_Y in rel:
                base = dev.name.replace(" Mouse", "").replace(" mouse", "")
                if base not in touchpad_bases:
                    return dev, False      # genuine external mouse
        if evdev.ecodes.EV_ABS in caps and touchpad is None:
            abs_axes = caps[evdev.ecodes.EV_ABS]
            codes = [a[0] if isinstance(a, tuple) else a for a in abs_axes]
            if evdev.ecodes.ABS_MT_POSITION_X in codes and evdev.ecodes.ABS_MT_POSITION_Y in codes:
                touchpad = dev
    if touchpad is not None:
        return touchpad, True
    raise RuntimeError("No mouse or touchpad found in /dev/input/event*")


def main() -> None:
    device, is_touchpad = find_input_device()
    device.grab()
    kind = "Touchpad" if is_touchpad else "Mouse"
    print(f"{kind} : {device.name}  ({device.path})")
    if DEBUG:
        print("DEBUG mode — printing raw events, not sending to serial")
        for event in device.read_loop():
            if event.type != evdev.ecodes.EV_SYN:
                print(evdev.categorize(event))
        return
    ser = serial.Serial(SERIAL_PORT, BAUD, timeout=0)
    print(f"Serial: {SERIAL_PORT} @ {BAUD} baud")
    print("Move mouse/finger to rotate cube. Ctrl-C to quit.")

    # Initial orientation: 30° tilt around X (matches firmware default)
    q = quat_from_axis_angle(1.0, 0.0, 0.0, 0.5236)
    dx = 0.0
    dy = 0.0
    last_move     = time.monotonic()
    auto_rotating = False
    last_auto     = last_move

    # Touchpad absolute-position tracking
    tp_x: float | None = None
    tp_y: float | None = None
    pending_x: float | None = None
    pending_y: float | None = None

    while True:
        readable, _, _ = select.select([device.fd], [], [], SELECT_TIMEOUT)

        if readable:
            for event in device.read():
                if not is_touchpad:
                    # External mouse: read REL events directly
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
                    # Touchpad: multitouch protocol (ABS_MT_POSITION_X/Y).
                    # The tracking ID fires only on lift (value -1) — reset
                    # last position then so the next touch has no jump.
                    if event.type == evdev.ecodes.EV_ABS:
                        if event.code == evdev.ecodes.ABS_MT_TRACKING_ID and event.value == -1:
                            tp_x = None
                            tp_y = None
                        elif event.code == evdev.ecodes.ABS_MT_POSITION_X:
                            pending_x = event.value
                        elif event.code == evdev.ecodes.ABS_MT_POSITION_Y:
                            pending_y = event.value
                    elif event.type == evdev.ecodes.EV_SYN and event.code == evdev.ecodes.SYN_REPORT:
                        if pending_x is not None and tp_x is not None:
                            dx += pending_x - tp_x
                        if pending_y is not None and tp_y is not None:
                            dy += pending_y - tp_y
                        if pending_x is not None:
                            tp_x = pending_x
                        if pending_y is not None:
                            tp_y = pending_y
                        pending_x = None
                        pending_y = None
                        if dx != 0.0 or dy != 0.0:
                            q = apply_rotation(q, dx, dy)
                            ser.write(pack_packet(q))
                            dx = 0.0
                            dy = 0.0
                            last_move     = time.monotonic()
                            auto_rotating = False
        else:
            now = time.monotonic()
            if not auto_rotating and now - last_move >= AUTO_ROTATE_DELAY:
                auto_rotating = True
                last_auto     = now
            if auto_rotating:
                # Time-based step keeps the spin speed constant regardless of
                # tick jitter; the dense tick keeps the UART stream continuous.
                dt = now - last_auto
                last_auto = now
                dq = quat_from_axis_angle(0.0, 1.0, 0.0, AUTO_ROTATE_RATE * dt)
                q = quat_normalize(quat_mul(dq, q))
                ser.write(pack_packet(q))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
