// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0

// borg_ftdi: the Nexys Video's HostLink as borgvk's device (BORG_HW_RAW), stdin and stdout
// relayed to the FT2232H's channel A in synchronous FIFO mode; channel B stays JTAG.
#include <ftdi.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <unistd.h>

// Sync FIFO mode on channel A takes channel B's buffers: leave it, or JTAG stays locked out.
static struct ftdi_context *dev;
static void leave(void) {
  if (dev) ftdi_set_bitmode(dev, 0xff, BITMODE_RESET);
}
static void on_signal(int sig) {
  leave();
  _exit(128 + sig);
}

static int fail(struct ftdi_context *f, const char *what) {
  fprintf(stderr, "borg_ftdi: %s: %s\n", what, ftdi_get_error_string(f));
  leave();
  return 1;
}

int main(void) {
  struct ftdi_context *f = ftdi_new();
  if (!f) return 1;
  if (ftdi_set_interface(f, INTERFACE_A) < 0) return fail(f, "interface");
  if (ftdi_usb_open(f, 0x0403, 0x6010) < 0) return fail(f, "open");
  dev = f;
  for (int s = 0; s < 4; s++) signal((int[]){SIGINT, SIGTERM, SIGHUP, SIGPIPE}[s], on_signal);
  if (ftdi_set_bitmode(f, 0xff, BITMODE_RESET) < 0 || ftdi_set_bitmode(f, 0xff, BITMODE_SYNCFF) < 0)
    return fail(f, "sync FIFO mode");
  ftdi_set_latency_timer(f, 2);
  ftdi_setflowctrl(f, SIO_RTS_CTS_HS);
  ftdi_read_data_set_chunksize(f, 1 << 16);
  ftdi_write_data_set_chunksize(f, 1 << 16);
  ftdi_tcioflush(f);

  static unsigned char buf[1 << 16];
  struct pollfd in = {.fd = 0, .events = POLLIN};
  for (;;) {
    // Commands first; the board answers only what was asked, so a read never blocks a write.
    while (poll(&in, 1, 0) > 0) {
      ssize_t n = read(0, buf, sizeof buf);
      if (n <= 0) goto done;
      for (ssize_t o = 0; o < n;) {
        int w = ftdi_write_data(f, buf + o, (int)(n - o));
        if (w < 0) return fail(f, "write");
        o += w;
      }
    }
    int r = ftdi_read_data(f, buf, sizeof buf);
    if (r < 0) return fail(f, "read");
    if (r > 0 && write(1, buf, (size_t)r) != r) goto done;
  }
done:
  leave();
  ftdi_usb_close(f);
  ftdi_free(f);
  return 0;
}
