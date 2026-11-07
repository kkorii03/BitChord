"""Unit tests for scripts/synctest.py. Run with `python scripts/test_synctest.py`.

Every fixture is a number token: no lyric text, no log line from a real song.
"""

from __future__ import annotations

import json
import sys
import tempfile
import types
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import synctest


def _no_network(*args, **kwargs):
    raise AssertionError("a test must not reach YouTube or Deepgram")


# A missing cache file would otherwise run yt-dlp or read the key and post audio.
synctest.deepgram_words = _no_network
synctest.subprocess = types.SimpleNamespace(run=_no_network)

A = "AAAAAAAAAAA"  # the row the test starts
B = "BBBBBBBBBBB"  # the song version the app replaces it with
DUMP = "sync" + "-" + "dump"  # the app's log marker, never written out whole

# Ten lines of two number tokens each; the first four digits are the line's key.
TEXT = ["11 22", "33 44", "55 11", "22 33", "44 55",
        "11 33", "22 55", "33 11", "44 22", "55 33"]


def brief(row):
    """The row without its parsed blocks, so a failed assert prints no log."""
    return {k: v for k, v in row.items() if k != "blocks"}


def sheet(start=10.0, step=10.0, offset=0.0):
    """Ten timed lines of number tokens, `offset` later than shown."""
    return [(n + 1, int((start + n * step) * 1000), t) for n, t in enumerate(TEXT)]


def heard(lines, late=0.05):
    """Tokens sung `late` after each line's time, one every 0.5 s."""
    words = []
    for _, ms, text in lines:
        t = ms / 1000.0 + late
        for k, w in enumerate(text.split()):
            words.append((w, t + 0.5 * k, t + 0.5 * k + 0.4))
    return words


def block(vid, lines, extra=""):
    out = [f"x {DUMP}: begin {vid} playing={vid} source=test base=none shift=0 match=0 lines={len(lines)}{extra}"]
    out += [f"x {DUMP}: line {vid} {n} {ms} {text}" for n, ms, text in lines]
    out.append(f"x {DUMP}: end {vid}")
    return out


