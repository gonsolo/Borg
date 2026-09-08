# Live demo: if the picture looks wrong

Run `./run-vkcube.sh` as usual. If you ever see a **corrupted / garbled
image instead of the spinning cube**, it's almost certainly not a hardware
or RTL bug -- it's the host/firmware serial protocol out of sync, most
often because a previous `vkcube` run got interrupted (killed, laptop
slept, `timeout` fired) partway through a transfer. `borg-shim`'s
upload-skip sentinel then thinks the firmware already has current
geometry/shaders when it doesn't.

**Fix (30 seconds):**

```sh
openFPGALoader -b ulx3s -r          # reset the board
rm -f /tmp/borgvk_ttyUSB0_setup     # clear the stale upload sentinel
./run-vkcube.sh                     # rerun -- forces a fresh upload
```

That's it. Confirmed 2026-09-08: this exact sequence recovered a corrupted
image with no other changes -- the bitstream and firmware were fine the
whole time.
