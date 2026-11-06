#!/usr/bin/env python3
"""How long does a played song go uncorrected, and where does the time go? (11-06)

Reads smoke or phone logcats. Numbers and ids only; sync-dump lines (lyrics) are
skipped. A song counts when it played at least --min-played seconds.

  ask     first sound -> the first request to the speech server went out
  answer  first sound -> its first answer came back
  shown   first sound -> a correction first reached the screen (base=ai/measured)

Usage: sync_latency.py [--min-played 20] <logcat glob> [<logcat glob> ...]
"""
import argparse
import glob
import os
import re
import statistics
from datetime import datetime

TS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)")
ID = r"([\w-]{11})"


def stamp(line):
    m = TS.match(line)
    return datetime.strptime("2026-" + m.group(1), "%Y-%m-%d %H:%M:%S.%f") if m else None


def read(path):
    songs = {}

    def song(vid):
        return songs.setdefault(vid, dict(run=os.path.basename(os.path.dirname(path)), id=vid, sends=[], results=[]))

    for line in open(path, encoding="utf-8", errors="replace"):
        if "sync-dump" in line:
            continue
        t = stamp(line)
        if not t:
            continue
        m = re.search(r"\[" + ID + r"\] TIMING first audio", line)
        if m:
            song(m.group(1)).setdefault("audio", t)
            continue
        m = re.search(r"song-hear: sent .*?wait=(\d+)ms send=(\d+)ms requests=(\d+) http=(\S+) words=(\S+) audio=\S+ copy=" + ID, line)
        if m:
            s = song(m.group(5 + 1))
            wait, send = int(m.group(1)), int(m.group(2))
            s["sends"].append(dict(end=t, wait=wait / 1000, send=send / 1000, requests=int(m.group(3)), http=m.group(4)))
            continue
        m = re.search(r"\[" + ID + r"\] song-hear: kept", line)
        if m:
            song(m.group(1))["kept"] = song(m.group(1)).get("kept", 0) + 1
            continue
        # The older build logged the request's start only for mixed sheets.
        m = re.search(r"\[" + ID + r"\] song-sync: lang=", line)
        if m:
            song(m.group(1)).setdefault("oldask", t)
            continue
        m = re.search(r"song-sync: " + ID + r" result=(\S+)", line)
        if m:
            s = song(m.group(1))
            s["results"].append(m.group(2))
            s.setdefault("answer", t)
            continue
        m = re.search(r"\[" + ID + r"\] (?:Deepgram Whisper|Whisper) timing", line)
        if m:
            song(m.group(1)).setdefault("answer", t)
            continue
        m = re.search(r"base-apply: " + ID + r" base=(\S+)", line)
        if m:
            s = song(m.group(1))
            s["last"] = m.group(2)
            if m.group(2) != "none":
                s.setdefault("shown", t)
            if "other-lyrics" in line:
                s["other"] = s.get("other", 0) + 1
            continue
        m = re.search(r"track-summary: " + ID + r" .*?firstAudio=(\S+) played=(\d+) lyrics=(\S+) sync=(\S+)", line)
        if m:
            s = song(m.group(1))
            s["played"], s["lyrics"], s["sync"] = int(m.group(3)), m.group(4), m.group(5)
    return songs.values()


def show(name, values):
    if not values:
        print(f"{name}: none")
        return
    v = sorted(values)
    print(f"{name}: n={len(v)} median {statistics.median(v):.1f} p25 {v[len(v) // 4]:.1f} p75 {v[len(v) * 3 // 4]:.1f} max {v[-1]:.1f}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-played", type=int, default=20)
    ap.add_argument("globs", nargs="+")
    args = ap.parse_args()
    rows = []
    for pattern in args.globs:
        for path in glob.glob(pattern, recursive=True):
            rows += [s for s in read(path) if "audio" in s and s.get("played", 0) >= args.min_played]
    print("songs", len(rows), "of which with lyrics", sum(1 for s in rows if s.get("lyrics", "none") != "none"))
    gap = lambda a, s: (s[a] - s["audio"]).total_seconds()
    sent = [s for s in rows if s["sends"]]
    show("first sound -> request out", [(s["sends"][0]["end"] - s["audio"]).total_seconds() - s["sends"][0]["send"] for s in sent] or
         [gap("oldask", s) for s in rows if "oldask" in s])
    show("first sound -> first answer", [gap("answer", s) for s in rows if "answer" in s])
    show("first sound -> correction on screen", [gap("shown", s) for s in rows if "shown" in s])
    show("wait for bytes", [x["wait"] for s in rows for x in s["sends"]])
    show("request", [x["send"] for s in rows for x in s["sends"]])
    requests = [sum(x["requests"] for x in s["sends"]) for s in sent]
    if requests:
        print("requests per song: mean", round(statistics.mean(requests), 2), "second requests sent:", sum(1 for s in sent for x in s["sends"] if x["requests"] > 1))
    print("answers read back without a request:", sum(s.get("kept", 0) for s in rows))
    print("sync at end:", {k: sum(1 for s in rows if s.get("sync") == k) for k in sorted({str(s.get("sync")) for s in rows})})
    print("last base:", {k: sum(1 for s in rows if s.get("last") == k) for k in sorted({str(s.get("last")) for s in rows})})
    print("ai answer for another sheet:", sum(1 for s in rows if s.get("other")), "songs")
    print("check results:", {k: sum(s["results"].count(k) for s in rows) for k in sorted({r for s in rows for r in s["results"]})})
    print("http:", {k: sum(1 for s in rows for x in s["sends"] if x["http"] == k) for k in sorted({x["http"] for s in rows for x in s["sends"]})})


if __name__ == "__main__":
    main()
