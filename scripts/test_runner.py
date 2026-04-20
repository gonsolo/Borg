#!/usr/bin/env python3
"""
scripts/test_runner.py — Borg parallel test runner with live display.

Execution order:
  Sequential setup:  generate_verilog → lint  (lint re-runs generate_verilog
                     via make PHONY, so serialise it to avoid mill lock contention)
  Parallel:          chisel:borg · chisel:tinyqv · software · cocotb:soc-core
  After soc-core:    cocotb:soc-borg  (shares test/soc/ dir with soc-core)
"""

import os, re, shutil, subprocess, sys, tempfile, threading, time
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Optional

# ── ANSI ──────────────────────────────────────────────────────────────────────
def _a(*codes: int) -> str:
    return f"\033[{';'.join(str(c) for c in codes)}m"

RESET = _a(0); BOLD = _a(1); DIM = _a(2)
GREEN = _a(32); RED = _a(31); CYAN = _a(36)
CLR   = "\033[K"   # clear to end of line

CHECK   = f"{GREEN}✓{RESET}"
CROSS   = f"{RED}✗{RESET}"
PENDING = f"{DIM}·{RESET}"
SPIN    = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"

# ── Suite model ───────────────────────────────────────────────────────────────
class State(Enum):
    PENDING = "pending"
    RUNNING = "running"
    PASS    = "pass"
    FAIL    = "fail"

@dataclass
class Suite:
    label:      str
    cmd:        str
    sequential: bool       = False   # must wait for previous suite in its group
    depends_on: str        = ""      # label of suite that must PASS before starting
    state:      State      = State.PENDING
    proc:       Optional[subprocess.Popen] = field(default=None, repr=False)
    log:        str        = ""
    start:      float      = 0.0
    elapsed:    float      = 0.0
    n_tests:    int        = 0

def make_suites(root: Path, mill: str, test_soc: str) -> list:
    golden   = root / "simulation" / "golden"
    compare  = f"python3 '{root}/scripts/compare_ppm.py'"
    verilator_dir = root / "simulation" / "verilator"
    arcilator_dir = root / "simulation" / "arcilator"

    def verilator_render(app: str) -> str:
        ppm = f"{app}_00.ppm"
        return (
            f"cd '{verilator_dir}' && make {app} && "
            f"{compare} '{verilator_dir}/{ppm}' '{golden}/{ppm}' --max-diff 1"
        )

    def arcilator_render(app: str) -> str:
        ppm = f"{app}_00.ppm"
        return (
            f"cd '{arcilator_dir}' && make {app} && "
            f"{compare} '{arcilator_dir}/{ppm}' '{golden}/{ppm}' --max-diff 1"
        )

    return [
        # ── Sequential setup ──────────────────────────────────────────────────
        Suite("setup  › generate_verilog",
              f"cd '{root}' && make generate_verilog",
              sequential=True),
        Suite("lint",
              f"cd '{root}' && make lint",
              sequential=True),          # depends on generate_verilog being done;
                                         # serialised here to avoid mill contention
        # ── Parallel (no inter-dependencies) ─────────────────────────────────
        # NOTE: chisel suites share the Mill build server — serialise them to
        # avoid "Another Mill process is running" lock contention.
        Suite("chisel › borg",
              f"cd '{root}' && {mill} hardware.borg.test"),
        Suite("chisel › tinyqv",
              f"cd '{root}' && {mill} hardware.tinyqv.test",
              depends_on="chisel › borg"),
        Suite("software",
              f"cd '{root}' && make -C software test"),
        Suite("cocotb › soc-core (rtl)",
              f"cd '{root}' && {test_soc} core"),
        # NOTE: verilator triangle/vkcube share obj_dir — serialise to prevent
        # parallel 'rm -rf obj_dir' races that corrupt the verilator_sim build.
        Suite("render › verilator › triangle", verilator_render("triangle")),
        Suite("render › verilator › vkcube",   verilator_render("vkcube"),
              depends_on="render › verilator › triangle"),
        # NOTE: arcilator triangle/vkcube share arcilator_sim — same reason.
        Suite("render › arcilator › triangle", arcilator_render("triangle")),
        Suite("render › arcilator › vkcube",   arcilator_render("vkcube"),
              depends_on="render › arcilator › triangle"),
        # ── Starts only after soc-core (shared test/soc/ dir) ────────────────
        Suite("cocotb › soc-borg  (rtl)",
              f"cd '{root}' && {test_soc} borg",
              depends_on="cocotb › soc-core (rtl)"),
        # ── FPGA render tests (skipped automatically if no /dev/ttyACM*) ──────
        # Exit 0 from the script = pass (includes the graceful skip case).
        Suite("render › fpga  (hw)",
              f"bash '{root}/scripts/fpga_render_test.sh'"),
    ]

# ── Helpers ───────────────────────────────────────────────────────────────────
def _fmt_time(secs: float) -> str:
    s = int(secs)
    return f"{s // 60}m {s % 60}s" if s >= 60 else f"{s}s"

