#!/usr/bin/env bash
# Voice recording to narration: rumble and noise out, sibilants and peaks tamed, -16 LUFS.
# usage: clean_voice.sh take.wav out.wav [noise reduction in dB, default 12]
set -euo pipefail
in="$1" out="$2" nr="${3:-12}"
chain="highpass=f=80,afftdn=nr=$nr:nf=-50:tn=1,deesser=i=0.4,acompressor=threshold=-20dB:ratio=3:attack=5:release=120:makeup=2"
stats=$(ffmpeg -hide_banner -nostats -i "$in" -af "$chain,loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json" -f null - 2>&1 |
  sed -n '/^{/,/^}/p')
val() { sed -n "s/.*\"$1\" : \"\([^\"]*\)\".*/\1/p" <<<"$stats"; }
ffmpeg -hide_banner -loglevel error -y -i "$in" -af "$chain,loudnorm=I=-16:TP=-1.5:LRA=11:linear=true:\
measured_I=$(val input_i):measured_TP=$(val input_tp):measured_LRA=$(val input_lra):\
measured_thresh=$(val input_thresh):offset=$(val target_offset)" -ar 48000 -c:a pcm_s24le "$out"
echo "$out: $(ffmpeg -hide_banner -nostats -i "$out" -af ebur128 -f null - 2>&1 | sed -n 's/^ *I: *\(.*LUFS\)/\1/p' | tail -1)"
