# Per-event seek report for a matrix set: for each seek mark, lyric-clock gap
# before/after and seconds until the next lyric-clock line. Reads only
# lyric-clock/matrix lines, never sync-dump text.
#
# Usage: seek-events.py <set-dir>
import pathlib
import re
import sys
from datetime import datetime

TS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)")
GAP = re.compile(r"lyric-clock: drawn=(-?\d+) reading=(-?\d+) age=(-?\d+)ms gap=(-?\d+)ms")


def ts(line):
    m = TS.match(line)
    return datetime.strptime("2026-" + m.group(1), "%Y-%m-%d %H:%M:%S.%f") if m else None


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    root = pathlib.Path(sys.argv[1])
    log = root / "smoke-logcat.txt"
    if not log.exists():
        print("missing", log)
        return 1
    rows = []
    song = None
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        if "sync-dump" in line:
            continue
        t = ts(line)
        if not t:
            continue
        m = re.search(r"matrix: set=\S+ song=([\w-]{11}) start", line)
        if m:
            song = m.group(1)
            continue
        if "matrix: set=" not in line or "seek " not in line:
            continue
        m = re.search(r"seek (rewind|fast-forward) (before|after)(?: state=(\w+) pos=(\d+))?", line)
        if not m:
            continue
        rows.append(dict(song=song, t=t, dir=m.group(1), phase=m.group(2), state=m.group(3), pos=m.group(4)))

    # lyric-clock samples per song between this song's start and the next start.
    starts = []
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        if "sync-dump" in line:
            continue
        t = ts(line)
        if not t:
            continue
        m = re.search(r"matrix: set=\S+ song=([\w-]{11}) start", line)
        if m:
            starts.append((t, m.group(1), m.group(1)))
    samples = []
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        if "sync-dump" in line:
            continue
        t = ts(line)
        m = GAP.search(line)
        if t and m:
            samples.append((t, abs(int(m.group(4)))))

    def song_range(i):
        a = starts[i][0]
        b = starts[i + 1][0] if i + 1 < len(starts) else datetime.max
        return a, b

    print(f"| song | seek | phase | state/pos | |gap| before (ms) | |gap| after (ms) | next lyric-clock (s) | samples after |")
    print("|" + "---|" * 9)
    # Report every song that has marks, using the nearest start before each mark.
    for r in rows:
        idx = max([i for i, s in enumerate(starts) if s[0] <= r["t"]], default=0)
        a, b = song_range(idx)
        before = [g for t, g in samples if a <= t < r["t"]]
        after = [(t, g) for t, g in samples if r["t"] <= t < b]
        nxt = (after[0][0] - r["t"]).total_seconds() if after else None
        # 10 s before the mark; 25 s after, or until the next mark, whichever ends first.
        nb = 10
        na = 25
        rb = [g for t, g in samples if 0 <= (r["t"] - t).total_seconds() <= nb]
        ra = [g for t, g in samples if 0 <= (t - r["t"]).total_seconds() <= na]
        print(f"| {r['song']} | {r['dir']} {r['phase']} | {r['phase']} | {r['state'] or '-'}/{r['pos'] or '-'} | "
              f"{max(rb) if rb else '-'} | {max(ra) if ra else '-'} | "
              f"{'' if nxt is None else f'{nxt:.1f}'} | {len(ra)} |")
    return 0


if __name__ == "__main__":
    sys.exit(main())
