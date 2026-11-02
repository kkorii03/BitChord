#!/usr/bin/env python3
"""Why did each song's lyric sync end up the way it did? (11-02)

synctest.py says how far off the shown lines were; this says why, from the same
smoke logcat: for every played song, when its lyrics came, whether a
measurement (chroma against another recording, or a Deepgram hearing) started,
when its answer came back, whether it was accepted, and whether it reached the
screen before the song left. Each song gets one cause:

  APPLIED        a correction was on screen (synctest decides if it was right)
  LATE           an answer was accepted, but only after the song had left
  NOT_APPLIED    an answer was accepted while the song played, yet the screen
                 kept base=none (an app bug between answer and display)
  REJECTED       every answer that came was refused (reason shown)
  DECODE_FAILED  the audio for the check could not be decoded
  NEVER_MEASURED no measurement for this song ever started or answered
  NO_LYRICS      no lyrics were shown

Lines carry their song as "[<id>] ..." since the 11-02 TrackLog change; older
logs have answers without an id, which are listed as "(song unknown)" under the
song that was playing, never counted as that song's answer.

Usage: sync_timeline.py <smoke-logcat.txt> [report.csv from synctest] > timeline.md
"""
import csv
import re
import sys
from datetime import datetime

LINE = re.compile(r"^(\d\d-\d\d) (\d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+?)\s*: (.*)$")
TAGGED = re.compile(r"^\[([A-Za-z0-9_-]{11})\] (.*)$")
TIMING = re.compile(r"(Deepgram Whisper|Whisper|AI) timing \(([^)]*)\): (.*)")
CHROMA = re.compile(r"chroma .*\((\S+)\) -> (\S+) \[(\w+)\]: (\w+)")
SUMMARY = re.compile(r"track-summary: (\S+) .*?firstAudio=(\S+) played=(\S+) lyrics=(\S+) sync=(\S+) shift=(\S+)")
SYNC = re.compile(r"lyrics-sync: (\S+) source=(\S+) base=(\S+)")


def t_of(day, clock):
    return datetime.strptime(f"2026-{day} {clock}", "%Y-%m-%d %H:%M:%S.%f").timestamp()


def read(path):
    rows = []
    app_pid = None
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            m = LINE.match(raw.rstrip("\n"))
            if not m:
                continue
            day, clock, pid, _tid, level, tag, msg = m.groups()
            if app_pid is None and tag == "BitChord":
                app_pid = pid
            if pid != app_pid:
                continue
            about = None
            tm = TAGGED.match(msg)
            if tm:
                about, msg = tm.groups()
            rows.append((t_of(day, clock), clock[:8], tag, msg, about))
    return rows


