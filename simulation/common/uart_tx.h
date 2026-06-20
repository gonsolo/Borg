#pragma once
#include <cstdint>
#include <cstddef>
#include <vector>

// Simulates a UART transmitter (host → firmware direction, i.e. the RXD line).
// Mirrors UartDecoder's 35-cycle-per-bit cadence (4 MHz / 115200 baud).
// idle bit = 1 (mark); start bit = 0; 8 data bits LSB-first; stop bit = 1.
class UartTx {
    static const int CYCLES_PER_BIT = 35;

    std::vector<uint8_t> queue;
    size_t head = 0;
    uint32_t pending_gap = 0;  // idle cycles to hold before starting queued bytes

    enum State { IDLE, START, DATA, STOP } state = IDLE;
    int  cycle_count = 0;
    int  bit_index   = 0;
    uint8_t current  = 0;

public:
    // Queue bytes to transmit.
    void enqueue(const uint8_t *data, size_t len) {
        queue.insert(queue.end(), data, data + len);
    }
    void enqueue(uint8_t b) { queue.push_back(b); }

    // Hold the line idle for `cycles` before consuming the byte queue.
    // Bytes enqueued AFTER this call are not affected by the gap.
    // Call before enqueue() to create a leading idle period (e.g. to let
    // the firmware's gap-wait complete before data arrives).
    void enqueue_gap(uint32_t cycles) { pending_gap += cycles; }

    bool empty() const { return head >= queue.size() && state == IDLE && pending_gap == 0; }

    // Call once per sim clock cycle.  Returns the current RXD bit value.
    uint8_t tick() {
        if (state == IDLE) {
            if (pending_gap > 0) { pending_gap--; return 1; }
            if (head >= queue.size()) return 1;
            current = queue[head++];
            state = START;
            cycle_count = 0;
            bit_index = 0;
        }

        uint8_t bit = 1;
        switch (state) {
        case START: bit = 0; break;
        case DATA:  bit = (current >> bit_index) & 1; break;
        case STOP:  bit = 1; break;
        default:    bit = 1; break;
        }

        if (++cycle_count >= CYCLES_PER_BIT) {
            cycle_count = 0;
            switch (state) {
            case START: state = DATA; bit_index = 0; break;
            case DATA:
                if (++bit_index >= 8) state = STOP;
                break;
            case STOP:  state = IDLE; break;
            default: break;
            }
        }
        return bit;
    }
};
