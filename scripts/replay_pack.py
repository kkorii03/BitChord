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
provider's own times), the later block for that same sheet when the app moved
it (lone is fine when it kept the provider's times), and a word file with
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


def word_file(round_dir, vid, audio, sheet_last_ms):
    """The large-model word file for `vid` from the recording the score used.

    The app's own transcript (`.app.`) is the recording the app played, which
    is the one the expected offset was measured on.  It is only the whole song
    when it reaches the sheet's last line; a 90 s window is not.  The YouTube
    copy (`.opus.`) is the same recording only when the score named the video
    itself; otherwise it is another upload and the fit would be compared with
    another recording's answer.  Only whisper-large is used: a medium
    transcript is a different measurement and is not substituted.
    """
    app = round_dir / (vid + ".app.whisper-large.words.json")
    if app.exists():
        try:
            words = json.loads(app.read_text(encoding="utf-8"))
            # shortcut: a 5 s tail is treated as the whole song; a longer
            # partial transcript falls back to the YouTube copy, upgrade if a
            # real song's last line is sung more than 5 s after its last word.
            if words and round(float(words[-1][2]) * 1000) >= sheet_last_ms - 5_000:
                return app
        except (OSError, ValueError, IndexError):
            pass
    if audio == vid:
        opus = round_dir / (vid + ".opus.whisper-large.words.json")
        if opus.exists():
            return opus
    return None


def fa_in(round_dir, vid):
    """The scorer's input for `vid`: the recording it scored and the lines it read."""
    path = round_dir / (vid + ".fa-in.json")
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def sheet_matches(fa, sheet):
    """Whether the scored lines are the sheet replayed, by line number and text.

    The scorer reads the app's final display; a sheet that changed between the
    base=none block and that display is not the sheet the expected offset
    belongs to.
    """
    # shortcut: only the lines the scorer sampled are compared; a sheet that
    # changed only on an unsampled line is not caught, upgrade if a changed
    # sheet slips through.
    scored = {line.get("n"): line.get("text") for line in fa.get("lines", []) if isinstance(line, dict)}
    return all(scored.get(n, text) == text for n, _, text in sheet)


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
    report = round_dir / "report.csv"
    if report.exists():
        with report.open(newline="", encoding="utf-8-sig") as f:
            for row in csv.DictReader(f):
                if (row.get("video") or "").strip() == vid:
                    median = number(row.get("median"))
                    # A score the scorer itself refused is not an answer: its
                    # few lines are not the song's offset.
                    if (row.get("error") or "").strip() or median is None:
                        return None
                    return round(median * 1000)
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
    """[line, sung ms, shown ms] for every line the independent score placed.

    The third number is what the app displayed, from the same `lines.csv` row;
    it is left out when an old score has no `shown_s` column.
    """
    path = round_dir / (vid + ".lines.csv")
    if not path.exists():
        return []
    out = []
    with path.open(newline="", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            line, sung = number(row.get("line")), number(row.get("sung_s"))
            if line is None or sung is None:
                continue
            entry = [int(line), round(sung * 1000)]
            shown = number(row.get("shown_s"))
            if shown is not None:
                entry.append(round(shown * 1000))
            out.append(entry)
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

    A lone base=none block is valid: the app may have kept the provider times.
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
            fa = fa_in(round_dir, vid)
            if fa is None or fa.get("audio") != vid:
                continue
            if not sheet_matches(fa, first[1]):
                continue
            words_path = word_file(round_dir, vid, fa.get("audio"), max(t for _, t, _ in first[1]))
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


def keep_heard2(old, vid, round_name):
    """The second hearing already recorded for this song's round, or None.

    `bench/replay_second_ear.py` adds `heard2` after the corpus is written. A
    re-pack must not erase that paid hearing, and a hearing of another round is
    another recording.
    """
    if not isinstance(old, dict) or old.get("id") != vid or old.get("round") != round_name:
        return None
    heard2 = old.get("heard2")
    return heard2 if isinstance(heard2, list) and heard2 else None


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
    fa = {"audio": "v", "lines": [{"n": 1, "text": "a"}, {"n": 2, "text": "b"}]}
    assert sheet_matches(fa, [(1, 0, "a"), (2, 0, "b")]), "sheet_matches dropped the same sheet"
    assert not sheet_matches(fa, [(1, 0, "a"), (2, 0, "c")]), "sheet_matches kept a changed sheet"
    root = Path("self-check-root").resolve()
    assert inside(root / "corpus", root), "inside missed a child"
    assert not inside(Path("self-check-elsewhere").resolve(), root), "inside overreached"
    # The second hearing is a paid recording of the same round: a re-pack must
    # keep it, and must not carry it onto another round or another song.
    old = {"id": "v", "round": "rs\\r1", "heard2": [{"t": "w", "s": 1, "e": 2}]}
    assert keep_heard2(old, "v", "rs\\r1") == old["heard2"], "keep_heard2 dropped a second hearing"
    assert keep_heard2(old, "v", "rs\\r2") is None, "keep_heard2 kept another round's hearing"
    assert keep_heard2(old, "w", "rs\\r1") is None, "keep_heard2 kept another song's hearing"


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
    second = 0
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
        path = args.out / (vid + ".json")
        try:
            old = json.loads(path.read_text(encoding="utf-8")) if path.exists() else None
        except (OSError, ValueError):
            old = None
        heard2 = keep_heard2(old, vid, round_name)
        corpus = {
            "id": vid,
            "language": language_for([t for _, _, t in first[1]]),
            "sheet": [{"n": n, "t": t, "text": text} for n, t, text in first[1]],
            "heard": words,
            # Each scored line's sung and displayed time: [line, sung ms, shown ms].
            "truth": line_truth(round_dir, vid),
            "expectedOffsetMs": expected,
            "baselineErrorMs": baseline_error,
            "stress": is_stress,
            "round": round_name,
        }
        if heard2:
            corpus["heard2"] = heard2
            second += 1
        corpus.update(verdicts)
        path.write_text(json.dumps(corpus, ensure_ascii=False), encoding="utf-8")
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
    print(f"second hearing: {second}")
    print(f"words: {total_words}")
    print(f"lines: {total_lines}")
    print(f"corpus: {args.out}")
    print(f"baseline: {args.baseline}")


if __name__ == "__main__":
    sys.exit(main())
