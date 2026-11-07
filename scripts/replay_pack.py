#!/usr/bin/env python3
"""Build the offline sync replay corpus from recorded phone logs.

A song's fit is a pure function of its sheet and the words heard.  This reads
the `sync-dump:` blocks and the `<id>.*.words.json` files under
`phone-out/rs` and `phone-out/rs2`, keeps the newest good round per song, and
writes one corpus file per song to `phone-out/replay/<id>.json` (outside the
repository).  It also writes the numbers-only baseline the JVM test compares
against to `bench/replay/baseline.json`.

The corpus holds lyric text, so it must stay outside the repository.  This
script prints counts only, never a line of lyrics.

A round is good for a song when its log has a `base=none` block (the
provider's own times), a later block for the same sheet, and a word file with
enough words.  The independent score in `lines.csv` (or `report.csv` /
`<round>.summary.json`) gives the offset the sheet still needs; the baseline
records the app's own error against that answer.
"""

import argparse
import csv
import json
import os
import re
import statistics
import sys
from pathlib import Path

PHONE_OUT = Path(r"C:\Users\jang\Desktop\BitChord\phone-out")
REPO = Path(__file__).resolve().parent.parent
MIN_WORDS = 40          # LyricsMismatch.MIN_HEARD_WORDS
MIN_LINES = 10          # LyricsMismatch.MIN_LINES_READ
STRESS_MS = 1_000       # a move this large is a stress song
STRESS_MEDIAN_MS = 500  # or a residual this large

META = re.compile(r"(rep|words|lit)=\S+\s+")
MISMATCH = re.compile(r"\[([A-Za-z0-9_-]+)\] lyrics-mismatch: .* -> (rejected|kept)")
REFINE = re.compile(r"lyrics-refine: ([A-Za-z0-9_-]+) .*applied=([yn])")
NO_AUDIO = re.compile(r"(?:song-sync|next-prep sync): (\S+) .*no-audio")


def parse_blocks(text):
    """Every complete sync-dump block in `text`, keyed by the video id it names.

    The log file is named after the requested video; the block names the video
    actually playing (a cover or a substitute), so the block's id is the key.
    """
    blocks = {}
    cur = None
    for raw in text.splitlines():
        i = raw.find("sync-dump: ")
        if i < 0:
            continue
        parts = raw[i + len("sync-dump: "):].rstrip("\r").split(" ", 4)
        if len(parts) < 2:
            continue
        kind, vid = parts[0], parts[1]
        if kind == "begin":
            head = dict(p.split("=", 1) for p in raw[i:].split()[3:] if "=" in p)
            cur = (vid, head, [])
        elif kind == "line" and cur is not None and cur[0] == vid and len(parts) >= 4:
            rest = parts[4] if len(parts) > 4 else ""
            while True:
                meta = META.match(rest)
                if not meta:
                    break
                rest = rest[meta.end():]
            cur[2].append((int(parts[2]), int(parts[3]), rest))
        elif kind == "end" and cur is not None and cur[0] == vid:
            blocks.setdefault(vid, []).append((cur[1], cur[2]))
            cur = None
    return blocks


def text_key(lines):
    """A hash of the sheet's text, so a changed sheet is not replayed as the same one."""
    return hash("\n".join(t for _, _, t in lines))


def word_file(round_dir, vid):
    """The large-model word file for `vid`: whole song first, then the app's 90 s.

    Only whisper-large is used; a medium transcript is a different measurement
    and is not substituted, so the replay compares like with like.
    """
    for suffix in (".opus.whisper-large.words.json", ".app.whisper-large.words.json"):
        path = round_dir / (vid + suffix)
        if path.exists():
            return path
    return None


def read_words(path):
    words = json.loads(path.read_text(encoding="utf-8"))
    return [{"t": w[0], "s": round(float(w[1]) * 1000), "e": round(float(w[2]) * 1000)}
            for w in words if len(w) >= 3]


