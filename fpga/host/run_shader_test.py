# SPDX-FileCopyrightText: (c) 2025-2026 Andreas Wendleder
# SPDX-License-Identifier: CERN-OHL-S-2.0

# Host-side script for Borg GPU triangle rendering.
# Renders rotated triangles via PSRAM and reads back framebuffer.

import time
import struct
import math
import rp2
import machine
from machine import Pin, SPI

import run_tinyqv


# --- FP16 conversion ---

def fp16_to_float(bits):
    """Convert FP16 bits to Python float."""
    sign = (bits >> 15) & 1
    exp = (bits >> 10) & 0x1F
    frac = bits & 0x3FF
    if exp == 0:
        val = (frac / 1024.0) * (2 ** -14)
    elif exp == 31:
        val = float('inf') if frac == 0 else float('nan')
    else:
        val = (1.0 + frac / 1024.0) * (2 ** (exp - 15))
    return -val if sign else val


def float_to_fp16(f):
    """Convert Python float to FP16 bits."""
    if f == 0.0:
        return 0x0000
    sign = 0
    if f < 0:
        sign = 1
        f = -f
    if f >= 65504.0:
        return (sign << 15) | 0x7C00
    if f < 2.0**-24:
        return sign << 15
    if f < 2.0**-14:
        return (sign << 15) | int(f / 2.0**-14 * 1024 + 0.5)
    exp = 0
    tmp = f
    while tmp >= 2.0:
        tmp /= 2.0
        exp += 1
    while tmp < 1.0:
        tmp *= 2.0
        exp -= 1
    frac_bits = int((tmp - 1.0) * 1024 + 0.5)
    biased = exp + 15
    if frac_bits >= 1024:
        frac_bits = 0
        biased += 1
    if biased >= 31:
        return (sign << 15) | 0x7C00
    return (sign << 15) | (biased << 10) | frac_bits


# --- PIO QPI write ---

@rp2.asm_pio(autopush=True, push_thresh=8, in_shiftdir=rp2.PIO.SHIFT_LEFT,
             autopull=True, pull_thresh=8, out_shiftdir=rp2.PIO.SHIFT_RIGHT,
             out_init=(rp2.PIO.IN_HIGH, rp2.PIO.OUT_HIGH, rp2.PIO.OUT_HIGH, rp2.PIO.IN_HIGH,
                       rp2.PIO.IN_HIGH, rp2.PIO.IN_HIGH, rp2.PIO.OUT_HIGH, rp2.PIO.OUT_HIGH),
             sideset_init=(rp2.PIO.OUT_HIGH))
def qspi_write():
    out(x, 8).side(1)
    out(y, 8).side(1)
    out(pindirs, 8).side(1)
    
    label("cmd_loop")
    out(pins, 8).side(0)
    jmp(x_dec, "cmd_loop").side(1)
    
    label("data_loop")
    out(pins, 8).side(0)
    jmp(y_dec, "data_loop").side(1)
    
    out(pins, 8).side(1)
    out(pindirs, 8).side(1)


def qpi_write(sm, addr, data_bytes):
    """Format QPI write protocol and feed to PIO."""
    num_bytes = len(data_bytes)
    sm.put(8 - 1)
    sm.put(num_bytes * 2 - 1)
    sm.put(0b11111111)
    
    def qpi_nibble(n):
        return ((n & 8) << 4) | (1 << 6) | ((n & 4) << 3) | (0 << 4) | ((n & 1) << 3) | (1 << 1) | ((n & 2) >> 1)
        
    sm.put(qpi_nibble(0))
    sm.put(qpi_nibble(0x2))
    sm.put(qpi_nibble((addr >> 20) & 0xF))
    sm.put(qpi_nibble((addr >> 16) & 0xF))
    sm.put(qpi_nibble((addr >> 12) & 0xF))
    sm.put(qpi_nibble((addr >> 8) & 0xF))
    sm.put(qpi_nibble((addr >> 4) & 0xF))
    sm.put(qpi_nibble((addr >> 0) & 0xF))
    
    for b in data_bytes:
        sm.put(qpi_nibble((b >> 4) & 0xF))
        sm.put(qpi_nibble(b & 0xF))
        
    sm.put(0b11111111)
    sm.put(0b01010110)


