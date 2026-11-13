#!/usr/bin/env python3
"""Load a binary into the Nexys DDR3 over the USB link (NexysLinuxTop) and start Hutt.

    hutt_run.py image.bin [--at ADDR] [--peek ADDR LEN] [--wait SECONDS]
"""
import argparse, os, struct, subprocess, time

p = argparse.ArgumentParser()
p.add_argument("image")
p.add_argument("--at", type=lambda s: int(s, 0), default=0)
p.add_argument("--peek", nargs=2, type=lambda s: int(s, 0))
p.add_argument("--wait", type=float, default=1.0)
a = p.parse_args()

ftdi = os.path.join(os.path.dirname(os.path.abspath(__file__)), "borg_ftdi")
link = subprocess.Popen([ftdi], stdin=subprocess.PIPE, stdout=subprocess.PIPE)


def send(b):
    link.stdin.write(b)
    link.stdin.flush()


def run(on):
    send(b"W" + struct.pack("<III", 1, 0, int(on)))


def read(addr, n):
    send(b"m" + struct.pack("<II", addr, n))
    return link.stdout.read(n)


data = open(a.image, "rb").read()
data += bytes(-len(data) % 4)
run(False)
t = time.time()
send(b"M" + struct.pack("<II", a.at, len(data)) + data)
assert read(a.at, len(data)) == data, "read-back differs"
print(f"loaded {len(data)} bytes at {a.at:#x} in {time.time() - t:.2f} s")
run(True)
if a.peek:
    time.sleep(a.wait)
    print(read(*a.peek))
link.stdin.close()
link.wait()
