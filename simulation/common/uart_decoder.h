#pragma once
#include <cstdint>

class UartDecoder {
    uint8_t last_uart = 1;
    int uart_bits_received = 0;
    int uart_cycles_waited = 0;
    uint8_t uart_byte = 0;
    bool uart_receiving = false;
    int cycles_per_bit = 35;  // 4 MHz / 115200 baud ≈ 35 cycles/bit

public:
    // Must match the firmware's own UART_BAUD divisor (set via CLOCK_MHZ at
    // build time) — this only affects decoding the firmware's debug TX output
    // for display, not the RXD injection path (see UartTx::set_cycles_per_bit).
    void set_cycles_per_bit(int cpb) { cycles_per_bit = cpb; }

    // Returns true if a full byte was just received
    bool tick(uint8_t txd);

    // Returns the last received byte
    uint8_t byte() const;
};
