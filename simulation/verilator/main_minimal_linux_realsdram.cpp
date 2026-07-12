// Minimal standalone Verilator harness for MinimalSocRealSdramSimTop:
// preloads real OpenSBI/Linux firmware directly into SdramChipModel's
// memory via the dbg_* backdoor (bypassing FlashBootLoader's byte-serial SPI
// copy), releases reset, and watches uo_out bit 6 (the debug console UART TX,
// matching MinimalSoC's `Cat(0.U(1.W), debug_uart_txd, 0.U(6.W))`) for
// OpenSBI/Linux boot output. Unlike main_minimal_linux.cpp (idealized
// SdramBackendSim), this goes through the REAL SdramBackend/SdramController/
// SdramChipModel JEDEC-protocol co-sim.
//
// Usage: ./minimal_linux_realsdram_sim <firmware.bin> [max_cycles] [trace_start] [trace_end]
// Passing trace_start/trace_end dumps an FST waveform (waveform.fst) for that
// cycle window (see main_minimal_linux.cpp for the same convention).
//
// Checkpointing (same convention as main_minimal_linux.cpp -- avoids re-paying
// the ~minutes-long boot-to-160M-cycles cost on every rerun when only the
// logging window needs to move while chasing task #15's instruction-fetch
// corruption bug): pass `--save-at CYCLE --save-path PATH` to serialize full
// DUT state once `cyc` reaches CYCLE, then exit. Later, pass `--load PATH
// --start-cycle CYCLE` to restore and resume the eval loop from there,
// skipping reset/preload entirely -- firmware.bin is not needed in that mode.

#include "../common/uart_decoder.h"
#include <VMinimalSocRealSdramSimTop.h>
#include <verilated.h>
#include <verilated_fst_c.h>
#include <verilated_save.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <vector>

