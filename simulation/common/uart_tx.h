#pragma once
#include <cstdint>
#include <cstddef>
#include <vector>

// Simulates a UART transmitter (host → firmware direction, i.e. the RXD line).
// Default cadence mirrors UartDecoder's 35-cycle-per-bit (4 MHz / 115200 baud).
// idle bit = 1 (mark); start bit = 0; 8 data bits LSB-first; stop bit = 1.
//
// cycles_per_bit must match the firmware's own UART_BAUD divisor (computed
// from CLOCK_MHZ at boot) for both sides to agree on bit timing — see
// set_cycles_per_bit().  The default (35 ≈ 4 MHz) matches the historical
// --cts-uart headless path; the interactive viewer uses a higher CLOCK_MHZ
// (and matching cycles_per_bit) so the firmware's software polling loop in
// the borgvk UART drain loop has enough slack per byte: the hardware UART
// receiver (UartRx.scala) parks in FSM_READY — ignoring the wire — until the
// CPU reads out the buffered byte, so a byte's cycles-per-bit budget must
// exceed the drain loop's per-byte software overhead or bytes are dropped.
class UartTx {
    int cycles_per_bit = 35;

    std::vector<uint8_t> queue;
    size_t head = 0;
    uint32_t pending_gap = 0;  // idle cycles to hold before starting queued bytes

    enum State { IDLE, START, DATA, STOP } state = IDLE;
    int  cycle_count = 0;
    int  bit_index   = 0;
    uint8_t current  = 0;

public:
    // Set the bit period (in sim cycles).  Must be called before any bytes are
    // enqueued/ticked — changing it mid-transmission would desync in-flight bits.
    void set_cycles_per_bit(int cpb) { cycles_per_bit = cpb; }

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
    // `cts` (clear-to-send) gates only the IDLE->START decision — i.e. whether a
    // NEW byte may begin this cycle.  A byte already in flight always finishes;
    // real UART flow control (and this receiver's uart_rts) only ever holds off
    // the START of the next byte, never aborts one mid-transmission.  Ignoring
    // cts entirely (the historical default) races the receiver: UartRx.scala
    // parks in FSM_READY (deaf to the wire) until the CPU polls it out, so a
    // byte arriving while cts is deasserted is silently dropped.
    uint8_t tick(bool cts = true) {
        if (state == IDLE) {
            if (pending_gap > 0) { pending_gap--; return 1; }
            if (head >= queue.size()) return 1;
            if (!cts) return 1;
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

        if (++cycle_count >= cycles_per_bit) {
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
