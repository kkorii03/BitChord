#!/usr/bin/env python3
"""Replays finished smoke logs through watchdog.Judge in log time; prints when it would have stopped.

smoke-playback.txt has no times, so a song's start is taken from the app's `play: ->`
line (reason=PLAYLIST_CHANGED), and a song that did not start from the wait before it.

Usage: watchdog_replay.py <folder with smoke-logcat.txt> [...]; a row per log, numbers and ids only.
"""
import os
import re
import statistics
import sys
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import watchdog  # noqa: E402

TS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)")
PLAY = re.compile(r"play: -> .*\(([\w-]{11})\) idx=\S+ reason=PLAYLIST_CHANGED")
GAP_S = 30  # a swap of the same request fires PLAYLIST_CHANGED again within seconds


def read_log(path):
    out = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if "sync-dump" in line:
                continue
            m = TS.match(line)
            if m:
                out.append((datetime.strptime("2026-" + m.group(1), "%Y-%m-%d %H:%M:%S.%f").timestamp(), line))
    return out


def script_events(lines, playback):
    """(time, playback line) per song; the wait is the usual gap between songs."""
    starts = []
    for t, line in lines:
        if PLAY.search(line) and (not starts or t - starts[-1] >= GAP_S):
            starts.append(t)
    gaps = [b - a for a, b in zip(starts, starts[1:])]
    wait = int(statistics.median(gaps)) - 5 if gaps else 150
    if not playback:
        return [(t, f"x{i} VIEW started") for i, t in enumerate(starts)], wait
    events, i, last = [], 0, lines[0][0]
    for line in playback:
        line = line.strip()
        if line.endswith(" started"):
            if i >= len(starts):
                break
            last = starts[i]
            i += 1
        else:
            last += wait + 35 if not events or events[-1][1].endswith(" started") else 35
        events.append((last, line))
    return [e for e in events if "; trying" not in e[1]], wait


def replay(folder):
    lines = read_log(os.path.join(folder, "smoke-logcat.txt"))
    if not lines:
        return None
    path = os.path.join(folder, "smoke-playback.txt")
    playback = open(path, encoding="utf-8", errors="replace").read().splitlines() if os.path.exists(path) else []
    events, wait = script_events(lines, playback)
    songs = sum(1 for _, s in events if watchdog.SCRIPT.match(s)) or 1
    judge = watchdog.Judge(lines[0][0], songs, wait)
    judge.log(lines[0][0], lines[0][1])
    e = 0
    sound = gap = 0
    prev = lines[0][0]
    for t, line in lines:
        gap = max(gap, t - prev)
        prev = t
        if "TIMING first audio" in line:
            sound += 1
        while e < len(events) and events[e][0] <= t:
            judge.script(events[e][0], events[e][1])
            e += 1
        judge.tick(t)
        judge.log(t, line)
        if judge.stop:
            break
    end = lines[-1][0]
    return dict(minutes=(end - lines[0][0]) / 60, songs=songs, wait=wait, sound=sum("TIMING first audio" in s for _, s in lines),
                stop=judge.stop, at=None if not judge.stop else (judge.stop_at - lines[0][0]) / 60,
                saved=0 if not judge.stop else (end - judge.stop_at) / 60, net=judge.net)


def main():
    for folder in sys.argv[1:]:
        r = replay(folder)
        if not r:
            print(f"{folder}\tEMPTY")
            continue
        stop = "-" if not r["stop"] else f"{r['stop'][0]}/{r['stop'][1]} song={r['stop'][2]} at={r['at']:.1f}m saved={r['saved']:.1f}m"
        print(f"{folder}\t{r['minutes']:.1f}m songs={r['songs']} wait={r['wait']} sound={r['sound']} net={r['net']}\t{stop}")


if __name__ == "__main__":
    main()
