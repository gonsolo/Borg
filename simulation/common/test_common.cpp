// Unit tests for simulation/common utilities.
// Compiled and run by `make -C simulation test-sim-common`.
// Tests uart_decoder (bit-bang UART receive) without a full simulation.

#include "uart_decoder.h"
#include <cassert>
#include <cstdio>

// ── UartDecoder ──────────────────────────────────────────────────────────────
//
// 4 MHz / 115200 baud ≈ 34.7 cycles/bit; decoder samples at cycle 35.
// After the falling edge that marks the start bit, the decoder offsets by -17
// (half a bit) so it samples the middle of each bit.

static const int CPB = 35;  // cycles per bit

static bool send_byte(UartDecoder& d, uint8_t val) {
    // Idle: a few cycles with txd=1 so last_uart is definitely 1.
    for (int i = 0; i < 5; i++) d.tick(1);

    // Start bit (0) for one full bit period.
    for (int i = 0; i < CPB; i++) {
        if (d.tick(0)) return false;  // spurious complete — bad
    }
    // 8 data bits, LSB first.
    for (int b = 0; b < 8; b++) {
        uint8_t bit = (val >> b) & 1;
        for (int i = 0; i < CPB; i++) {
            if (d.tick(bit)) return false;
        }
    }
    // Stop bit: drive high until the decoder fires.
    for (int i = 0; i < CPB + 10; i++) {
        if (d.tick(1)) return true;
    }
    return false;  // never fired
}

static void test_uart_single() {
    UartDecoder d;
    assert(send_byte(d, 'A') && d.byte() == 'A');
    printf("  uart: 'A' (0x41) OK\n");
}

static void test_uart_multibyte() {
    UartDecoder d;
    const char* msg = "Hi\n";
    for (int i = 0; msg[i]; i++) {
        assert(send_byte(d, (uint8_t)msg[i]) && d.byte() == (uint8_t)msg[i]);
    }
    printf("  uart: multi-byte 'Hi\\n' OK\n");
}

static void test_uart_all_ones() {
    UartDecoder d;
    assert(send_byte(d, 0xFF) && d.byte() == 0xFF);
    printf("  uart: 0xFF OK\n");
}

static void test_uart_all_zeros() {
    UartDecoder d;
    assert(send_byte(d, 0x00) && d.byte() == 0x00);
    printf("  uart: 0x00 OK\n");
}

int main() {
    printf("sim-common unit tests\n");

    printf("uart_decoder:\n");
    test_uart_single();
    test_uart_multibyte();
    test_uart_all_ones();
    test_uart_all_zeros();

    printf("All tests passed.\n");
    return 0;
}
