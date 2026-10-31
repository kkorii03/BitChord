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

    def test_sync_skipped_reason_distribution(self) -> None:
        text = log_text(
            line(
                "19:00:00.000",
                "next-prep: A version=a lyrics=lrc hq=none "
                "sync=skipped(no-match) mix=none in 100ms",
            ),
            line(
                "19:00:01.000",
                "next-prep: B version=b lyrics=lrc hq=none "
                "sync=skipped(timeout) mix=none in 100ms",
            ),
            line(
                "19:00:02.000",
                "next-prep: C version=c lyrics=lrc hq=none "
                "sync=applied mix=none in 100ms",
            ),
        )
        report = analyze(text)
        sync = metric(report, "D1-sync")
        self.assertEqual(3, sync.attempts)
        self.assertEqual(1, sync.successes)
        self.assertEqual(
            {"no-match": 1, "timeout": 1},
            report.stats["next_prep"]["sync_skip_reasons"],
        )
        self.assertEqual(2, metric(report, "D1-sync-skip").attempts)

    def test_sync_skip_distribution_includes_no_lyrics_rows(self) -> None:
        text = log_text(
            line(
                "19:00:00.000",
                "next-prep: A version=a lyrics=lrc hq=none "
                "sync=skipped(no-match) mix=none in 100ms",
            ),
            line(
                "19:00:01.000",
                "next-prep: B version=b lyrics=none hq=none "
                "sync=skipped(no-lyrics) mix=none in 100ms",
            ),
            line(
                "19:00:02.000",
                "next-prep: C version=c lyrics=lrc hq=none "
                "sync=applied mix=none in 100ms",
            ),
        )
        report = analyze(text)
        sync = metric(report, "D1-sync")
        self.assertEqual(2, sync.attempts)
        self.assertEqual(1, sync.successes)
        self.assertEqual(
            {"no-match": 1, "no-lyrics": 1},
            report.stats["next_prep"]["sync_skip_reasons"],
        )
        self.assertEqual(2, metric(report, "D1-sync-skip").attempts)

    def test_next_prep_sync_line_separates_failure_from_healthy_no_answer(self) -> None:
        text = log_text(
            line(
                "19:00:00.000",
                "next-prep: A version=a lyrics=lrc hq=none "
                "sync=skipped(no-answer) mix=none in 100ms",
            ),
            line(
                "19:00:00.001",
                "next-prep sync: a result=no-answer reason=none",
            ),
            line(
                "19:00:01.000",
                "next-prep: B version=b lyrics=lrc hq=none "
                "sync=skipped(no-answer) mix=none in 100ms",
            ),
            line(
                "19:00:01.001",
                "next-prep sync: b result=failed:no-audio reason=no-audio",
            ),
            line(
                "19:00:02.000",
                "next-prep: C version=c lyrics=lrc hq=none "
                "sync=applied mix=none in 100ms",
            ),
            line(
                "19:00:02.001",
                "next-prep sync: c result=answered reason=applied",
            ),
        )
        report = analyze(text)
        sync = metric(report, "D1-sync")
        self.assertEqual(3, sync.attempts)
        self.assertEqual(1, sync.successes)
        self.assertEqual(
            {"no-answer": 1, "no-audio": 1},
            report.stats["next_prep"]["sync_skip_reasons"],
        )
        self.assertEqual(1, report.stats["next_prep"]["sync_failed"])


    def test_verified_refine_after_file_timed_skip_counts_as_sync(self) -> None:
        text = log_text(
            line(
                "13:09:00.000",
                "next-prep: Dynamite version=HaEYUJ2aRHs lyrics=lrc.red hq=none "
                "sync=skipped(file-timed) mix=none in 100ms",
            ),
            line(
                "13:09:00.001",
                "next-prep sync: HaEYUJ2aRHs result=skipped:file-timed reason=file-timed",
            ),
            line(
                "13:09:03.000",
                "lyrics-refine: HaEYUJ2aRHs lines=26 median=68ms mad=51ms "
                "slope=0 model=const applied=n base=none",
            ),
            line(
                "13:11:03.000",
                "track-summary: HaEYUJ2aRHs 'Dynamite' video=n source=browse:MPREb_x "
                "resolve=direct firstAudio=1200 played=210 lyrics=lrc.red "
                "sync=verified shift=68 match=- manualOffset=0 ended=natural",
            ),
        )
        report = analyze(text)
        sync = metric(report, "D1-sync")
        self.assertEqual(1, sync.attempts)
        self.assertEqual(1, sync.successes)
        self.assertTrue(sync.passed)
        self.assertEqual(0, metric(report, "D1-sync-skip").attempts)
        self.assertEqual(1, report.stats["next_prep"]["sync_verified"])
        self.assertEqual(1, metric(report, "D6-sync").successes)


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

    def test_verify_id_must_match_flac_evidence_not_just_title(self) -> None:
        text = log_text(
            play("19:00:00.000", "id1", "Same Title"),
            play("19:00:30.000", "id2", "Same Title"),
            line(
                "19:00:05.000",
                "hq-verify: Same Title Qobuz match=95% offset=0 -> accepted id=id2",
            ),
            line("19:00:10.000", "id1 <- audio/flac 44.1kHz 900kbps 16-bit 2ch", tag="DECODE"),
            line("19:00:40.000", "id2 <- audio/opus 48kHz 160kbps ?-bit 2ch", tag="DECODE"),
        )
        report = analyze(text)
        playback = metric(report, "D2-playback")
        self.assertEqual(1, playback.attempts)
        self.assertEqual(0, playback.successes)
        self.assertFalse(playback.passed)

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

    def test_hq_swap_states_and_hq_ahead_count_as_flac_evidence(self) -> None:
        text = log_text(
            play("19:00:00.000", "id1", "Lossless Song"),
            line("19:00:01.000", "hq-verify: Lossless Song Qobuz match=95% offset=0 -> accepted"),
            line("19:00:02.000", "hq-swap: id1 start at=1000 offset=0"),
            line("19:00:03.000", "hq-swap: id1 done"),
            line("19:00:04.000", "hq-swap: id2 skipped download-failed"),
            play("19:00:05.000", "id2", "Ahead Song"),
            line("19:00:06.000", "hq-verify: Ahead Song Qobuz match=95% offset=0 -> accepted"),
            line("19:00:07.000", "hq-ahead: id2 re-prepared next player as FLAC"),
        )
        report = analyze(text)
        playback = metric(report, "D2-playback")
        self.assertEqual(2, playback.attempts)
        self.assertEqual(2, playback.successes)
        self.assertEqual(1, report.stats["lossless"]["swap_started"])
        self.assertEqual(1, report.stats["lossless"]["swap_done"])
        self.assertEqual(1, report.stats["lossless"]["swap_skipped"])
        self.assertEqual(
            {"download-failed": 1},
            report.stats["lossless"]["swap_skip_reasons"],
        )
        self.assertEqual(1, report.stats["lossless"]["hq_ahead"])
        self.assertEqual(2, metric(report, "D2-swap").attempts)
        self.assertEqual(1, metric(report, "D2-swap").successes)


    def test_hq_swap_done_counts_playback_after_the_verify_line(self) -> None:
        text = log_text(
            line(
                "13:07:50.000",
                "play: -> Dynamite - BTS (HaEYUJ2aRHs) idx=0/10 reason=AUTO",
            ),
            line(
                "13:07:50.100",
                "HaEYUJ2aRHs <- audio/opus 48kHz 160kbps ?-bit 2ch",
                tag="DECODE",
            ),
            line("13:07:58.000", "hq-swap: HaEYUJ2aRHs start at=12509 offset=1"),
            line("13:07:59.000", "hq-swap: HaEYUJ2aRHs done"),
            line(
                "13:08:00.000",
                "hq-verify: Dynamite Qobuz match=95% offset=1 -> accepted",
            ),
        )
        report = analyze(text)
        playback = metric(report, "D2-playback")
        self.assertEqual(1, playback.attempts)
        self.assertEqual(1, playback.successes)
        self.assertTrue(playback.passed)


