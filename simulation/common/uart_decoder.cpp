#include "uart_decoder.h"

bool UartDecoder::tick(uint8_t txd) {
    if (!uart_receiving) {
        if (last_uart == 1 && txd == 0) {
            uart_receiving = true;
            uart_cycles_waited = -(cycles_per_bit / 2); // sample middle of bit 0
            uart_bits_received = 0;
            uart_byte = 0;
        }
    } else {
        uart_cycles_waited++;
        if (uart_cycles_waited == cycles_per_bit) {
            uart_cycles_waited = 0;
            if (uart_bits_received < 8) {
                uart_byte |= (txd << uart_bits_received);
                uart_bits_received++;
            } else { // 8 bits received. Stop bit is next.
                uart_receiving = false;
                last_uart = txd;
                return true; // Byte complete
            }
        }
    }
    last_uart = txd;
    return false;
}

uint8_t UartDecoder::byte() const {
    return uart_byte;
}
