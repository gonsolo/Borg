#!/usr/bin/env python3
"""
Send a known-good 4x4 MVP to vkcube on the ULX3S over serial — exercises the
firmware's 0xAD full-MVP path (borg_vkcube.c) independently of the borgvk Mesa
driver and WSI.

Packet (matches mesa/src/borg/vulkan/borgvk_serial.c):
  byte 0      : 0xAD marker
  bytes 1..64 : 16 little-endian float32, column-major mvp[4][4]
  byte 65     : XOR checksum of bytes 1..64

The "known-good" MVP deliberately mirrors the firmware's OWN projection so this
test is decoupled from cube.c's NDC/handedness/FOV conventions:

    MVP = TS · R,   TS = scale(0.5, 0.5, 0.25) · translate_z(0.5)

i.e. exactly what the firmware bakes for a 0xAC rotation packet. If the cube
appears and spins, the 0xAD decode + direct-uniforms path works; the remaining
unknown is then only whether cube.c's real MVP uses the same conventions.

Usage:
  python3 scripts/borgvk_mvp_test.py [/dev/ttyUSB0] [--static]
    (default: slow Y-spin from a 30 deg X tilt; --static holds the tilt)
"""

import sys
import math
import struct
import time
import serial

PORT  = "/dev/ttyUSB0"
BAUD  = 115200
RATE  = 0.6      # rad/s spin
# 66-byte packets at 115200 baud cap at ~174/s (11.52 KB/s line rate); stay well
# under that so the FTDI TX buffer never backs up (overdriving it throws EIO and
# can drop the port).  ~83/s = 5.5 KB/s is still far denser than the firmware's
# few-fps drain, so a packet is always waiting at the frame boundary.
TICK  = 0.012
STATIC = "--static" in sys.argv
for a in sys.argv[1:]:
    if a.startswith("/dev"):
        PORT = a

# Firmware projection constants (software/borg/borg_vkcube.c).
SXY, SZ, TZ = 0.5, 0.25, 0.5


def quat_from_axis_angle(ax, ay, az, ang):
    s, c = math.sin(ang * 0.5), math.cos(ang * 0.5)
    return (c, ax * s, ay * s, az * s)


def quat_mul(a, b):
    aw, ax, ay, az = a
    bw, bx, by, bz = b
    return (aw*bw - ax*bx - ay*by - az*bz,
            aw*bx + ax*bw + ay*bz - az*by,
            aw*by - ax*bz + ay*bw + az*bx,
            aw*bz + ax*by - ay*bx + az*bw)


def quat_normalize(q):
    w, x, y, z = q
    n = math.sqrt(w*w + x*x + y*y + z*z)
    return (w/n, x/n, y/n, z/n)


def rot_cols(q):
    """Column-major 3x3 rotation as a 9-tuple (same layout the firmware uses)."""
    w, x, y, z = q
    return (1-2*(y*y+z*z), 2*(x*y+w*z),   2*(x*z-w*y),    # col 0
            2*(x*y-w*z),   1-2*(x*x+z*z), 2*(y*z+w*x),    # col 1
            2*(x*z+w*y),   2*(y*z-w*x),   1-2*(x*x+y*y))  # col 2


def mvp_from_rot(m):
    """MVP = TS · R, column-major 16-tuple — mirrors firmware mat4_mul_ts()."""
    # R as column-major 4x4: cols 0..2 are the 3x3 (w=0), col 3 = (0,0,0,1).
    r = [m[0], m[1], m[2], 0.0,
         m[3], m[4], m[5], 0.0,
         m[6], m[7], m[8], 0.0,
         0.0,  0.0,  0.0,  1.0]
    out = [0.0] * 16
    for col in range(4):
        out[col*4 + 0] = SXY * r[col*4 + 0]
        out[col*4 + 1] = SXY * r[col*4 + 1]
        out[col*4 + 2] = SZ * r[col*4 + 2] + TZ * r[col*4 + 3]
        out[col*4 + 3] = r[col*4 + 3]
    return out


def pack(mvp):
    payload = struct.pack("<16f", *mvp)
    csum = 0
    for b in payload:
        csum ^= b
    return bytes([0xAD]) + payload + bytes([csum])


def open_port():
    # Don't toggle DTR/RTS on open (avoids nudging the FTDI / re-enumeration).
    s = serial.Serial()
    s.port = PORT
    s.baudrate = BAUD
    s.timeout = 0
    s.write_timeout = 1
    s.dtr = False
    s.rts = False
    s.open()
    return s


def main():
    print(f"Serial: {PORT} @ {BAUD} — sending 0xAD MVP packets "
          f"({'static 30 deg tilt' if STATIC else 'spinning'}). Ctrl-C to quit.")
    ser = open_port()

    # Start at the firmware's default 30 deg X tilt.
    q = quat_from_axis_angle(1.0, 0.0, 0.0, 0.5236)
    last = time.monotonic()
    sent = 0
    while True:
        now = time.monotonic()
        if not STATIC:
            dt = now - last
            last = now
            dq = quat_from_axis_angle(0.0, 1.0, 0.0, RATE * dt)
            q = quat_normalize(quat_mul(dq, q))
        try:
            ser.write(pack(mvp_from_rot(rot_cols(q))))
        except (serial.SerialException, OSError) as e:
            # Transient FTDI drop — reopen and keep going so the stream survives.
            print(f"  serial drop ({e}); reconnecting…")
            try:
                ser.close()
            except Exception:
                pass
            time.sleep(0.4)
            try:
                ser = open_port()
            except Exception:
                pass
            continue
        sent += 1
        if sent % 200 == 0:
            print(f"  {sent} packets sent")
        time.sleep(TICK)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print()