def number(value):
    """A CSV/JSON cell as a float; missing or non-numeric cells are None."""
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    try:
        return float(text)
    except ValueError:
        return None


def median_error(round_dir, vid):
    """The independent score's median error in ms, or None.

    `lines.csv` is the per-line score keyed by the header it actually has;
    `report.csv` and `<round>.summary.json` carry the same median for songs
    whose lines were not written out.  A missing `error_s` column falls back
    to a millisecond column or to the other files, never to a lyric row.
    """
    lines_csv = round_dir / (vid + ".lines.csv")
    if lines_csv.exists():
        with lines_csv.open(newline="", encoding="utf-8-sig") as f:
            reader = csv.DictReader(f)
            fields = {name.strip().lower(): name for name in (reader.fieldnames or [])
                      if isinstance(name, str)}
            scale = 1000.0
            column = next((fields[c] for c in ("error_s", "err_s", "error") if c in fields), None)
            if column is None:
                column = next((fields[c] for c in ("error_ms", "err_ms") if c in fields), None)
                scale = 1.0
            if column is not None:
                errors = []
                for row in reader:
                    value = number(row.get(column))
                    if value is not None:
                        errors.append(value)
                if errors:
                    return round(statistics.median(errors) * scale)
    report = round_dir / "report.csv"
    if report.exists():
        with report.open(newline="", encoding="utf-8-sig") as f:
            for row in csv.DictReader(f):
                if (row.get("video") or "").strip() == vid:
                    median = number(row.get("median"))
                    if median is not None:
                        return round(median * 1000)
    # A round's own summary sits beside it; a flat rs/rs2 root keeps all rounds
    # in one `all.summary.json` keyed by round name.
    for summary in (round_dir.parent / (round_dir.name + ".summary.json"),
                    round_dir / "all.summary.json"):
        if not summary.exists():
            continue
        data = json.loads(summary.read_text(encoding="utf-8"))
        if isinstance(data, list):
            rows = data
        else:
            rows = []
            for group in data.values():
                if isinstance(group, list):
                    rows.extend(group)
                elif isinstance(group, dict):
                    rows.append(group)
        for row in rows:
            if not isinstance(row, dict):
                continue
            if str(row.get("video", "")).strip() == vid:
                median = number(row.get("median"))
                if median is not None:
                    return round(median * 1000)
    return None