class AutoplayTests(unittest.TestCase):
    def test_refill_zero_refill_and_same_second_rescue(self) -> None:
        text = log_text(
            line("19:00:00.000", "autoplay: +radio 3 +similar 1 +fav 0 (A | B | C)"),
            line("19:00:10.000", "autoplay: dropped 2 cover/karaoke rows"),
            line("19:00:20.000", "autoplay: dropped 2 cover/karaoke rows"),
            line("19:00:20.500", "autoplay: +radio 0 +similar 0 +fav 0 ()"),
        )
        report = analyze(text)
        refill = metric(report, "D3-refill")
        self.assertEqual(3, refill.attempts)
        self.assertEqual(1, refill.successes)
        self.assertFalse(refill.passed)
        self.assertEqual(1, report.stats["autoplay"]["zero_refill"])

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

    def test_video_radio_refill_uses_fallback_stage(self) -> None:
        text = log_text(
            line(
                "19:00:00.000",
                "video-radio: seed=e mode=video picked=4 from=radio dropped=0 fallback=empty",
            ),
            line(
                "19:00:01.000",
                "video-radio: seed=n mode=video picked=0 from=radio dropped=0 fallback=normal",
            ),
            line("19:00:01.500", "autoplay: +radio 2 +similar 1 +fav 0 (A | B | C)"),
            line("19:00:02.000", "queue: autoplay-append n=3 at=5 size=8 by=autoplay"),
            line(
                "19:00:03.000",
                "video-radio: seed=p mode=video picked=2 from=radio dropped=0 fallback=partial",
            ),
        )
        report = analyze(text)
        refill = metric(report, "D3-refill")
        self.assertEqual(3, refill.attempts)
        self.assertEqual(2, refill.successes)
        self.assertFalse(refill.passed)
        self.assertEqual(1, report.stats["autoplay"]["video_deferred"])
        self.assertEqual(2, report.stats["autoplay"]["video_refills"])
        self.assertEqual(
            {"empty": 1, "normal": 1, "partial": 1},
            report.stats["autoplay"]["video_fallback"],
        )

    def test_radio_and_append_for_one_refill_count_once(self) -> None:
        text = log_text(
            line("19:00:00.000", "autoplay: +radio 2 +similar 1 +fav 0 (A | B | C)"),
            line("19:00:01.000", "queue: autoplay-append n=3 at=5 size=8 by=autoplay"),
        )
        report = analyze(text)
        refill = metric(report, "D3-refill")
        self.assertEqual(1, refill.attempts)
        self.assertEqual(1, refill.successes)
        self.assertTrue(refill.passed)
        self.assertEqual(1, report.stats["autoplay"]["refill_pairs"])

    def test_video_radio_append_refill_and_mode_distribution(self) -> None:
        text = log_text(
            line("19:00:00.000", "video-radio: seed=s1 mode=video picked=3 from=radio dropped=0 fallback=none"),
            line("19:00:01.000", "video-radio: seed=s2 mode=music picked=2 from=similar dropped=1 fallback=none"),
            line("19:00:02.000", "queue: autoplay-append n=3 at=5 size=8 by=autoplay"),
            line("19:00:03.000", "queue: autoplay-append n=0 at=5 size=5 by=autoplay"),
        )
        report = analyze(text)
        refill = metric(report, "D3-refill")
        self.assertEqual(4, refill.attempts)
        self.assertEqual(3, refill.successes)
        self.assertFalse(refill.passed)
        self.assertEqual(2, report.stats["autoplay"]["append"])
        self.assertEqual([3, 0], report.stats["autoplay"]["append_n"])
        modes = report.stats["video_radio"]["modes"]
        self.assertEqual([3], modes["video"]["picked"])
        self.assertEqual({"radio": 1}, modes["video"]["from"])
        self.assertEqual([2], modes["music"]["picked"])
        self.assertEqual({"similar": 1}, modes["music"]["from"])
        self.assertEqual(2, metric(report, "D4-modes").attempts)
        self.assertEqual(2, metric(report, "D4-modes").successes)


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

    def test_aborted_start_is_not_used_as_planned_fade(self) -> None:
        text = log_text(
            line(
                "19:00:00.000",
                "crossfade: start out=a in=b fade=500ms cue=0ms smart=true",
            ),
            line(
                "19:00:00.500",
                "crossfade: abort out=a in=b phase=FADING handedOff=false",
            ),
            line(
                "19:00:01.000",
                "crossfade: start out=a in=b fade=1000ms cue=0ms smart=true",
            ),
            line(
                "19:00:02.000",
                "crossfade: end out=a in=b fade=1000ms overlap=1100ms tail=0ms",
            ),
        )
        report = analyze(text)
        self.assertEqual([1000], report.stats["crossfade"]["planned_fades"])
        self.assertEqual([1.1], report.stats["crossfade"]["overlap_ratios"])
        self.assertEqual(1, report.stats["crossfade"]["abort"])

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

    def test_crossfade_completion_overlap_and_gain_quarters(self) -> None:
        text = log_text(
            line(
                "19:00:00.000",
                "crossfade: start out=a in=b fade=1000ms cue=0ms smart=true "
                "gains in=1.000 out=0.000",
            ),
            line(
                "19:00:01.000",
                "crossfade: end out=a in=b fade=1000ms overlap=1100ms tail=0ms "
                "progress=1.000 25%=0.383/0.924 50%=0.707/0.707 75%=0.924/0.383 "
                "gains end=1.000/0.000",
            ),
            line(
                "19:00:10.000",
                "crossfade: start out=b in=c fade=800ms cue=0ms smart=false "
                "gains in=1.000 out=0.000",
            ),
        )
        report = analyze(text)
        complete = metric(report, "D7-complete")
        self.assertEqual(2, complete.attempts)
        self.assertEqual(1, complete.successes)
        self.assertEqual([1000], report.stats["crossfade"]["planned_fades"])
        self.assertEqual([1100], report.stats["crossfade"]["overlaps"])
        self.assertEqual([1.1], report.stats["crossfade"]["overlap_ratios"])
        self.assertEqual(
            {"25": 1, "50": 1, "75": 1},
            report.stats["crossfade"]["gain_quarters"],
        )
        self.assertEqual(3, metric(report, "D7-gains").attempts)
        self.assertIn("계획 fade 평균 1000ms", metric(report, "D7-overlap").criterion)

    def test_unplayable_alt_and_skip_with_next_action_gap(self) -> None:
        text = log_text(
            play("19:00:00.000", "id1", "Bad"),
            line("19:00:01.000", "unplayable: id1 Video unavailable -> alt id1b"),
            play("19:00:06.000", "id1b", "Fixed"),
            line("19:00:10.000", "unplayable: id2 Age restricted -> skip"),
            play("19:00:18.000", "id3", "Next"),
        )
        report = analyze(text)
        unplayable = metric(report, "D8-unplayable")
        self.assertEqual(2, unplayable.attempts)
        self.assertEqual(1, unplayable.successes)
        self.assertEqual(1, report.stats["first_audio"]["unplayable_alt"])
        self.assertEqual(1, report.stats["first_audio"]["unplayable_skip"])
        self.assertEqual(
            [5.0, 8.0], report.stats["first_audio"]["unplayable_gaps"]
        )
        self.assertIn("대체 성공 1 · 건너뜀 1", unplayable.criterion)


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
        self.assertEqual(1, metric(report, "D9-download").attempts)
        self.assertEqual(2, metric(report, "D9-hq-library").attempts)
        self.assertFalse(metric(report, "D9-download").passed)

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


