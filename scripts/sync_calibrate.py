#!/usr/bin/env python3
"""How wrong is the sync scorer itself? (11-02, user: "the checking algorithm may be the odd one")

synctest.py judges a shown line against the time a hearing model (Deepgram's
whisper-large/-medium) put the line's first sound at. If that reference is off,
a perfect sheet fails. This feeds the scorer sheets whose timing is known to be
right — Apple Music's hand-timed lyrics from the AMLL TTML DB — on the very
YouTube copy and heard words synctest already cached, and prints what the
scorer makes of them. A correct scorer gives ~0 bias and a tight spread there;
whatever it reports is its own error, not the app's.

Usage: sync_calibrate.py <dir with <vid>.opus.<model>.words.json> <vid>=<itunes query> ...
"""
import importlib.util
import json
import re
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("synctest", HERE / "synctest.py")
st = importlib.util.module_from_spec(spec)
spec.loader.exec_module(st)

TTML_BASE = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/am-lyrics/"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "BitChord-sync-calibrate"})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read()
        except urllib.error.HTTPError:
            raise
        except Exception:  # the dorm link resets connections now and then
            if attempt == 4:
                raise
            time.sleep(3 * (attempt + 1))


def itunes(query):
    q = urllib.parse.urlencode({"term": query, "entity": "song", "limit": 10, "country": "us"})
    return json.loads(get(f"https://itunes.apple.com/search?{q}"))["results"]


def seconds(t):
    """TTML clock: 83.456s | 1:23.456 | 00:01:23.456."""
    t = t.strip()
    if t.endswith("ms"):
        return float(t[:-2]) / 1000
    if t.endswith("s"):
        return float(t[:-1])
    parts = [float(p) for p in t.split(":")]
    out = 0.0
    for p in parts:
        out = out * 60 + p
    return out


def ttml_lines(raw):
    """[(n, start_ms, text)] from the <p> elements, background vocals left out."""
    root = ET.fromstring(raw)
    lines = []
    for p in root.iter():
        if not p.tag.endswith("}p") and p.tag != "p":
            continue
        begin = p.get("begin")
        if begin is None:
            continue
        parts = []
        for el in p.iter():
            role = next((v for k, v in el.attrib.items() if k.endswith("role")), "")
            if role in ("x-bg", "x-translation", "x-roman"):
                continue
            if el is not p and el.text:
                parts.append(el.text)
            if el is not p and el.tail:
                parts.append(el.tail)
        if not parts and p.text:
            parts = [p.text]
        text = re.sub(r"\s+", " ", "".join(parts)).strip()
        if text:
            lines.append((len(lines), int(round(seconds(begin) * 1000)), text))
    return lines


RAW_BASE = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/raw-lyrics/"


def heard_words(folder, vid, lang="en"):
    """The two models' words for YouTube's Opus copy of vid: cached, else fetched and heard
    exactly as synctest.run_song does it (same itag, same models, same cache names)."""
    words = []
    for m in st.MODELS:
        f = next(folder.rglob(f"{vid}.opus.{m}.words.json"), None)
        words.append(json.loads(f.read_text(encoding="utf-8")) if f else None)
    if None not in words:
        return words
    st.OUT.mkdir(parents=True, exist_ok=True)
    full = st.OUT / f"{vid}.webm"
    if not full.exists():
        st.subprocess.run([st.YTDLP, "-f", "251/bestaudio[ext=webm]/bestaudio", "-o", str(full),
                           f"https://www.youtube.com/watch?v={vid}"], capture_output=True, timeout=600)
    if not full.exists():
        return None
    return [st.hear(f"{vid}.opus", full, "audio/webm", lang, m) for m in st.MODELS]


def main():
    folder = Path(sys.argv[1])
    errors_all = []
    for arg in sys.argv[2:]:
        # <vid>=<itunes query>, or <vid>=@<raw-lyrics file> from the AMLL index.
        vid, query = arg.split("=", 1)
        lines = None
        if query.startswith("@"):
            lines = ttml_lines(get(RAW_BASE + query[1:]))
            pick = dict(trackName=query[1:], artistName="(raw-lyrics)", trackTimeMillis=0)
        for h in ([] if lines else itunes(query)):
            try:
                lines = ttml_lines(get(TTML_BASE + f"{h['trackId']}.ttml"))
                pick = h
                break
            except Exception:
                continue
        if not lines:
            print(f"{vid}: no AMLL sheet for '{query}'")
            continue
        # Heard in the sheet's own language, as synctest hears a song.
        words = heard_words(folder, vid, st.language_for(lines))
        if not words or None in words:
            print(f"{vid}: no heard words")
            continue
        both, either, gap = st.agreed([st.align(lines, w) for w in words])
        heard_until = min(w[-1][1] for w in words if w)
        eligible = sum(1 for _, ms, t in lines if len(st.norm(t)[:st.PREFIX_CHARS]) >= 2 and ms / 1000 <= heard_until)
        s = st.score(both, eligible, len(either)) if both else None
        errs = [shown - sung for _, shown, sung, _ in both]
        errors_all += errs
        print(f"{vid} '{pick['trackName']}' / {pick['artistName']} ({pick['trackTimeMillis'] / 1000:.1f}s) "
              f"sheet lines={len(lines)} agreed={len(both)} heard={len(either)}/{eligible} model_gap={gap}")
        if s:
            print(f"   scorer says: {s['verdict']} median={s['median']:+.3f} |med|={s['median_abs']:.3f} "
                  f"in0.2={s['in02']:.0%} in0.3={s['in03']:.0%} max={s['max_abs']} shape={s['shape']} why={s['why']}")
            print("   per-line err (shown - heard, s): " + " ".join(f"{e:+.2f}" for e in errs[:40]))
    if errors_all:
        a = [abs(e) for e in errors_all]
        print(f"ALL {len(a)} lines: median={statistics.median(errors_all):+.3f} |med|={statistics.median(a):.3f} "
              f"in0.2={sum(x <= 0.2 for x in a) / len(a):.0%} in0.3={sum(x <= 0.3 for x in a) / len(a):.0%}")


if __name__ == "__main__":
    main()
