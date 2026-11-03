"""Unit tests for scripts/smoke/realuse.py. Run with `python3 -m unittest`."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "smoke"))

import realuse


def log_line(message: str) -> str:
    return f"11-03 12:00:00.000  1234  5678 I BitChord: {message}"


def play(video_id: str, title: str, artist: str, idx: int) -> str:
    return log_line(
        f"play: -> {title} - {artist} ({video_id}) idx={idx}/8 reason=auto meta=none"
    )


def summary(video_id: str, title: str, first_audio: str, ended: str = "skipped",
            video: bool = True) -> str:
    return log_line(
        f"track-summary: {video_id} '{title}' video={'y' if video else 'n'} "
        "source=browse:MPREb_x resolve=proxy "
        f"firstAudio={first_audio} played=60 lyrics=none sync=none shift=- "
        f"match=- manualOffset=0 ended={ended}"
    )


class SummaryTest(unittest.TestCase):
    def run_summary(self, text: str, expected: int):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "logcat.txt"
            report = Path(tmp) / "report.md"
            log.write_text(text, encoding="utf-8")
            code = realuse.main(
                ["summary", str(log), "--report", str(report), "--expected", str(expected)]
            )
            return code, report.read_text(encoding="utf-8")

    def test_a_no_audio_track_fails_the_run_and_keeps_its_own_line(self):
        text = "\n".join(
            [
                play("mv1", "Song A", "Singer A", 0),
                log_line("holding mv1 until it is within 25s of playing"),
                log_line("song version: mv1 -> aud1 via search before-audio"),
                log_line("released mv1"),
                summary("mv1", "Song A", "900"),
                play("mv2", "Song B", "Singer B", 1),
                log_line("holding mv2 until it is within 25s of playing"),
                log_line("[mv2] video: no picture (no stream)"),
                summary("mv2", "Song B", "-", ended="skipped"),
            ]
        )
        code, report = self.run_summary(text, 2)
        self.assertEqual(1, code)
        self.assertIn(
            'REALUSE 1/2 id=mv1 title="Song A" artist="Singer A" '
            "firstAudio=900ms version=mv1->aud1 gate=ok ended=skipped "
            "video=song-version",
            report,
        )
        self.assertIn(
            'REALUSE 2/2 id=mv2 title="Song B" artist="Singer B" '
            "firstAudio=NO AUDIO version=none gate=HELD:mv2 ended=skipped "
            "video=no-picture",
            report,
        )
        self.assertIn("REALUSE played 1/2, no-audio 1, held 1, mv-kept 0", report)

    def test_all_tracks_with_audio_pass_even_when_a_cover_is_kept(self):
        text = "\n".join(
            [
                play("cov1", "Cover A", "오화요", 0),
                log_line("song version: cov1 kept (none)"),
                summary("cov1", "Cover A", "0"),
                play("cov2", "Cover B", "오화요", 1),
                summary("cov2", "Cover B", "1200"),
            ]
        )
        code, report = self.run_summary(text, 2)
        self.assertEqual(0, code)
        self.assertIn("version=kept(none)", report)
        self.assertIn("REALUSE played 2/2, no-audio 0, held 0, mv-kept 1", report)

    def test_a_track_that_never_started_is_reported_as_no_audio(self):
        text = play("only1", "Only", "One", 0) + "\n" + summary("only1", "Only", "50")
        code, report = self.run_summary(text, 2)
        self.assertEqual(1, code)
        self.assertIn("REALUSE 2/2 id=-", report)
        self.assertIn("REALUSE played 1/2, no-audio 1, held 0, mv-kept 0", report)


class PlayButtonTest(unittest.TestCase):
    def test_the_largest_재생_node_is_the_header_button(self):
        xml = (
            '<hierarchy><node text="재생" bounds="[10,10][30,30]"/>'
            '<node content-desc="재생" bounds="[40,40][240,140]"/>'
            '<node text="Play" bounds="[0,0][5,5]"/></hierarchy>'
        )
        self.assertEqual("140 90", realuse.play_button(xml))

    def test_no_play_node_is_none(self):
        self.assertIsNone(realuse.play_button('<hierarchy><node text="다음"/></hierarchy>'))


if __name__ == "__main__":
    unittest.main()