class SwappedSheet(unittest.TestCase):
    """The app replaced a video row with its song version: the played id's sheet
    is the one a listener saw, so that is the one scored."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.old_out = synctest.OUT
        synctest.OUT = self.dir

    def tearDown(self):
        synctest.OUT = self.old_out

    def _caches(self, vid, lines):
        words = heard(lines)
        for model in synctest.MODELS:
            (self.dir / f"{vid}.opus.{model}.words.json").write_text(json.dumps(words), encoding="utf-8")
        (self.dir / f"{vid}.webm").write_bytes(b"")

    def test_played_id_sheet_is_scored(self):
        played = sheet()
        self._caches(B, played)
        # The row's own sheet stayed 0.9 s late: scoring it would be a FAIL.
        row_sheet = [(n, ms + 900, text) for n, ms, text in sheet()]
        log = [f"x BitChord: song version: {A} -> {B} via search"]
        log += block(A, row_sheet) + block(B, played)
        (self.dir / f"{A}.log").write_text("\n".join(log), encoding="utf-8")

        row = synctest.run_song(A, 75, True)

        self.assertEqual(row.get("swapped"), B, brief(row))
        self.assertEqual(row.get("playing"), B, brief(row))
        self.assertEqual(row.get("verdict"), "PASS", brief(row))
        self.assertGreaterEqual(row.get("matched", 0), 8, brief(row))

    def test_played_sheet_never_dumped_keeps_the_row_sheet(self):
        # The row was replaced after its last dump and the new sheet never dumped:
        # the row's sheet is the only evidence of what was on screen, so it is
        # still scored (and the row is not reported as swapped).
        self._caches(B, sheet())
        self._caches(A, sheet())
        log = [f"x BitChord: song version: {A} -> {B} via search"]
        log += block(A, sheet())
        (self.dir / f"{A}.log").write_text("\n".join(log), encoding="utf-8")

        row = synctest.run_song(A, 75, True)

        self.assertEqual(row.get("swapped"), "", brief(row))
        self.assertEqual(row.get("playing"), A, brief(row))


class SwappedAudio(unittest.TestCase):
    """The played sheet is scored on the played recording. The row's WAV may be the
    same song with a long intro, which puts a right sheet seconds off on paper."""

    INTRO = 11.5  # the row's video starts this much before the played copy

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.old_out = synctest.OUT
        synctest.OUT = self.dir
        # Four-digit tokens: offset_between only counts words of 3+ letters.
        self.played = [(n, ms, " ".join(w * 2 for w in text.split())) for n, ms, text in sheet()]
        # What the played copy sings, and the same singing on the row's clock.
        self.own = heard(self.played)
        self.row_words = [[w, s + self.INTRO, e + self.INTRO] for w, s, e in self.own]
        for model in synctest.MODELS:
            (self.dir / f"{B}.opus.{model}.words.json").write_text(json.dumps(self.own), encoding="utf-8")
            (self.dir / f"{A}.app.{model}.words.json").write_text(json.dumps(self.row_words), encoding="utf-8")
        (self.dir / f"{B}.webm").write_bytes(b"")
        (self.dir / f"{A}.wav").write_bytes(b"0" * 2000)
        log = [f"x BitChord: song version: {A} -> {B} via search"] + block(B, self.played)
        (self.dir / f"{A}.log").write_text("\n".join(log), encoding="utf-8")

    def tearDown(self):
        synctest.OUT = self.old_out

    def test_played_wav_lines_up_the_played_copy(self):
        (self.dir / f"{B}.wav").write_bytes(b"0" * 2000)
        (self.dir / f"{B}.app.{synctest.MODELS[0]}.words.json").write_text(json.dumps(self.own), encoding="utf-8")
        row = synctest.run_song(A, 75, True)
        self.assertEqual(row.get("verdict"), "PASS", brief(row))
        self.assertLess(abs(row["median"]), 0.1, brief(row))

    def test_row_wav_never_lines_up_the_played_copy(self):
        # The played WAV was not saved: the played copy is scored on its own clock
        # and labelled unchecked; the row's recording says nothing about this sheet.
        row = synctest.run_song(A, 75, True)
        self.assertEqual(row.get("verdict"), "PASS", brief(row))
        self.assertIn("not checked", row.get("audio", ""), brief(row))

    def test_a_late_played_sheet_still_fails(self):
        # Same recording, sheet truly 1.5 s early: the fix must not hide it.
        (self.dir / f"{B}.wav").write_bytes(b"0" * 2000)
        late = [[w, s + 1.5, e + 1.5] for w, s, e in self.own]
        for model in synctest.MODELS:
            (self.dir / f"{B}.opus.{model}.words.json").write_text(json.dumps(late), encoding="utf-8")
        (self.dir / f"{B}.app.{synctest.MODELS[0]}.words.json").write_text(json.dumps(late), encoding="utf-8")
        row = synctest.run_song(A, 75, True)
        self.assertEqual(row.get("verdict"), "FAIL", brief(row))
        self.assertLess(row["median"], -1.0, brief(row))


class WorstLines(unittest.TestCase):
    """>5 s lines are re-checked: a repeat picked at the wrong place shows as an
    alternative that keeps the order of the lines scored around it."""

    def test_alternative_inside_neighbours_is_reported(self):
        lines = sheet()
        both = [(1, 10.0, 10.05, 1.0), (2, 30.0, 20.0, 0.9), (3, 40.0, 39.95, 1.0)]
        words = sorted(heard(sheet()) + [("33", 34.0, 34.4), ("44", 34.5, 34.9)], key=lambda w: w[1])
        self.assertEqual(synctest.worst_lines(lines, both, words), "n=2 +10.00s alts=1")

    def test_only_place_sung_is_a_real_error(self):
        lines = sheet()
        both = [(1, 10.0, 10.05, 1.0), (2, 30.0, 20.0, 0.9), (3, 40.0, 39.95, 1.0)]
        self.assertEqual(synctest.worst_lines(lines, both, heard(sheet())), "n=2 +10.00s alts=0")

    def test_nothing_over_five_seconds(self):
        lines = sheet()
        self.assertEqual(synctest.worst_lines(lines, [(1, 10.0, 10.05, 1.0)], heard(sheet())), "")


class Strictness(unittest.TestCase):
    """The rules the change must not loosen: an already-good sheet still passes,
    an already-bad one still fails, and one gross line is not a pass."""

    def test_good_sheet_passes(self):
        good = [(n, 10.0 * n + 0.05, 10.0 * n, 1.0) for n in range(10)]
        self.assertEqual(synctest.score(good)["verdict"], "PASS")

    def test_bad_sheet_fails(self):
        bad = [(n, 10.0 * n + 0.4, 10.0 * n, 1.0) for n in range(10)]
        self.assertEqual(synctest.score(bad)["verdict"], "FAIL")

    def test_one_gross_line_is_not_a_pass(self):
        good = [(n, 10.0 * n + 0.05, 10.0 * n, 1.0) for n in range(10)]
        gross = good[:9] + [(9, 98.0, 90.0, 1.0)]
        s = synctest.score(gross)
        self.assertNotEqual(s["verdict"], "PASS")
        self.assertIn("최대", s["why"])


if __name__ == "__main__":
    unittest.main()
