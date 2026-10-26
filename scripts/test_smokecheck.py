"""Unit tests for scripts/smokecheck.py. Run with `python3 -m unittest`."""

from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import smokecheck


def log_line(message: str) -> str:
    return f"10-26 12:00:00.000  1234  5678 I BitChord: {message}"


def summary(video_id: str, title: str, video: bool) -> str:
    return log_line(
        f"track-summary: {video_id} '{title}' video={'y' if video else 'n'} "
        "source=browse:MPREb_x resolve=direct firstAudio=120 played=210 "
        "lyrics=lrc.red sync=ai shift=-820 match=91 manualOffset=0 ended=natural"
    )


def statuses(log: str) -> dict[int, smokecheck.Check]:
    return {check.number: check for check in smokecheck.check_log(log)}


class MvPlaybackTest(unittest.TestCase):
    def test_video_row_without_version_swap_fails(self):
        result = statuses(summary("vid1", "Song", True))[1]
        self.assertEqual("FAIL", result.status)
        self.assertIn("vid1", result.details[0])

    def test_video_row_with_version_swap_passes(self):
        log = "\n".join(
            [
                log_line("song version: vid1 -> aud1 via search before-audio"),
                summary("vid1", "Song", True),
            ]
        )
        self.assertEqual("PASS", statuses(log)[1].status)

    def test_title_marker_is_enough_to_fail(self):
        log = summary("vid2", "Song (Official Video)", False)
        self.assertEqual("FAIL", statuses(log)[1].status)

    def test_resolved_song_version_summary_passes(self):
        log = "\n".join(
            [
                log_line("song version: vid3 -> aud3 via cache before-audio"),
                summary("aud3", "Song (Official Video)", False),
            ]
        )
        self.assertEqual("PASS", statuses(log)[1].status)


class SongVersionMatchTest(unittest.TestCase):
    def test_no_official_song_match_fails_with_name(self):
        log = log_line(
            "audio switch: no official song match for 'No Song' by 'Artist'"
        )
        result = statuses(log)[2]
        self.assertEqual("FAIL", result.status)
        self.assertTrue(any("No Song" in detail for detail in result.details))

    def test_artist_apostrophe_is_kept_in_the_name(self):
        log = log_line(
            "audio switch: no official song match for 'No Song' by 'Guns N' Roses'"
        )
        result = statuses(log)[2]
        self.assertTrue(any("Guns N' Roses" in detail for detail in result.details))

    def test_clean_log_passes(self):
        self.assertEqual("PASS", statuses("")[2].status)


class SyncTest(unittest.TestCase):
    def test_lyrics_shift_over_three_seconds_fails(self):
        log = log_line(
            "lyrics-sync: vid1 source=ai base=0 shift=3500ms match=90% "
            "bias=0ms manual=0ms stored=n recheck=none silenceSkipMs=0 video=y"
        )
        result = statuses(log)[3]
        self.assertEqual("FAIL", result.status)
        self.assertIn("3500", result.details[0])

    def test_lyrics_shift_within_limit_passes(self):
        log = log_line(
            "lyrics-sync: vid1 source=ai base=0 shift=2500ms match=90% "
            "bias=0ms manual=0ms stored=n recheck=none silenceSkipMs=0 video=y"
        )
        self.assertEqual("PASS", statuses(log)[3].status)

    def test_song_sync_refused_shift_fails(self):
        log = log_line(
            "song-sync: vid1 result=refused-shift match=80% shift=100 secs=60 words=10"
        )
        result = statuses(log)[3]
        self.assertEqual("FAIL", result.status)
        self.assertIn("refused-shift", result.details[0])

    def test_song_sync_large_applied_shift_fails(self):
        log = log_line(
            "song-sync: vid1 result=applied match=80% shift=-3100 secs=60 words=10"
        )
        self.assertEqual("FAIL", statuses(log)[3].status)