int main(int argc, char** argv) {
    std::string fw_path;
    std::string load_path, save_path;
    uint64_t start_cycle = 0;
    uint64_t save_at = (uint64_t)-1;
    std::vector<std::string> positional;

    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        if (a == "--load" && i + 1 < argc) { load_path = argv[++i]; }
        else if (a == "--start-cycle" && i + 1 < argc) { start_cycle = strtoull(argv[++i], nullptr, 10); }
        else if (a == "--save-at" && i + 1 < argc) { save_at = strtoull(argv[++i], nullptr, 10); }
        else if (a == "--save-path" && i + 1 < argc) { save_path = argv[++i]; }
        else { positional.push_back(a); }
    }

    size_t posIdx = 0;
    if (load_path.empty()) {
        if (positional.empty()) {
            fprintf(stderr, "Usage: %s <firmware.bin> [max_cycles] [trace_start] [trace_end] "
                             "[--save-at CYCLE --save-path PATH]\n", argv[0]);
            fprintf(stderr, "   or: %s --load PATH --start-cycle CYCLE [max_cycles] [trace_start] [trace_end]\n", argv[0]);
            return 1;
        }
        fw_path = positional[posIdx++];
    }
    uint64_t max_cycles = positional.size() > posIdx ? strtoull(positional[posIdx++].c_str(), nullptr, 10) : 20'000'000ULL;
    uint64_t trace_start = positional.size() > posIdx ? strtoull(positional[posIdx++].c_str(), nullptr, 10) : (uint64_t)-1;
    uint64_t trace_end   = positional.size() > posIdx ? strtoull(positional[posIdx++].c_str(), nullptr, 10) : (uint64_t)-1;

    Verilated::commandArgs(argc, argv);
    Verilated::traceEverOn(true);

    std::vector<uint8_t> fw;
    size_t fw_size = 0;
    if (load_path.empty()) {
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
        // payload) vs. a bare fw_payload.bin — see main_minimal_linux.cpp for the
        // full story (loading a wrapped file unstripped shifts the whole image 4
        // bytes from where it truly belongs, which OpenSBI's own fw_start
        // relocation sanity check correctly, if misleadingly, flags as a fault).
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
        fw.assign(fw_data, fw_data + fw_size);
    }

    auto* top = new VMinimalSocRealSdramSimTop;

    VerilatedFstC* tfp = nullptr;
    if (trace_start != (uint64_t)-1) {
        tfp = new VerilatedFstC;
        top->trace(tfp, 99);
        tfp->open("waveform.fst");
        fprintf(stderr, "Tracing enabled: cycles [%llu, %llu] -> waveform.fst\n",
                (unsigned long long)trace_start, (unsigned long long)trace_end);
    }
    uint64_t vtime = 0;

    if (!load_path.empty()) {
        fprintf(stderr, "Restoring checkpoint from %s (resuming at cyc %llu)...\n",
                load_path.c_str(), (unsigned long long)start_cycle);
        VerilatedRestore is;
        is.open(load_path.c_str());
        is >> *top;
        is.close();
        fprintf(stderr, "Checkpoint restored.\n");
    } else {
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
    }

    UartDecoder dec;
    dec.set_cycles_per_bit(217);  // 25 MHz / 115200 baud

    std::string line_buf;
    uint64_t last_report = start_cycle;
    uint32_t last_instr_addr = 0xFFFFFFFF;
    uint64_t last_progress_cyc = start_cycle;
    uint32_t last_trap_seq = 0;
    uint32_t last_x18_write_seq = 0;
    for (uint64_t cyc = start_cycle; cyc < max_cycles; cyc++) {
        bool tracing = tfp && cyc >= trace_start && cyc <= trace_end;
        top->clk = 0; top->eval();
        if (tracing) { tfp->dump(vtime++); }
        top->clk = 1; top->eval();
        if (tracing) { tfp->dump(vtime++); }

        if (cyc >= save_at && !save_path.empty()) {
            fprintf(stderr, "[CHECKPOINT] saving state at cyc %llu -> %s\n",
                    (unsigned long long)cyc, save_path.c_str());
            VerilatedSave os;
            os.open(save_path.c_str());
            os << *top;
            os.close();
            fprintf(stderr, "[CHECKPOINT] saved. Exiting.\n");
            if (tfp) { tfp->close(); delete tfp; }
            delete top;
            return 0;
        }

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

        if (top->dbg_trap_seq != last_trap_seq) {
            fprintf(stderr,
                "[TRAP seq=%u cyc %llu] from_pc=0x%llx cause=0x%llx to_priv=%d target_pc=0x%llx "
                "mtvec=0x%llx stvec=0x%llx ra=0x%llx\n",
                top->dbg_trap_seq, (unsigned long long)cyc,
                (unsigned long long)top->dbg_trap_from_pc, (unsigned long long)top->dbg_trap_cause,
                top->dbg_trap_to_priv, (unsigned long long)top->dbg_trap_target_pc,
                (unsigned long long)top->dbg_trap_mtvec, (unsigned long long)top->dbg_trap_stvec,
                (unsigned long long)top->dbg_ra);
            last_trap_seq = top->dbg_trap_seq;
        }

        // Superseded by the fetch-corruption finding below (x18 was never
        // actually the bug, just a decode artifact of the corrupted
        // instruction) -- disabled for long unattended runs, since it fires
        // unconditionally on every x18 write with no rate limit (thousands
        // of events within the first 20M cycles alone).
        if (getenv("SIM_X18_TRACE") && top->dbg_x18_write_seq != last_x18_write_seq) {
            fprintf(stderr,
                "[X18-WRITE seq=%u cyc %llu] pc=0x%llx val=0x%llx\n",
                top->dbg_x18_write_seq, (unsigned long long)cyc,
                (unsigned long long)top->dbg_x18_write_pc,
                (unsigned long long)top->dbg_x18_write_val);
            last_x18_write_seq = top->dbg_x18_write_seq;
        }

        // Task #15 (2026-07-10, extended): the corrupted nputs+0x2c fetch
        // (word addr 0x238, byte 0x8e0) turned out to be an InstrCache HIT
        // (2-cycle BRAM read), not a live SDRAM race -- see the REGW trace
        // below. That means the bad word 0x00800900 was written into the
        // cache at some EARLIER fill and has been served ever since. Log
        // every fill to this cache line's INDEX (0x038, so this also catches
        // any aliasing address that maps to the same line: 0x038, 0x238,
        // 0x438, ...) across the FULL run, unconditional -- fills are rare
        // relative to total cycles, so this stays small, and it finds the
        // exact historical fill event (and whether the data was already
        // corrupt AT fill time, implicating the real SDRAM path, or the
        // fill was correct and something else clobbers the BRAM afterward)
        // in one run instead of bisecting backward with checkpoints.
        // Task #15 (2026-07-10, latest pivot): SdramChipModel's own accAddr
        // reconstruction is CORRECT for the corrupted word-0x470 read
        // (matches hand-computed 0x000470 exactly) yet the stored data
        // itself is already wrong -- and hundreds of EARLIER reads of this
        // same word (cyc 8.8M-48.7M, see ICACHE-FILL trace) were correct.
        // That means something WROTE over this address between the last
        // good read and the corrupted one -- not a read-path bug at all.
        // Unconditional, full-run trace of every chip write landing on word
        // 0x470 or 0x471 (byte 0x8e0-0x8e3, the corrupted instruction) to
        // find the culprit write's cycle/data in one run.
        // Task #15: narrow window bracketing the two CHIP-WRITE events found
        // in the prior run (cyc 52,465,968 / 52,465,979) -- prints every CPU
        // store in that window to identify the exact instruction (PC) and
        // the address/data IT thought it was writing, to see whether this is
        // an address-computation bug (writing to a wrong-but-plausible
        // location) or something stranger.
        static uint32_t last_store_seq_win = 0;
        if (top->dbg_store_seq != last_store_seq_win) {
            last_store_seq_win = top->dbg_store_seq;
            if (cyc >= 52'465'900ULL && cyc <= 52'466'050ULL) {
                fprintf(stderr,
                    "[STORE seq=%u cyc %llu] pc=0x%llx phys_addr=0x%llx data=0x%llx\n",
                    top->dbg_store_seq, (unsigned long long)cyc,
                    (unsigned long long)top->dbg_store_pc,
                    (unsigned long long)top->dbg_store_phys_addr,
                    (unsigned long long)top->dbg_store_data);
            }
        }

        static uint32_t last_chip_write_seq = 0;
        if (top->dbg_chip_write_seq != last_chip_write_seq) {
            last_chip_write_seq = top->dbg_chip_write_seq;
            if (top->dbg_chip_write_acc_addr == 0x470u || top->dbg_chip_write_acc_addr == 0x471u) {
                fprintf(stderr,
                    "[CHIP-WRITE seq=%u cyc %llu] acc_addr=0x%06x data=0x%04x dqm=0x%x\n",
                    top->dbg_chip_write_seq, (unsigned long long)cyc,
                    top->dbg_chip_write_acc_addr, top->dbg_chip_write_data, (unsigned)top->dbg_chip_write_dqm);
            }
        }

        static uint32_t last_ic_fill_seq = 0;
        if (top->dbg_ic_fill_seq != last_ic_fill_seq) {
            last_ic_fill_seq = top->dbg_ic_fill_seq;
            // Matches ANY address aliasing to this cache-line index (not just
            // the one specific corrupted address from the earlier
            // investigation), so this fires very frequently across a large
            // kernel's code footprint -- gated off by default for long
            // unattended runs to avoid runaway log volume (~100M+ events
            // extrapolated over a 20B-cycle run).
            if (getenv("SIM_ICACHE_TRACE") && (top->dbg_ic_fill_word_addr & 0x1FFu) == 0x038u) {
                fprintf(stderr,
                    "[ICACHE-FILL seq=%u cyc %llu] word_addr=0x%x (byte=0x%x) data=0x%08x\n",
                    top->dbg_ic_fill_seq, (unsigned long long)cyc,
                    top->dbg_ic_fill_word_addr, top->dbg_ic_fill_word_addr << 2,
                    top->dbg_ic_fill_data);
            }
        }

        // Task #15 (2026-07-10, isolated the exact corruption event): the
        // ICACHE-FILL trace above found the corrupted fill for word_addr
        // 0x238 (byte 0x8e0) happens exactly once, at cyc 52,676,975 -- every
        // fill before that (hundreds) correctly returned 0x00058913; this one
        // returned 0x00800900 and every later access was a cache HIT
        // replaying that bad word. Unconditional dump of the real
        // MemoryController/SdramBackend/SdramController state around that
        // exact cycle (already-wired signals -- no new probes needed) to see
        // the actual bad SDRAM transaction's shape, not diluted by hundreds
        // of innocent cache hits like the cyc-159M window was.
        if (cyc >= 52'676'900ULL && cyc <= 52'677'050ULL) {
            fprintf(stderr,
                "[SDRAM cyc %llu] mem_state=%d be_state=%d ctrl_state=%d ctrl_rdy=%d | "
                "instr(req_v=%d req_r=%d resp_v=%d addr=0x%x) | "
                "ic_state=%d ic_addr_reg=0x%x ic_is_hit=%d ic_fill_seq=%u | "
                "dq_in=0x%04x be_readword=0x%04x mem_hw0=0x%04x mem_hw1=0x%04x\n",
                (unsigned long long)cyc,
                top->dbg_mem_state, top->dbg_be_state, top->dbg_ctrl_state, top->dbg_ctrl_rdy,
                top->dbg_instr_req_valid, top->dbg_instr_req_ready, top->dbg_instr_resp_valid,
                top->dbg_instr_addr,
                top->dbg_ic_state, top->dbg_ic_addr_reg, top->dbg_ic_is_hit, top->dbg_ic_fill_seq,
                top->dbg_dq_in, top->dbg_be_readword, top->dbg_mem_hw0, top->dbg_mem_hw1);
        }

        // Task #15 (further localizing the confirmed dq_in-level
        // corruption): SdramChipModel's own address-reconstruction/read
        // decode, printed on every cycle its READ or ACTIVATE command is
        // asserted (rare -- one cycle per command, so unconditional in this
        // window is fine) so the chip's ba/row/col can be checked against
        // hand-computed expectations (halfword 0 = word addr 0x470 ->
        // ba=2,row=0,col=0x70; halfword 1 = word addr 0x471 -> col=0x71).
        if (cyc >= 52'676'900ULL && cyc <= 52'677'050ULL &&
            (top->dbg_chip_is_read || top->dbg_chip_is_act)) {
            fprintf(stderr,
                "[CHIP cyc %llu] %s ba=%u addr_pin=0x%03x col=0x%02x open_row=0x%03x "
                "acc_addr=0x%06x rd_data=0x%04x\n",
                (unsigned long long)cyc,
                top->dbg_chip_is_act ? "ACT " : "READ",
                (unsigned)top->dbg_chip_ba, (unsigned)top->dbg_chip_addr_pin,
                (unsigned)top->dbg_chip_col, (unsigned)top->dbg_chip_open_row,
                (unsigned)top->dbg_chip_acc_addr, (unsigned)top->dbg_chip_rd_data);
        }

        // Task #15: unconditional, every-cycle (not just on fetch-address
        // change) dump of pc/regfile-write-bus, narrowly windowed around one
        // occurrence of nputs+0x2c (`mv s2,a1`, byte 0x8e0) during the known
        // hang loop -- to see whether wen ever asserts with wAddr=18 there at
        // all, since the FSM may take multiple cycles per instruction and
        // the address-change-gated FINE print above can miss the exact cycle.
        //
        // Extended (2026-07-10, same investigation): the prior REGW-only trace
        // showed the CORRUPTED fetch (pc=0x8e0) completing its Hutt-side
        // sFetchReq->sFetchResp->sExec handshake in just TWO cycles -- far
        // faster than MemoryController's real-SDRAM FSM should ever allow for
        // a fresh 2-halfword fetch (the preceding LD at 0x8d8 needed ~18
        // cycles in sMemResp for ONE halfword-equivalent access). That means
        // either the fetch wasn't a fresh transaction at all (stale
        // req/resp.fire), or MemoryController/backend/controller genuinely
        // raced through their FSMs abnormally fast. Add the already-wired
        // MemoryController/SdramBackend/SdramController state signals
        // (dbg_mem_state/be_state/ctrl_state/ctrl_rdy plus the instr
        // req/resp handshake bits) to see exactly what those layers were
        // doing, cycle by cycle, through this exact window.
        // Task #15 (2026-07-11): the real-SDRAM sim independently reproduces
        // the real-hardware hang using the SAME plain firmware, first
        // observed stalled from cyc ~898,666,662 onward (right after "printk:
        // legacy bootconsole [sbi0] disabled" -- matches real hardware's
        // exact stall line). Checkpointed at cyc 910,000,000 (well inside the
        // stall). This traces the literal PC sequence executed during the
        // stall to see whether it's a tight fixed-PC spin (software
        // wait-condition bug) or varied work each attempt (points toward a
        // scheduler/RCU/interrupt-delivery stall instead).
        static uint64_t last_pc_trace = 0xffffffffffffffffULL;
        if (cyc >= 910'000'000ULL && cyc <= 910'050'000ULL && top->dbg_pc != last_pc_trace) {
            fprintf(stderr, "[PCTRACE cyc %llu] pc=0x%llx instr=0x%08x wen=%d waddr=%llu wdata=0x%llx\n",
                    (unsigned long long)cyc, (unsigned long long)top->dbg_pc,
                    (unsigned int)top->dbg_instr, top->dbg_reg_wen,
                    (unsigned long long)top->dbg_reg_waddr, (unsigned long long)top->dbg_reg_wdata);
            last_pc_trace = top->dbg_pc;
        }

        // Task #15 (2026-07-11, follow-up): the PC trace above showed only
        // normal timer-tick housekeeping (timekeeping_update_from_shadow,
        // task_tick_fair/update_curr) during the stall -- no lockup, no spin
        // loop. Widened window (910M-916M, ~6M cycles/~2.4s hw-equivalent),
        // watching entry PCs of the actual scheduling-decision functions to
        // see whether __schedule/pick_next_task_fair are ever invoked at all
        // during the stall (vs. only the tick's accounting running, which
        // would point at a wake-up/need_resched bug rather than a "keeps
        // rescheduling idle" bug).
        static uint32_t sched_entry_hits = 0;
        static uint64_t last_sched_pc = 0xffffffffffffffffULL;
        if (cyc <= 916'000'000ULL && top->dbg_pc != last_sched_pc) {
            uint64_t pc = top->dbg_pc;
            const char* fn = nullptr;
            if (pc == 0xffffffff802d5ac8ULL) fn = "__schedule";
            else if (pc == 0xffffffff80047a4cULL) fn = "pick_next_task_fair";
            else if (pc == 0xffffffff8003dbecULL) fn = "try_to_wake_up";
            else if (pc == 0xffffffff8003df74ULL) fn = "wake_up_process";
            else if (pc == 0xffffffff802d62e4ULL) fn = "schedule_idle";
            else if (pc == 0xffffffff8004bb0cULL) fn = "do_idle";
            else if (pc == 0xffffffff8003bfc8ULL) fn = "resched_curr";
            else if (pc == 0xffffffff8003c048ULL) fn = "resched_cpu";
            else if (pc == 0xffffffff8003c008ULL) fn = "resched_curr_lazy";
            else if (pc == 0xffffffff8003c474ULL) fn = "wakeup_preempt";
            else if (pc == 0xffffffff800481e0ULL) fn = "wakeup_preempt_idle";
            else if (pc == 0xffffffff802d6284ULL) fn = "__cond_resched";
            else if (pc == 0xffffffff802d60f0ULL) fn = "schedule";
            else if (pc == 0xffffffff80270244ULL) fn = "khvcd";
            else if (pc == 0xffffffff8026ff50ULL) fn = "__hvc_poll";
            if (fn) {
                fprintf(stderr, "[SCHED-ENTRY hit=%u cyc %llu] %s a0=0x%llx\n",
                        sched_entry_hits, (unsigned long long)cyc, fn,
                        (unsigned long long)top->dbg_a0);
                sched_entry_hits++;
            }
            last_sched_pc = pc;
        }

        // Task #15 (2026-07-11, follow-up #2): __schedule/try_to_wake_up/
        // wake_up_process never fire cyc 910M-916M (see SCHED-ENTRY above).
        // Since the checkpoint starts AT 910M (already mid-stall), it can't
        // show whether the wake-up call for pid 22 (or synchronize_srcu's
        // grace-period completion) happened BEFORE the stall began -- this
        // probe runs unconditionally across the WHOLE run (cheap: these are
        // rare one-shot events, not per-tick) from cycle 0 up to 916M to
        // find out whether wake_up_new_task/complete/complete_all/
        // swake_up_one are ever called at all before the stall, and if so,
        // exactly when (their last-seen cycle vs. the stall's cyc ~898.6M
        // onset pins down whether the wake-up call is missing entirely or
        // happens but has no effect).
        static uint32_t wake_entry_hits = 0;
        static uint64_t last_wake_pc = 0xffffffffffffffffULL;
        if (cyc <= 916'000'000ULL && top->dbg_pc != last_wake_pc) {
            uint64_t pc = top->dbg_pc;
            const char* fn = nullptr;
            if (pc == 0xffffffff8003e490ULL) fn = "wake_up_new_task";
            else if (pc == 0xffffffff80053b04ULL) fn = "complete";
            else if (pc == 0xffffffff80053b8cULL) fn = "complete_all";
            else if (pc == 0xffffffff80054184ULL) fn = "swake_up_one";
            if (fn) {
                fprintf(stderr, "[WAKE-ENTRY hit=%u cyc %llu] %s a0=0x%llx\n",
                        wake_entry_hits, (unsigned long long)cyc, fn,
                        (unsigned long long)top->dbg_a0);
                wake_entry_hits++;
            }
            last_wake_pc = pc;
        }

        // Task #15 (2026-07-11, follow-up #3, most targeted probe): do_idle's
        // OWN entry PC (0x...bb0c) is a one-time hit (function entered once
        // at boot, loops internally) so it never refires -- the REAL
        // repeated check happens inside the loop body at 0x...bb30 ("ld
        // a5,0(tp); andi a5,a5,16; bnez a5,...bb98" -- testing bit 4 (value
        // 16) of thread_info->flags, loaded via tp at offset 0, branching to
        // schedule_idle if set). If TIF_NEED_RESCHED truly is being set
        // (per resched_curr_lazy's continuous firing + the is_idle_task
        // promotion-to-urgent logic in __resched_curr), this check MUST see
        // it and branch away -- if it doesn't, this is the exact broken
        // link. Watch entry to this specific loop-check PC and read x15
        // (a5) as the VERY NEXT regfile write after it, to see the actual
        // loaded+masked flags value live during the stall.
        static uint32_t idle_check_hits = 0;
        if (cyc <= 916'000'000ULL && top->dbg_pc >= 0xffffffff8004bb0cULL &&
            top->dbg_pc <= 0xffffffff8004bb9cULL &&
            top->dbg_reg_wen && top->dbg_reg_waddr == 15) {
            fprintf(stderr, "[IDLE-CHECK hit=%u cyc %llu] pc=0x%llx a5=0x%llx\n",
                    idle_check_hits, (unsigned long long)cyc,
                    (unsigned long long)top->dbg_pc,
                    (unsigned long long)top->dbg_reg_wdata);
            idle_check_hits++;
        }

        // Task #15 (2026-07-11, follow-up #4): the 910M-3.5B extension
        // showed the system permanently cycling among just 4 kernel
        // threads (swapper/0=pid1, kworker/u4:0, kworker/u4:1,
        // ksoftirqd/0) with ZERO further kernel_clone() calls across 2.59B
        // cycles, and the two dominant interrupted-PCs are
        // finish_task_switch (scheduler thrashing) and crng_make_state
        // (random.c). crng_make_state's disassembly reads the global
        // `crng_init` state variable (0xffffffff80541c44) at
        // 0x...79234 (`lw a2,...<crng_init>`) and branches on it -- if
        // crng_init never reaches 2 ("ready"), anything blocked on
        // wait_for_random_bytes()/get_random_bytes_wait() could stall
        // indefinitely (a well-known real-world Linux boot-hang class on
        // platforms lacking a hardware RNG, worsened by a deterministic
        // cycle-accurate simulator's lack of real interrupt-timing
        // jitter to credit as entropy). Watch the regfile writeback of
        // that exact `lw a2,<crng_init>` to see its live value over time.
        static uint32_t crng_init_hits = 0;
        static uint64_t last_crng_init_val = 0xffffffffffffffffULL;
        if (top->dbg_pc >= 0xffffffff80279220ULL && top->dbg_pc <= 0xffffffff80279260ULL &&
            top->dbg_reg_wen && top->dbg_reg_waddr == 12) {
            uint64_t val = (unsigned long long)top->dbg_reg_wdata;
            if (val != last_crng_init_val || (crng_init_hits % 50) == 0) {
                fprintf(stderr, "[CRNG-INIT hit=%u cyc %llu] pc=0x%llx crng_init_or_a2=%llu\n",
                        crng_init_hits, (unsigned long long)cyc,
                        (unsigned long long)top->dbg_pc, (unsigned long long)val);
            }
            last_crng_init_val = val;
            crng_init_hits++;
        }

        // Task #15 (2026-07-12): now that the rng-seed fix gets boot past
        // the crng_init stall, T1 ("ls /bin | cat") itself hangs -- matching
        // /init's own comment that T1 was written to bisect task #15's
        // documented real-hardware-only race in vfs_statx's path-walk
        // (filename_lookup). Watch entry into both functions (unconditional,
        // whole run) to see whether the stall is BEFORE vfs_statx is ever
        // reached (exec/pipe/fork setup) or WITHIN the path walk itself.
        static uint32_t vfs_statx_hits = 0;
        static uint32_t filename_lookup_hits = 0;
        if (top->dbg_pc == 0xffffffff800f9704ULL) {
            fprintf(stderr, "[VFS-STATX hit=%u cyc %llu]\n", vfs_statx_hits, (unsigned long long)cyc);
            vfs_statx_hits++;
        }
        if (top->dbg_pc == 0xffffffff80104e84ULL) {
            fprintf(stderr, "[FILENAME-LOOKUP hit=%u cyc %llu]\n", filename_lookup_hits, (unsigned long long)cyc);
            filename_lookup_hits++;
        }
        // Follow-up: vfs_statx is called exactly 3x (cyc ~4569M/4870M/4876M)
        // then never again through 6B cycles -- ls never gets to actually
        // list /bin's ~87 entries. Narrow further: is the stall before
        // getdents64/iterate_dir/dcache_readdir are ever reached, or within?
        static uint32_t getdents_hits = 0, iterdir_hits = 0, dcachedir_hits = 0;
        if (top->dbg_pc == 0xffffffff8010a454ULL) {
            fprintf(stderr, "[GETDENTS64 hit=%u cyc %llu]\n", getdents_hits, (unsigned long long)cyc);
            getdents_hits++;
        }
        if (top->dbg_pc == 0xffffffff80109ce0ULL) {
            fprintf(stderr, "[ITERATE-DIR hit=%u cyc %llu]\n", iterdir_hits, (unsigned long long)cyc);
            iterdir_hits++;
        }
        if (top->dbg_pc == 0xffffffff80125e64ULL) {
            fprintf(stderr, "[DCACHE-READDIR hit=%u cyc %llu]\n", dcachedir_hits, (unsigned long long)cyc);
            dcachedir_hits++;
        }
        // Follow-up 2: getdents64/iterate_dir/dcache_readdir are NEVER
        // reached (0 hits through 7B cycles) even though vfs_statx fires
        // exactly 3x then stops -- so the stall is upstream of directory
        // reading. Check whether the shell ever forks+execs ls and cat, and
        // sets up their pipe, at all.
        static uint32_t clone_hits = 0, execve_hits = 0, pipe_hits = 0, wait_hits = 0;
        if (top->dbg_pc == 0xffffffff800140e0ULL) {
            fprintf(stderr, "[KERNEL-CLONE hit=%u cyc %llu]\n", clone_hits, (unsigned long long)cyc);
            clone_hits++;
        }
        if (top->dbg_pc == 0xffffffff800fa650ULL) {
            fprintf(stderr, "[BPRM-EXECVE hit=%u cyc %llu]\n", execve_hits, (unsigned long long)cyc);
            execve_hits++;
        }
        if (top->dbg_pc == 0xffffffff800fe5d0ULL) {
            fprintf(stderr, "[DO-PIPE2 hit=%u cyc %llu]\n", pipe_hits, (unsigned long long)cyc);
            pipe_hits++;
        }
        if (top->dbg_pc == 0xffffffff80017bb0ULL) {
            fprintf(stderr, "[DO-WAIT hit=%u cyc %llu]\n", wait_hits, (unsigned long long)cyc);
            wait_hits++;
        }

        if (cyc >= 159'978'440ULL && cyc <= 159'978'500ULL) {
            fprintf(stderr,
                "[REGW cyc %llu] pc=0x%llx wen=%d waddr=%llu wdata=0x%llx state=%d wbExecEn=%d d.rd=%llu instr=0x%08x | "
                "mem_state=%d be_state=%d ctrl_state=%d ctrl_rdy=%d instr(req_v=%d req_r=%d resp_v=%d addr=0x%x) | "
                "ic_state=%d ic_addr_reg=0x%x ic_is_hit=%d\n",
                (unsigned long long)cyc, (unsigned long long)top->dbg_pc,
                top->dbg_reg_wen, (unsigned long long)top->dbg_reg_waddr,
                (unsigned long long)top->dbg_reg_wdata, top->dbg_state,
                top->dbg_wbexec_en, (unsigned long long)top->dbg_d_rd,
                (unsigned int)top->dbg_instr,
                top->dbg_mem_state, top->dbg_be_state, top->dbg_ctrl_state, top->dbg_ctrl_rdy,
                top->dbg_instr_req_valid, top->dbg_instr_req_ready, top->dbg_instr_resp_valid,
                top->dbg_instr_addr,
                top->dbg_ic_state, top->dbg_ic_addr_reg, top->dbg_ic_is_hit);
        }

        if (top->dbg_instr_addr != last_instr_addr) {
            // PC-gated: fires every time the fetch address BECOMES
            // print+0x394's `add s2,s2,a0` (word addr 0x500 = byte 0x1400),
            // showing a0/s2/s3 at that exact instant -- settles whether
            // nputs()'s return value genuinely reaches print() as 1
            // (software-visible data issue elsewhere) or arrives as
            // something else (Hutt call/return-value correctness bug).
            static uint32_t add_s2_hits = 0;
            if (top->dbg_instr_addr == 0x500 && cyc >= 50'000'000ULL && add_s2_hits < 200) {
                fprintf(stderr, "[ADD-S2 hit=%u cyc %llu] a0=0x%llx s2(before)=0x%llx s3=0x%llx\n",
                        add_s2_hits, (unsigned long long)cyc, (unsigned long long)top->dbg_a0,
                        (unsigned long long)top->dbg_s2, (unsigned long long)top->dbg_s3);
                add_s2_hits++;
            }
            // Same idea, but at nputs()'s OWN `ret` (word addr 0x261 = byte
            // 0x984) -- if a0 is already 0 HERE, the bug is inside nputs()
            // itself (or the call into it), not in anything print() does
            // afterward (nothing executes between the two probe points).
            static uint32_t nputs_ret_hits = 0;
            if (top->dbg_instr_addr == 0x261 && cyc >= 50'000'000ULL && nputs_ret_hits < 200) {
                fprintf(stderr, "[NPUTS-RET hit=%u cyc %llu] a0=0x%llx s2=0x%llx s5=0x%llx\n",
                        nputs_ret_hits, (unsigned long long)cyc, (unsigned long long)top->dbg_a0,
                        (unsigned long long)top->dbg_s2, (unsigned long long)top->dbg_s5);
                nputs_ret_hits++;
            }
            if (cyc < 20000 || cyc - last_progress_cyc > 500000) {
                fprintf(stderr, "[cyc %llu] instr fetch addr changed: 0x%x -> 0x%x\n",
                        (unsigned long long)cyc, last_instr_addr, top->dbg_instr_addr);
            }
            // Task #15: unconditional, full-detail print on EVERY address
            // change within a tight window bracketing one known stall
            // period, to see the exact cycle-level sequence (no rate
            // limiting -- this is what tells us whether e.g. ctrl_rdy just
            // never asserts, or a req/resp handshake never completes).
            if (cyc >= 159'977'800ULL && cyc <= 159'980'300ULL) {
                fprintf(stderr,
                    "[FINE cyc %llu] addr 0x%x -> 0x%x | s1=0x%llx a5=0x%llx s3=0x%llx s5=0x%llx s2=0x%llx a0=0x%llx\n",
                    (unsigned long long)cyc, last_instr_addr, top->dbg_instr_addr,
                    (unsigned long long)top->dbg_s1, (unsigned long long)top->dbg_a5,
                    (unsigned long long)top->dbg_s3, (unsigned long long)top->dbg_s5,
                    (unsigned long long)top->dbg_s2, (unsigned long long)top->dbg_a0);
            }
            last_instr_addr = top->dbg_instr_addr;
            last_progress_cyc = cyc;
        }

        // Task #15: tight window around the known misaligned-access-
        // calibration hang (observed starting ~cyc 50-100M in prior runs).
        // Print cycleCounter/mtime every 100,000 cycles in this window to
        // directly see whether either free-running counter ever stalls.
        static uint64_t last_ctr_print = 0;
        if (cyc >= 40'000'000ULL && cyc <= 160'000'000ULL && cyc - last_ctr_print >= 100'000ULL) {
            fprintf(stderr,
                    "[CTR cyc %llu] cycleCounter=%llu mtime=%llu instr_addr=0x%x | "
                    "s1=0x%llx a5=0x%llx s3=0x%llx s5=0x%llx s2=0x%llx (s5-s1=%lld) (s3-s2=%lld)\n",
                    (unsigned long long)cyc, (unsigned long long)top->dbg_cycle_counter,
                    (unsigned long long)top->dbg_mtime, top->dbg_instr_addr,
                    (unsigned long long)top->dbg_s1, (unsigned long long)top->dbg_a5,
                    (unsigned long long)top->dbg_s3, (unsigned long long)top->dbg_s5,
                    (unsigned long long)top->dbg_s2,
                    (long long)(top->dbg_s5 - top->dbg_s1),
                    (long long)(top->dbg_s3 - top->dbg_s2));
            last_ctr_print = cyc;
        }

        // Same tight window as the FINE address-change print above, but
        // UNCONDITIONAL every 1000 cycles regardless of address change --
        // this is what shows a SUSTAINED stall (PC not moving at all)
        // rather than just the moments the PC does change.
        static uint64_t last_fine_print = 0;
        if (cyc >= 158'800'000ULL && cyc <= 159'000'000ULL && cyc - last_fine_print >= 1000ULL) {
            fprintf(stderr,
                "[TICK cyc %llu] addr=0x%x | mem_state=%d be_state=%d ctrl_state=%d ctrl_rdy=%d | "
                "instr(req_v=%d req_r=%d resp_v=%d) data(req_v=%d req_r=%d write=%d resp_v=%d)\n",
                (unsigned long long)cyc, top->dbg_instr_addr,
                top->dbg_mem_state, top->dbg_be_state, top->dbg_ctrl_state, top->dbg_ctrl_rdy,
                top->dbg_instr_req_valid, top->dbg_instr_req_ready, top->dbg_instr_resp_valid,
                top->dbg_data_req_valid, top->dbg_data_req_ready, top->dbg_data_req_write,
                top->dbg_data_resp_valid);
            last_fine_print = cyc;
        }

        if (cyc - last_report >= 1'000'000) {
            fprintf(stderr,
                "... %llu cycles | mem_state=%d be_state=%d ctrl_state=%d ctrl_rdy=%d | "
                "instr(req_v=%d req_r=%d resp_v=%d addr=0x%x) data(req_v=%d req_r=%d) | "
                "cycleCounter=%llu mtime=%llu | last progress %llu cycles ago\n",
                (unsigned long long)cyc, top->dbg_mem_state, top->dbg_be_state, top->dbg_ctrl_state,
                top->dbg_ctrl_rdy, top->dbg_instr_req_valid, top->dbg_instr_req_ready,
                top->dbg_instr_resp_valid, top->dbg_instr_addr, top->dbg_data_req_valid,
                top->dbg_data_req_ready, (unsigned long long)top->dbg_cycle_counter,
                (unsigned long long)top->dbg_mtime, (unsigned long long)(cyc - last_progress_cyc));
            last_report = cyc;
        }
    }

    fprintf(stderr, "\nDone: %llu cycles simulated, no more output expected within budget.\n",
            (unsigned long long)max_cycles);

    fprintf(stderr, "\nFinal s2 (flush-loop progress counter) = 0x%llx, s3 (flush length) = 0x%llx\n",
            (unsigned long long)top->dbg_s2, (unsigned long long)top->dbg_s3);

    // Dump OpenSBI's console_tbuf itself (fixed address 0x42108, per
    // sbi_console.c / the fw_payload.elf disassembly at print+0x328) via
    // the same debug backdoor used for firmware preload, to see what's
    // actually buffered and being (endlessly) flushed.
    {
        uint64_t dumpStart = 0x42100;
        fprintf(stderr, "Memory dump [0x%llx, 0x%llx) (console_out_lock/console_tbuf_len/console_tbuf):\n",
                (unsigned long long)dumpStart, (unsigned long long)(dumpStart + 128));
        for (uint64_t base = dumpStart; base < dumpStart + 128; base += 16) {
            fprintf(stderr, "  0x%06llx: ", (unsigned long long)base);
            unsigned char bytes[16];
            for (int i = 0; i < 16; i += 2) {
                uint64_t byteAddr = base + i;
                uint32_t wordAddr = (uint32_t)(byteAddr >> 1) & 0xFFFFFF;
                top->dbg_raddr = wordAddr;
                top->eval();
                uint16_t d = top->dbg_rdata;
                bytes[i]     = d & 0xFF;
                bytes[i + 1] = (d >> 8) & 0xFF;
            }
            for (int i = 0; i < 16; i++) fprintf(stderr, "%02x ", bytes[i]);
            fprintf(stderr, " |");
            for (int i = 0; i < 16; i++) {
                unsigned char c = bytes[i];
                fprintf(stderr, "%c", (c >= 0x20 && c < 0x7f) ? c : '.');
            }
            fprintf(stderr, "|\n");
        }
    }

    if (tfp) { tfp->close(); delete tfp; }
    delete top;
    return 0;
}
