#pragma once
#include <cstdint>

class UartDecoder {
    uint8_t last_uart = 1;
    int uart_bits_received = 0;
    int uart_cycles_waited = 0;
    uint8_t uart_byte = 0;
    bool uart_receiving = false;

public:
    // Returns true if a full byte was just received
    bool tick(uint8_t txd);
    
    // Returns the last received byte
    uint8_t byte() const;
};
