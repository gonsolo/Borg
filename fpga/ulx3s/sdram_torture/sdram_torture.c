// SPDX-FileCopyrightText: © 2026 Andreas Wendleder
// SPDX-License-Identifier: CERN-OHL-S-2.0
//
// Bare-metal RV64 SDRAM stress test for task #15's "Bug A" -- a real-
// hardware-only, non-deterministic hang whose exact stall point varies
// across boots of the same Linux/OpenSBI binary (see the project memory
// for the full investigation). That variability points at a hardware
// timing race rather than a software logic bug, most plausibly in the
// real SDRAM path (MemoryController/SdramBackend/SdramController), which
// already had one real-hardware-only race fixed before (an SdramBackend
// ready-detector race around auto-refresh, commit 4927fd8) and is
// exactly what Bug A's failing syscalls (readdir, path lookups, fork's
// COW pages, ftrace code patching) all hammer heavily.
//
// No OpenSBI, no Linux, no MMU -- runs directly at physical address 0 in
// M-mode (FlashBootLoader loads it there and jumps, same as OpenSBI's own
// entry on this platform). Iterates a mixed-size (byte/half/word/double),
// mixed-pattern (sequential sweep + random-address read-modify-write)
// tight loop against a 6 MiB test region with no delays between accesses,
// so refresh boundaries and bank/row switches are crossed constantly and
// densely -- reusing this real hardware's own timing (not a simulated
// approximation of it) to try to reproduce the race far faster than
// waiting for Linux's own comparatively sparse, OS-scheduled memory
// traffic to happen to hit it.

#include <stdint.h>

#define UART_TX     (*(volatile uint32_t *)0x08000018UL)
#define UART_STATUS (*(volatile uint32_t *)0x0800001CUL)

static void uart_putc(char c) {
    while (UART_STATUS & 1u)
        ;
    UART_TX = (uint32_t)(unsigned char)c;
}

static void uart_puts(const char *s) {
    while (*s) {
        if (*s == '\n') uart_putc('\r');
        uart_putc(*s++);
    }
}

static void uart_puthex64(uint64_t v) {
    uart_putc('0');
    uart_putc('x');
    for (int i = 60; i >= 0; i -= 4) {
        int nib = (int)((v >> i) & 0xf);
        uart_putc(nib < 10 ? ('0' + nib) : ('a' + nib - 10));
    }
}

static void uart_putdec(uint64_t v) {
    char buf[20];
    int n = 0;
    if (v == 0) {
        uart_putc('0');
        return;
    }
    while (v > 0 && n < 20) {
        buf[n++] = (char)('0' + (v % 10));
        v /= 10;
    }
    while (n > 0)
        uart_putc(buf[--n]);
}

// xorshift64 -- deterministic pseudo-random sequence, reseedable so the
// write pass and verify pass generate the identical sequence independently.
static uint64_t xorshift64(uint64_t *state) {
    uint64_t x = *state;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    *state = x;
    return x;
}

// ---------------------------------------------------------------------------
// CLINT timer interrupt -- injects genuinely asynchronous (from the main
// loop's perspective) extra SDRAM traffic. Hutt only samples pending
// interrupts at instruction-fetch boundaries (Hutt.scala's is(sFetchReq)
// check), so this can't literally abort an in-flight load/store mid-cycle,
// but the ISR's own memory access still lands at unpredictable times
// relative to the foreground test's SDRAM transactions and the ~7.8us
// auto-refresh period -- something a plain bare-metal loop with
// interrupts disabled (as phases 1-5 alone would be) never exercises,
// unlike Linux's constant timer-tick-driven scheduling activity.
// ---------------------------------------------------------------------------
#define CLINT_BASE  0x02000000UL
#define MTIME_LO    (*(volatile uint32_t *)(CLINT_BASE + 0x0))
#define MTIME_HI    (*(volatile uint32_t *)(CLINT_BASE + 0x4))
#define MTIMECMP_LO (*(volatile uint32_t *)(CLINT_BASE + 0x8))
#define MTIMECMP_HI (*(volatile uint32_t *)(CLINT_BASE + 0xC))

