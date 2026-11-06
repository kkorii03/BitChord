#!/usr/bin/env python3
"""Was the next song's correction ready when it started? One row per track start.

Reads smoke logcats and exported phone logs; sync-dump lines (lyrics) are never
read. Times are seconds from the row becoming current, so negative is while the
song before was still playing.

  how    AUTO/SEEK = the queue moved on; PLAYLIST_CHANGED = a link replaced it
  prep   `next-prep start` for this id, and its sync result
  ask    first request for its words went out; n< n> = requests sent before and
         after the row became current
  ans    first song-sync answer
  hit    the first-frame cache had the row (screen, service)
  shown  base of the first lyrics-sync line: what the screen first showed
  fix    first base other than none

Usage: next_prep_table.py [--queue-only] <log glob> [...]
"""
import argparse
import glob
import re
from datetime import datetime, timedelta

ID = r"([\w-]{11})"
PATTERNS = [
    ("play", re.compile(r"play: -> .*\(" + ID + r"\) idx=\S+ reason=(\S+)")),
    ("prep", re.compile(r"next-prep start: " + ID + r"()")),
    ("done", re.compile(r"next-prep sync: " + ID + r" result=(\S+)")),
    ("used", re.compile(r"next-prep used: " + ID + r" where=(\S+ hit=\S+)")),
    ("sent", re.compile(r"song-hear: sent .*?send=(\d+)ms requests=(\d+) .*?copy=" + ID)),
    ("ans", re.compile(r"song-sync: " + ID + r" (result=\S+ match=\d+%)")),
    ("shown", re.compile(r"lyrics-sync: " + ID + r" source=\S+ base=(\S+)")),
    ("base", re.compile(r"base-apply: " + ID + r" base=(\S+)")),
]


def stamp(line):
    m = re.match(r"(?:\d{4}-)?(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)\s", line)
    return datetime.strptime("2026-" + m.group(1), "%Y-%m-%d %H:%M:%S.%f") if m else None


def events(path):
    for line in open(path, encoding="utf-8", errors="replace"):
        if "sync-dump" in line or not (t := stamp(line)):
            continue
        for kind, pattern in PATTERNS:
            if m := pattern.search(line):
                if kind == "sent":
                    # The line is written when the answer lands.
                    yield t - timedelta(milliseconds=int(m.group(1))), kind, m.group(3), int(m.group(2))
                else:
                    yield t, kind, m.group(1), m.group(2)
                break


def rows(path):
    log = sorted(events(path), key=lambda e: e[0])
    starts = []
    for t, kind, vid, detail in log:
        # A row arriving again for a version or source swap is the same start.
        if kind == "play" and (not starts or starts[-1][1] != vid):
            starts.append((t, vid, detail))
    for n, (t, vid, how) in enumerate(starts):
        was = starts[n - 1][0] if n else datetime.min
        then = starts[n + 1][0] if n + 1 < len(starts) else datetime.max
        # From the row before's start: its bundle is what prepared this one.
        mine = [e for e in log if e[2] == vid and was <= e[0] < then]
        before = [e for e in mine if e[0] < t]
        after = [e for e in mine if e[0] >= t]

        def first(kind, within=mine):
            return next((e for e in within if e[1] == kind), None)

        def rel(e):
            return f"{(e[0] - t).total_seconds():+.0f}" if e else "-"

        shown, ans = first("shown", after), first("ans")
        fix = next((e for e in after if e[1] in ("shown", "base") and e[3] != "none"), None)
        yield [
            f"{t:%H:%M:%S}", f"`{vid}`", how,
            rel(first("prep", before)), (first("done", before) or [0, 0, 0, "-"])[3],
            rel(first("sent")), sum(e[3] for e in before if e[1] == "sent"), sum(e[3] for e in after if e[1] == "sent"),
            f"{rel(ans)} {ans[3]}" if ans else "-",
            " ".join(sorted(e[3].replace("where=", "").replace(" hit=", ":") for e in after if e[1] == "used")) or "-",
            f"{shown[3]}@{rel(shown)}" if shown else "-",
            f"{fix[3]}@{rel(fix)}" if fix else "-",
        ]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--queue-only", action="store_true")
    ap.add_argument("globs", nargs="+")
    args = ap.parse_args()
    print("| at | id | how | prep | result | ask | n< | n> | ans | hit | shown | fix |")
    print("|---" * 12 + "|")
    for pattern in args.globs:
        for path in sorted(glob.glob(pattern, recursive=True)):
            for r in rows(path):
                if not (args.queue_only and r[2] == "PLAYLIST_CHANGED"):
                    print("| " + " | ".join(map(str, r)) + " |")


if __name__ == "__main__":
    main()
