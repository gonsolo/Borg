#!/usr/bin/env python3
"""Diagnostic: stream a static 0xAD MVP while reading the firmware's UART probe
output on the same port. Firmware debug chars (see borg_vkcube.c):
  'G' = full 0xAD packet, checksum OK (have_mvp set)
  'X' = full 0xAD packet, checksum MISMATCH
  '?' = 0xAD packet started but timed out mid-assembly (bytes dropped/split)
  (none of these among the cycle reports => 0xAD marker never even locked)
"""
import sys, math, struct, time, serial

PORT = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB0"
SXY, SZ, TZ = 0.5, 0.25, 0.5

# Static 30deg X-tilt rotation (firmware default), as column-major MVP = TS·R.
a = 0.5236
c, s = math.cos(a), math.sin(a)
m = (1,0,0,  0,c,s,  0,-s,c)          # column-major 3x3
r = [m[0],m[1],m[2],0, m[3],m[4],m[5],0, m[6],m[7],m[8],0, 0,0,0,1]
mvp = [0.0]*16
for col in range(4):
    mvp[col*4+0] = SXY*r[col*4+0]
    mvp[col*4+1] = SXY*r[col*4+1]
    mvp[col*4+2] = SZ*r[col*4+2] + TZ*r[col*4+3]
    mvp[col*4+3] = r[col*4+3]
payload = struct.pack("<16f", *mvp)
csum = 0
for b in payload: csum ^= b
PKT = bytes([0xAD]) + payload + bytes([csum])
print(f"MVP={[round(x,3) for x in mvp]}  csum=0x{csum:02x}", flush=True)

ser = serial.Serial()
ser.port = PORT; ser.baudrate = 115200; ser.timeout = 0
ser.dtr = False; ser.rts = False
ser.open()

t_send = 0.0
while True:
    now = time.monotonic()
    if now - t_send >= 0.012:          # ~83 pkt/s, under line rate
        try: ser.write(PKT)
        except Exception as e: print(f"[send err {e}]", flush=True)
        t_send = now
    data = ser.read(256)
    if data:
        sys.stdout.write(data.decode("latin1"))
        sys.stdout.flush()
    time.sleep(0.001)