// Scratch region for the ISR's own writes -- near the top of the 32 MiB
// SDRAM, well clear of both our code (<1 MiB) and the main test region
// (TEST_BASE..TEST_BASE+TEST_SIZE = 2 MiB..8 MiB), so the ISR's traffic
// adds real, concurrent SDRAM arbitration pressure without corrupting
// the foreground phases' own expected-value tracking.
#define IRQ_SCRATCH_BASE 0x01F00000UL
#define IRQ_SCRATCH_SIZE 0x8000UL
// 1000 (~40us) was too tight relative to the ISR's own overhead (register
// save/restore + a real SDRAM access + CLINT re-arm), causing a livelock
// where interrupts re-triggered faster than the handler could clear them
// -- observed as a total silent hang (self-inflicted, not a hardware
// finding). 100k ticks (~4ms, still far more frequent than any OS
// scheduler tick) gives comfortable headroom while still injecting
// plenty of asynchronous traffic over the test's runtime.
#define IRQ_PERIOD       100000ULL

static volatile uint64_t irq_count = 0;
static volatile uint64_t irq_mismatches = 0;
static uint64_t irq_rng_state = 0xABCDEF1234567890ULL;

static inline uint64_t read_mtime(void) {
    uint32_t hi, lo;
    do {
        hi = MTIME_HI;
        lo = MTIME_LO;
    } while (hi != MTIME_HI);
    return ((uint64_t)hi << 32) | lo;
}

static inline void write_mtimecmp(uint64_t v) {
    MTIMECMP_LO = 0xFFFFFFFFu; /* park it far out while updating hi, avoid a spurious early fire */
    MTIMECMP_HI = (uint32_t)(v >> 32);
    MTIMECMP_LO = (uint32_t)v;
}

void timer_isr(void) {
    irq_count++;
    uint64_t off = xorshift64(&irq_rng_state) % (IRQ_SCRATCH_SIZE - 8);
    volatile uint64_t *p = (volatile uint64_t *)(IRQ_SCRATCH_BASE + (off & ~7UL));
    uint64_t v = xorshift64(&irq_rng_state);
    *p = v;
    uint64_t got = *p;
    if (got != v) irq_mismatches++;
    write_mtimecmp(read_mtime() + IRQ_PERIOD);
}

extern void trap_vector(void);

static inline void csr_write_mtvec(uint64_t v) {
    asm volatile("csrw mtvec, %0" ::"r"(v));
}
static inline void csr_enable_timer_irq(void) {
    uint64_t mtie = 1UL << 7; /* mie.MTIE */
    asm volatile("csrs mie, %0" ::"r"(mtie));
    uint64_t mie = 1UL << 3; /* mstatus.MIE */
    asm volatile("csrs mstatus, %0" ::"r"(mie));
}

// Test region: starts well clear of our own code/stack (< 1 MiB, see
// link.ld), spans 2 MiB..8 MiB so it crosses many SDRAM bank/row
// boundaries (13-bit row, 2-bit bank, 9-bit column decode in
// SdramController.scala) and, at 25 MHz, many multiples of the ~7.8us
// (~195-cycle) auto-refresh period.
#define TEST_BASE  0x0200000UL
#define TEST_SIZE  0x0600000UL
#define TEST_WORDS (TEST_SIZE / 8)

static uint64_t errors = 0;

static void report_mismatch(const char *phase, uint64_t addr, uint64_t expect, uint64_t got) {
    errors++;
    uart_puts("\n*** MISMATCH [");
    uart_puts(phase);
    uart_puts("] addr=");
    uart_puthex64(addr);
    uart_puts(" expect=");
    uart_puthex64(expect);
    uart_puts(" got=");
    uart_puthex64(got);
    uart_puts(" ***\n");
}

// Phase 5: instruction-cache thrashing concurrent with data access. Hutt's
// InstrCache (hardware/hutt/src/InstrCache.scala) is 512 lines x 1 word =
// 2 KiB total, direct-mapped -- phases 1-4 above run entirely from a
// ~2 KiB loop body that stays icache-RESIDENT after the first pass, so
// they generate almost NO real SDRAM instruction-fetch traffic after
// warm-up. That's unlike Bug A's actual failure mode (Linux exec'ing
// busybox applets, walking rarely-hit kernel code paths, ftrace runtime
// patching), which constantly MISSES the icache and issues real SDRAM
// instruction fetches interleaved with data accesses -- exactly the
// combined-arbitration scenario MemoryController.scala's strict
// CPU-read > CPU-write > GPU-write > GPU-read > instr-fetch priority
// exists to referee. Force that here with 64 worker functions, each
// padded to 1 KiB (>> the 2 KiB cache's per-line granularity, so
// consecutive random calls almost always alias/evict), spanning 64 KiB
// total (32x the cache's capacity) -- called in random order, each doing
// one real memory op, so every call is both an icache miss (real instr
// fetch through SdramBackend) AND a data access competing for the same
// arbiter, at the same time.
#define WORKER(n)                                                              \
    __attribute__((noinline, aligned(1024))) static void worker_##n(           \
        volatile uint8_t *base, uint64_t off, uint64_t val) {                  \
        volatile uint64_t *p = (volatile uint64_t *)(base + (off & ~7UL));     \
        *p = val ^ (n);                                                        \
        asm volatile(".rept 200\n\tnop\n\t.endr" ::: "memory");                \
    }

