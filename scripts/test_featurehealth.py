"""Unit tests for scripts/featurehealth.py. Run with `python3 -m unittest`."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import featurehealth

HEADER = """BitChord diagnostic log
exported: 2026-10-28 20:00:00 +0900
version: 1.2.3 (123) release
git: abcdef123456
android: 15 (API 35)
---
"""


def line(at: str, message: str, tag: str = "BitChord", level: str = "I") -> str:
    return f"2026-10-28 {at} {level}/{tag} {message}"


def log_text(*lines: str) -> str:
    return HEADER + "\n".join(lines) + "\n"


def analyze(text: str) -> featurehealth.Report:
    return featurehealth.analyze(featurehealth.parse_log_text(text))


def metric(report: featurehealth.Report, key: str) -> featurehealth.Metric:
    return report.metrics_by_key[key]


def summary(
    at: str,
    video_id: str,
    title: str,
    *,
    video: bool = False,
    first_audio: str = "1200",
    lyrics: str = "lrc.red",
    sync: str = "ai",
    ended: str = "natural",
) -> str:
    return line(
        at,
        f"track-summary: {video_id} '{title}' video={'y' if video else 'n'} "
        "source=browse:MPREb_x resolve=direct "
        f"firstAudio={first_audio} played=210 lyrics={lyrics} sync={sync} "
        "shift=-120 match=91 manualOffset=0 "
        f"ended={ended}",
    )


def play(at: str, video_id: str, title: str, reason: str = "AUTO") -> str:
    return line(
        at,
        f"play: -> {title} - Artist ({video_id}) idx=0/10 reason={reason} meta=ok",
    )


class HeaderAndPeriodTests(unittest.TestCase):
    def test_header_period_and_played_count(self) -> None:
        report = analyze(
            log_text(
                play("19:00:00.000", "id1", "One"),
                play("19:01:00.000", "id2", "Two"),
                summary("19:02:00.000", "id1", "One"),
            )
        )
        self.assertEqual("1.2.3 (123) release", report.log.version)
        self.assertEqual("abcdef123456", report.log.git)
        self.assertEqual(2, report.played_songs)
        start, end = report.log.period
        self.assertEqual("19:00:00", start.strftime("%H:%M:%S"))
        self.assertEqual("19:02:00", end.strftime("%H:%M:%S"))

    def test_since_filters_lines(self) -> None:
        text = log_text(
            play("19:00:00.000", "old", "Old"),
            play("19:10:00.000", "new", "New"),
        )
        log = featurehealth.parse_log_text(
            text, featurehealth._parse_since("2026-10-28 19:05")
        )
        report = featurehealth.analyze(log)
        self.assertEqual(1, report.played_songs)
        self.assertEqual("new", report.log.lines[0].text.split("(")[1].split(")")[0])


class NextPrepTests(unittest.TestCase):
    def test_stages_and_sync_criterion(self) -> None:
        rows = [
            line(
                f"19:0{i}:00.000",
                f"next-prep: Song {i} version=v{i} lyrics=lrc.red hq=hq{i} "
                f"sync={'applied' if i < 4 else 'skipped(no-match)'} mix=ready in {100 + i}ms",
            )
            for i in range(1, 6)
        ]
        rows.append(
            line(
                "19:06:00.000",
                "next-prep: No Lyrics version=v6 lyrics=none hq=none "
                "sync=skipped(no-lyrics) mix=none in 500ms",
            )
        )
        report = analyze(log_text(*rows))
        sync = metric(report, "D1-sync")
        self.assertEqual(5, sync.attempts)
        self.assertEqual(3, sync.successes)
        self.assertFalse(sync.passed)
        self.assertEqual(6, metric(report, "D1-lyrics").attempts)
        self.assertEqual(5, metric(report, "D1-lyrics").successes)
        self.assertEqual(5, metric(report, "D1-hq").successes)
        self.assertEqual(5, metric(report, "D1-mix").successes)
        self.assertEqual(169, report.stats["next_prep"]["avg_ms"])
        self.assertEqual(500, report.stats["next_prep"]["max_ms"])

    def test_sync_criterion_passes_at_eighty_percent(self) -> None:
        rows = []
        for i in range(5):
            sync = "applied" if i < 4 else "skipped(timeout)"
            rows.append(
                line(
                    f"19:0{i}:00.000",
                    f"next-prep: Song {i} version=v{i} lyrics=lrc hq=none "
                    f"sync={sync} mix=none in 100ms",
                )
            )
        report = analyze(log_text(*rows))
        self.assertTrue(metric(report, "D1-sync").passed)
        self.assertEqual(0.8, metric(report, "D1-sync").rate)

    def test_video_and_switched_off_rows_leave_the_sync_denominator(self) -> None:
        text = log_text(
            line(
                "19:00:00.000",
                "next-prep: Video version=v1 video=y lyrics=lrc hq=none "
                "sync=skipped(video) mix=none in 100ms",
            ),
            line(
                "19:01:00.000",
                "next-prep: Off version=v2 video=n lyrics=lrc hq=none "
                "sync=skipped(switch-off) mix=none in 100ms",
            ),
            line(
                "19:02:00.000",
                "next-prep: Timed version=v3 video=n lyrics=lrc hq=none "
                "sync=applied mix=none in 100ms",
            ),
        )
        report = analyze(text)
        sync = metric(report, "D1-sync")
        self.assertEqual(1, sync.attempts)
        self.assertEqual(1, sync.successes)
        self.assertTrue(sync.passed)
        self.assertEqual(2, report.stats["next_prep"]["not_attempted"])

    def test_bare_skipped_without_a_reason_is_still_a_failure(self) -> None:
        text = log_text(
            line(
                "19:00:00.000",
                "next-prep: Old version=v1 lyrics=lrc hq=none "
                "sync=skipped mix=none in 100ms",
            )
        )
        report = analyze(text)
        sync = metric(report, "D1-sync")
        self.assertEqual(1, sync.attempts)
        self.assertEqual(0, sync.successes)
        self.assertFalse(sync.passed)

    def test_abandoned_and_no_next_row_are_counted_as_skips(self) -> None:
        text = log_text(
            line("19:00:00.000", "next-prep: skipped reason=no-next-row"),
            line("19:01:00.000", "next-prep: abandoned Song reason=row-changed"),
        )
        report = analyze(text)
        self.assertEqual(2, report.stats["next_prep"]["skipped"])
        self.assertEqual(
            {"no-next-row": 1, "row-changed": 1},
            report.stats["next_prep"]["skip_reasons"],
        )
        self.assertEqual(2, metric(report, "D1-skipped").attempts)


class LosslessTests(unittest.TestCase):
    def test_accepted_verify_needs_actual_flac_playback(self) -> None:
        text = log_text(
            play("19:00:00.000", "flac1", "Flac Song"),
            play("19:00:30.000", "opus1", "Opus Song"),
            line("19:00:05.000", "hq-verify: Flac Song Qobuz match=95% offset=0 -> accepted"),
            line("19:00:06.000", "hq-verify: Opus Song Qobuz match=95% offset=0 -> accepted"),
            line("19:00:10.000", "flac1 <- audio/flac 44.1kHz 900kbps 16-bit 2ch", tag="DECODE"),
            line("19:00:40.000", "opus1 <- audio/opus 48kHz 160kbps ?-bit 2ch", tag="DECODE"),
            line("19:00:07.000", "upgrade for opus1 kept for next play; no mid-song swap"),
            line("19:00:41.000", "manual upgrade unnecessary for opus1: already at the best available quality"),
        )
        report = analyze(text)
        metric_d2 = metric(report, "D2-playback")
        self.assertEqual(2, metric_d2.attempts)
        self.assertEqual(1, metric_d2.successes)
        self.assertFalse(metric_d2.passed)
        self.assertEqual(1, report.stats["lossless"]["kept_for_next"])
        self.assertEqual(1, report.stats["lossless"]["manual_unnecessary"])

    def test_upgrade_ahead_ready_at_flac_counts_as_event(self) -> None:
        text = log_text(
            play("19:00:00.000", "id1", "Ahead Song"),
            line("19:00:01.000", "upgrade ahead: 'Ahead Song' ready at FLAC 24-bit from Qobuz"),
            line("19:00:02.000", "'Ahead Song' is already playing audio/flac from cache; no second look needed"),
        )
        report = analyze(text)
        self.assertEqual(1, metric(report, "D2-playback").attempts)
        self.assertEqual(1, metric(report, "D2-playback").successes)
        self.assertTrue(metric(report, "D2-playback").passed)

    def test_bracket_format_tag_counts_as_flac_evidence(self) -> None:
        text = log_text(
            line("19:00:00.000", "hq-verify: Tagged Song Qobuz match=95% offset=0 -> accepted"),
            line("19:00:01.000", "crossfade: start out=old in=new fade=1000ms [player-a:flac]"),
            play("19:00:02.000", "new", "Tagged Song"),
        )
        report = analyze(text)
        self.assertEqual(1, metric(report, "D2-playback").successes)

    def test_crossfade_end_bracket_format_counts_as_flac_evidence(self) -> None:
        text = log_text(
            play("19:00:00.000", "new", "End Tagged Song"),
            line("19:00:01.000", "hq-verify: End Tagged Song Qobuz match=95% offset=0 -> accepted"),
            line("19:00:02.000", "crossfade: end out=old in=new fade=1000ms overlap=1000ms [player-b:flac]"),
        )
        report = analyze(text)
        self.assertEqual(1, metric(report, "D2-playback").successes)

    def test_verify_id_matches_playback_even_when_titles_differ(self) -> None:
        text = log_text(
            play("19:00:00.000", "flac1", "Song (Official Video)"),
            line(
                "19:00:05.000",
                "hq-verify: Song Qobuz match=95% offset=0 -> accepted id=flac1",
            ),
            line("19:00:10.000", "flac1 <- audio/flac 44.1kHz 900kbps 16-bit 2ch", tag="DECODE"),
        )
        report = analyze(text)
        self.assertEqual(1, metric(report, "D2-playback").attempts)
        self.assertEqual(1, metric(report, "D2-playback").successes)
        self.assertTrue(metric(report, "D2-playback").passed)

    def test_verify_id_does_not_accept_a_different_recording_with_the_same_title(self) -> None:
        text = log_text(
            play("19:00:00.000", "other", "Same Title"),
            line(
                "19:00:05.000",
                "hq-verify: Same Title Qobuz match=95% offset=0 -> accepted id=wanted",
            ),
            line("19:00:10.000", "other <- audio/flac 44.1kHz 900kbps 16-bit 2ch", tag="DECODE"),
        )
        report = analyze(text)
        self.assertEqual(1, metric(report, "D2-playback").attempts)
        self.assertEqual(0, metric(report, "D2-playback").successes)
        self.assertFalse(metric(report, "D2-playback").passed)

    def test_skipped_hq_swap_is_reported_with_its_reason(self) -> None:
        text = log_text(
            play("19:00:00.000", "id1", "Song"),
            line("19:00:05.000", "hq-verify: Song Qobuz match=95% offset=0 -> accepted id=id1"),
            line("19:00:06.000", "hq-swap: id1 skipped current-clock-unknown"),
        )
        report = analyze(text)
        self.assertFalse(metric(report, "D2-playback").passed)
        self.assertEqual(1, report.stats["lossless"]["swap_skipped"])
        self.assertEqual({"id1": ["current-clock-unknown"]}, report.stats["lossless"]["swap_skip_ids"])


class AutoplayTests(unittest.TestCase):
    def test_refill_waits_for_a_network_backed_radio_before_calling_it_zero(self) -> None:
        text = log_text(
            line("19:00:00.000", "autoplay: +radio 3 +similar 1 +fav 0 (A | B | C)"),
            line("19:00:10.000", "autoplay: dropped 2 cover/karaoke rows seed=seed1"),
            # A personalised top-up fetches its radio before logging; thirty
            # seconds is ordinary, and the old one-second window called this a
            # zero-refill failure.
            line("19:00:40.000", "autoplay: +radio 2 +similar 1 +fav 0 (D | E) seed=seed1"),
            line("19:05:00.000", "autoplay: dropped 2 cover/karaoke rows seed=seed2"),
        )
        report = analyze(text)
        refill = metric(report, "D3-refill")
        self.assertEqual(3, refill.attempts)
        self.assertEqual(2, refill.successes)
        self.assertFalse(refill.passed)
        self.assertEqual(1, report.stats["autoplay"]["zero_refill"])

    def test_seedless_radio_does_not_absorb_a_seeded_drop(self) -> None:
        text = log_text(
            line("19:00:10.000", "autoplay: dropped 2 cover/karaoke rows seed=seed1"),
            line("19:00:20.000", "autoplay: +radio 3 +similar 1 +fav 0 (A | B | C)"),
        )
        report = analyze(text)
        refill = metric(report, "D3-refill")
        self.assertEqual(2, refill.attempts)
        self.assertEqual(1, refill.successes)
        self.assertFalse(refill.passed)
        self.assertEqual(1, report.stats["autoplay"]["zero_refill"])

    def test_explicit_skip_is_logged_and_counted(self) -> None:
        text = log_text(
            line("19:00:00.000", "autoplay: skipped reason=no-seed"),
            line("19:01:00.000", "autoplay: +radio 0 +similar 0 +fav 0 ()"),
        )
        report = analyze(text)
        self.assertEqual(1, report.stats["autoplay"]["skipped"])
        self.assertEqual({"no-seed": 1}, report.stats["autoplay"]["skip_reasons"])
        self.assertFalse(metric(report, "D3-refill").passed)

    def test_unwanted_repeat_only_when_repeat_is_off(self) -> None:
        text = log_text(
            line("19:00:00.000", "mode: repeat=off (was one)"),
            play("19:00:01.000", "id1", "Repeat Me", reason="REPEAT"),
            line("19:00:10.000", "mode: repeat=all (was off)"),
            play("19:00:11.000", "id2", "Allowed", reason="REPEAT"),
        )
        report = analyze(text)
        repeat = metric(report, "D3-repeat")
        self.assertEqual(2, repeat.attempts)
        self.assertEqual(1, repeat.successes)
        self.assertFalse(repeat.passed)
        self.assertEqual(1, report.stats["autoplay"]["unwanted_repeat"])

    def test_video_radio_distribution(self) -> None:
        text = log_text(
            line("19:00:00.000", "video-radio: seed=s1 mode=video picked=3 from=radio dropped=0 fallback=none"),
            line("19:00:01.000", "video-radio: seed=s2 mode=video picked=0 from=empty dropped=2 fallback=empty"),
            line("19:00:02.000", "video-radio: seed=s3 mode=music picked=5 from=radio dropped=0 fallback=none"),
        )
        report = analyze(text)
        video = metric(report, "D4-video")
        self.assertEqual(2, video.attempts)
        self.assertEqual(1, video.successes)
        self.assertEqual({"radio": 1, "empty": 1}, report.stats["video_radio"]["from"])


class VersionAndLyricsTests(unittest.TestCase):
    def test_track_summary_video_cover_lyrics_and_sync(self) -> None:
        text = log_text(
            summary("19:00:00.000", "v1", "Normal Video", video=True),
            summary("19:01:00.000", "v2", "Song (cover)", video=True, lyrics="none", sync="none"),
            summary("19:02:00.000", "v3", "Plain", video=False, lyrics="lrc", sync="none"),
            summary("19:03:00.000", "v4", "Another", video=False),
        )
        report = analyze(text)
        self.assertEqual(2, metric(report, "D5-video-ratio").successes)
        self.assertEqual(1, metric(report, "D5-cover-video").attempts)
        self.assertEqual(1, metric(report, "D5-cover-video").successes)
        self.assertEqual(3, metric(report, "D6-lyrics").successes)
        self.assertEqual(3, metric(report, "D6-sync").attempts)
        self.assertEqual(2, metric(report, "D6-sync").successes)

    def test_song_version_swap_and_kept_counts(self) -> None:
        text = log_text(
            line("19:00:00.000", "song version: mv1 -> aud1 via search before-audio"),
            line("19:01:00.000", "song version: mv2 kept (none)"),
            line("19:02:00.000", "song version: mv3 kept (timeout)"),
            line("19:03:00.000", "song version: mv4 kept (local file, none)"),
        )
        report = analyze(text)
        self.assertEqual(1, report.stats["song_version"]["swap"])
        self.assertEqual(2, report.stats["song_version"]["kept_none"])
        self.assertEqual(1, report.stats["song_version"]["kept_timeout"])
        self.assertEqual(4, metric(report, "D5-version").attempts)


class CrossfadeAndStartTests(unittest.TestCase):
    def test_crossfade_counts(self) -> None:
        text = log_text(
            line("19:00:00.000", "crossfade: start out=a in=b fade=1000ms cue=0ms smart=true"),
            line("19:00:01.000", "crossfade: skipped b->c reason=no-gap"),
            line("19:00:02.000", "crossfade: abort out=b in=c phase=ARMING handedOff=false"),
            line("19:00:03.000", "crossfade: end out=a in=b fade=1000ms overlap=1200ms tail=0ms"),
        )
        report = analyze(text)
        self.assertEqual(1, report.stats["crossfade"]["start"])
        self.assertEqual(1, report.stats["crossfade"]["end"])
        self.assertEqual(1, report.stats["crossfade"]["skipped"])
        self.assertEqual({"no-gap": 1}, report.stats["crossfade"]["skip_reasons"])
        self.assertEqual(1, report.stats["crossfade"]["abort"])
        self.assertEqual([1200], report.stats["crossfade"]["overlaps"])

    def test_first_audio_error_and_failed_start_gap(self) -> None:
        text = log_text(
            play("19:00:00.000", "id1", "Fast"),
            summary("19:00:10.000", "id1", "Fast", first_audio="1000"),
            play("19:00:20.000", "id2", "Slow"),
            summary("19:00:30.000", "id2", "Slow", first_audio="5000", ended="error"),
            line("19:00:31.000", "failed to start after 3 attempts (ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) — skipping to the next track"),
            play("19:00:38.000", "id3", "Next"),
        )
        report = analyze(text)
        self.assertEqual(3000, report.stats["first_audio"]["median_ms"])
        self.assertEqual(1, report.stats["first_audio"]["over_3s"])
        self.assertEqual(1, report.stats["first_audio"]["errors"])
        self.assertFalse(metric(report, "D8-error").passed)
        failed = metric(report, "D8-failed-start")
        self.assertEqual(1, failed.attempts)
        self.assertEqual([18.0], report.stats["first_audio"]["gaps"])


class DownloadLifecycleQualityTests(unittest.TestCase):
    def test_download_failures_and_repeated_ids(self) -> None:
        text = log_text(
            line("19:00:00.000", "download failed for id1: HTTP 500"),
            line("19:00:01.000", "download failed for id1: HTTP 500"),
            line("19:00:02.000", "hq-library: id1 did not land; it will be retried after the cooldown"),
            line("19:00:03.000", "hq-library: id2 did not land; automatic retries exhausted"),
            line("19:00:04.000", "could not start the download service: no window"),
        )
        report = analyze(text)
        self.assertEqual(2, report.stats["downloads"]["failed"])
        self.assertEqual({"id1": 2}, report.stats["downloads"]["failed_ids"])
        self.assertEqual(1, report.stats["downloads"]["hq_missing_ids"]["id1"])
        self.assertEqual(1, report.stats["downloads"]["service_failed"])
        self.assertFalse(metric(report, "D9-download").passed)

    def test_download_and_hq_library_successes_give_a_rate(self) -> None:
        done = [line(f"19:00:0{i}.000", f"download: id{i} done") for i in range(9)]
        landed = [line(f"19:00:1{i}.000", f"hq-library: id{i} landed") for i in range(9)]
        text = log_text(
            *done,
            *landed,
            line("19:00:20.000", "download failed for id9: HTTP 500"),
            line("19:00:21.000", "hq-library: id9 did not land; it will be retried after the cooldown"),
        )
        report = analyze(text)
        # One id still failed, so the criterion "실패 0건 / did not land 0건"
        # is not met even though the rate is 90%.
        self.assertEqual(10, metric(report, "D9-download").attempts)
        self.assertEqual(9, metric(report, "D9-download").successes)
        self.assertFalse(metric(report, "D9-download").passed)
        self.assertEqual(10, metric(report, "D9-hq-library").attempts)
        self.assertEqual(9, metric(report, "D9-hq-library").successes)
        self.assertFalse(metric(report, "D9-hq-library").passed)
        self.assertEqual(9, report.stats["downloads"]["done"])
        self.assertEqual(9, report.stats["downloads"]["hq_landed"])

    def test_download_attempts_count_unique_ids_after_a_retry(self) -> None:
        text = log_text(
            line("19:00:00.000", "download failed for id1: HTTP 500"),
            line("19:00:01.000", "download failed for id1: HTTP 500"),
            line("19:00:02.000", "download: id1 done"),
            line("19:00:03.000", "hq-library: id1 did not land; it will be retried after the cooldown"),
            line("19:00:04.000", "hq-library: id1 landed"),
        )
        report = analyze(text)
        self.assertEqual(1, metric(report, "D9-download").attempts)
        self.assertEqual(1, metric(report, "D9-download").successes)
        self.assertTrue(metric(report, "D9-download").passed)
        self.assertEqual(1, metric(report, "D9-hq-library").attempts)
        self.assertEqual(1, metric(report, "D9-hq-library").successes)
        self.assertTrue(metric(report, "D9-hq-library").passed)
        self.assertEqual({"id1": 2}, report.stats["downloads"]["failed_ids"])

    def test_lifecycle_orphan_window_counts_warm_and_network(self) -> None:
        text = log_text(
            line("19:00:00.000", "lifecycle: process start pid=100 cold"),
            line("19:00:02.000", "InnerTubeX warmed in 40ms"),
            line("19:00:03.000", "network changed to wifi"),
            line("19:00:20.000", "lifecycle: process start pid=200 cold"),
            line("19:00:25.000", "lifecycle: foreground"),
        )
        report = analyze(text)
        lifecycle = metric(report, "D10-lifecycle")
        self.assertEqual(2, lifecycle.attempts)
        self.assertEqual(1, lifecycle.successes)
        self.assertFalse(lifecycle.passed)
        self.assertEqual(1, report.stats["lifecycle"]["orphans"])
        self.assertEqual(1, report.stats["lifecycle"]["warmed_in_orphan"])
        self.assertEqual(1, report.stats["lifecycle"]["network_in_orphan"])

    def test_quality_trigger_against_actual_opus(self) -> None:
        text = log_text(
            play("19:00:00.000", "id1", "Opus Song"),
            line("19:00:01.000", "id1 <- audio/opus 48kHz 160kbps ?-bit 2ch", tag="DECODE"),
            line("19:00:02.000", "manual upgrade unnecessary for id1: already at the best available quality"),
            play("19:00:10.000", "id2", "Flac Song"),
            line("19:00:11.000", "id2 <- audio/flac 44.1kHz 900kbps 16-bit 2ch", tag="DECODE"),
            line("19:00:12.000", "upgrade reverted: replacement reports 1000ms against 2000ms"),
        )
        report = analyze(text)
        quality = metric(report, "D11-quality")
        self.assertEqual(2, quality.attempts)
        self.assertEqual(1, quality.successes)
        self.assertFalse(quality.passed)
        self.assertEqual(1, report.stats["quality"]["opus"])


class RenderingTests(unittest.TestCase):
    def test_markdown_has_table_and_verdicts(self) -> None:
        report = analyze(
            log_text(
                line("19:00:00.000", "autoplay: dropped 1 cover/karaoke rows"),
                play("19:00:01.000", "id1", "Song", reason="REPEAT"),
                line("19:00:02.000", "mode: repeat=off (was all)"),
            )
        )
        markdown = featurehealth.render_markdown(report)
        self.assertIn("| 기능 | 시도 | 성공 | 성공률 | 기준 | 판정 | 대표 실패 예 |", markdown)
        self.assertIn("PASS", markdown)
        self.assertIn("FAIL", markdown)
        self.assertIn("버전: 1.2.3 (123) release", markdown)
        self.assertIn("git: abcdef123456", markdown)

    def test_main_writes_markdown(self) -> None:
        text = log_text(play("19:00:00.000", "id1", "Song"))
        with tempfile.TemporaryDirectory() as folder:
            log_path = Path(folder) / "log.txt"
            md_path = Path(folder) / "report.md"
            log_path.write_text(text, encoding="utf-8")
            code = featurehealth.main([str(log_path), "--md", str(md_path)])
            self.assertEqual(0, code)
            self.assertTrue(md_path.exists())
            self.assertIn("# BitChord 기능 건강 검사", md_path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
