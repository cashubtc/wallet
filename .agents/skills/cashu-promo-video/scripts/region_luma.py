#!/usr/bin/env python3
"""Mean luma of one region of a video, per frame at 60 fps.

Use it to find frame-exact events in a take: a tap's first response, a toast,
a sheet leaving, or the Simulator's empty Face ID box fading in and out. It
prints only the frames where the mean moves by at least --min-delta (plus the
first), so a still screen stays quiet.

  scripts/region_luma.py take-60.mp4 START LENGTH X Y W H [--min-delta 0.3] [--all]

X Y W H are pixels in the video (a 1206 × 2622 iPhone 17 Pro take is 3× the
app's points). Needs ffmpeg on PATH.
"""
import argparse
import subprocess

p = argparse.ArgumentParser()
p.add_argument("video")
p.add_argument("start", type=float)
p.add_argument("length", type=float)
p.add_argument("x", type=int)
p.add_argument("y", type=int)
p.add_argument("w", type=int)
p.add_argument("h", type=int)
p.add_argument("--min-delta", type=float, default=0.3)
p.add_argument("--all", action="store_true", help="print every frame")
a = p.parse_args()

sw, sh = max(1, a.w // 4), max(1, a.h // 4)
raw = subprocess.run(
    ["ffmpeg", "-v", "error", "-ss", str(a.start), "-t", str(a.length), "-i", a.video,
     "-vf", f"fps=60,crop={a.w}:{a.h}:{a.x}:{a.y},scale={sw}:{sh},format=gray",
     "-f", "rawvideo", "-"],
    capture_output=True, check=True).stdout
n = sw * sh
prev = None
for i in range(len(raw) // n):
    frame = raw[i * n:(i + 1) * n]
    mean = sum(frame) / n
    t = a.start + i / 60
    if a.all or prev is None or abs(mean - prev) >= a.min_delta:
        delta = "" if prev is None else f"  Δ{mean - prev:+6.2f}"
        print(f"{t:8.3f}s  f{round(t * 60):6d}  luma {mean:6.2f}{delta}")
    prev = mean