def line_truth(round_dir, vid):
    """[line, sung ms] for every line the independent score placed; empty without lines.csv."""
    path = round_dir / (vid + ".lines.csv")
    if not path.exists():
        return []
    out = []
    with path.open(newline="", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            line, sung = number(row.get("line")), number(row.get("sung_s"))
            if line is not None and sung is not None:
                out.append([int(line), round(sung * 1000)])
    return out


ENGLISH_WORDS = {
    "the", "you", "i", "me", "my", "your", "and", "to", "a", "it", "is", "in", "on", "of", "we", "that", "this",
    "be", "love", "don't", "i'm", "it's", "with", "for", "all", "so", "can", "know", "just", "what", "when", "oh",
}


def language_for(texts):
    """The same script choice as `Deepgram.languageFor`, so `spellableLines` matches the app."""
    hangul = kana = han = latin = 0
    for line in texts:
        for c in line:
            if "\uac00" <= c <= "\ud7a3":
                hangul += 1
            elif "\u3040" <= c <= "\u30ff":
                kana += 1
            elif "\u4e00" <= c <= "\u9fff":
                han += 1
            elif c.isascii() and c.isalpha():
                latin += 1
    total = max(hangul + kana + han + latin, 1)
    if hangul * 4 >= total:
        return "ko"
    if kana * 10 >= total:
        return "ja"
    if han * 2 >= total:
        return "zh"
    words = [w for line in texts for w in re.split(r"[^a-z']+", line.lower()) if w]
    if len(words) >= 8 and sum(w in ENGLISH_WORDS for w in words) * 5 >= len(words):
        return "en"
    return "multi"


def sheet_pair(blocks):
    """The base=none block and its later block for the same sheet, or None.

    One log names several providers for a song; pairing the first and the last
    block overall pairs two different sheets whenever the providers disagree.
    Group by the sheet's text so the shift belongs to the sheet replayed.
    """
    groups = {}
    for block in blocks:
        groups.setdefault(text_key(block[1]), []).append(block)
    best = None
    for group in groups.values():
        first = group[0]
        if first[0].get("base") != "none" or len(first[1]) < MIN_LINES:
            continue
        last = group[-1]
        if len(first[1]) != len(last[1]):
            continue
        # Prefer a sheet the app actually shifted, then the more complete one.
        rank = (1 if last[0].get("base") != "none" else 0, len(first[1]))
        if best is None or rank > best[0]:
            best = (rank, first, last)
    return None if best is None else (best[1], best[2])


def round_time(round_dir):
    """When the round ran: its newest log or word file, not the directory entry."""
    times = []
    for pattern in ("*.log", "*.words.json"):
        for path in round_dir.glob(pattern):
            try:
                times.append(path.stat().st_mtime)
            except OSError:
                continue
    if times:
        return max(times)
    try:
        return round_dir.stat().st_mtime
    except OSError:
        return 0.0


def round_candidates(round_dir):
    """Good songs in one round: vid -> (first block, last block, words, median, verdicts)."""
    found = {}
    verdicts = {}
    no_audio = set()
    logs = sorted(round_dir.glob("*.log"), key=lambda p: p.stat().st_mtime)
    for log in logs:
        try:
            text = log.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for line in text.splitlines():
            m = MISMATCH.search(line)
            if m:
                verdicts.setdefault(m.group(1), {})["mismatchRejected"] = 1 if m.group(2) == "rejected" else 0
            m = REFINE.search(line)
            if m:
                verdicts.setdefault(m.group(1), {})["refineApplied"] = 1 if m.group(2) == "y" else 0
            m = NO_AUDIO.search(line)
            if m:
                no_audio.add(m.group(1))
        blocks = parse_blocks(text)
        for vid, list_ in blocks.items():
            pair = sheet_pair(list_)
            if pair is None:
                continue
            first, last = pair
            words_path = word_file(round_dir, vid)
            if words_path is None:
                continue
            words = read_words(words_path)
            if len(words) < MIN_WORDS:
                continue
            median = median_error(round_dir, vid)
            if median is None:
                continue
            found[vid] = (first, last, words, median, verdicts.get(vid, {}))
    # A song the app heard no audio for cannot be replayed, even if an older
    # block in the same round still has a sheet.
    for vid in no_audio:
        found.pop(vid, None)
    return found


def inside(path, root):
    """Whether `path` is at or under `root`; case- and drive-aware on Windows."""
    try:
        child = os.path.normcase(str(path))
        parent = os.path.normcase(str(root))
        return os.path.commonpath([child, parent]) == parent
    except ValueError:  # different drives
        return False


def self_check():
    """The pairing and path guard a wrong build could silently depend on."""
    def sheet(tag):
        return [(i, i * 100, f"{tag}{i}") for i in range(1, MIN_LINES + 1)]

    base = ({"base": "none", "shift": "0"}, sheet("a"))
    other = ({"base": "none", "shift": "0"}, sheet("x"))
    shifted = ({"base": "ai", "shift": "50"}, sheet("a"))
    first, last = sheet_pair([other, base, shifted])
    assert first is base and last is shifted, "sheet_pair paired another sheet"
    single = sheet_pair([other])
    assert single is not None and single[0] is other, "sheet_pair dropped a lone provider sheet"
    root = Path("self-check-root").resolve()
    assert inside(root / "corpus", root), "inside missed a child"
    assert not inside(Path("self-check-elsewhere").resolve(), root), "inside overreached"


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--rs", type=Path, default=PHONE_OUT / "rs")
    ap.add_argument("--rs2", type=Path, default=PHONE_OUT / "rs2")
    ap.add_argument("--out", type=Path, default=PHONE_OUT / "replay")
    ap.add_argument("--baseline", type=Path, default=REPO / "bench" / "replay" / "baseline.json")
    args = ap.parse_args()
    self_check()

    repo = REPO.resolve()
    out = args.out.resolve()
    if inside(out, repo):
        ap.error("--out must be outside the repository: the corpus holds lyric text")
    if out.exists() and not out.is_dir():
        ap.error(f"--out is not a directory: {out}")
    baseline_path = args.baseline.resolve()
    if inside(baseline_path, out):
        ap.error("--baseline must be outside the corpus directory: the test reads every *.json there")

    rounds = []
    for root in (args.rs, args.rs2):
        if not root.is_dir():
            continue
        # A round is usually a subdirectory, but a flat rs/rs2 keeps its logs
        # and word files at the root; treat the root itself as a round then.
        if any(root.glob("*.log")) or any(root.glob("*.words.json")):
            rounds.append(root)
        rounds.extend(p for p in root.iterdir() if p.is_dir())
    rounds.sort(key=lambda p: (round_time(p), str(p)))

    best = {}  # vid -> (mtime, round_dir, first, last, words, median, verdicts)
    for round_dir in rounds:
        for vid, (first, last, words, median, verdicts) in round_candidates(round_dir).items():
            mtime = round_time(round_dir)
            if vid not in best or mtime >= best[vid][0]:
                best[vid] = (mtime, round_dir, first, last, words, median, verdicts)

    args.out.mkdir(parents=True, exist_ok=True)
    baseline = []
    stress = 0
    total_words = total_lines = 0
    for vid, (_, round_dir, first, last, words, median, verdicts) in sorted(best.items()):
        shift = int(last[0].get("shift", 0))
        expected = shift - median
        baseline_error = abs(median)
        is_stress = abs(expected) >= STRESS_MS or baseline_error >= STRESS_MEDIAN_MS
        stress += is_stress
        total_words += len(words)
        total_lines += len(first[1])
        try:
            round_name = str(round_dir.relative_to(PHONE_OUT))
        except ValueError:  # --rs/--rs2 pointed somewhere else
            round_name = str(round_dir)
        corpus = {
            "id": vid,
            "language": language_for([t for _, _, t in first[1]]),
            "sheet": [{"n": n, "t": t, "text": text} for n, t, text in first[1]],
            "heard": words,
            # Each scored line's sung time, from the independent score: [line, ms].
            "truth": line_truth(round_dir, vid),
            "expectedOffsetMs": expected,
            "baselineErrorMs": baseline_error,
            "stress": is_stress,
            "round": round_name,
        }
        corpus.update(verdicts)
        (args.out / (vid + ".json")).write_text(
            json.dumps(corpus, ensure_ascii=False), encoding="utf-8")
        entry = {
            "id": vid,
            "expectedOffsetMs": expected,
            "baselineErrorMs": baseline_error,
            # The baseline is numbers-only and the JVM test reads this as an int.
            "stress": int(is_stress),
            "toleranceMs": 1_500 if is_stress else 300,
        }
        entry.update(verdicts)
        baseline.append(entry)

    args.baseline.parent.mkdir(parents=True, exist_ok=True)
    args.baseline.write_text(
        json.dumps({"version": 1, "songs": baseline}, ensure_ascii=False, indent=1) + "\n",
        encoding="utf-8")

    print(f"rounds: {len(rounds)}")
    print(f"songs: {len(best)}")
    print(f"stress: {stress}")
    print(f"words: {total_words}")
    print(f"lines: {total_lines}")
    print(f"corpus: {args.out}")
    print(f"baseline: {args.baseline}")


if __name__ == "__main__":
    sys.exit(main())