def _bar(filled: int, total: int, colour: str = CYAN) -> str:
    f = max(0, min(total, filled))
    return f"{colour}[{'█' * f}{'░' * (total - f)}]{RESET}"

def count_tests(log: str) -> int:
    if not log or not os.path.exists(log):
        return 0
    try:
        text = Path(log).read_text(errors="replace")
        n = len(re.findall(r'^\+ ', text, re.MULTILINE))
        if n:
            return n
        m = re.search(r'(\d+) passed', text)
        if m:
            return int(m.group(1))
    except OSError:
        pass
    return 0

# ── Display ───────────────────────────────────────────────────────────────────
LABEL_W = 30
BAR_W   = 22

def _render(suites: list, frame: int, total_done: int, lines_printed: list) -> None:
    now  = time.monotonic()
    spin = SPIN[frame % len(SPIN)]
    out  = []

    for s in suites:
        # ── icon & elapsed ────────────────────────────────────────────────────
        if s.state == State.PENDING:
            icon  = PENDING
            et    = ""
            extra = f"  {DIM}pending{RESET}"
            bar   = _bar(0, BAR_W)
        elif s.state == State.RUNNING:
            icon  = f"{CYAN}{spin}{RESET}"
            et    = _fmt_time(now - s.start)
            extra = f"  {DIM}{et}{RESET}"
            # partial fill = global progress so far
            bar   = _bar(total_done * BAR_W // len(suites), BAR_W)
        elif s.state == State.PASS:
            icon   = CHECK
            et     = _fmt_time(s.elapsed)
            n_str  = f"  ({s.n_tests} tests)" if s.n_tests else ""
            extra  = f"  {DIM}{et}{n_str}{RESET}"
            bar    = _bar(BAR_W, BAR_W, GREEN)   # full green bar
        else:  # FAIL
            icon   = CROSS
            et     = _fmt_time(s.elapsed)
            extra  = f"  {DIM}{et}{RESET}"
            bar    = _bar(BAR_W, BAR_W, RED)     # full red bar

        lbl = s.label.ljust(LABEL_W)
        out.append(f"  {icon}  {lbl}  {bar}{extra}")

    # Overwrite previous render
    if lines_printed[0]:
        sys.stdout.write(f"\033[{lines_printed[0]}A")
    for line in out:
        sys.stdout.write(f"\r{CLR}{line}\n")
    sys.stdout.flush()
    lines_printed[0] = len(out)

# ── Suite lifecycle ───────────────────────────────────────────────────────────
def _start(suite: Suite, log_dir: str) -> None:
    safe        = re.sub(r'[^\w]', '_', suite.label)
    suite.log   = os.path.join(log_dir, f"{safe}.log")
    suite.start = time.monotonic()
    suite.state = State.RUNNING
    fh = open(suite.log, "w")
    suite.proc = subprocess.Popen(suite.cmd, shell=True,
                                  stdout=fh, stderr=subprocess.STDOUT)
    fh.close()

def _append_mill_worker_logs(suite: Suite, root: Path) -> None:
    """After a Mill test suite finishes, append worker stdout logs so failures are visible."""
    if "chisel" not in suite.label:
        return
    # Determine which hardware module to look at
    if "borg" in suite.label:
        pattern = root / "out" / "hardware" / "borg" / "test" / "testForked.dest"
    elif "tinyqv" in suite.label:
        pattern = root / "out" / "hardware" / "tinyqv" / "test" / "testForked.dest"
    else:
        return

    worker_logs = sorted(pattern.glob("worker-*.log")) if pattern.exists() else []
    result_logs = sorted(pattern.glob("worker-*/result.log")) if pattern.exists() else []

    combined = worker_logs + result_logs
    if not combined:
        return

    try:
        with open(suite.log, "a") as out:
            out.write("\n\n--- Mill worker output ---\n")
            for wlog in combined:
                try:
                    out.write(wlog.read_text(errors="replace"))
                    out.write("\n")
                except OSError:
                    pass
    except OSError:
        pass

def _finish(suite: Suite, root: Path) -> None:
    suite.proc.wait()
    suite.elapsed = time.monotonic() - suite.start
    _append_mill_worker_logs(suite, root)
    if suite.proc.returncode == 0:
        suite.state   = State.PASS
        suite.n_tests = count_tests(suite.log)
    else:
        suite.state = State.FAIL


# ── Runner ────────────────────────────────────────────────────────────────────
def main() -> None:
    root     = Path(__file__).resolve().parent.parent
    ncpus    = int(os.environ.get("MILL_JOBS", os.cpu_count() or 4))
    mill     = f"mill --no-server -j {ncpus}"
    test_soc = f"make -j1 -C '{root}/test/soc' -B"

    log_dir    = tempfile.mkdtemp(prefix="borg-test-")
    persist_dir = root / "test_logs"
    suites     = make_suites(root, mill, test_soc)
    by_label   = {s.label: s for s in suites}

    frame         = [0]
    lines_printed = [0]
    stop_event    = threading.Event()
    lock          = threading.Lock()
    start_time    = time.monotonic()

    def total_done() -> int:
        return sum(1 for s in suites if s.state in (State.PASS, State.FAIL))

    def display_loop() -> None:
        while not stop_event.is_set():
            with lock:
                _render(suites, frame[0], total_done(), lines_printed)
                frame[0] += 1
            time.sleep(0.08)

    # ── Header ────────────────────────────────────────────────────────────────
    n_seq  = sum(1 for s in suites if s.sequential)
    n_par  = len(suites) - n_seq - sum(1 for s in suites if s.depends_on)
    n_dep  = sum(1 for s in suites if s.depends_on)
    print()
    print(f"  {BOLD}Borg test suite{RESET}   "
          f"{DIM}({n_seq} sequential setup → {n_par} parallel → {n_dep} chained){RESET}")
    print(f"  {'─' * 60}")
    with lock:
        _render(suites, frame[0], 0, lines_printed)

    dt = threading.Thread(target=display_loop, daemon=True)
    dt.start()

    first_failure = None
    try:
        # ── Sequential suites ─────────────────────────────────────────────────
        for s in (x for x in suites if x.sequential):
            _start(s, log_dir)
            s.proc.wait()
            _finish(s, root)
            if s.state == State.FAIL:
                stop_event.set(); dt.join()
                with lock:
                    _render(suites, frame[0], total_done(), lines_printed)
                print(f"\n  {RED}{BOLD}Setup step '{s.label}' failed — aborting.{RESET}")
                _show_failures(suites)
                sys.exit(1)

        # ── Parallel + chained suites ─────────────────────────────────────────
        # Start all suites that have no depends_on and are not sequential
        remaining = [s for s in suites if not s.sequential]
        for s in list(remaining):
            if not s.depends_on:
                _start(s, log_dir)

        # Poll: finish suites, unblock dependents
        first_failure = None
        while remaining:
            for s in list(remaining):
                if s.state == State.RUNNING and s.proc.poll() is not None:
                    _finish(s, root)
                    remaining.remove(s)
                    if s.state == State.FAIL:
                        first_failure = s
                    # Unblock any suite waiting on this one
                    for other in remaining:
                        if other.depends_on == s.label and other.state == State.PENDING:
                            if s.state == State.PASS:
                                _start(other, log_dir)
                            else:
                                other.state = State.FAIL  # skip if dep failed
                                remaining.remove(other)
            if first_failure:
                # Kill remaining running suites and abort
                for s in list(remaining):
                    if s.state == State.RUNNING and s.proc:
                        s.proc.kill()
                        s.proc.wait()
                        s.elapsed = time.monotonic() - s.start
                        s.state = State.FAIL
                    elif s.state == State.PENDING:
                        s.state = State.FAIL
                remaining.clear()
                break
            time.sleep(0.05)

    finally:
        stop_event.set()
        dt.join()
        with lock:
            _render(suites, frame[0], total_done(), lines_printed)

    # ── Failure detail (show only the first failure immediately) ────────────
    if first_failure:
        _show_failures([first_failure])
    else:
        _show_failures(suites)

    # ── Summary ───────────────────────────────────────────────────────────────
    failures = [s for s in suites if s.state == State.FAIL]
    wall     = _fmt_time(time.monotonic() - start_time)
    n        = len(suites)
    print(f"  {'─' * 60}")
    if not failures:
        print(f"  {GREEN}{BOLD}✓  All {n}/{n} suites passed{RESET}  {DIM}({wall}){RESET}")
    else:
        names = ", ".join(s.label for s in failures)
        print(f"  {RED}{BOLD}✗  {len(failures)}/{n} suite(s) FAILED: {names}"
              f"{RESET}  {DIM}({wall}){RESET}")
    print()
    # ── Persist logs ──────────────────────────────────────────────────────────
    persist_dir.mkdir(exist_ok=True)
    ts = time.strftime("%Y%m%d_%H%M%S")
    run_dir = persist_dir / ts
    shutil.copytree(log_dir, str(run_dir))
    # Keep a "latest" symlink for quick access
    latest = persist_dir / "latest"
    if latest.is_symlink() or latest.exists():
        latest.unlink()
    latest.symlink_to(run_dir.name)
    print(f"  {DIM}Logs saved to test_logs/{ts}/ (test_logs/latest){RESET}")
    print()
    if failures:
        sys.exit(1)

def _show_failures(suites: list) -> None:
    for s in suites:
        if s.state != State.FAIL or not s.log or not os.path.exists(s.log):
            continue
        print()
        print(f"  {RED}{BOLD}✗ {s.label}{RESET}")
        print(f"  {DIM}┌─ last output {'─' * 44}{RESET}")
        lines = Path(s.log).read_text(errors="replace").splitlines()
        for line in lines[-30:]:
            print(f"  │ {line}")
        print(f"  {DIM}└{'─' * 51}{RESET}")

if __name__ == "__main__":
    main()
