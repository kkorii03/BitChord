"""Unit tests for scripts/phonecheck.py. Run with `python3 -m unittest`."""

from __future__ import annotations

import datetime
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import phonecheck
import smokecheck


def log_line(message: str) -> str:
    return f"10-26 22:00:00.000  1234  5678 I BitChord: {message}"


def summary(video_id: str, title: str, video: bool = True) -> str:
    return log_line(
        f"track-summary: {video_id} '{title}' video={'y' if video else 'n'} "
        "source=browse:MPREb_x resolve=direct firstAudio=120 played=210 "
        "lyrics=lrc.red sync=ai shift=-120 match=91 manualOffset=0 ended=natural"
    )


def dump_block(video_id: str, lines: list[tuple[int, int, str]]) -> list[str]:
    out = [log_line(f"sync-dump: begin {video_id} source=ai base=0 shift=100")]
    for number, start_ms, text in lines:
        out.append(log_line(f"sync-dump: line {video_id} {number} {start_ms} {text}"))
    out.append(log_line(f"sync-dump: end {video_id}"))
    return out


LINES = [
    (1, 10000, "hello darkness my old friend"),
    (2, 14000, "i've come to talk with you again"),
    (3, 18000, "because a vision softly creeping"),
    (4, 22000, "left its seeds while i was sleeping"),
    (5, 26000, "and the vision that was planted"),
]


def heard_words(offset: float = 0.1) -> list[tuple[str, float, float]]:
    words: list[tuple[str, float, float]] = []
    for _number, start_ms, text in LINES:
        when = start_ms / 1000.0 - offset
        for word in text.split():
            words.append((word, when, when + 0.3))
            when += 0.35
    return words


def songs(*rows: str) -> list[smokecheck.SongSpec]:
    return smokecheck.parse_songs("\n".join(rows))


def base_log() -> str:
    lines: list[str] = []
    for index in range(1, 6):
        lines.append(
            log_line(f"song version: mv{index} -> aud{index} via search before-audio")
        )
    lines += [
        log_line("hq-verify: Song Qobuz match=95% offset=0 -> accepted reference=wav"),
        log_line("hq-verify: Song2 Qobuz match=95% offset=0 -> accepted reference=wav"),
        log_line("hq-verify: Song3 Qobuz match=80% offset=0 -> rejected reference=wav"),
        summary("cover1", "Dance Monkey"),
    ]
    lines.extend(dump_block("cover1", LINES))
    return "\n".join(lines)


BASE_SONGS = "\n".join(
    [
        "mv1\tmv",
        "mv2\tmv",
        "mv3\tmv",
        "mv4\tmv",
        "mv5\tmv",
        "hq1\thq",
        "hq2\thq",
        "hq3\thq",
        "cover1\tcover",
    ]
)


class WaitTest(unittest.TestCase):
    def test_kind_wait_times(self):
        self.assertEqual(45, phonecheck.wait_seconds("mv"))
        self.assertEqual(90, phonecheck.wait_seconds("hq"))
        self.assertEqual(75, phonecheck.wait_seconds("cover"))
        self.assertEqual(75, phonecheck.wait_seconds("plain"))
        self.assertEqual(75, phonecheck.wait_seconds("unknown"))


class MvAndHqTest(unittest.TestCase):
    def test_all_mv_swaps_pass(self):
        self.assertEqual((5, 5), phonecheck.mv_progress(base_log(), songs(BASE_SONGS)))

    def test_one_missing_mv_swap_is_counted(self):
        text = base_log().replace(
            log_line("song version: mv5 -> aud5 via search before-audio"), ""
        )
        self.assertEqual((4, 5), phonecheck.mv_progress(text, songs(BASE_SONGS)))

    def test_hq_counts_every_verdict(self):
        text = "\n".join(
            [
                log_line(
                    "hq-verify: Song Qobuz match=95% offset=0 -> accepted reference=wav"
                ),
                log_line(
                    "hq-verify: Song2 Qobuz match=80% offset=0 -> rejected reference=wav"
                ),
                log_line("hq-verify: 'Song3' no reference reason=decode-failed"),
                log_line("decode: hq-verify failed at=decode boom"),
            ]
        )
        self.assertEqual(
            dict(accepted=1, rejected=1, decode_failed=1, no_reference=1),
            phonecheck.hq_counts(text),
        )


