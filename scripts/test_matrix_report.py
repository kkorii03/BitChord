"""Unit tests for scripts/smoke/matrix_report.py. Run with `python3 -m unittest`."""

from __future__ import annotations

import sys
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "smoke"))

import matrix_report


def line(message: str, ts: str = "10-26 12:00:00.000") -> str:
    return f"{ts}  1234  5678 D BitChord: {message}"


def at(seconds: float) -> datetime:
    return datetime(2026, 10, 26, 12, 0, 0) + timedelta(seconds=seconds)


class RecoveryTest(unittest.TestCase):
    def test_seconds_to_the_next_clock_line(self):
        gaps = [(at(2), 0, 0), (at(5), 0, 0)]
        events = [("lyrics", at(0)), ("hq", at(4))]
        self.assertEqual(matrix_report.recovery(events, gaps), "lyrics:2.0 hq:1.0")

    def test_an_event_with_no_clock_after_it_is_left_out(self):
        self.assertEqual(matrix_report.recovery([("hq", at(9))], [(at(2), 0, 0)]), "")


class PlaysTest(unittest.TestCase):
    def test_lyrics_open_and_hq_swap_are_marks_with_times(self):
        log = "\n".join(
            [
                line("play: -> Song - Artist (XbGs_qK2PQA) idx=0/10 reason=auto", "10-26 12:00:00.000"),
                line("matrix: set=t lyrics open try=1", "10-26 12:00:05.000"),
                line("lyric-clock: drawn=100 reading=90 age=10ms gap=0ms", "10-26 12:00:07.000"),
                line("hq-swap: XbGs_qK2PQA start at=1000 offset=0", "10-26 12:00:10.000"),
                line("lyric-clock: drawn=200 reading=190 age=10ms gap=0ms", "10-26 12:00:11.500"),
            ]
        )
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "smoke-logcat.txt"
            path.write_text(log, encoding="utf-8")
            plays, _ = matrix_report.plays(path)
        self.assertEqual(len(plays), 1)
        p = plays[0]
        self.assertEqual(p["swaps"], 1)
        self.assertEqual(len(p["hq"]), 1)
        self.assertEqual(len(p["gaps"]), 2)
        events = [("lyrics", t) for t, mark in p["marks"] if mark.startswith("lyrics open")]
        events += [("hq", t) for t in p["hq"]]
        self.assertEqual(matrix_report.recovery(events, p["gaps"]), "lyrics:2.0 hq:1.5")


if __name__ == "__main__":
    unittest.main()