def qpi_write_word(sm, addr, value):
    data = struct.pack('<I', value & 0xFFFFFFFF)
    qpi_write(sm, addr, data)


# --- PIO QPI read ---

PSRAM_IO_SPI_ADDR = 0x001000

@rp2.asm_pio(autopush=True, push_thresh=8, in_shiftdir=rp2.PIO.SHIFT_LEFT,
             autopull=True, pull_thresh=8, out_shiftdir=rp2.PIO.SHIFT_RIGHT,
             out_init=(rp2.PIO.IN_HIGH, rp2.PIO.OUT_HIGH, rp2.PIO.OUT_HIGH, rp2.PIO.IN_HIGH,
                       rp2.PIO.IN_HIGH, rp2.PIO.IN_HIGH, rp2.PIO.OUT_HIGH, rp2.PIO.OUT_HIGH),
             sideset_init=(rp2.PIO.OUT_HIGH))
def qspi_read():
    out(x, 8).side(1)
    out(y, 8).side(1)
    out(pindirs, 8).side(1)
    
    label("cmd_loop")
    out(pins, 8).side(0)
    jmp(x_dec, "cmd_loop").side(1)
    
    out(pindirs, 8).side(0)
    label("data_loop")
    in_(pins, 8).side(1)
    jmp(y_dec, "data_loop").side(0)
    
    out(pins, 8).side(1)
    out(pindirs, 8).side(1)


def qpi_read(sm, addr, num_bytes):
    """Format QPI read protocol and collect data from PIO."""
    buf = bytearray(num_bytes * 2 + 4)
    sm.put(8 - 1)
    sm.put(num_bytes * 2 + 4 - 1)
    sm.put(0b11111111)
    
    def qpi_nibble(n):
        return ((n & 8) << 4) | (1 << 6) | ((n & 4) << 3) | (0 << 4) | ((n & 1) << 3) | (1 << 1) | ((n & 2) >> 1)
        
    sm.put(qpi_nibble(0))
    sm.put(qpi_nibble(0xB))
    sm.put(qpi_nibble((addr >> 20) & 0xF))
    sm.put(qpi_nibble((addr >> 16) & 0xF))
    sm.put(qpi_nibble((addr >> 12) & 0xF))
    sm.put(qpi_nibble((addr >> 8) & 0xF))
    sm.put(qpi_nibble((addr >> 4) & 0xF))
    sm.put(qpi_nibble((addr >> 0) & 0xF))
    
    sm.put(0b01010110)
    
    for i in range(num_bytes * 2 + 4):
        buf[i] = sm.get()
        
    sm.put(0b11111111)
    sm.put(0b01010110)
    
    # Reassemble bytes from nibbles (skip 4 dummy nibbles)
    out_buf = bytearray(num_bytes)
    for i in range(num_bytes):
        h = buf[4 + i * 2]
        l = buf[4 + i * 2 + 1]
        hn = ((h >> 4) & 8) | ((h >> 3) & 4) | ((h << 1) & 2) | ((h >> 3) & 1)
        ln = ((l >> 4) & 8) | ((l >> 3) & 4) | ((l << 1) & 2) | ((l >> 3) & 1)
        out_buf[i] = (hn << 4) | ln
        
    return out_buf


def qpi_read_word(sm, addr):
    data = qpi_read(sm, addr, 4)
    return struct.unpack('<I', data)[0]


# --- Triangle rendering ---

def edge_fn(ax, ay, bx, by, px, py):
    """Signed area for point-in-triangle test."""
    return (bx - ax) * (py - ay) - (by - ay) * (px - ax)

