#!/usr/bin/env bash
# scripts/test_summary.sh — parallel Borg test runner with live display.
#
# Parallelisation strategy (Threadripper 1920x):
#   Wave 1 (all parallel): lint · chisel:borg · chisel:tinyqv · software
#   Wave 2 (sequential):   cocotb:soc-core → cocotb:soc-borg
#     (cocotb tests share test/soc/ build artefacts, kept sequential)
#
# Usage: called directly by `make test-all`.

set -euo pipefail

# ── terminal colours & symbols ────────────────────────────────────────────────
GREEN="\033[0;32m";  RED="\033[0;31m";  CYAN="\033[0;36m"
YELLOW="\033[0;33m"; BOLD="\033[1m";    DIM="\033[2m";  RESET="\033[0m"
CHECK="${GREEN}✓${RESET}"; CROSS="${RED}✗${RESET}"
SPIN=('⠋' '⠙' '⠹' '⠸' '⠼' '⠴' '⠦' '⠧' '⠇' '⠏')
PENDING_ICON="${DIM}·${RESET}"

# ── suite definitions ─────────────────────────────────────────────────────────
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MILL_JOBS="${MILL_JOBS:-$(nproc)}"     # auto-detect; override with MILL_JOBS=N
MILL="mill --no-server -j ${MILL_JOBS}"
TEST_SOC="make -j1 -C $ROOT/test/soc -B"  # soc tests are single-threaded

# Wave 1: fully parallel (no shared build artefacts)
# Wave 2: sequential (share test/soc/ directory)
SUITE_LABELS=(
    "setup  › generate_verilog"
    "lint"
    "chisel › borg"
    "chisel › tinyqv"
    "software"
    "cocotb › soc-core (rtl)"
    "cocotb › soc-borg  (rtl)"
)
SUITE_CMDS=(
    "cd '$ROOT' && make generate_verilog"
    "cd '$ROOT' && make lint"
    "cd '$ROOT' && $MILL hardware.borg.test"
    "cd '$ROOT' && $MILL hardware.tinyqv.test"
    "cd '$ROOT' && make -C software test"
    "cd '$ROOT' && $TEST_SOC core"
    "cd '$ROOT' && $TEST_SOC borg"
)
# Wave membership: -1 = setup (sequential, blocking), 0 = wave 1 (parallel), 1 = wave 2 (sequential)
SUITE_WAVE=(-1 0 0 0 0 1 1)

N="${#SUITE_LABELS[@]}"
LOG_DIR="$(mktemp -d /tmp/borg-test-XXXXXX)"
trap 'rm -rf "$LOG_DIR"' EXIT

# ── per-suite state ───────────────────────────────────────────────────────────
declare -a SUITE_PID;    for (( i=0; i<N; i++ )); do SUITE_PID[$i]=0;         done
declare -a SUITE_STATE;  for (( i=0; i<N; i++ )); do SUITE_STATE[$i]="pending"; done
declare -a SUITE_N;      for (( i=0; i<N; i++ )); do SUITE_N[$i]=0;           done

PASS=0; FAIL=0; declare -a FAILURES=()

# ── helpers ───────────────────────────────────────────────────────────────────
LABEL_W=26
BAR_W=24

bar() {                  # bar <done> <total>
    local done=$1 total=$2 i
    local filled=$(( done * BAR_W / total ))
    local empty=$(( BAR_W - filled ))
    local b=""
    for (( i=0; i<filled; i++ )); do b+="█"; done
    for (( i=0; i<empty;  i++ )); do b+="░"; done
    printf "${CYAN}[%s]${RESET}" "$b"
}

count_tests() {          # count_tests <logfile>  →  integer
    local log="$1" n
    n=$(grep -c '^\+ ' "$log" 2>/dev/null || true)
    [[ "$n" -gt 0 ]] && { echo "$n"; return; }
    n=$(grep -oP '\d+(?= passed)' "$log" 2>/dev/null | tail -1 || true)
    [[ -n "$n" && "$n" -gt 0 ]] && { echo "$n"; return; }
    echo "0"
}

elapsed() {              # elapsed <start_epoch>  →  "Xs" or "Xm Ys"
    local s=$(( SECONDS - $1 ))
    if (( s < 60 )); then printf "%ds" "$s"
    else printf "%dm %ds" $(( s/60 )) $(( s%60 )); fi
}

# ── display engine ────────────────────────────────────────────────────────────
FRAME=0
DONE_SUITES=0
START_TIME=$SECONDS
declare -a SUITE_START; for (( i=0; i<N; i++ )); do SUITE_START[$i]=$SECONDS; done

render_all() {
    local i done_count=0
    for (( i=0; i<N; i++ )); do
        [[ "${SUITE_STATE[$i]}" != "pending" && "${SUITE_STATE[$i]}" != "running" ]] && (( done_count++ )) || true
    done
    for (( i=0; i<N; i++ )); do
        local state="${SUITE_STATE[$i]}"
        local label="${SUITE_LABELS[$i]}"
        local log="$LOG_DIR/suite_${i}.log"
        case "$state" in
          pending)
            printf "  %s  %-*s  " "${PENDING_ICON}" "$LABEL_W" "$label"
            bar 0 "$N"
            printf "  ${DIM}pending${RESET}\n"
            ;;
          running)
            local spin="${SPIN[$((FRAME % ${#SPIN[@]}))]}"
            local et; et=$(elapsed "${SUITE_START[$i]}")
            printf "  ${CYAN}%s${RESET}  %-*s  " "$spin" "$LABEL_W" "$label"
            bar "$done_count" "$N"
            printf "  ${DIM}%s${RESET}\n" "$et"
            ;;
          pass)
            local n="${SUITE_N[$i]}"
            local et; et=$(elapsed "${SUITE_START[$i]}")
            printf "  %s  %-*s  " "${CHECK}" "$LABEL_W" "$label"
            bar "$done_count" "$N"
            if [[ "$n" -gt 0 ]]; then
                printf "  ${DIM}%s  (%d tests)${RESET}\n" "$et" "$n"
            else
                printf "  ${DIM}%s${RESET}\n" "$et"
            fi
            ;;
          fail)
            local et; et=$(elapsed "${SUITE_START[$i]}")
            printf "  %s  %-*s  " "${CROSS}" "$LABEL_W" "$label"
            bar "$done_count" "$N"
            printf "  ${DIM}%s${RESET}\n" "$et"
            ;;
        esac
    done
    (( FRAME++ )) || true
}

