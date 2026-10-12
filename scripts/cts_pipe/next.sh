#!/usr/bin/env bash
# Runs batches of 50 from $D/pipe.list starting at $D/pipe.pos until a batch has a failure not in
# defer.rx (or the time budget ends). State lives in $PIPE_DIR (default out/cts_pipe).
H=$(cd "$(dirname "$0")" && pwd); cd "$H/../.."
D=${PIPE_DIR:-out/cts_pipe}; mkdir -p $D
touch $D/pipe.pos $D/pipe.done $D/pipe.deferred $D/pipe.tally
[ -s $D/pipe.pos ] || echo 0 > $D/pipe.pos
end=$(( $(date +%s) + ${BUDGET:-420} ))
while (( $(date +%s) < end )); do
  pos=$(cat $D/pipe.pos); total=$(wc -l < $D/pipe.list)
  (( pos >= total )) && { echo "list done"; exit 0; }
  sed -n "$((pos+1)),$((pos+50))p" $D/pipe.list > $D/batch.list
  rm -rf $D/batch
  OUT=$D/batch CASELIST=$D/batch.list JOBS=${JOBS:-10} DIRECT=1 CASE_TIMEOUT=60 scripts/cts_par.sh batch > $D/batch.log 2>&1
  r=$(awk '{c[$1]++} END{for(k in c) printf "%s=%d ", k, c[k]}' $D/batch/results.txt)
  echo "$pos $(sed -n 1p $D/batch.list | cut -d. -f4-6 | cut -c1-60) $r" >> $D/pipe.tally
  if grep -a "^\(Fail\|Crash\|Timeout\|InternalError\|ResourceError\)" $D/batch/results.txt | awk '{print $2}' | grep -v -E -f "$H/defer.rx" | grep -q .; then
    echo "STOP at $pos: $r"; exit 1
  fi
  grep -a '^\(Fail\|Crash\|Timeout\)' $D/batch/results.txt | awk '{print $2}' >> $D/pipe.deferred
  awk '{print $1, $2}' $D/batch/results.txt >> $D/pipe.done
  echo $((pos+50)) > $D/pipe.pos
done
echo "budget over at $(cat $D/pipe.pos)"
