// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// ftdi_reset: both FT2232H channels out of any bitmode and USB-reset; a channel A left in sync FIFO
// mode otherwise hangs openFPGALoader after "Jtag frequency".
#include <ftdi.h>
#include <stdio.h>

int main(void) {
  int bad = 0;
  for (int i = 0; i < 2; i++) {
    struct ftdi_context *f = ftdi_new();
    if (!f) return 1;
    ftdi_set_interface(f, i ? INTERFACE_B : INTERFACE_A);
    if (ftdi_usb_open(f, 0x0403, 0x6010) < 0) {
      fprintf(stderr, "ftdi_reset: channel %c: %s\n", 'A' + i, ftdi_get_error_string(f));
      bad = 1;
    } else {
      bad |= ftdi_set_bitmode(f, 0xff, BITMODE_RESET) < 0 || ftdi_tcioflush(f) < 0 || ftdi_usb_reset(f) < 0;
      ftdi_usb_close(f);
    }
    ftdi_free(f);
  }
  return bad;
}