class SyncTest(unittest.TestCase):
    def test_fake_log_and_wav_are_scored(self):
        with tempfile.TemporaryDirectory() as directory:
            wav_dir = Path(directory)
            (wav_dir / "cover1.wav").write_bytes(b"RIFF" + b"\0" * 2000)
            rows = phonecheck.score_sync(
                base_log(),
                songs("cover1\tcover"),
                wav_dir,
                transcribe=lambda _path, _language, _model: heard_words(),
            )
            self.assertEqual(1, len(rows))
            row = rows[0]
            self.assertEqual("PASS", row["verdict"])
            self.assertEqual(5, row["matched"])
            self.assertAlmostEqual(0.1, row["median"], places=2)
            self.assertEqual(1.0, row["in03"])
            self.assertEqual(5, len(row["errors"]))

            summary = phonecheck.sync_summary(rows)
            self.assertIsNotNone(summary)
            self.assertEqual(5, summary["lines"])
            self.assertEqual(1, summary["songs"])
            self.assertAlmostEqual(0.1, summary["median"], places=2)
            self.assertEqual(1.0, summary["in03"])

    def test_missing_wav_is_reported_not_scored(self):
        with tempfile.TemporaryDirectory() as directory:
            rows = phonecheck.score_sync(
                base_log(),
                songs("cover1\tcover"),
                Path(directory),
                transcribe=lambda _path, _language, _model: heard_words(),
            )
        self.assertEqual(1, len(rows))
        self.assertIn("no wav", rows[0]["error"])
        self.assertIsNone(phonecheck.sync_summary(rows))

    def test_search_row_takes_the_id_it_played_not_an_unclaimed_dump(self):
        # An MV's replacement id also appears as an unclaimed sync-dump; only the
        # id logged right after the search played belongs to the search row.
        text = "\n".join(
            dump_block("mvreplace01", [(1, 10000, "other")])
            + dump_block("searchvid", [(1, 10000, "hello world")])
        )
        phonecheck.SEARCH_IDS.clear()
        phonecheck.SEARCH_IDS["search:Espresso Sabrina Carpenter"] = "searchvid"
        try:
            rows = phonecheck.sync_candidates(
                songs("search:Espresso Sabrina Carpenter\tplain"), text
            )
        finally:
            phonecheck.SEARCH_IDS.clear()
        self.assertEqual(
            [("search:Espresso Sabrina Carpenter", "plain", "searchvid")], rows
        )

    def test_duplicate_explicit_rows_are_scored_once(self):
        text = "\n".join(dump_block("cover1", LINES))
        rows = phonecheck.sync_candidates(songs("cover1\tcover", "cover1\tplain"), text)
        self.assertEqual([("cover1", "cover", "cover1")], rows)

    def test_search_row_without_a_dump_is_still_listed(self):
        rows = phonecheck.sync_candidates(
            songs("search:Espresso Sabrina Carpenter\tplain"), ""
        )
        self.assertEqual(
            [("search:Espresso Sabrina Carpenter", "plain", "")], rows
        )
        scored = phonecheck.score_sync(
            "",
            songs("search:Espresso Sabrina Carpenter\tplain"),
            Path("/nonexistent"),
            transcribe=lambda _path, _language, _model: heard_words(),
        )
        self.assertEqual(1, len(scored))
        self.assertIn("id 없음", scored[0]["error"])


