# SPDX-FileCopyrightText: (c) 2025-2026 Andreas Wendleder
# SPDX-License-Identifier: CERN-OHL-S-2.0

# Host-side script for PSRAM round-trip Borg rotation test.
# One-way test: firmware writes results to PSRAM, host reads them.
# Uses PIO-based QPI read (no exit sequence needed).

import time
import struct
import rp2
import machine
from machine import Pin, SPI

import run_tinyqv

# --- Test cases ---
TEST_CASES = [
    ("0",          1.0, 0.0),
    ("pi/2",       1.0, 0.0),
    ("pi/4",       1.0, 1.0),
    ("pi (180)",   1.0, 0.0),
    ("0 (2,0.5)",  2.0, 0.5),
]

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

# This PIO program handles high-speed timing to transfer data from the workstation into the PSRAM on the PMOD.
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

# This function formats the Quad-SPI write protocol (command and address) and feeds it to the PIO hardware engine.
def qpi_write(sm, addr, data_bytes):
    # Setup the PIO state machine for a Write (0x02) in QPI mode
    num_bytes = len(data_bytes)
    
    # x = 8 cmd nibbles - 1
    sm.put(8 - 1)
    # y = data nibbles - 1
    sm.put(num_bytes * 2 - 1)
    
    # pindirs
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

# PSRAM input/output address: 0x01001000 CPU = 0x001000 SPI
PSRAM_IO_SPI_ADDR = 0x001000

# This PIO program handles high-speed timing to read data from the PSRAM back to the workstation.
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

# This function formats the Quad-SPI read protocol (command and address) and collects data from the PIO hardware engine.
def qpi_read(sm, addr, num_bytes):
    # Setup the PIO state machine for a Fast Read (0x0B) in QPI mode
    buf = bytearray(num_bytes * 2 + 4)
    
    # x = 8 cmd nibbles - 1
    sm.put(8 - 1)
    # y = data nibbles + dummy nibbles - 1 (2 data nibbles/byte + 4 dummy nibbles)
    sm.put(num_bytes * 2 + 4 - 1)
    
    # pindirs
    sm.put(0b11111111) 
    
    # 8 Cmd nibbles (Fast Read 0x0B + 24-bit addr)
    # Each byte to PIO sets the 8 pins.
    # Pins: SD3(7), RAMB(6), SD2(5), RAMA(4), SD0(3), SCK(2-sideset), CS(1), SD1(0)
    # We want RAM_A_SEL(4)=0, CS(1)=1
    
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
    
    # pindirs for read
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
        
        # Pins: SD3(7), RAMB(6), SD2(5), RAMA(4), SD0(3), SCK(2-sideset), CS(1), SD1(0)
        # Extract data bits:
        # bit 3 of nibble = SD3 (pin 7)
        # bit 2 of nibble = SD2 (pin 5)
        # bit 1 of nibble = SD1 (pin 0) -> !! Wait, original code had SD1 on pin 0
        # bit 0 of nibble = SD0 (pin 3)
        hn = ((h >> 4) & 8) | ((h >> 3) & 4) | ((h << 1) & 2) | ((h >> 3) & 1)
        ln = ((l >> 4) & 8) | ((l >> 3) & 4) | ((l << 1) & 2) | ((l >> 3) & 1)
        
        out_buf[i] = (hn << 4) | ln
        
    return out_buf


def qpi_read_word(sm, addr):
    data = qpi_read(sm, addr, 4)
    return struct.unpack('<I', data)[0]


