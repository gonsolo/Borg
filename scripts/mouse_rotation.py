#!/usr/bin/env python3
"""
Feed mouse/touchpad rotation into vkcube running on the ULX3S, via a shared
file read by the borgvk DRM shim (mesa/src/borg/drm/borg_shim.c) — NOT over
UART.  cube.c free-spins on its own internal timer with no input hooks, and
the firmware now always renders host-supplied geometry with a host-supplied
full MVP (no local model to swap a rotation into), so there is no meaningful
firmware-side packet for this anymore (the old 0xAC packet was retired along
with the hardcoded-geometry TS-bake it depended on).  Instead this script
writes its column-major 3×3 rotation matrix (9 × 4-byte LE float, no marker
byte) to MOUSE_ROTATION_PATH; borg_shim.c right-multiplies it into cube.c's
already-computed MVP right before shipping the 0xAD serial packet, so it
layers a trackball nudge on top of (rather than replacing) cube's auto-spin.
Written via write-to-temp + rename so the shim never sees a torn write.

Mouse X rotates around world Y; mouse Y rotates around the current right
vector (trackball — no gimbal lock).

Requirements:
  pip install evdev
  sudo usermod -aG input $USER   (then re-login, or run with sudo)

Usage:
  python3 scripts/mouse_rotation.py
"""

import os
import sys
import math
import select
import struct
import tempfile
import time
import evdev

DEBUG              = "--debug" in sys.argv
MOUSE_ROTATION_PATH = "/tmp/borg_mouse_rotation.bin"
SENSITIVITY        = 0.005   # radians per raw count (mouse); touchpad uses same but ABS range is ~5000 units
AUTO_ROTATE_DELAY  = 5.0     # seconds of inactivity before auto-rotate
AUTO_ROTATE_RATE   = 0.6     # radians/second (~1 rev / 10 s), time-based
# Tick fast so auto-rotate steps and the on-disk rotation file both stay
# smooth/responsive; the shim just reads whatever is most recently written.
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

def write_rotation(q):
    # Write-to-temp + rename so borg_shim.c (read_mouse_rotation()) never
    # observes a torn write — rename() is atomic within the same directory.
    data = struct.pack("<9f", *quat_to_col_mat3(q))
    d = os.path.dirname(MOUSE_ROTATION_PATH) or "."
    fd, tmp_path = tempfile.mkstemp(dir=d)
    try:
        os.write(fd, data)
    finally:
        os.close(fd)
    os.rename(tmp_path, MOUSE_ROTATION_PATH)


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
        print("DEBUG mode — printing raw events, not writing the rotation file")
        for event in device.read_loop():
            if event.type != evdev.ecodes.EV_SYN:
                print(evdev.categorize(event))
        return
    print(f"Rotation file: {MOUSE_ROTATION_PATH} (read by borg_shim.c)")
    print("Move mouse/finger to rotate cube. Requires run-vkcube.sh to be running. Ctrl-C to quit.")

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
                            write_rotation(q)
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
                            write_rotation(q)
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
                # Time-based step keeps the spin speed constant regardless of tick jitter.
                dt = now - last_auto
                last_auto = now
                dq = quat_from_axis_angle(0.0, 1.0, 0.0, AUTO_ROTATE_RATE * dt)
                q = quat_normalize(quat_mul(dq, q))
                write_rotation(q)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
