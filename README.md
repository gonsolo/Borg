![](../../workflows/gds/badge.svg) ![](../../workflows/docs/badge.svg) ![](../../workflows/test/badge.svg) ![](../../workflows/fpga/badge.svg)

# Borg - European Graphics Processing Unit

## Foundational workflow for an open-source GPU

The Borg (Bring your Own Graphics) project aims to establish the complete foundational workflow
for an open-source GPU using entirely free and open Electronic Design Automation (EDA) tools.
Recognizing that full GPU development is highly complex, the initiative capitalizes on recent
advances in low-cost chip manufacturing to make individual tape-outs feasible for small teams.
The initial, focused objective is to successfully design, verify, and manufacture a tiny
floating-point unit (FPU)—the central component of modern graphics processors—by validating
every step of the pipeline, from high-level design and FPGA prototyping to the final
RTL-to-GDS-flow. This strategic focus proves the viability of an open-source manufacturing and
development pathway for future graphics hardware.

The prototype based on Firesim can be found in the
[pre-NLnet](https://github.com/gonsolo/OldBorg/tree/PreNLnet) repository.

## Borg TinyQV

This is a tiny floating point peripheral within the TinyQV SOC.

## Prerequisites

* Nix
* Git
* Make

## Task 1: Basic FPU on software simulator

### Milestone 1a: Get floating-point multiplication and addition running on a simulator.

* git clone --recurse-submodules git@github.com:gonsolo/borg_tinyqv.git
* cd borg_tinyqv
* make nix_peripheral_test

This runs tests of the Borg peripheral at the Scala/Chisel and cocotb levels.

### Milestone 1b: Add cocotb tests for the FPU unit and integrate it into TinyQV, a small RISC-V processor written for Tinytapeout.

* make nix_tt_test 

This runs tests of the actual SOC at the cocotb level.