def run():
    # We need to manually do the run_tinyqv setup steps to inject our SPI write
    machine.freq(112_000_000)

    for i in range(30):
        Pin(i, Pin.IN, pull=None)

    flash_sel = Pin(17, Pin.IN, Pin.PULL_UP)
    ice_creset_b = machine.Pin(27, machine.Pin.OUT)
    ice_creset_b.value(0)
    
    run_tinyqv.program_firmware.program('firmware/borg_psram.bin')
    run_tinyqv.setup_flash()

    run_tinyqv.setup_ram()

    # Initialize PIO for QPI writes (no FPGA clock needed - PIO drives SPI bus directly)
    sm = rp2.StateMachine(0, qspi_write, 16_000_000, out_base=Pin(0), sideset_base=Pin(2))
    sm.active(1)

    n_tests = len(TEST_CASES)
    qpi_write_word(sm, PSRAM_IO_SPI_ADDR, n_tests)

    for i, (label, x_f, y_f) in enumerate(TEST_CASES):
        if i == 0:   vals = (0x3C00, 0x3C00, 0x8000, 0x0000, 0x0000)
        elif i == 1: vals = (0x0000, 0x3C00, 0xBC00, 0x3C00, 0x0000)
        elif i == 2: vals = (0x39A8, 0x3C00, 0xB9A8, 0x39A8, 0x3C00)
        elif i == 3: vals = (0xBC00, 0x3C00, 0x0000, 0x0000, 0x0000)
        elif i == 4: vals = (0x3C00, 0x4000, 0x8000, 0x0000, 0x3800)

        base_addr = PSRAM_IO_SPI_ADDR + (1 + i * 5) * 4
        qpi_write_word(sm, base_addr + 0,  vals[0])
        qpi_write_word(sm, base_addr + 4,  vals[1])
        qpi_write_word(sm, base_addr + 8,  vals[2])
        qpi_write_word(sm, base_addr + 12, vals[3])
        qpi_write_word(sm, base_addr + 16, vals[4])

    print("PSRAM input data written via QPI PIO.")
    sm.active(0)
    del sm

    # Now boot the FPGA
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
    qspi_sd0  = Pin(3, Pin.OUT)
    qspi_sd1  = Pin(0, Pin.OUT)
    qspi_sd2  = Pin(5, Pin.OUT)
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

    # Start FPGA clock
    global _clk
    _clk = machine.PWM(Pin(24), freq=4_000_000, duty_u16=32768)

    time.sleep(1)
    # Stop clock and assert FPGA reset to release the QSPI bus
    _clk.deinit()
    Pin(24, Pin.IN, pull=Pin.PULL_DOWN)
    rst_n = Pin(12, Pin.OUT)
    rst_n.off()
    time.sleep(0.001)

    # Put the FPGA into reset (tri-state its outputs)
    ice_creset_b = machine.Pin(27, machine.Pin.OUT)
    ice_creset_b.value(0)
    time.sleep(0.01)

    # Release all QSPI pins before re-initializing PSRAM
    for p in [0, 1, 2, 3, 4, 5, 6, 7]:
        Pin(p, Pin.IN, pull=None)
    Pin(12, Pin.IN, pull=Pin.PULL_DOWN)

    # Re-initialize PSRAM into QPI mode so PIO reads work reliably
    run_tinyqv.setup_ram()

    # Initialize PIO for QPI reads
    sm = rp2.StateMachine(0, qspi_read, 16_000_000, in_base=Pin(0), out_base=Pin(0), sideset_base=Pin(2))
    sm.active(1)

    n_tests = len(TEST_CASES)

    # Output starts at +128 bytes from PSRAM_IO_SPI_ADDR (matching firmware PSRAM_RESULT)
    out_base = PSRAM_IO_SPI_ADDR + 128
    
    done = qpi_read_word(sm, out_base + n_tests * 8)
    if done != 0xDEAD:
        print("WARNING: Done marker not found (got 0x%08X, expected 0xDEAD)" % done)

    print("\n--- DEBUG: Host checking PSRAM inputs via QPI ---")
    r_n = qpi_read_word(sm, PSRAM_IO_SPI_ADDR)
    print("N_TESTS at %06X = 0x%08X" % (PSRAM_IO_SPI_ADDR, r_n))
    for j in range(5):
        base_addr = PSRAM_IO_SPI_ADDR + (1 + j * 5) * 4
        print("T%d cos at %06X = 0x%08X" % (j, base_addr, qpi_read_word(sm, base_addr)))
    print("-------------------------------------------------")

    print()
    print("  +================+=============+====================+")
    print("  | Input (x, y)   | Angle       | Output (rx, ry)    |")
    print("  +================+=============+====================+")

    for i, (label, x_f, y_f) in enumerate(TEST_CASES):
        rx_bits = qpi_read_word(sm, out_base + i * 8) & 0xFFFF
        ry_bits = qpi_read_word(sm, out_base + i * 8 + 4) & 0xFFFF
        rx = fp16_to_float(rx_bits)
        ry = fp16_to_float(ry_bits)

        print("  | (%5.1f,%5.1f)  | %-11s | (%7.3f,%7.3f)  |" % (x_f, y_f, label, rx, ry))

    print("  +================+=============+====================+")
    print()
    print("Done!")

    sm.active(0)

    # --- Triangle rotation demo ---
    render_frames()


