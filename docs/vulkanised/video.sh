#!/usr/bin/env bash
# The Video Vault video from build/slides.pdf and video.list: build/video.mp4, 1080p30.
# usage: video.sh (after `make slides`; `make video` does both)
set -euo pipefail
cd "$(dirname "$0")"
out=build/video
mkdir -p $out
rm -f $out/cut*.mp4
page() {
  if [[ $1 =~ ^[0-9]+$ ]]; then echo "$1"; return; fi
  local p; p=$(sed -n "s/^\\\\newlabel{$1}{{[^}]*}{\([0-9]*\)}.*/\1/p" build/slides.aux)
  [[ -n $p ]] || { echo "video.sh: no frame labelled $1" >&2; exit 1; }
  echo "$p"
}
fit="scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2"
enc=(-c:v libx264 -crf 20 -preset medium -pix_fmt yuv420p -r 30 -c:a aac -b:a 160k -ar 48000 -ac 1)
fades() { echo "fade=t=in:d=0.3,fade=t=out:st=$(echo "$1 - 0.3" | bc):d=0.3"; }
slide() { local p; p=$(page "$1"); pdftoppm -f "$p" -l "$p" -r 240 -png -singlefile build/slides.pdf "$2"; }
n=0
while read -r frame media secs <&3; do
  [[ -z $frame || $frame == \#* ]] && continue
  n=$((n + 1)); cut=$out/cut$(printf %03d $n).mp4
  case $media in
    *.m4a|*.wav|*.flac)   # the slide, under its take: half a second before, a second after
      slide "$frame" $out/slide
      ./clean_voice.sh "$media" $out/voice.wav > /dev/null
      d=$(echo "$(ffprobe -v error -show_entries format=duration -of csv=p=0 $out/voice.wav) + 1.5" | bc)
      ffmpeg -hide_banner -loglevel error -y -loop 1 -t "$d" -i $out/slide.png -i $out/voice.wav \
        -vf "$fit:white,$(fades "$d")" -af "adelay=500:all=1,apad" -t "$d" "${enc[@]}" "$cut" ;;
    -)                    # the slide, silent
      slide "$frame" $out/slide
      ffmpeg -hide_banner -loglevel error -y -loop 1 -t "$secs" -i $out/slide.png \
        -f lavfi -t "$secs" -i anullsrc=r=48000:cl=mono -vf "$fit:white,$(fades "$secs")" -t "$secs" "${enc[@]}" "$cut" ;;
    *)                    # footage instead of the slide, looped or cut to its seconds, its sound off
      ffmpeg -hide_banner -loglevel error -y -stream_loop -1 -t "$secs" -i "$media" \
        -f lavfi -t "$secs" -i anullsrc=r=48000:cl=mono -map 0:v -map 1:a \
        -vf "$fit:black,$(fades "$secs")" -t "$secs" "${enc[@]}" "$cut" ;;
  esac
done 3< video.list
ls $out/cut*.mp4 | sed 's|^build/video/|file |' > $out/cuts.txt
ffmpeg -hide_banner -loglevel error -y -f concat -i $out/cuts.txt -c copy -movflags +faststart build/video.mp4
echo "build/video.mp4: $n cuts, $(ffprobe -v error -show_entries format=duration -of csv=p=0 build/video.mp4) s"
