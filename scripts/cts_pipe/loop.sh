#!/usr/bin/env bash
H=$(cd "$(dirname "$0")" && pwd); D=${PIPE_DIR:-out/cts_pipe}; mkdir -p "$H/../../$D"
BUDGET=${BUDGET:-28000} "$H/next.sh" > "$H/../../$D/loop.out" 2>&1