class DeepgramTest(unittest.TestCase):
    def test_wav_is_streamed_with_a_content_length(self):
        captured = {}

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self, *_args):
                return (
                    b'{"results":{"channels":[{"alternatives":[{"words":'
                    b'[{"word":"hello","start":1.0,"end":1.2}]}]}]}}'
                )

        def fake_urlopen(request, timeout=0):
            captured["request"] = request
            return FakeResponse()

        original = phonecheck.urllib.request.urlopen
        phonecheck.urllib.request.urlopen = fake_urlopen
        try:
            with tempfile.TemporaryDirectory() as directory:
                wav = Path(directory) / "x.wav"
                wav.write_bytes(b"RIFF" + b"\0" * 5000)
                words = phonecheck.deepgram_wav(wav, "en", "model", "key")
        finally:
            phonecheck.urllib.request.urlopen = original

        self.assertEqual([("hello", 1.0, 1.2)], words)
        request = captured["request"]
        self.assertFalse(isinstance(request.data, bytes))
        self.assertEqual("5004", request.get_header("Content-length"))

    def test_missing_key_skips_the_request(self):
        with tempfile.TemporaryDirectory() as directory:
            wav = Path(directory) / "x.wav"
            wav.write_bytes(b"RIFF" + b"\0" * 5000)
            self.assertIsNone(phonecheck.deepgram_wav(wav, "en", "model", ""))


class ReportTest(unittest.TestCase):
    def _rows(self) -> list[dict]:
        return phonecheck.score_sync(
            base_log(),
            songs("cover1\tcover"),
            Path("/nonexistent"),
            transcribe=lambda _path, _language, _model: heard_words(),
        )

    def test_report_starts_with_mv_hq_and_sync_summary(self):
        text = base_log()
        all_songs = songs(BASE_SONGS)
        checks = smokecheck.check_log(text, songs=all_songs)
        rows = phonecheck.score_sync(
            text,
            all_songs,
            Path("/nonexistent"),
            transcribe=lambda _path, _language, _model: heard_words(),
        )
        # No WAV on this path: the top summary still has to be honest.
        self.assertIsNone(phonecheck.sync_summary(rows))
        report = phonecheck.build_report(
            text,
            all_songs,
            Path("phone-out/phonecheck/logcat.txt"),
            rows,
            checks,
            datetime.datetime(2026, 10, 26, 22, 0),
        )
        self.assertIn("MV 5/5 통과", report)
        self.assertIn("HQ accepted 2", report)
        self.assertIn("싱크 채점 곡 없음", report)
        self.assertIn("## 싱크 곡별", report)
        self.assertIn("cover1", report)
        self.assertIn("## 스모크 판정", report)

    def test_report_has_the_sync_table_and_ratio(self):
        text = base_log()
        all_songs = songs(BASE_SONGS)
        checks = smokecheck.check_log(text, songs=all_songs)
        with tempfile.TemporaryDirectory() as directory:
            wav_dir = Path(directory)
            (wav_dir / "cover1.wav").write_bytes(b"RIFF" + b"\0" * 2000)
            rows = phonecheck.score_sync(
                text,
                all_songs,
                wav_dir,
                transcribe=lambda _path, _language, _model: heard_words(),
            )
        report = phonecheck.build_report(
            text,
            all_songs,
            Path("phone-out/phonecheck/logcat.txt"),
            rows,
            checks,
            datetime.datetime(2026, 10, 26, 22, 0),
        )
        self.assertIn("싱크 중앙값 +0.10초", report)
        self.assertIn("0.3초 이내 100%", report)
        self.assertIn("| 곡 | 종류 | 판정 |", report)
        self.assertIn("| cover1 | cover | PASS |", report)

    def test_report_lists_a_song_that_could_not_be_scored(self):
        text = base_log()
        all_songs = songs(BASE_SONGS)
        checks = smokecheck.check_log(text, songs=all_songs)
        rows = self._rows()
        report = phonecheck.build_report(
            text, all_songs, Path("logcat.txt"), rows, checks
        )
        self.assertIn("no wav", report)


if __name__ == "__main__":
    unittest.main()
