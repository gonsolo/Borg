#!/usr/bin/env python3
# Run vkcube-borg in qemu-riscv64 (user mode, an rv64ima CPU: no C, F or D) against a host-side
# `direct_sim --raw` standing in for the render node. usage: run-qemu.py vkcube-borg direct_sim shader.cache out.ppm [frames]
import os, subprocess, sys

exe, sim, cache, ppm = sys.argv[1:5]
frames = sys.argv[5] if len(sys.argv) > 5 else "1"
to_r, to_w = os.pipe()      # qemu -> device
from_r, from_w = os.pipe()  # device -> qemu
dev = subprocess.Popen([sim, "--raw"], stdin=to_r, stdout=from_w, close_fds=True)
env = dict(os.environ, BORGVK_HW="1", BORGVK_SHADER_CACHE=cache, BORGVK_DUMP_PPM=ppm,
           BORG_HW_FDS=f"{to_w},{from_r}")
cpu = "rv64,c=false,f=false,d=false,zfa=false,zfh=false,zfhmin=false,v=false"
q = subprocess.run(["qemu-riscv64", "-cpu", cpu, exe, "--wsi", "display", "--c", frames, "--suppress_popups"],
                   env=env, pass_fds=(to_w, from_r))
os.close(to_w)
dev.wait()
sys.exit(q.returncode)
