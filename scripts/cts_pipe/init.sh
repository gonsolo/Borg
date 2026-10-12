#!/usr/bin/env bash
# init.sh CASELIST: the monolithic pipeline cases without sampler and depth, from position 0.
cd "$(dirname "$0")/../.."; D=${PIPE_DIR:-out/cts_pipe}; mkdir -p $D
grep "^dEQP-VK\.pipeline\.monolithic\." "$1" | grep -v -E "^dEQP-VK\.pipeline\.monolithic\.(sampler|depth)\." > $D/pipe.list
echo 0 > $D/pipe.pos; : > $D/pipe.tally; : > $D/pipe.done; : > $D/pipe.deferred
wc -l < $D/pipe.list
