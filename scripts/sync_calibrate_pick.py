#!/usr/bin/env python3
"""Picks calibration songs for sync_calibrate.py (11-02): AMLL hand-timed sheets whose
recording has a YouTube art track the same length as Apple's (within 1 s), so the
sheet's clock is the YouTube copy's clock. Prints `<vid>=@<raw-lyrics file>` args.

Usage: sync_calibrate_pick.py <raw-lyrics-index.jsonl> [count] [seed]
"""
import json
import random
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
YTDLP = str(ROOT / "tools" / "bin" / "yt-dlp.exe")


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def lookup(apple_id):
    url = f"https://itunes.apple.com/lookup?id={apple_id}&country=us"
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=20) as r:
                res = json.load(r).get("results", [])
                return res[0] if res else None
        except Exception:
            time.sleep(3 * (attempt + 1))
    return None


def main():
    sys.stdout.reconfigure(encoding="utf-8")  # artist names; `-I` ignores PYTHONIOENCODING
    index, count = Path(sys.argv[1]), int(sys.argv[2]) if len(sys.argv) > 2 else 12
    rng = random.Random(int(sys.argv[3]) if len(sys.argv) > 3 else 1102)
    pool = []
    for line in index.read_text(encoding="utf-8").splitlines():
        j = json.loads(line)
        md = {k: v for k, v in j["metadata"]}
        ids = md.get("appleMusicId") or []
        name = (md.get("musicName") or [""])[0]
        if ids and name and name.isascii():
            pool.append((ids[0], name, j["rawLyricFile"]))
    rng.shuffle(pool)
    picked = 0
    for apple_id, name, raw in pool:
        if picked >= count:
            break
        hit = lookup(apple_id)
        if not hit or "trackTimeMillis" not in hit:
            continue
        want = hit["trackTimeMillis"] / 1000
        artist = hit["artistName"]
        out = subprocess.run([YTDLP, "--flat-playlist", "--print", "%(id)s|%(channel)s|%(duration)s|%(title)s",
                              f"ytsearch6:{artist} {hit['trackName']}"], capture_output=True, text=True,
                             encoding="utf-8", errors="replace", timeout=120).stdout
        for row in out.splitlines():
            parts = row.split("|", 3)
            if len(parts) < 4 or parts[2] in ("NA", ""):
                continue
            vid, channel, dur, title = parts
            # An art track: the artist's channel, the bare song title, Apple's length.
            if norm(title) == norm(hit["trackName"]) and abs(float(dur) - want) <= 1.0 and \
                    norm(artist.split(",")[0].split("&")[0]) in norm(channel):
                print(f"{vid}=@{raw}  # {artist} - {hit['trackName']} {want:.0f}s", flush=True)
                picked += 1
                break


if __name__ == "__main__":
    main()
