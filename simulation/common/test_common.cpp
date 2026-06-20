// Unit tests for simulation/common utilities.
// Compiled and run by `make -C simulation test-sim-common`.
// Tests uart_decoder (bit-bang UART receive) and flat_read16/flat_write16
// (MemBackendIO address decoding + byte-enable logic) without a full simulation.

#include "uart_decoder.h"
#include "common_sim.h"
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

// ── flat_read16 / flat_write16 ────────────────────────────────────────────────
//
// addrIn bit 23 = 0 → flash; bit 23 = 1 → flat.
// Per-chip byte address = (addrIn & 0x7FFFFF) << 1 (16-bit words).

static void test_flat_flat_roundtrip() {
    QSPIMemory flash(256, true), flat(256, false);
    // addrIn with bit 23 set → DRAM, word 3 → byte 6.
    uint32_t addr = (1u << 23) | 3u;
    flat_write16(&flash, &flat, addr, 0xBEEF, 0x3);
    uint16_t val = flat_read16(&flash, &flat, addr);
    assert(val == 0xBEEF);
    printf("  flat: DRAM round-trip 0xBEEF OK\n");
}

static void test_flat_flash_roundtrip() {
    QSPIMemory flash(256, true), flat(256, false);
    // bit 23 clear → flash, word 5 → byte 10.
    uint32_t addr = 5u;
    flat_write16(&flash, &flat, addr, 0x1234, 0x3);
    uint16_t val = flat_read16(&flash, &flat, addr);
    assert(val == 0x1234);
    printf("  flat: flash round-trip 0x1234 OK\n");
}

static void test_flat_byte_enables() {
    QSPIMemory flash(256, true), flat(256, false);
    uint32_t addr = (1u << 23) | 1u;
    // Write both bytes first.
    flat_write16(&flash, &flat, addr, 0xFFFF, 0x3);
    // Overwrite only lower byte with 0xAB.
    flat_write16(&flash, &flat, addr, 0x00AB, 0x1);
    uint16_t val = flat_read16(&flash, &flat, addr);
    assert((val & 0x00FF) == 0xAB && (val & 0xFF00) == 0xFF00);
    // Overwrite only upper byte with 0xCD.
    flat_write16(&flash, &flat, addr, 0xCD00, 0x2);
    val = flat_read16(&flash, &flat, addr);
    assert(val == 0xCDAB);
    printf("  flat: byte-enable 0x1/0x2 OK\n");
}

static void test_flat_flash_flat_isolation() {
    QSPIMemory flash(256, true), flat(256, false);
    // Same logical word index in flash and flat should be independent.
    uint32_t flash_addr = 2u;
    uint32_t flat_addr = (1u << 23) | 2u;
    flat_write16(&flash, &flat, flash_addr, 0xAAAA, 0x3);
    flat_write16(&flash, &flat, flat_addr, 0x5555, 0x3);
    assert(flat_read16(&flash, &flat, flash_addr) == 0xAAAA);
    assert(flat_read16(&flash, &flat, flat_addr) == 0x5555);
    printf("  flat: flash/flat isolation OK\n");
}

static void test_flat_oob_read() {
    QSPIMemory flash(4, true), flat(4, false);
    // Word 10 → byte 20, beyond size 4 → should return 0 without crashing.
    uint16_t val = flat_read16(&flash, &flat, 10u);
    assert(val == 0);
    printf("  flat: out-of-bounds read returns 0 OK\n");
}

int main() {
    printf("sim-common unit tests\n");

    printf("uart_decoder:\n");
    test_uart_single();
    test_uart_multibyte();
    test_uart_all_ones();
    test_uart_all_zeros();

    printf("flat_read16/flat_write16:\n");
    test_flat_flat_roundtrip();
    test_flat_flash_roundtrip();
    test_flat_byte_enables();
    test_flat_flash_flat_isolation();
    test_flat_oob_read();

    printf("All tests passed.\n");
    return 0;
}