def main():
    rows = read(sys.argv[1])
    verdicts = {}
    if len(sys.argv) > 2:
        with open(sys.argv[2], encoding="utf-8") as f:
            for r in csv.DictReader(f):
                verdicts[r.get("video", "")] = (r.get("verdict", ""), r.get("median", ""))

    # Song windows: first lyrics-sync line of each played id, in order.
    starts, summary, last_sync = {}, {}, {}
    for t, clock, tag, msg, about in rows:
        m = SYNC.search(msg)
        if m:
            starts.setdefault(m.group(1), t)
            last_sync[m.group(1)] = (m.group(2), m.group(3))
        m = SUMMARY.search(msg)
        if m:
            summary[m.group(1)] = m.groups()[1:]
    order = sorted(starts, key=starts.get)
    ends = {vid: (starts[order[i + 1]] if i + 1 < len(order) else float("inf")) for i, vid in enumerate(order)}

    def playing_at(t):
        cur = None
        for vid in order:
            if starts[vid] <= t:
                cur = vid
        return cur

    events = {vid: [] for vid in order}
    answers = {vid: [] for vid in order}  # (t, accepted, text)
    unknown = {vid: [] for vid in order}
    measuring = {vid: [] for vid in order}
    decode_fail = {vid: [] for vid in order}
    for t, clock, tag, msg, about in rows:
        m = CHROMA.search(msg)
        if m:
            vid = m.group(2)
            if vid in events:
                ok = m.group(4) == "accepted"
                answers[vid].append((t, ok, f"chroma [{m.group(3)}] {m.group(4)}"))
                events[vid].append((t, f"chroma vs {m.group(1)} [{m.group(3)}]: {m.group(4)}"))
            continue
        m = TIMING.search(msg)
        if m:
            text = f"{m.group(1)} ({m.group(2)}): {m.group(3)}"
            ok = "% agree" in m.group(3) and "below" not in m.group(3)
            vid = about
            if vid in events:
                answers[vid].append((t, ok, text))
                events[vid].append((t, text))
            else:
                cur = playing_at(t)
                if cur:
                    unknown[cur].append((t, text if vid is None else f"[{vid}] {text}"))
            continue
        vid = about
        if vid is None:
            for cand in order:
                if cand in msg:
                    vid = cand
                    break
        if vid not in events:
            if "decode:" in msg and ("failed" in msg or "dropped" in msg):
                cur = playing_at(t)
                if cur:
                    unknown[cur].append((t, msg[:120]))
            continue
        if "-> measuring" in msg or "song check" in msg or "next-prep" in msg:
            measuring[vid].append(t)
            events[vid].append((t, msg[:140]))
        elif "decode:" in msg and ("failed" in msg or "dropped" in msg):
            decode_fail[vid].append(t)
            events[vid].append((t, msg[:140]))
        elif msg.startswith("lyrics-sync:") or msg.startswith("track-summary:"):
            events[vid].append((t, msg[:160]))

    print("# Sync timeline per song\n")
    print("| song | verdict | median | lyrics | cause | first answer | detail |")
    print("|---|---|---|---|---|---|---|")
    causes = {}
    for vid in order:
        s = summary.get(vid)
        lyrics = s[2] if s else last_sync.get(vid, ("?",))[0]
        sync = s[3] if s else last_sync.get(vid, ("", "none"))[1]
        start, end = starts[vid], ends[vid]
        ok_answers = [a for a in answers[vid] if a[1]]
        first = ok_answers[0] if ok_answers else None
        first_txt = f"+{first[0] - start:.0f}s" if first else "-"
        if lyrics in ("none", "-", "?") and not last_sync.get(vid):
            cause, detail = "NO_LYRICS", ""
        elif sync not in ("none", "-", ""):
            cause, detail = "APPLIED", f"sync={sync}"
        elif first and first[0] > end:
            cause, detail = "LATE", f"answer +{first[0] - start:.0f}s, song left at +{end - start:.0f}s"
        elif first:
            cause, detail = "NOT_APPLIED", f"accepted +{first[0] - start:.0f}s while playing: {first[2][:60]}"
        elif answers[vid]:
            cause, detail = "REJECTED", answers[vid][-1][2][:80]
        elif decode_fail[vid]:
            cause, detail = "DECODE_FAILED", f"{len(decode_fail[vid])} failed decodes"
        else:
            cause, detail = "NEVER_MEASURED", f"{len(unknown[vid])} unattributed answers/failures while it played"
        causes[cause] = causes.get(cause, 0) + 1
        v, med = verdicts.get(vid, ("", ""))
        print(f"| {vid} | {v} | {med} | {lyrics} | **{cause}** | {first_txt} | {detail} |")
    print("\n" + ", ".join(f"{k} {n}" for k, n in sorted(causes.items(), key=lambda kv: -kv[1])) + "\n")

    for vid in order:
        start = starts[vid]
        print(f"## {vid} (played {datetime.fromtimestamp(start).strftime('%H:%M:%S')}, left +{ends[vid] - start:.0f}s)")
        for t, text in sorted(events[vid])[:40]:
            print(f"- +{t - start:.0f}s {text}")
        for t, text in unknown[vid][:10]:
            print(f"- +{t - start:.0f}s (song unknown) {text}")
        print()


if __name__ == "__main__":
    main()
