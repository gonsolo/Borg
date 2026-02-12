#include "spi.h"

void spi_send_bytes(const uint8_t* data, int len, bool end_txn, bool set_dc)
{
    uint8_t ctrl = set_dc ? 0x8 : 0;
    for (int i = 0; i < len; ++i) {
        if (end_txn && i == len - 1) ctrl |= 0x4;
        spi_send_data(data[i], ctrl);
    }
}

void spi_send_recv_bytes(const uint8_t* data_out, uint8_t* data_in, int len, bool end_txn, bool set_dc)
{
   uint8_t ctrl = set_dc ? 0x8 : 0;
    for (int i = 0; i < len; ++i) {
        if (end_txn && i == len - 1) ctrl |= 0x4;
        data_in[i] = spi_send_recv_data(data_out[i], ctrl);
    }
}