class QualityUpgradeTest(unittest.TestCase):
    def test_only_rejections_fail(self):
        log = log_line(
            "hq-verify: Song Qobuz match=80% offset=0 -> rejected reference=wav"
        )
        self.assertEqual("FAIL", statuses(log)[4].status)

    def test_one_acceptance_passes(self):
        log = "\n".join(
            [
                log_line(
                    "hq-verify: Song Qobuz match=80% offset=0 -> rejected reference=wav"
                ),
                log_line(
                    "hq-verify: Song Qobuz match=95% offset=0 -> accepted reference=wav"
                ),
            ]
        )
        result = statuses(log)[4]
        self.assertEqual("PASS", result.status)
        self.assertIn("accepted=1", result.details[0])

    def test_decode_failure_counts_as_a_candidate(self):
        log = log_line("decode: hq-verify failed at=decode boom")
        self.assertEqual("FAIL", statuses(log)[4].status)

    def test_no_reference_counts_as_a_candidate(self):
        log = log_line("hq-verify: 'Song' no reference reason=decode-failed")
        self.assertEqual("FAIL", statuses(log)[4].status)

    def test_no_candidates_passes(self):
        self.assertEqual("PASS", statuses("")[4].status)


class CrashTest(unittest.TestCase):
    def test_fatal_exception_fails(self):
        log = "10-26 12:00:00.000  1234  5678 E AndroidRuntime: FATAL EXCEPTION: main"
        self.assertEqual("FAIL", statuses(log)[5].status)

    def test_anr_fails(self):
        log = "10-26 12:00:00.000  1234  5678 E ActivityManager: ANR in com.dev.bitchord"
        self.assertEqual("FAIL", statuses(log)[5].status)

    def test_clean_log_passes(self):
        self.assertEqual("PASS", statuses("")[5].status)


class NextPrepTest(unittest.TestCase):
    def test_mv_id_left_in_version_fails(self):
        log = "\n".join(
            [
                log_line("song version: mv1 kept (none)"),
                log_line(
                    "next-prep: Song version=mv1 lyrics=none hq=none sync=skipped "
                    "mix=none in 120ms"
                ),
            ]
        )
        result = statuses(log)[6]
        self.assertEqual("FAIL", result.status)
        self.assertIn("version=mv1", result.details[0])

    def test_resolved_version_passes(self):
        log = "\n".join(
            [
                log_line("song version: mv1 -> aud1 via search before-audio"),
                log_line(
                    "next-prep: Song version=aud1 lyrics=lrc.red hq=qobuz "
                    "sync=applied mix=ready in 120ms"
                ),
            ]
        )
        self.assertEqual("PASS", statuses(log)[6].status)

    def test_mv_title_in_next_prep_fails(self):
        log = log_line(
            "next-prep: Song (Official Video) version=mv2 lyrics=none hq=none "
            "sync=skipped mix=none in 120ms"
        )
        self.assertEqual("FAIL", statuses(log)[6].status)

    def test_lyrics_sync_video_flag_identifies_the_mv_id(self):
        log = "\n".join(
            [
                log_line(
                    "lyrics-sync: mv4 source=ai base=0 shift=100ms match=90% "
                    "bias=0ms manual=0ms stored=n recheck=none silenceSkipMs=0 video=y"
                ),
                log_line(
                    "next-prep: Song version=mv4 lyrics=none hq=none "
                    "sync=skipped mix=none in 120ms"
                ),
            ]
        )
        self.assertEqual("FAIL", statuses(log)[6].status)


class OutputTest(unittest.TestCase):
    def test_table_and_markdown_show_every_check(self):
        checks = smokecheck.check_log("")
        table = smokecheck.render_table(checks, "x.log")
        report = smokecheck.render_markdown(checks, "x.log")
        self.assertIn("PASS", table)
        self.assertEqual(6, len(checks))
        for check in checks:
            self.assertIn(check.name, table)
            self.assertIn(check.name, report)
        self.assertIn("| # | 항목 | 판정 | 상세 |", report)

    def test_main_returns_one_on_failure_and_writes_report(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "logcat.txt"
            report = Path(directory) / "report.md"
            log.write_text(
                "10-26 12:00:00.000  1234  5678 E AndroidRuntime: "
                "FATAL EXCEPTION: main\n",
                encoding="utf-8",
            )
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                code = smokecheck.main([str(log), "--report", str(report)])
            self.assertEqual(1, code)
            self.assertTrue(report.exists())
            self.assertIn("FAIL", report.read_text(encoding="utf-8"))

    def test_main_returns_zero_when_all_checks_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "logcat.txt"
            log.write_text("", encoding="utf-8")
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                code = smokecheck.main([str(log)])
            self.assertEqual(0, code)


if __name__ == "__main__":
    unittest.main()