# Move cursor up N lines to overwrite
move_up() { printf "\033[%dA" "$N"; }

# ── suite lifecycle ───────────────────────────────────────────────────────────
start_suite() {
    local i=$1
    SUITE_STATE[$i]="running"
    SUITE_START[$i]=$SECONDS
    bash -c "${SUITE_CMDS[$i]}" > "$LOG_DIR/suite_${i}.log" 2>&1 &
    SUITE_PID[$i]=$!
}

finish_suite() {
    local i=$1 exit_code=$2
    if [[ $exit_code -eq 0 ]]; then
        SUITE_STATE[$i]="pass"
        SUITE_N[$i]=$(count_tests "$LOG_DIR/suite_${i}.log")
        (( PASS++ )) || true
    else
        SUITE_STATE[$i]="fail"
        (( FAIL++ )) || true
        FAILURES+=("${SUITE_LABELS[$i]}")
    fi
}

# ── header ────────────────────────────────────────────────────────────────────
echo ""
echo -e "  ${BOLD}Borg test suite${RESET}   ${DIM}(setup → 4 parallel → 2 sequential)${RESET}"
echo -e "  ──────────────────────────────────────────────────────────"
render_all    # initial render (all pending)

# ── WAVE -1: blocking setup (generate_verilog) ────────────────────────────────
for (( i=0; i<N; i++ )); do
    [[ "${SUITE_WAVE[$i]}" -ne -1 ]] && continue
    move_up
    start_suite "$i"
    render_all
    while kill -0 "${SUITE_PID[$i]}" 2>/dev/null; do
        sleep 0.08
        move_up; render_all
    done
    wait "${SUITE_PID[$i]}"; code=$?
    finish_suite "$i" "$code"
    move_up; render_all
    # Abort early if setup fails — nothing else can run
    [[ ${SUITE_STATE[$i]} == "fail" ]] && { echo -e "\n  ${RED}${BOLD}Setup failed — aborting.${RESET}"; exit 1; }
done

# ── WAVE 0: parallel suites ───────────────────────────────────────────────────
move_up
for (( i=0; i<N; i++ )); do
    [[ "${SUITE_WAVE[$i]}" -eq 0 ]] && start_suite "$i"
done
render_all

WAVE0_DONE=0
WAVE0_COUNT=0; for (( i=0; i<N; i++ )); do [[ "${SUITE_WAVE[$i]}" -eq 0 ]] && (( WAVE0_COUNT++ )) || true; done

while (( WAVE0_DONE < WAVE0_COUNT )); do
    sleep 0.08
    for (( i=0; i<N; i++ )); do
        [[ "${SUITE_WAVE[$i]}" -ne 0 || "${SUITE_STATE[$i]}" != "running" ]] && continue
        local_pid="${SUITE_PID[$i]}"
        if ! kill -0 "$local_pid" 2>/dev/null; then
            wait "$local_pid"; code=$?
            finish_suite "$i" "$code"
            (( WAVE0_DONE++ )) || true
        fi
    done
    move_up; render_all
done

# ── WAVE 1: sequential cocotb ─────────────────────────────────────────────────
for (( i=0; i<N; i++ )); do
    [[ "${SUITE_WAVE[$i]}" -ne 1 ]] && continue
    move_up
    start_suite "$i"
    render_all
    while kill -0 "${SUITE_PID[$i]}" 2>/dev/null; do
        sleep 0.08
        move_up; render_all
    done
    wait "${SUITE_PID[$i]}"; code=$?
    finish_suite "$i" "$code"
    move_up; render_all
done

# ── failure detail ────────────────────────────────────────────────────────────
for (( i=0; i<N; i++ )); do
    [[ "${SUITE_STATE[$i]}" != "fail" ]] && continue
    echo ""
    echo -e "  ${RED}${BOLD}✗ ${SUITE_LABELS[$i]}${RESET}"
    echo -e "  ${DIM}┌─ last output ─────────────────────────────────────────${RESET}"
    tail -30 "$LOG_DIR/suite_${i}.log" | sed 's/^/  │ /'
    echo -e "  ${DIM}└───────────────────────────────────────────────────────${RESET}"
done

# ── summary ───────────────────────────────────────────────────────────────────
TOTAL=$(( PASS + FAIL ))
WALL=$(elapsed "$START_TIME")
echo -e "  ──────────────────────────────────────────────────────────"
if [[ $FAIL -eq 0 ]]; then
    echo -e "  ${GREEN}${BOLD}✓  All ${PASS}/${TOTAL} suites passed${RESET}  ${DIM}(${WALL})${RESET}"
else
    echo -e "  ${RED}${BOLD}✗  ${FAIL}/${TOTAL} suite(s) FAILED: ${FAILURES[*]}${RESET}  ${DIM}(${WALL})${RESET}"
    exit 1
fi
echo ""