# --- Triangle rasterization helpers ---

import math

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


def edge_fn(ax, ay, bx, by, px, py):
    """Signed area for point-in-triangle test."""
    return (bx - ax) * (py - ay) - (by - ay) * (px - ax)


def write_ppm(filename, fb, w, h):
    """Write a PPM P3 image."""
    with open(filename, 'w') as f:
        f.write("P3\n%d %d\n255\n" % (w, h))
        for y in range(h):
            for x in range(w):
                if fb[y * w + x]:
                    f.write("255 255 255 ")
                else:
                    f.write("0 0 0 ")
            f.write("\n")


# --- Triangle rendering constants ---
WIDTH = HEIGHT = 16
# Triangle vertices centered at origin, scaled to 60% of half-width
_s = WIDTH * 0.3
TRI = [(0.0, -_s), (-_s, _s), (_s, _s)]


def render_frame(frame):
    """Render a single frame of the rotating triangle using Borg hardware."""
    angle = frame * 36.0 * 3.14159265 / 180.0
    cos_a = math.cos(angle)
    sin_a = math.sin(angle)

    cos_fp = float_to_fp16(cos_a)
    nsin_fp = float_to_fp16(-sin_a)
    sin_fp = float_to_fp16(sin_a)

    # --- Write 3 vertices to PSRAM ---
    run_tinyqv.setup_ram()
    sm_w = rp2.StateMachine(0, qspi_write, 16_000_000,
                            out_base=Pin(0), sideset_base=Pin(2))
    sm_w.active(1)

    qpi_write_word(sm_w, PSRAM_IO_SPI_ADDR, float_to_fp16(angle))
    for vi, (vx, vy) in enumerate(TRI):
        base = PSRAM_IO_SPI_ADDR + (1 + vi * 2) * 4
        qpi_write_word(sm_w, base + 0, float_to_fp16(vx))
        qpi_write_word(sm_w, base + 4, float_to_fp16(vy))

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

    # --- Read 3 rotated vertices ---
    sm_r = rp2.StateMachine(0, qspi_read, 16_000_000,
                            in_base=Pin(0), out_base=Pin(0),
                            sideset_base=Pin(2))
    sm_r.active(1)

    out_base = PSRAM_IO_SPI_ADDR + 128
    
    # Read DONE marker
    done = qpi_read_word(sm_r, out_base + 288 * 4)
    print("Done marker: 0x%04X" % done)

    # --- Read hardware rasterizer framebuffer ---
    fb = bytearray(WIDTH * HEIGHT)
    for py in range(HEIGHT):
        for px in range(WIDTH):
            val = qpi_read_word(sm_r, out_base + (32 + py * WIDTH + px) * 4) & 0xFF
            fb[py * WIDTH + px] = val

    sm_r.active(0)
    del sm_r

    # --- Write PPM ---
    fname = "/remote/triangle_%02d.ppm" % frame
    write_ppm(fname, fb, WIDTH, HEIGHT)
    print("Frame %02d (%.0f deg): %s" % (frame, frame * 36.0, fname))


def render_frames():
    """Render 10 frames of a rotating triangle using Borg hardware."""
    print("\n--- Rendering 10 triangle frames ---")
    for frame in range(10):
        render_frame(frame)
    print("All frames rendered.")


def run_animation():
    """Entry point for rendering all 10 frames."""
    machine.freq(112_000_000)

    for i in range(30):
        Pin(i, Pin.IN, pull=None)

    flash_sel = Pin(17, Pin.IN, Pin.PULL_UP)
    ice_creset_b = machine.Pin(27, machine.Pin.OUT)
    ice_creset_b.value(0)

    run_tinyqv.program_firmware.program('firmware/borg_psram.bin')
    run_tinyqv.setup_flash()

    print("\n--- Rendering 10 triangle frames ---")
    for frame in range(10):
        run_tinyqv.setup_ram()
        render_frame(frame)
    print("All frames rendered.")

run_animation()
