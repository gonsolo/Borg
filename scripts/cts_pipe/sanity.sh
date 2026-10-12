#!/usr/bin/env bash
# rerun a random sample of cases that passed before; print any that no longer pass
cd "$(dirname "$0")/../.."; D=${PIPE_DIR:-out/cts_pipe}
N=${1:-400}
grep -a "^Pass" $D/pipe.done | awk '{print $2}' | shuf -n $N --random-source=<(yes) > $D/san.list
rm -rf $D/san; OUT=$D/san CASELIST=$D/san.list JOBS=10 DIRECT=1 CASE_TIMEOUT=60 scripts/cts_par.sh san > $D/san.log 2>&1
awk '{c[$1]++} END{for(k in c) printf "%s=%d ", k, c[k]; print ""}' $D/san/results.txt
grep -a -v "^Pass\|^NotSupported" $D/san/results.txt | awk '{print $1,$2}' | sed 's/dEQP-VK.pipeline.monolithic.//' | head -20