# Vertex colors for barycentric interpolation (grayscale)
VTX_COLORS = [0.3, 0.5, 1.0]

WIDTH = HEIGHT = 16
_s = WIDTH * 0.3
TRI = [(0.0, -_s), (-_s, _s), (_s, _s)]


def write_ppm(filename, fb, w, h, sx=None, sy=None):
    """Write a PPM P3 image with barycentric color interpolation.
    If sx/sy (screen-space vertices) are provided, interpolate vertex colors.
    Otherwise treat fb values as FP16 grayscale."""
    with open(filename, 'w') as f:
        f.write("P3\n%d %d\n255\n" % (w, h))
        for y in range(h):
            for x in range(w):
                val = fb[y * w + x]
                if val and sx is not None:
                    px, py_ = x + 0.5, y + 0.5
                    e0 = edge_fn(sx[0], sy[0], sx[1], sy[1], px, py_)
                    e1 = edge_fn(sx[1], sy[1], sx[2], sy[2], px, py_)
                    e2 = edge_fn(sx[2], sy[2], sx[0], sy[0], px, py_)
                    total = e0 + e1 + e2

                    if abs(total) > 1e-6:
                        w0 = e1 / total
                        w1 = e2 / total
                        w2 = e0 / total
                        intensity = w0 * VTX_COLORS[0] + w1 * VTX_COLORS[1] + w2 * VTX_COLORS[2]
                        intensity = max(0.0, min(1.0, intensity))
                    else:
                        intensity = 0.5
                    c = int(intensity * 255 + 0.5)
                    f.write("%d %d %d " % (c, c, c))
                elif val:
                    intensity = fp16_to_float(val)
                    c = max(0, min(255, int(intensity * 255 + 0.5)))
                    f.write("%d %d %d " % (c, c, c))
                else:
                    f.write("0 0 0 ")
            f.write("\n")


