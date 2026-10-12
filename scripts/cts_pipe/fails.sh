#!/usr/bin/env bash
D=${PIPE_DIR:-$(dirname "$0")/../../out/cts_pipe}
grep -a "^\(Fail\|Crash\|Timeout\|InternalError\|ResourceError\)" $D/batch/results.txt | awk '{print $1,$2}' | sed 's/dEQP-VK.pipeline.monolithic.//' | sort -k2
