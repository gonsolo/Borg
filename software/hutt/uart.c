#include "uart.h"

#define NANOPRINTF_USE_FIELD_WIDTH_FORMAT_SPECIFIERS 1
#define NANOPRINTF_USE_PRECISION_FORMAT_SPECIFIERS 1
#define NANOPRINTF_USE_LARGE_FORMAT_SPECIFIERS 0
#define NANOPRINTF_USE_FLOAT_FORMAT_SPECIFIERS 1
#define NANOPRINTF_USE_BINARY_FORMAT_SPECIFIERS 0
#define NANOPRINTF_USE_WRITEBACK_FORMAT_SPECIFIERS 0
#define NANOPRINTF_SNPRINTF_SAFE_TRIM_STRING_ON_OVERFLOW 1

#define NANOPRINTF_IMPLEMENTATION
#include "nanoprintf.h"

/* Main UART (PeriUart, user peripheral region, sel=2):
 *   addr 0x00 = TX write / RX read
 *   addr 0x04 = status: bit0 = TX busy, bit1 = RX data available  */
#define UART_DATA   ((volatile uint32_t *)0x08000800)
#define UART_STATUS ((volatile uint32_t *)0x08000804)

/* Debug UART (SoC inline register, TX-only):
 *   0x08000018 = TX byte
 *   0x0800001C = status: bit0 = TX busy                           */
#define DEBUG_UART_DATA   ((volatile uint32_t *)0x08000018)
#define DEBUG_UART_STATUS ((volatile uint32_t *)0x0800001C)

void uart_putc_polling(int c) {
    while (*UART_STATUS & 1)
        ;
    *UART_DATA = c;
}

void uart_putc(int c) {
    uart_putc_polling(c);
}

void uart_puts(const char *c) {
    while (*c)
        uart_putc_polling(*c++);
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

int uart_is_char_available(void) {
    return (*UART_STATUS >> 1) & 1;
}

int uart_getc(void) {
    while (!uart_is_char_available())
        ;
    return *UART_DATA & 0xFF;
}

void debug_uart_putc(int c) {
    while (*DEBUG_UART_STATUS & 1)
        ;
    *DEBUG_UART_DATA = c;
}

void debug_uart_put_buffer(const char *c, int len) {
    while (len-- > 0) {
        if (*c == '\n')
            debug_uart_putc('\r');
        debug_uart_putc(*c++);
    }
}

static void uart_putc2(int c, void *ctx) {
    (void)ctx;
    if (c == '\n')
        uart_putc_polling('\r');
    uart_putc_polling(c);
}

static void debug_uart_putc2(int c, void *ctx) {
    (void)ctx;
    if (c == '\n')
        debug_uart_putc('\r');
    debug_uart_putc(c);
}

int uart_printf(const char *fmt, ...) {
    va_list val;
    va_start(val, fmt);
    int const rv = npf_vpprintf(&uart_putc2, NULL, fmt, val);
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

/* Stubs retained for API compatibility; unused on Hutt (no interrupts). */
volatile int16_t uart_rx_interrupt_char = 3;
volatile uint8_t uart_rx_interrupt_seen = 0;
