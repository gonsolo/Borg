#include "uart.h"

#define NANOPRINTF_USE_FIELD_WIDTH_FORMAT_SPECIFIERS 1
#define NANOPRINTF_USE_PRECISION_FORMAT_SPECIFIERS 1
#define NANOPRINTF_USE_LARGE_FORMAT_SPECIFIERS 0
#define NANOPRINTF_USE_FLOAT_FORMAT_SPECIFIERS 1
#define NANOPRINTF_USE_BINARY_FORMAT_SPECIFIERS 0
#define NANOPRINTF_USE_WRITEBACK_FORMAT_SPECIFIERS 0
#define NANOPRINTF_SNPRINTF_SAFE_TRIM_STRING_ON_OVERFLOW 1

// Compile nanoprintf in this translation unit.
#define NANOPRINTF_IMPLEMENTATION
#include "nanoprintf.h"

#define UART_TX 0x08000080
#define UART_STATUS 0x08000084

void uart_putc_polling(int c) {
  while (*(volatile uint32_t *)UART_STATUS & 1)
    ;
  *(volatile uint32_t *)UART_TX = c;
}


static void uart_putc_polling2(int c, void *ctx) {
  if (c == '\n')
    uart_putc_polling('\r');
  uart_putc_polling(c);
}

void uart_puts(const char *c) {
  while (*c) {
    uart_putc_polling(*c++);
  }
  uart_putc_polling('\r');
  uart_putc_polling('\n');
}

void uart_put_buffer(const char *c, int len) {
  while (len-- > 0) {
    if (*c == '\n')
      uart_putc('\r');
    uart_putc(*c++);
  }
}

void debug_uart_put_buffer(const char *c, int len) {
  while (len-- > 0) {
    if (*c == '\n')
      debug_uart_putc('\r');
    debug_uart_putc(*c++);
  }
}

static void debug_uart_putc2(int c, void *ctx) {
  if (c == '\n')
    debug_uart_putc('\r');
  debug_uart_putc(c);
}

int uart_printf(const char *fmt, ...) {
  va_list val;
  va_start(val, fmt);
  int const rv = npf_vpprintf(&uart_putc_polling2, NULL, fmt, val);
  va_end(val);
  return rv;
}

int debug_uart_printf(const char *fmt, ...) {
  va_list val;
  va_start(val, fmt);
  int const rv = npf_vpprintf(&debug_uart_putc2, NULL, fmt, val);
  va_end(val);
  return rv;
}

char __attribute__((section(".uninitialized_data.uart"))) uart_tx_buffer[64];

#ifndef TINYQV_SIM
// The very large RX buffer is a hack to avoid the host needing flow control.
// Fortunately we have plenty of RAM.
char __attribute__((section(".uninitialized_data.uart"))) uart_rx_buffer[65536];
#else
char __attribute__((section(".uninitialized_data.uart"))) uart_rx_buffer[64];
#endif