#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Measure Take alignment offset and drift from a spike alignment WAV (ticket #2, protocol step 4).

The WAV is written by "Save alignment WAV": left = Backing Audio (test tone with a click on every
beat), right = latency-compensated Take. Preferably the Take is an acoustic loopback (earpiece held
against the mic, recording the clicks themselves); clapping along works only roughly, because people
clap ahead of a regular beat.

For every click on the left channel the nearest clap on the right channel (within +-150 ms) is
paired. Offset = clap - click (positive: the Take is late). Drift = how much the offset changes
over the Take, from a linear fit, scaled to 20 s.

Pure standard library; no numpy needed.

    python3 analyze_alignment.py alignment-wired-20261115-120000.wav
"""
import argparse
import statistics
import sys
import wave
from array import array

PAIR_WINDOW_S = 0.150
REFRACTORY_S = 0.200


def read_stereo(path):
    with wave.open(path, "rb") as w:
        if w.getnchannels() != 2 or w.getsampwidth() != 2:
            sys.exit("expected a 16-bit stereo WAV from the spike")
        rate = w.getframerate()
        data = array("h", w.readframes(w.getnframes()))
    if sys.byteorder == "big":
        data.byteswap()
    return rate, data[0::2], data[1::2]


def onsets(samples, rate, rel_threshold):
    """Onset frames of transients: first difference (a crude high-pass that suppresses the pad's
    low tones), threshold relative to the loud end of the signal, with a refractory period."""
    diff = [abs(samples[i] - samples[i - 1]) for i in range(1, len(samples))]
    if not diff:
        return []
    loud = sorted(diff)[int(len(diff) * 0.999)] or 1
    threshold = loud * rel_threshold
    refractory = int(REFRACTORY_S * rate)
    found, last = [], -refractory
    for i, d in enumerate(diff):
        if d >= threshold and i - last >= refractory:
            found.append(i + 1)
            last = i
    return found


def linear_slope(xs, ys):
    mx, my = statistics.fmean(xs), statistics.fmean(ys)
    den = sum((x - mx) ** 2 for x in xs)
    return 0.0 if den == 0 else sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("wav")
    parser.add_argument("--click-threshold", type=float, default=0.5, help="relative to the loudest clicks (default 0.5)")
    parser.add_argument("--clap-threshold", type=float, default=0.3, help="relative to the loudest claps (default 0.3)")
    parser.add_argument("-v", "--verbose", action="store_true", help="print every pair")
    args = parser.parse_args()

    rate, left, right = read_stereo(args.wav)
    clicks = onsets(left, rate, args.click_threshold)
    claps = onsets(right, rate, args.clap_threshold)
    window = int(PAIR_WINDOW_S * rate)

    pairs = []
    edge = int(0.05 * rate)  # the file starts mid-sound on both channels: not real onsets
    for c in (c for c in clicks if c >= edge):
        nearest = min(claps, key=lambda p: abs(p - c), default=None)
        if nearest is not None and abs(nearest - c) <= window:
            pairs.append((c, nearest - c))

    duration = len(left) / rate
    print(f"file: {args.wav}  ({duration:.1f} s at {rate} Hz)")
    print(f"clicks detected: {len(clicks)}   claps detected: {len(claps)}   paired: {len(pairs)}")
    if len(pairs) < 5:
        sys.exit("too few pairs: clap closer to the mic, or adjust --click-threshold / --clap-threshold")

    times = [c / rate for c, _ in pairs]
    offsets_ms = [o * 1000 / rate for _, o in pairs]
    if args.verbose:
        for t, o in zip(times, offsets_ms):
            print(f"  t={t:7.3f} s  offset={o:+7.1f} ms")

    slope = linear_slope(times, offsets_ms)  # ms per second
    print(f"Take alignment offset, mean:   {statistics.fmean(offsets_ms):+.1f} ms")
    print(f"                       median: {statistics.median(offsets_ms):+.1f} ms")
    print(f"                       stdev:  {statistics.pstdev(offsets_ms):.1f} ms  (scatter: loopback ~1 ms, clapping much more)")
    steps = find_steps(times, offsets_ms)
    if not steps:
        print(f"Drift over 20 s:               {slope * 20:+.1f} ms  (linear fit over {times[-1] - times[0]:.1f} s)")
    else:
        # A step is a sudden timeline slip (e.g. one burst lost in an xrun), not drift: report
        # the steps and fit drift only inside the longest step-free segment.
        print(f"Steps (sudden jumps, not drift): {len(steps)}")
        for t, jump in steps:
            print(f"  at t={t:6.2f} s: {jump:+.1f} ms")
        bounds = [0] + [times.index(t) for t, _ in steps] + [len(times)]
        a, b = max(zip(bounds, bounds[1:]), key=lambda seg: seg[1] - seg[0])
        if b - a >= 5:
            seg_slope = linear_slope(times[a:b], offsets_ms[a:b])
            print(f"Drift over 20 s (between steps): {seg_slope * 20:+.1f} ms  "
                  f"(linear fit over {times[b - 1] - times[a]:.1f} s)")
    print("pass (loopback): |mean| <= 5 ms, |drift| < 2 ms, no steps")


def find_steps(times, offsets_ms, min_jump_ms=1.5):
    """Sudden jumps between consecutive pairs that are much larger than the normal scatter."""
    diffs = [b - a for a, b in zip(offsets_ms, offsets_ms[1:])]
    if not diffs:
        return []
    typical = statistics.median(abs(d) for d in diffs)
    threshold = max(min_jump_ms, 4 * typical)
    return [(times[i + 1], d) for i, d in enumerate(diffs) if abs(d) >= threshold]


if __name__ == "__main__":
    main()
