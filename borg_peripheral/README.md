![](../../workflows/gds/badge.svg) ![](../../workflows/docs/badge.svg) ![](../../workflows/test/badge.svg) ![](../../workflows/fpga/badge.svg)

# Borg Peripheral

This is a full peripheral for TinyQV intended to be taped out with
Tinytapeout. Right now it implements a floating point adder.

## Working

* Mill test (Arch, Nix)
* TT test, docs (Arch, Github, Nix)
* TT gds (Arch, Github, Nix)
* Chisel simple increment
* Test working in borg_tinyqv (Arch)

## TODO

* Chisel floating point addition + test
* Chisel floating point multiplication + test
* When pico-ice arrives: FPGA testing
* Submit for next shuttle
