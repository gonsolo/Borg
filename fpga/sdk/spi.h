#pragma once

#include <stdint.h>
#include <stdbool.h>

// Low level SPI send function.  Ctrl is:
//   Bit 2: High to end txn, if low CS remains asserted after byte is sent
//   Bit 3: DC control
// The send is scheduled and proceeds in the background, the received byte is ignored
void spi_send_data(uint8_t data, uint8_t ctrl);

// Low level SPI send and receive function.  Ctrl is:
//   Bit 2: High to end txn, if low CS remains asserted after byte is sent
//   Bit 3: DC control
// This function blocks and returns the received byte
uint8_t spi_send_recv_data(uint8_t data, uint8_t ctrl);

// Set the SPI config.
// Bits 0-6 are the divider: 
//   The SPI clock divider is set to 2 * (divider + 1).
//   The valid range of the argument is 0-127, giving clock dividers of 2 - 256.
//   At reset the default clock divider is 8 (register is set to 3).
// Bit 7 sets the read latency:
//   If 0 reads are sampled half an SPI clock cycle after the rising edge
//   If 1 reads are sampled a full SPI clock cycle after the rising edge
void spi_set_config(uint32_t config);

// Send multiple bytes, ending the transaction if specified
void spi_send_bytes(const uint8_t* data, int len, bool end_txn, bool set_dc);
void spi_send_recv_bytes(const uint8_t* data_out, uint8_t* data_in, int len, bool end_txn, bool set_dc);