WORKER(0)  WORKER(1)  WORKER(2)  WORKER(3)  WORKER(4)  WORKER(5)  WORKER(6)  WORKER(7)
WORKER(8)  WORKER(9)  WORKER(10) WORKER(11) WORKER(12) WORKER(13) WORKER(14) WORKER(15)
WORKER(16) WORKER(17) WORKER(18) WORKER(19) WORKER(20) WORKER(21) WORKER(22) WORKER(23)
WORKER(24) WORKER(25) WORKER(26) WORKER(27) WORKER(28) WORKER(29) WORKER(30) WORKER(31)
WORKER(32) WORKER(33) WORKER(34) WORKER(35) WORKER(36) WORKER(37) WORKER(38) WORKER(39)
WORKER(40) WORKER(41) WORKER(42) WORKER(43) WORKER(44) WORKER(45) WORKER(46) WORKER(47)
WORKER(48) WORKER(49) WORKER(50) WORKER(51) WORKER(52) WORKER(53) WORKER(54) WORKER(55)
WORKER(56) WORKER(57) WORKER(58) WORKER(59) WORKER(60) WORKER(61) WORKER(62) WORKER(63)

typedef void (*worker_fn)(volatile uint8_t *, uint64_t, uint64_t);
#define W(n) worker_##n
static const worker_fn workers[64] = {
    W(0),  W(1),  W(2),  W(3),  W(4),  W(5),  W(6),  W(7),
    W(8),  W(9),  W(10), W(11), W(12), W(13), W(14), W(15),
    W(16), W(17), W(18), W(19), W(20), W(21), W(22), W(23),
    W(24), W(25), W(26), W(27), W(28), W(29), W(30), W(31),
    W(32), W(33), W(34), W(35), W(36), W(37), W(38), W(39),
    W(40), W(41), W(42), W(43), W(44), W(45), W(46), W(47),
    W(48), W(49), W(50), W(51), W(52), W(53), W(54), W(55),
    W(56), W(57), W(58), W(59), W(60), W(61), W(62), W(63),
};