def render_frame(frame):
    """Render a single frame of the rotating triangle using Borg hardware."""
    angle = frame * 36.0 * 3.14159265 / 180.0
    cos_a = math.cos(angle)
    sin_a = math.sin(angle)

    cos_fp = float_to_fp16(cos_a)
    nsin_fp = float_to_fp16(-sin_a)
    sin_fp = float_to_fp16(sin_a)

    # --- Write vertices + trig values to PSRAM ---
    run_tinyqv.setup_ram()
    sm_w = rp2.StateMachine(0, qspi_write, 16_000_000,
                            out_base=Pin(0), sideset_base=Pin(2))
    sm_w.active(1)

    qpi_write_word(sm_w, PSRAM_IO_SPI_ADDR, cos_fp)
    for vi, (vx, vy) in enumerate(TRI):
        base = PSRAM_IO_SPI_ADDR + (1 + vi * 2) * 4
        qpi_write_word(sm_w, base + 0, float_to_fp16(vx))
        qpi_write_word(sm_w, base + 4, float_to_fp16(vy))
    qpi_write_word(sm_w, PSRAM_IO_SPI_ADDR + 7 * 4, sin_fp)
    qpi_write_word(sm_w, PSRAM_IO_SPI_ADDR + 8 * 4, nsin_fp)

    for i in range(32, 288):
        qpi_write_word(sm_w, PSRAM_IO_SPI_ADDR + i * 4, 0)

    sm_w.active(0)
    del sm_w

    # --- Boot FPGA ---
    ice_creset_b = machine.Pin(27, machine.Pin.OUT)
    ice_done = machine.Pin(26, machine.Pin.IN)
    time.sleep_us(10)
    ice_creset_b.value(1)

    while ice_done.value() == 0:
        time.sleep(0.001)

    rst_n = Pin(12, Pin.OUT)
    clk = Pin(24, Pin.OUT)
    clk.off()
    rst_n.on()
    time.sleep(0.001)
    rst_n.off()

    clk.on()
    time.sleep(0.001)
    clk.off()
    time.sleep(0.001)

    flash_sel = Pin(1, Pin.OUT)
    qspi_sd0 = Pin(3, Pin.OUT)
    qspi_sd1 = Pin(0, Pin.OUT)
    qspi_sd2 = Pin(5, Pin.OUT)
    ram_a_sel = Pin(4, Pin.OUT)
    ram_b_sel = Pin(6, Pin.OUT)

    flash_sel.on()
    ram_a_sel.on()
    ram_b_sel.on()
    qspi_sd0.on()
    qspi_sd1.off()
    qspi_sd2.off()

    for i in range(10):
        clk.off()
        time.sleep(0.001)
        clk.on()
        time.sleep(0.001)

    Pin(1, Pin.IN, pull=Pin.PULL_UP)
    Pin(2, Pin.IN, pull=Pin.PULL_DOWN)
    Pin(3, Pin.IN, pull=None)
    Pin(0, Pin.IN, pull=None)
    Pin(4, Pin.IN, pull=Pin.PULL_UP)
    Pin(5, Pin.IN, pull=None)
    Pin(6, Pin.IN, pull=Pin.PULL_UP)
    Pin(7, Pin.IN, pull=None)

    rst_n.on()
    time.sleep(0.001)
    clk.off()

    _clk = machine.PWM(Pin(24), freq=4_000_000, duty_u16=32768)
    time.sleep(2)

    # --- Stop and reset FPGA ---
    _clk.deinit()
    Pin(24, Pin.IN, pull=Pin.PULL_DOWN)
    rst_n = Pin(12, Pin.OUT)
    rst_n.off()
    time.sleep(0.001)
    ice_creset_b.value(0)
    time.sleep(0.01)
    for p in [0, 1, 2, 3, 4, 5, 6, 7]:
        Pin(p, Pin.IN, pull=None)
    Pin(12, Pin.IN, pull=Pin.PULL_DOWN)

    run_tinyqv.setup_ram()

    # --- Read framebuffer from PSRAM ---
    sm_r = rp2.StateMachine(0, qspi_read, 16_000_000,
                            in_base=Pin(0), out_base=Pin(0),
                            sideset_base=Pin(2))
    sm_r.active(1)

    out_base = PSRAM_IO_SPI_ADDR + 128
    
    done = qpi_read_word(sm_r, out_base + 288 * 4)
    print("Done marker: 0x%04X" % done)

    fb = [0] * (WIDTH * HEIGHT)
    for py in range(HEIGHT):
        for px in range(WIDTH):
            val = qpi_read_word(sm_r, out_base + (32 + py * WIDTH + px) * 4) & 0xFFFF
            fb[py * WIDTH + px] = val

    # Read screen-space vertices for barycentric interpolation
    sx_verts = [0.0] * 3
    sy_verts = [0.0] * 3
    for v in range(3):
        sx_bits = qpi_read_word(sm_r, out_base + (16 + v * 2) * 4) & 0xFFFF
        sy_bits = qpi_read_word(sm_r, out_base + (16 + v * 2 + 1) * 4) & 0xFFFF
        sx_verts[v] = fp16_to_float(sx_bits)
        sy_verts[v] = fp16_to_float(sy_bits)

    sm_r.active(0)
    del sm_r

    # --- Write PPM ---
    fname = "/remote/triangle_%02d.ppm" % frame
    write_ppm(fname, fb, WIDTH, HEIGHT, sx_verts, sy_verts)
    print("Frame %02d (%.0f deg): %s" % (frame, frame * 36.0, fname))


def render_frames():
    """Render 10 frames of a rotating triangle."""
    print("\n--- Rendering 10 triangle frames ---")
    for frame in range(10):
        render_frame(frame)
    print("All frames rendered.")


def run_single_frame(frame=0):
    """Render a single frame (called by 'make triangle_ppm')."""
    print("\n--- Rendering triangle frame %d ---" % frame)
    render_frame(frame)
    print("Frame rendered.")
