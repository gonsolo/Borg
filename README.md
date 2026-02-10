![](../../workflows/gds/badge.svg) ![](../../workflows/docs/badge.svg) ![](../../workflows/test/badge.svg) ![](../../workflows/fpga/badge.svg)

# Borg TinyQV

This is a tiny floating point peripheral within the TinyQV SOC.

# Prerequisites

* Nix
* Git
* Make

# Task 1: Basic FPU on software simulator

## Milestone 1a: Get floating-point multiplication and addition running on a simulator.

* git clone --recurse-submodules git@github.com:gonsolo/borg_tinyqv.git
* cd borg_tinyqv
* make nix_peripheral_test

This runs tests of the Borg peripheral at the Scala/Chisel and cocotb levels.

## Milestone 1b: Add cocotb tests for the FPU unit and integrate it into TinyQV, a small RISC-V processor written for Tinytapeout.

* make nix_tt_test 

This runs tests of the actual SOC at the cocotb level.