int main(void) {
    uart_puts("\n\nSDRAM torture test (task #15 Bug A) starting\n");
    uart_puts("test region: ");
    uart_puthex64(TEST_BASE);
    uart_puts(" - ");
    uart_puthex64(TEST_BASE + TEST_SIZE);
    uart_puts("\n");

    csr_write_mtvec((uint64_t)&trap_vector);
    write_mtimecmp(read_mtime() + IRQ_PERIOD);
    csr_enable_timer_irq();
    uart_puts("timer IRQ enabled, period=");
    uart_putdec(IRQ_PERIOD);
    uart_puts(" mtime ticks\n");

    uint64_t iter = 0;
    uint64_t heartbeat = 0;

    for (;;) {
        uint64_t seed = 0xC0FFEE1234ULL ^ (iter * 0x9E3779B97F4A7C15ULL);

        // Phase 1: sequential doubleword write sweep, no delay between
        // accesses -- refresh (~every 195 cycles at 25MHz) lands mid-sweep
        // constantly, and every write here crosses row/bank boundaries as
        // it walks the 6 MiB region.
        uint64_t st = seed;
        for (uint64_t i = 0; i < TEST_WORDS; i++) {
            volatile uint64_t *p = (volatile uint64_t *)(TEST_BASE + i * 8);
            *p = xorshift64(&st);
            if ((i & 0xFFFFF) == 0) uart_putc('.');
        }

        // Phase 2: sequential doubleword read-back verify (separate pass --
        // catches corruption that only shows up later, not just immediately
        // after the write, e.g. a stale cache line or a lost refresh).
        st = seed;
        for (uint64_t i = 0; i < TEST_WORDS; i++) {
            volatile uint64_t *p = (volatile uint64_t *)(TEST_BASE + i * 8);
            uint64_t expect = xorshift64(&st);
            uint64_t got = *p;
            if (got != expect) report_mismatch("seq-verify", TEST_BASE + i * 8, expect, got);
        }

        // Phase 3: random-address read-modify-write hammering -- every
        // access jumps to an unrelated address, forcing a bank/row
        // activate-or-precharge decision (SdramController's sRDWR retry
        // path) on nearly every single access, immediately verified.
        st = seed ^ 0xDEADBEEFCAFEULL;
        for (uint64_t i = 0; i < TEST_WORDS; i++) {
            uint64_t idx = xorshift64(&st) % TEST_WORDS;
            volatile uint64_t *p = (volatile uint64_t *)(TEST_BASE + idx * 8);
            uint64_t v = *p;
            *p = ~v;
            uint64_t v2 = *p;
            if (v2 != ~v) report_mismatch("rmw", TEST_BASE + idx * 8, ~v, v2);
        }

        // Phase 4: mixed byte/halfword/word RMW on a small sub-region --
        // byte writes go through MemoryController's separate read-modify-
        // write lane (byteWriteHw/rmwBg), a historically fragile path
        // (see project memory: SdramBackendSim byte-write neighbour-
        // clobber bug, already fixed) -- interleave sizes and addresses
        // (including unaligned-within-word) to stress it under the same
        // no-delay, refresh-crossing conditions as the phases above.
        st = seed ^ 0x1234567890ULL;
        volatile uint8_t *base8 = (volatile uint8_t *)TEST_BASE;
        for (uint64_t i = 0; i < TEST_WORDS; i++) {
            uint64_t off = xorshift64(&st) % (TEST_SIZE - 8);
            unsigned sel = (unsigned)(xorshift64(&st) & 3);
            if (sel == 0) {
                uint8_t v = (uint8_t)xorshift64(&st);
                base8[off] = v;
                uint8_t got = base8[off];
                if (got != v) report_mismatch("byte", TEST_BASE + off, v, got);
            } else if (sel == 1) {
                volatile uint16_t *p16 = (volatile uint16_t *)(base8 + (off & ~1UL));
                uint16_t v = (uint16_t)xorshift64(&st);
                *p16 = v;
                uint16_t got = *p16;
                if (got != v) report_mismatch("half", TEST_BASE + (off & ~1UL), v, got);
            } else if (sel == 2) {
                volatile uint32_t *p32 = (volatile uint32_t *)(base8 + (off & ~3UL));
                uint32_t v = (uint32_t)xorshift64(&st);
                *p32 = v;
                uint32_t got = *p32;
                if (got != v) report_mismatch("word", TEST_BASE + (off & ~3UL), v, got);
            } else {
                volatile uint64_t *p64 = (volatile uint64_t *)(base8 + (off & ~7UL));
                uint64_t v = xorshift64(&st);
                *p64 = v;
                uint64_t got = *p64;
                if (got != v) report_mismatch("dword", TEST_BASE + (off & ~7UL), v, got);
            }
        }

        // Phase 5: icache-thrashing worker calls concurrent with data
        // hammering (see WORKER/workers[] above) -- every call is both a
        // real instruction-fetch (icache miss, forced by the 64x1KiB
        // spread far exceeding the 2KiB direct-mapped cache) and a data
        // write through the same MemoryController arbiter, verified after
        // the call returns.
        st = seed ^ 0xF00DFACEUL;
        for (uint64_t i = 0; i < TEST_WORDS / 4; i++) {
            unsigned widx = (unsigned)(xorshift64(&st) & 63);
            uint64_t off = xorshift64(&st) % (TEST_SIZE - 8);
            uint64_t val = xorshift64(&st);
            workers[widx](base8, off, val);
            volatile uint64_t *p = (volatile uint64_t *)(base8 + (off & ~7UL));
            uint64_t expect = val ^ widx;
            uint64_t got = *p;
            if (got != expect) report_mismatch("icache-thrash", TEST_BASE + (off & ~7UL), expect, got);
        }

        iter++;
        heartbeat++;
        uart_puts("\niter=");
        uart_putdec(iter);
        uart_puts(" errors=");
        uart_putdec(errors);
        uart_puts(" heartbeat=");
        uart_putdec(heartbeat);
        uart_puts(" irq_count=");
        uart_putdec(irq_count);
        uart_puts(" irq_mismatches=");
        uart_putdec(irq_mismatches);
        uart_puts("\n");
    }

    return 0;
}