class FeatureHealthHeaderTests(unittest.TestCase):
    def test_app_counters_are_read_and_shown_beside_log_counts(self) -> None:
        text = (
            HEADER
            + "feature health (last 24h):\n"
            + "feature-health: D1-lyrics attempts=3 successes=2\n"
            + "feature-health: D1-sync attempts=2 successes=1 reasons=timeout=1\n"
            + "feature-health: D9-download failures=1\n"
            + line(
                "19:00:00.000",
                "next-prep: A version=a lyrics=lrc.red hq=none sync=applied mix=none in 100ms",
            )
            + "\n"
        )
        report = analyze(text)
        self.assertEqual("3", report.stats["feature_health"]["D1-lyrics"]["attempts"])
        self.assertEqual("2", report.stats["feature_health"]["D1-lyrics"]["successes"])
        self.assertEqual(
            "timeout=1",
            report.stats["feature_health"]["D1-sync"]["reasons"],
        )

        console = featurehealth.render_console(report)
        self.assertIn("앱 24h 시도/성공", console)
        self.assertIn("3/2", console)
        self.assertIn("실패 1", console)

        markdown = featurehealth.render_markdown(report)
        self.assertIn("앱 24h 시도/성공", markdown)
        self.assertIn("3/2", markdown)

    def test_log_without_app_counters_renders_dashes(self) -> None:
        report = analyze(log_text(play("19:00:00.000", "id1", "Song")))
        self.assertEqual({}, report.stats["feature_health"])
        self.assertIn("—", featurehealth.render_console(report))


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
        self.assertIn(
            "| 기능 | 시도 | 성공 | 성공률 | 앱 24h 시도/성공 | 기준 | 판정 | 대표 실패 예 |",
            markdown,
        )
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
