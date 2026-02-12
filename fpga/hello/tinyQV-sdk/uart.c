#include "uart.h"

#define NANOPRINTF_USE_FIELD_WIDTH_FORMAT_SPECIFIERS 1
#define NANOPRINTF_USE_PRECISION_FORMAT_SPECIFIERS 1
#define NANOPRINTF_USE_LARGE_FORMAT_SPECIFIERS 1
#define NANOPRINTF_USE_FLOAT_FORMAT_SPECIFIERS 1
#define NANOPRINTF_USE_BINARY_FORMAT_SPECIFIERS 0
#define NANOPRINTF_USE_WRITEBACK_FORMAT_SPECIFIERS 0
#define NANOPRINTF_SNPRINTF_SAFE_TRIM_STRING_ON_OVERFLOW 1

// Compile nanoprintf in this translation unit.
#define NANOPRINTF_IMPLEMENTATION
#include "nanoprintf.h"

static void uart_putc2(int c, void*) {
    if (c == '\n') uart_putc('\r');
    uart_putc(c);
}

void uart_puts(const char* c) {
    while (*c) {
        uart_putc(*c++);
    }
    uart_putc('\r');
    uart_putc('\n');
}

void uart_put_buffer(const char* c, int len) {
    while (len-- > 0) {
        if (*c == '\n') uart_putc('\r');
        uart_putc(*c++);
    }
}

void debug_uart_put_buffer(const char* c, int len) {
    while (len-- > 0) {
        if (*c == '\n') debug_uart_putc('\r');
        debug_uart_putc(*c++);
    }
}

int uart_printf(const char *fmt, ...) {
    va_list val;
    va_start(val, fmt);
    int const rv = npf_vpprintf(&uart_putc2, NULL, fmt, val);
    va_end(val);
    return rv;
}

char __attribute__((section(".uninitialized_data.uart"))) uart_tx_buffer[64];

#ifndef TINYQV_SIM
// The very large RX buffer is a hack to avoid the host needing flow control.  Fortunately we have plenty of RAM.
char __attribute__((section(".uninitialized_data.uart"))) uart_rx_buffer[65536];
#else
char __attribute__((section(".uninitialized_data.uart"))) uart_rx_buffer[64];
#endif