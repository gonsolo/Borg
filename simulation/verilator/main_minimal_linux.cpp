// Minimal standalone Verilator harness for MinimalSocSimTop: preloads real
// OpenSBI/Linux firmware directly into the behavioral SDRAM via the dbg_*
// backdoor (bypassing FlashBootLoader's byte-serial SPI copy, which real
// hardware already showed completing — boot_done=1), releases reset, and
// watches uo_out bit 6 (the debug console UART TX, matching MinimalSoC's
// `Cat(0.U(1.W), debug_uart_txd, 0.U(6.W))`) for OpenSBI/Linux boot output.
//
// Usage: ./minimal_linux_sim <firmware.bin> [max_cycles] [trace_start] [trace_end]
// Passing trace_start/trace_end dumps an FST waveform (waveform.fst) for that
// cycle window — useful for post-mortem inspection with gtkwave or a VCD/FST
// text dump, without paying the size/speed cost of tracing the whole run.

#include "../common/uart_decoder.h"
#include <VMinimalSocSimTop.h>
#include <verilated.h>
#include <verilated_fst_c.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <vector>

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s <firmware.bin> [max_cycles] [trace_start] [trace_end]\n", argv[0]);
        return 1;
    }
    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);

    std::string fw_path = argv[1];
    uint64_t max_cycles = argc > 2 ? strtoull(argv[2], nullptr, 10) : 20'000'000ULL;
    uint64_t trace_start = argc > 3 ? strtoull(argv[3], nullptr, 10) : (uint64_t)-1;
    uint64_t trace_end   = argc > 4 ? strtoull(argv[4], nullptr, 10) : (uint64_t)-1;

    std::ifstream f(fw_path, std::ios::binary | std::ios::ate);
    if (!f) {
        fprintf(stderr, "Cannot open firmware file: %s\n", fw_path.c_str());
        return 1;
    }
    size_t file_size = (size_t)f.tellg();
    f.seekg(0);
    std::vector<uint8_t> raw(file_size);
    f.read((char*)raw.data(), file_size);

    // Auto-detect FlashBootLoader's wrapped format (4-byte LE length header +
    // payload) vs. a bare fw_payload.bin, so this harness can accept either
    // without silently loading firmware 4 bytes offset from where it truly
    // belongs (that offset is exactly what a real FlashBootLoader would have
    // stripped before ever touching SDRAM — feeding it in unstripped once
    // caused OpenSBI's own fw_start relocation sanity check to correctly, but
    // misleadingly, fail).
    size_t fw_size;
    const uint8_t* fw_data;
    uint32_t header_len = file_size >= 4
        ? (raw[0] | (raw[1] << 8) | (raw[2] << 16) | (raw[3] << 24)) : 0;
    if (file_size >= 4 && header_len == file_size - 4) {
        fw_size = file_size - 4;
        fw_data = raw.data() + 4;
        fprintf(stderr, "Loaded wrapped firmware: %zu bytes total, stripped 4-byte header, %zu bytes payload\n",
                file_size, fw_size);
    } else {
        fw_size = file_size;
        fw_data = raw.data();
        fprintf(stderr, "Loaded firmware: %zu bytes\n", fw_size);
    }
    std::vector<uint8_t> fw(fw_data, fw_data + fw_size);

    auto* top = new VMinimalSocSimTop;

    VerilatedFstC* tfp = nullptr;
    if (trace_start != (uint64_t)-1) {
        tfp = new VerilatedFstC;
        top->trace(tfp, 99);
        tfp->open("waveform.fst");
        fprintf(stderr, "Tracing enabled: cycles [%llu, %llu] -> waveform.fst\n",
                (unsigned long long)trace_start, (unsigned long long)trace_end);
    }
    uint64_t vtime = 0;

    top->dbg_we = 0; top->dbg_waddr = 0; top->dbg_wdata = 0; top->dbg_raddr = 0;
    top->clk = 0; top->rst_n = 0; top->ena = 1; top->ui_in = 0;
    for (int i = 0; i < 10; i++) { top->eval(); top->clk = 1; top->eval(); top->clk = 0; }

    // Preload: flash byte F -> SDRAM word F>>1 (2 bytes per 16-bit word),
    // matching FlashBootLoader's own byte-serial copy target layout exactly.
    fprintf(stderr, "Preloading firmware into SDRAM via debug backdoor...\n");
    uint32_t evenLen = (uint32_t)((fw_size + 1) & ~1u);
    for (uint32_t b = 0; b < evenLen; b += 2) {
        uint16_t lo = fw[b];
        uint16_t hi = (b + 1 < fw_size) ? fw[b + 1] : 0;
        uint16_t d = lo | (hi << 8);
        top->dbg_we = 1; top->dbg_waddr = (b >> 1) & 0xFFFFFF; top->dbg_wdata = d;
        top->clk = 0; top->eval(); top->clk = 1; top->eval();
    }
    top->dbg_we = 0;
    fprintf(stderr, "Preload done (%u words). Releasing reset.\n", evenLen >> 1);

    top->rst_n = 1;

    UartDecoder dec;
    dec.set_cycles_per_bit(217);  // 25 MHz / 115200 baud

    std::string line_buf;
    uint64_t last_report = 0;
    for (uint64_t cyc = 0; cyc < max_cycles; cyc++) {
        bool tracing = tfp && cyc >= trace_start && cyc <= trace_end;
        top->clk = 0; top->eval();
        if (tracing) { tfp->dump(vtime++); }
        top->clk = 1; top->eval();
        if (tracing) { tfp->dump(vtime++); }

        uint8_t txd = (top->uo_out >> 6) & 1;
        if (dec.tick(txd)) {
            uint8_t c = dec.byte();
            putchar(c);
            fflush(stdout);
            if (c == '\n') {
                if (!line_buf.empty()) fprintf(stderr, "[UART] %s\n", line_buf.c_str());
                line_buf.clear();
            } else if (c >= 0x20 && c < 0x7f) {
                line_buf += (char)c;
            }
        }

        if (cyc - last_report >= 2'000'000) {
            fprintf(stderr, "... %llu cycles simulated | pc=0x%llx mtime=0x%llx mtimecmp=0x%llx irq=%d\n",
                    (unsigned long long)cyc, (unsigned long long)top->dbg_pc,
                    (unsigned long long)top->dbg_mtime, (unsigned long long)top->dbg_mtimecmp,
                    (int)top->dbg_timer_irq);
            last_report = cyc;
        }

        // Log every mtimecmp write (CLINT.mtimecmpWriteSeq incrementing) so we
        // can see the complete history of timer re-arms and find the LAST one
        // before the timer permanently stops being rearmed.
        static uint32_t last_mtimecmp_seq = 0xFFFFFFFFu;
        if (top->dbg_mtimecmp_write_seq != last_mtimecmp_seq) {
            last_mtimecmp_seq = top->dbg_mtimecmp_write_seq;
            fprintf(stderr, "[MTIMECMP-WRITE cyc %llu seq=%u] mtime=0x%llx mtimecmp=0x%llx\n",
                    (unsigned long long)cyc, top->dbg_mtimecmp_write_seq,
                    (unsigned long long)top->dbg_mtime, (unsigned long long)top->dbg_mtimecmp);
        }

        // Chasing a mallocng NULL-deref: group->meta reads 0 in free() despite
        // malloc() having written it via `m->mem->meta = m`. Log every TLB
        // fill (VA->PPN) unconditionally (page faults are rare relative to
        // total instructions) to see whether the same VA ever gets remapped
        // to a different PPN, and every physical store in the tail of the
        // run (bounded window to avoid an unbounded log) to see whether the
        // group-header write actually lands and with what data/address.
        // PTW-FILL/STORE logging below is gated off by default (DEBUG_VA_TRACE)
        // -- unconditional PTW-FILL logging alone produces multi-GB logs over
        // a 1B+ cycle run and makes the sim I/O-bound. Flip this on and set
        // the VA page filter(s) below to the addresses relevant to whatever
        // is being chased next; see the mallocng-SIGSEGV investigation
        // (2026-07-08 session) for the methodology this was built for.
#define DEBUG_VA_TRACE 0
#if DEBUG_VA_TRACE
        static uint32_t last_ptw_fill_seq = 0xFFFFFFFFu;
        if (top->dbg_ptw_fill_seq != last_ptw_fill_seq) {
            last_ptw_fill_seq = top->dbg_ptw_fill_seq;
            fprintf(stderr, "[PTW-FILL cyc %llu seq=%u] va=0x%llx ppn=0x%llx level=%d\n",
                    (unsigned long long)cyc, top->dbg_ptw_fill_seq,
                    (unsigned long long)top->dbg_ptw_fill_va, (unsigned long long)top->dbg_ptw_fill_ppn,
                    (int)top->dbg_ptw_fill_level);
        }
        static uint32_t last_store_seq = 0xFFFFFFFFu;
        if (top->dbg_store_seq != last_store_seq) {
            last_store_seq = top->dbg_store_seq;
            uint64_t va = (unsigned long long)top->dbg_store_va;
            uint64_t vpage = va >> 12;
            // Set to whatever VA page(s) matter for the current investigation.
            if (false) {
                fprintf(stderr, "[STORE cyc %llu seq=%u] pc=0x%llx va=0x%llx paddr=0x%llx data=0x%llx\n",
                        (unsigned long long)cyc, top->dbg_store_seq,
                        (unsigned long long)top->dbg_store_pc, va,
                        (unsigned long long)top->dbg_store_phys_addr,
                        (unsigned long long)top->dbg_store_data);
            }
        }
#endif

        // Chasing a child-process crash immediately after fork() with
        // garbage ra/pc (epc=-2, ra=-1). Log every write to x1 (ra) plus
        // satp/privLevel, windowed around the known crash cycle for this
        // build, to find the exact instruction where ra becomes -1.
        static uint32_t last_x1_write_seq = 0xFFFFFFFFu;
        if (top->dbg_x1_write_seq != last_x1_write_seq && cyc >= 1'183'000'000ULL && cyc <= 1'186'000'000ULL) {
            last_x1_write_seq = top->dbg_x1_write_seq;
            fprintf(stderr, "[X1-WRITE cyc %llu seq=%u] pc=0x%llx val=0x%llx satp=0x%llx priv=%d\n",
                    (unsigned long long)cyc, top->dbg_x1_write_seq,
                    (unsigned long long)top->dbg_x1_write_pc, (unsigned long long)top->dbg_x1_write_val,
                    (unsigned long long)top->dbg_satp, (int)top->dbg_priv_level);
        }

        // epc=-2 at the crash is the sepc CSR, not a GPR -- trace every
        // sepc write (both trap-entry auto-saves and the kernel's own
        // explicit `csrw sepc, ...` on trap exit) to find where it becomes
        // -2, and every load (address+value) to see what feeds it (e.g.
        // `REG_L a2, PT_EPC(sp)` reading -2 out of the kernel stack).
        static uint32_t last_sepc_write_seq = 0xFFFFFFFFu;
        if (top->dbg_sepc_write_seq != last_sepc_write_seq && cyc >= 1'183'000'000ULL && cyc <= 1'186'000'000ULL) {
            last_sepc_write_seq = top->dbg_sepc_write_seq;
            fprintf(stderr, "[SEPC-WRITE cyc %llu seq=%u] pc=0x%llx val=0x%llx\n",
                    (unsigned long long)cyc, top->dbg_sepc_write_seq,
                    (unsigned long long)top->dbg_sepc_write_pc, (unsigned long long)top->dbg_sepc_write_val);
        }
        static uint32_t last_load_seq = 0xFFFFFFFFu;
        if (top->dbg_load_seq != last_load_seq && cyc >= 1'183'000'000ULL && cyc <= 1'186'000'000ULL) {
            last_load_seq = top->dbg_load_seq;
            if (inWindow) {
                fprintf(stderr, "[LOAD cyc %llu seq=%u] pc=0x%llx va=0x%llx paddr=0x%llx rd=%u val=0x%llx\n",
                        (unsigned long long)cyc, top->dbg_load_seq,
                        (unsigned long long)top->dbg_load_pc, (unsigned long long)top->dbg_load_va,
                        (unsigned long long)top->dbg_load_phys_addr, (unsigned)top->dbg_load_rd,
                        (unsigned long long)top->dbg_load_val);
            }
            // The exact crashing LOAD identified earlier: userspace pc=0xb8310,
            // rd=1 (ra), reading -1 from what should be a valid saved return
            // address. Print unconditionally (full run, no window) WITH satp,
            // to compare its address-space (page-table root) against the
            // COW-fault event that populated this physical page, and settle
            // whether they're the same mm (pre-exec fork child still running)
            // or genuinely different mms (post-execve, meaning the new stack
            // wrongly aliased a stale physical page).
            if (top->dbg_load_pc == 0xb8310ULL && top->dbg_load_rd == 1) {
                fprintf(stderr, "[CRASH-LOAD cyc %llu seq=%u] pc=0x%llx va=0x%llx paddr=0x%llx val=0x%llx satp=0x%llx priv=%d\n",
                        (unsigned long long)cyc, top->dbg_load_seq,
                        (unsigned long long)top->dbg_load_pc, (unsigned long long)top->dbg_load_va,
                        (unsigned long long)top->dbg_load_phys_addr, (unsigned long long)top->dbg_load_val,
                        (unsigned long long)top->dbg_satp, (int)top->dbg_priv_level);
            }
        }
        static uint32_t last_store_seq2 = 0xFFFFFFFFu;
        if (top->dbg_store_seq != last_store_seq2) {
            last_store_seq2 = top->dbg_store_seq;
            if (inWindow) {
                fprintf(stderr, "[STORE cyc %llu seq=%u] pc=0x%llx va=0x%llx paddr=0x%llx data=0x%llx\n",
                        (unsigned long long)cyc, top->dbg_store_seq,
                        (unsigned long long)top->dbg_store_pc, (unsigned long long)top->dbg_store_va,
                        (unsigned long long)top->dbg_store_phys_addr,
                        (unsigned long long)top->dbg_store_data);
            }
        }

        // Root cause found (2026-07-09): the crashing LOAD is
        // [LOAD cyc 1182828224 seq=8315926] pc=0xb8310 va=0x3ff3775648
        // paddr=0x608648 rd=1 val=0xffffffffffffffff -- pure userspace
        // (priv=0), an ordinary function-epilogue `ld ra, ...(sp)` reading
        // -1 out of what should be a valid saved return address on the new
        // process's stack, 8 bytes below the crash dump's reported sp
        // (0x3ff3775650). That -1 register value is what later becomes the
        // jalr `ret`'s target, masked to epc=-2 on the fault. Question: was
        // this exact word EVER legitimately written before this load, or is
        // it uninitialized SDRAM content (never-zeroed page)? Full-run
        // (unconditional, no cycle-window gate -- this is the whole reason
        // for --save-at/--load checkpointing) search for any store landing
        // in this physical word, +/- a few words for alignment slop.
        static uint32_t last_store_seq3 = 0xFFFFFFFFu;
        if (top->dbg_store_seq != last_store_seq3) {
            last_store_seq3 = top->dbg_store_seq;
            uint64_t sp = (unsigned long long)top->dbg_store_phys_addr;
            // Destination page (0x608xxx, the eventual crash page) AND the
            // memcpy's SOURCE page (0x60exxx, the parent's saved pt_regs) --
            // asking the same "was this word ever legitimately written"
            // question one level further back in the same run. Widened to
            // the FULL 4KB page (not just the 128-byte frame slice) to catch
            // a write landing at an unexpected offset within the same page.
            if ((sp >= 0x608000ULL && sp <= 0x608FFFULL) ||
                (sp >= 0x60e000ULL && sp <= 0x60eFFFULL)) {
                fprintf(stderr, "[STACKWORD-STORE cyc %llu seq=%u] pc=0x%llx va=0x%llx paddr=0x%llx data=0x%llx satp=0x%llx priv=%d\n",
                        (unsigned long long)cyc, top->dbg_store_seq,
                        (unsigned long long)top->dbg_store_pc, (unsigned long long)top->dbg_store_va,
                        (unsigned long long)top->dbg_store_phys_addr,
                        (unsigned long long)top->dbg_store_data,
                        (unsigned long long)top->dbg_satp, (int)top->dbg_priv_level);
            }
        }

        // Musl fork()'s own prologue/epilogue, identified via the unstripped
        // busybox build: pc=0xb8190 is `sd ra,184(sp)` (saving the address
        // of whoever called fork()), pc=0xb8310 is `ld ra,184(sp)` in the
        // SAME function's epilogue. _Fork() (the clone syscall) runs
        // in between, in the same function body. Trace EVERY fork() call's
        // prologue store and epilogue load system-wide (unconditional, full
        // run -- these are individually rare) to see what physical address
        // each actually targets and whether the crash's satp
        // (0x80002000000009f4) has a matching, correctly-written prologue.
        static uint32_t last_store_seq4 = 0xFFFFFFFFu;
        if (top->dbg_store_seq != last_store_seq4) {
            last_store_seq4 = top->dbg_store_seq;
            if (top->dbg_store_pc == 0xb8190ULL) {
                fprintf(stderr, "[FORK-PROLOGUE-STORE cyc %llu seq=%u] va=0x%llx paddr=0x%llx data=0x%llx satp=0x%llx\n",
                        (unsigned long long)cyc, top->dbg_store_seq,
                        (unsigned long long)top->dbg_store_va, (unsigned long long)top->dbg_store_phys_addr,
                        (unsigned long long)top->dbg_store_data, (unsigned long long)top->dbg_satp);
            }
        }
        static uint32_t last_load_seq2 = 0xFFFFFFFFu;
        if (top->dbg_load_seq != last_load_seq2) {
            last_load_seq2 = top->dbg_load_seq;
            if (top->dbg_load_pc == 0xb8310ULL) {
                fprintf(stderr, "[FORK-EPILOGUE-LOAD cyc %llu seq=%u] va=0x%llx paddr=0x%llx val=0x%llx satp=0x%llx priv=%d\n",
                        (unsigned long long)cyc, top->dbg_load_seq,
                        (unsigned long long)top->dbg_load_va, (unsigned long long)top->dbg_load_phys_addr,
                        (unsigned long long)top->dbg_load_val, (unsigned long long)top->dbg_satp, (int)top->dbg_priv_level);
            }
        }
        // Chasing whether the kernel ever flushes Hutt's TLB (sfence.vma)
        // between the parent's fork()-prologue write (correct) and its own
        // later illegitimate direct write to the same shared page -- a
        // stale writable TLB entry surviving a COW read-only downgrade
        // would explain the parent writing straight through without a
        // fault. Unconditional, full run -- sfence.vma is individually rare.
        static uint32_t last_sfence_seq = 0xFFFFFFFFu;
        if (top->dbg_sfence_seq != last_sfence_seq) {
            last_sfence_seq = top->dbg_sfence_seq;
            fprintf(stderr, "[SFENCE-VMA cyc %llu seq=%u] pc=0x%llx satp=0x%llx priv=%d\n",
                    (unsigned long long)cyc, top->dbg_sfence_seq,
                    (unsigned long long)top->dbg_sfence_pc, (unsigned long long)top->dbg_satp, (int)top->dbg_priv_level);
        }
    }

    fprintf(stderr, "\nDone: %llu cycles simulated, no more output expected within budget.\n",
            (unsigned long long)max_cycles);

    if (tfp) { tfp->close(); delete tfp; }
    delete top;
    return 0;
}
