#!/bin/zsh
# A contact sheet of a take (or a film) for authoring cuts by eye: `fps`
# frames per second from `start` for `length` seconds, tiled `cols` wide.
# Frame i of the sheet (left to right, top to bottom) is at start + i / fps.
#
#   scripts/contact_sheet.sh <video> <start s> <length s> <out.png> [fps=5] [cols=10] [width=160] [crop]
#
# `crop` is an ffmpeg crop expression applied first, e.g. "iw:ih*0.5:0:ih*0.35"
# for the middle of a phone, or "540:1080:540:0" for the right phone of a
# two-phone film.
set -euo pipefail
video="$1" start="$2" length="$3" out="$4"
fps="${5:-5}" cols="${6:-10}" width="${7:-160}" crop="${8:-}"
frames=$(python3 -c "import math; print(max(1, math.ceil($length * $fps)))")
rows=$(( (frames + cols - 1) / cols ))
vf="fps=$fps"
[[ -n "$crop" ]] && vf="crop=$crop,$vf"
ffmpeg -v error -y -ss "$start" -t "$length" -i "$video" \
  -vf "$vf,scale=$width:-1,tile=${cols}x${rows}" -frames:v 1 "$out"
print "sheet → $out ($frames frames, ${cols}×${rows}; frame i = ${start}s + i/${fps})"
