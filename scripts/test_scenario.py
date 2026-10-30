"""Unit tests for scripts/smoke/scenario.py. Run with `python3 -m unittest`."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "smoke"))

import scenario


def line(
    message: str,
    ts: str = "10-26 12:00:00.000",
    pid: int = 1234,
    tag: str = "BitChord",
) -> scenario.LogLine:
    return scenario.LogLine(scenario.parse_ts(ts), message, pid, tag)


def play(
    video_id: str,
    reason: str = "AUTO",
    ts: str = "10-26 12:00:00.000",
    label: str = "Song - Artist",
    pid: int = 1234,
) -> scenario.LogLine:
    return line(
        f"play: -> {label} ({video_id}) idx=0/10 reason={reason} meta=ok",
        ts,
        pid,
    )


def autoplay(titles: list[str], ts: str = "10-26 12:00:00.000") -> scenario.LogLine:
    return line(
        "autoplay: +radio 3 +similar 1 +fav 2 (" + " | ".join(titles) + ")",
        ts,
    )


def radio(
    seed: str,
    mode: str,
    picked: int = 6,
    ts: str = "10-26 12:00:00.000",
) -> scenario.LogLine:
    return line(
        f"video-radio: seed={seed} mode={mode} picked={picked} "
        "from=radio dropped=1 fallback=none",
        ts,
    )


def titles(count: int, cover: bool = False) -> list[str]:
    rows = [f"Song {i} (Live) - Artist {i}" for i in range(count)]
    if cover:
        rows[0] = "Dance Monkey (Cover) - Someone"
    return rows


class AutoplayTitlesTest(unittest.TestCase):
    def test_parentheses_in_title_survive(self):
        found = scenario.autoplay_titles(
            [autoplay(["Song (Live) - A", "Other - B"])]
        )
        self.assertEqual(["Song (Live) - A", "Other - B"], found)

    def test_dropped_line_is_not_attached(self):
        found = scenario.autoplay_titles(
            [
                line("autoplay: dropped 3 cover/karaoke rows"),
                line("autoplay: dropped abc (cover/karaoke)"),
            ]
        )
        self.assertEqual([], found)

    def test_missing_fav_prefix_is_ignored(self):
        found = scenario.autoplay_titles(
            [line("autoplay: +radio 2 (A - X | B - Y)")]
        )
        self.assertEqual([], found)


class CoverVideoTest(unittest.TestCase):
    def test_a1_uses_video_radio_not_track_summary(self):
        result = scenario.judge_a1(
            [radio("k4b7dnA8z0I", "video"), autoplay(titles(5))]
        )
        self.assertEqual("PASS", result.status)
        self.assertIn("video-radio", result.actual)

    def test_a1_without_video_radio_is_skip(self):
        result = scenario.judge_a1([autoplay(titles(5))])
        self.assertEqual("SKIP", result.status)
        self.assertIn("no video-radio", result.actual)

    def test_a1_with_wrong_seed_is_skip(self):
        result = scenario.judge_a1(
            [radio("other", "video"), autoplay(titles(5))]
        )
        self.assertEqual("SKIP", result.status)

    def test_a1_known_mode_without_titles_fails(self):
        result = scenario.judge_a1([radio("k4b7dnA8z0I", "video")])
        self.assertEqual("FAIL", result.status)

    def test_a1_fewer_than_five_fails(self):
        result = scenario.judge_a1(
            [radio("k4b7dnA8z0I", "video"), autoplay(titles(4))]
        )
        self.assertEqual("FAIL", result.status)

    def test_a2_uses_first_video_radio(self):
        result = scenario.judge_a2(
            [radio("found-by-search", "video"), autoplay(titles(5))]
        )
        self.assertEqual("PASS", result.status)

    def test_a3_music_without_cover_passes(self):
        result = scenario.judge_a3(
            [radio("tones", "music"), autoplay(titles(3))]
        )
        self.assertEqual("PASS", result.status)

    def test_a3_music_with_cover_fails(self):
        result = scenario.judge_a3(
            [radio("tones", "music"), autoplay(titles(3, cover=True))]
        )
        self.assertEqual("FAIL", result.status)

    def test_a3_video_mode_fails(self):
        result = scenario.judge_a3(
            [radio("tones", "video"), autoplay(titles(3))]
        )
        self.assertEqual("FAIL", result.status)


class RepeatTest(unittest.TestCase):
    def test_three_distinct_ids_pass(self):
        result = scenario.judge_a4(
            [play("a", "AUTO"), play("b", "AUTO"), play("c", "AUTO")]
        )
        self.assertEqual("PASS", result.status)

    def test_duplicate_id_fails(self):
        result = scenario.judge_a4(
            [play("a", "AUTO"), play("a", "AUTO"), play("b", "AUTO")]
        )
        self.assertEqual("FAIL", result.status)

    def test_repeat_reason_fails(self):
        result = scenario.judge_a4(
            [
                play("a", "AUTO"),
                play("b", "AUTO"),
                play("c", "AUTO"),
                line("play: -> x (d) idx=0/1 reason=REPEAT meta=ok"),
            ]
        )
        self.assertEqual("FAIL", result.status)


class CrossfadeTest(unittest.TestCase):
    def base(self, **kwargs) -> list[scenario.LogLine]:
        auto = kwargs.get("auto", True)
        after = kwargs.get("after", True)
        delta = kwargs.get("delta", 8)
        pushed = kwargs.get("pushed", True)
        prior = kwargs.get("prior", True)
        rows: list[scenario.LogLine] = []
        if prior:
            rows.extend(
                [
                    line("debug-scenario: play-local n=2 first=old", "10-26 12:00:00.000"),
                    line(
                        "crossfade: start out=old in=old2 fade=8000ms cue=0ms smart=true",
                        "10-26 12:00:01.000",
                    ),
                    play("old2", "AUTO", "10-26 12:00:09.000"),
                ]
            )
        rows.append(
            line(
                "smoke-scenario: A9 files=bitchord-smoke-run-440|bitchord-smoke-run-660",
                "10-26 12:00:20.000",
            )
        )
        rows.append(
            line(
                "debug-scenario: play-local n=2 first=bitchord-smoke-run-440 "
                "id=content://media/1",
                "10-26 12:00:21.000",
            )
        )
        rows.append(
            play(
                "content://media/1",
                "PLAYLIST_CHANGED",
                "10-26 12:00:21.000",
                "bitchord-smoke-run-440 - Smoke",
            )
        )
        if after:
            rows.append(
                line(
                    "crossfade: start out=content://media/1 in=content://media/2 "
                    "fade=8000ms cue=0ms smart=true",
                    "10-26 12:00:30.000",
                )
            )
        if auto:
            second = 30 + delta
            rows.append(
                play(
                    "content://media/2",
                    "AUTO",
                    f"10-26 12:00:{second:02d}.000",
                    (
                        "bitchord-smoke-run-660 - Smoke"
                        if pushed
                        else "unrelated - Someone"
                    ),
                )
            )
        return rows

    def test_auto_play_at_fade_passes(self):
        result = scenario.judge_a9(self.base())
        self.assertEqual("PASS", result.status)

    def test_prior_scenario_crossfade_is_not_reused(self):
        result = scenario.judge_a9(self.base(after=False, auto=False))
        self.assertEqual("SKIP", result.status)

    def test_no_auto_play_fails_even_with_finish(self):
        rows = self.base(auto=False)
        rows.append(
            line(
                "finish levels inVol=1.0 outVol=0.0 handedOff=false",
                "10-26 12:00:38.000",
            )
        )
        result = scenario.judge_a9(rows)
        self.assertEqual("FAIL", result.status)

    def test_auto_play_outside_tolerance_fails(self):
        result = scenario.judge_a9(self.base(delta=11))
        self.assertEqual("FAIL", result.status)

    def test_unpushed_track_fails(self):
        result = scenario.judge_a9(self.base(pushed=False))
        self.assertEqual("FAIL", result.status)


class BlockedTrackTest(unittest.TestCase):
    def test_measures_from_blocked_play_not_marker(self):
        rows = [
            line("smoke-scenario: C5 start", "10-26 12:00:00.000"),
            play("other", "AUTO", "10-26 12:00:05.000"),
            play("7QihqRWMrz8", "OTHER(0)", "10-26 12:00:10.000"),
            play("next", "AUTO", "10-26 12:00:13.000"),
        ]
        result = scenario.judge_c5(rows)
        self.assertEqual("PASS", result.status)
        self.assertIn("3.0s", result.actual)

    def test_delayed_prior_play_is_not_next(self):
        rows = [
            line("smoke-scenario: C5 start", "10-26 12:00:00.000"),
            play("other", "AUTO", "10-26 12:00:05.000"),
            play("7QihqRWMrz8", "OTHER(0)", "10-26 12:00:10.000"),
            play("next", "AUTO", "10-26 12:00:16.000"),
        ]
        result = scenario.judge_c5(rows)
        self.assertEqual("FAIL", result.status)

    def test_version_swap_is_not_recovery(self):
        rows = [
            play("7QihqRWMrz8", "OTHER(0)", "10-26 12:00:10.000"),
            line(
                "song version: 7QihqRWMrz8 -> aud123 via search before-audio",
                "10-26 12:00:11.000",
            ),
            play("aud123", "AUTO", "10-26 12:00:11.000"),
            play("next", "AUTO", "10-26 12:00:20.000"),
        ]
        result = scenario.judge_c5(rows)
        self.assertEqual("FAIL", result.status)

    def test_extraction_attempt_fails(self):
        rows = [
            play("7QihqRWMrz8", "OTHER(0)", "10-26 12:00:10.000"),
            play("next", "AUTO", "10-26 12:00:12.000"),
            line("extraction attempt 2 of 3", "10-26 12:00:12.000"),
        ]
        result = scenario.judge_c5(rows)
        self.assertEqual("FAIL", result.status)


class CrashTest(unittest.TestCase):
    def test_unrelated_process_crash_is_ignored(self):
        rows = [
            line("smoke-scenario: C6 package=com.dev.bitchord pids=1234 5678"),
            line(
                "FATAL EXCEPTION: main",
                pid=9999,
                tag="AndroidRuntime",
            ),
        ]
        result = scenario.judge_c6(rows)
        self.assertEqual("PASS", result.status)

    def test_app_pid_crash_fails(self):
        rows = [
            line("smoke-scenario: C6 package=com.dev.bitchord pids=1234 5678"),
            line("FATAL EXCEPTION: main", pid=1234, tag="AndroidRuntime"),
        ]
        result = scenario.judge_c6(rows)
        self.assertEqual("FAIL", result.status)

    def test_anr_for_package_fails(self):
        rows = [
            line("smoke-scenario: C6 package=com.dev.bitchord pids=1234"),
            line("ANR in com.dev.bitchord", pid=1000, tag="ActivityManager"),
        ]
        result = scenario.judge_c6(rows)
        self.assertEqual("FAIL", result.status)

    def test_retry_count_is_kept(self):
        rows = [
            line("smoke-scenario: C6 package=com.dev.bitchord pids=1234"),
            line("retry 1/3", pid=1234),
            line("retry 2/3", pid=9999, tag="Other"),
        ]
        result = scenario.judge_c6(rows)
        self.assertIn("retry=1", result.actual)


class ReportTest(unittest.TestCase):
    def run_main(self, directory: Path, name: str, rows: list[str]) -> tuple[int, str]:
        (directory / f"{name}.txt").write_text("\n".join(rows) + "\n", encoding="utf-8")
        report = directory / "report.md"
        code = scenario.main(["--dir", str(directory), "--report", str(report)])
        return code, report.read_text(encoding="utf-8")

    def test_all_skip_is_not_green(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            code, report = self.run_main(
                directory,
                "A1",
                [line("nothing useful").msg],
            )
            self.assertEqual(3, code)
            self.assertIn("결과: **SKIP**", report)

    def test_one_pass_is_green(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            rows = [
                play("a", "AUTO", "10-26 12:00:01.000").msg,
                play("b", "AUTO", "10-26 12:00:02.000").msg,
                play("c", "AUTO", "10-26 12:00:03.000").msg,
            ]
            code, report = self.run_main(directory, "A4", rows)
            self.assertEqual(0, code)
            self.assertIn("결과: **PASS**", report)

    def test_one_fail_is_red(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            rows = [
                play("a", "AUTO", "10-26 12:00:01.000").msg,
                play("a", "AUTO", "10-26 12:00:02.000").msg,
                play("b", "AUTO", "10-26 12:00:03.000").msg,
            ]
            code, report = self.run_main(directory, "A4", rows)
            self.assertEqual(1, code)
            self.assertIn("결과: **FAIL**", report)


if __name__ == "__main__":
    unittest.main()
