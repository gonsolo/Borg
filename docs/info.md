<!---

This file is used to generate your project datasheet. Please fill in the information below and delete any unused
sections.

You can also include images in this folder and reference them in the markdown. Each image must be less than
512 kb in size, and the combined size of all images must be less than 1 MB.
-->

## How it works

Borg renders a 10 frame animation of a rotating white triangle on black background.
The framebuffer and main code runs on the Raspberry but the vertex shader is run on the Borg
hardware on the FPGA or ASIC.

## How to test

in ./fpga: ```make triangle```

## External hardware

You have to have the QSPI PMOD from the Tinytapeout store.
For running on the FPGA a pico-ice and a Raspberry Debug probe is used.
