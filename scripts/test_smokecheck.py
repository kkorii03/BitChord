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


def play(video_id: str, title: str, artist: str) -> str:
    return log_line(
        f"play: -> {title} - {artist} ({video_id}) idx=0/10 reason=auto meta=none"
    )


def specs(*rows: str) -> list[smokecheck.SongSpec]:
    return smokecheck.parse_songs("\n".join(rows))


def statuses(log: str) -> dict[int, smokecheck.Check]:
    return {check.number: check for check in smokecheck.check_log(log)}


def statuses_with_songs(
    log: str, songs: list[smokecheck.SongSpec]
) -> dict[int, smokecheck.Check]:
    return {
        check.number: check
        for check in smokecheck.check_log(log, songs=songs)
    }


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

    def test_cover_video_with_no_catalogue_song_passes(self):
        log = "\n".join(
            [
                play("cover1", "Dance Monkey", "오화요"),
                log_line(
                    "audio switch: no official song match for 'Dance Monkey' by '오화요'"
                ),
                summary("cover1", "Dance Monkey", True),
            ]
        )
        self.assertEqual("PASS", statuses(log)[1].status)

    def test_downloaded_cover_kept_local_file_none_passes(self):
        log = "\n".join(
            [
                log_line("song version: cover3 kept (local file, none)"),
                summary("cover3", "Dance Monkey", True),
            ]
        )
        self.assertEqual("PASS", statuses(log)[1].status)

    def test_cover_marker_without_version_swap_passes(self):
        log = "\n".join(
            [
                log_line("song version: cover2 -> none via none (cover)"),
                summary("cover2", "Dance Monkey", True),
            ]
        )
        self.assertEqual("PASS", statuses(log)[1].status)

    def test_kept_none_video_without_mv_marker_passes(self):
        log = "\n".join(
            [
                log_line("song version: cover3 kept (none)"),
                summary("cover3", "Dance Monkey", True),
            ]
        )
        self.assertEqual("PASS", statuses(log)[1].status)

    def test_kept_none_with_mv_marker_still_fails(self):
        log = "\n".join(
            [
                log_line("song version: vid6 kept (none)"),
                summary("vid6", "Song (Official Video)", True),
            ]
        )
        self.assertEqual("FAIL", statuses(log)[1].status)

    def test_mv_title_still_fails_when_no_catalogue_song_was_found(self):
        log = "\n".join(
            [
                log_line(
                    "audio switch: no official song match for 'Song' by 'Artist'"
                ),
                summary("vid4", "Song (Official Video)", True),
            ]
        )
        self.assertEqual("FAIL", statuses(log)[1].status)

    def test_no_match_for_a_different_title_does_not_excuse_the_video(self):
        log = "\n".join(
            [
                log_line(
                    "audio switch: no official song match for 'Other Song' by 'Artist'"
                ),
                summary("vid5", "Song", True),
            ]
        )
        self.assertEqual("FAIL", statuses(log)[1].status)

    def test_no_match_for_a_different_artist_does_not_excuse_the_video(self):
        log = "\n".join(
            [
                play("vid5", "Song", "Other Artist"),
                log_line(
                    "audio switch: no official song match for 'Song' by 'Artist'"
                ),
                summary("vid5", "Song", True),
            ]
        )
        self.assertEqual("FAIL", statuses(log)[1].status)

    def test_no_match_for_the_same_artist_and_id_excuses_the_video(self):
        log = "\n".join(
            [
                play("cover1", "Song", "Artist"),
                log_line(
                    "audio switch: no official song match for 'Song' by 'Artist'"
                ),
                summary("cover1", "Song", True),
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

    def test_a_discarded_shift_is_not_judged_when_a_later_line_drops_it(self):
        log = "\n".join(
            [
                log_line(
                    "lyrics-sync: vid1 source=ai base=ai shift=11662ms match=90% "
                    "bias=0ms manual=0ms stored=n recheck=none silenceSkipMs=0 video=n"
                ),
                log_line(
                    "lyrics-sync: vid1 source=none base=none shift=-ms match=- "
                    "bias=0ms manual=0ms stored=n recheck=none silenceSkipMs=0 video=n"
                ),
            ]
        )
        self.assertEqual("PASS", statuses(log)[3].status)

    def test_a_large_shift_on_the_last_line_still_fails(self):
        log = "\n".join(
            [
                log_line(
                    "lyrics-sync: vid1 source=ai base=0 shift=2500ms match=90% "
                    "bias=0ms manual=0ms stored=n recheck=none silenceSkipMs=0 video=n"
                ),
                log_line(
                    "lyrics-sync: vid1 source=ai base=0 shift=-3100ms match=90% "
                    "bias=0ms manual=0ms stored=n recheck=none silenceSkipMs=0 video=n"
                ),
            ]
        )
        result = statuses(log)[3]
        self.assertEqual("FAIL", result.status)
        self.assertIn("3100", result.details[0])

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


class QualityUpgradeNoWavTest(unittest.TestCase):
    """11-05: r12b failed check 4 only because the reference WAV never arrived
    (`no reference reason=no-wav`, emulator network). An unmeasured row is not a
    regression, but a measured row that failed still is."""

    def test_every_row_without_a_reference_is_skipped(self):
        log = "\n".join(
            [
                log_line("hq-verify: 'Looks Can Kill' no reference reason=no-wav"),
                log_line("hq-verify: 'Lemon Tang' no reference reason=no-wav"),
            ]
        )
        result = statuses(log)[4]
        self.assertEqual("SKIP", result.status)
        self.assertIn("no-wav=2", result.details[0])

    def test_a_dropped_download_is_not_a_decode_failure(self):
        # r55d: the only row with a verdict-shaped failure was the candidate's
        # connection closing on the emulator route, counted as decode-failed.
        log = "\n".join(
            [
                log_line("hq-verify: 'Lemon Tang' no reference reason=no-wav"),
                log_line("decode: hq-verify failed at=download connection closed"),
            ]
        )
        result = statuses_with_songs(log, specs("hq1\thq"))[4]
        self.assertEqual("SKIP", result.status)
        self.assertIn("decode-failed=0", result.details[0])
        self.assertIn("download-network=1", result.details[0])

    def test_a_failure_for_an_unlisted_row_is_not_counted(self):
        # A song reached by seeking is not in the set's list: its refused
        # download must not fail a list whose hq row has no verdict at all.
        songs = specs("hq1\thq")
        log = "\n".join(
            [
                log_line(
                    "[F1ZyommhrVo] decode: hq-verify failed at=download HTTP 403"
                ),
                log_line("hq-verify: 'Lemon Tang' no reference reason=no-wav"),
            ]
        )
        checks = smokecheck.check_log(log, songs=songs, hq_configured=True)
        self.assertEqual("SKIP", {check.number: check for check in checks}[4].status)

    def test_a_listed_hq_song_rejected_with_an_id_still_fails(self):
        songs = specs("hq1\thq")
        log = log_line(
            "[hq1] hq-verify: Song Qobuz match=80% offset=0 -> rejected reference=wav"
        )
        self.assertEqual("FAIL", statuses_with_songs(log, songs)[4].status)

    def test_a_listed_hq_song_refused_download_with_an_id_still_fails(self):
        songs = specs("hq1\thq")
        for failure in ("at=download HTTP 403", "at=download bytes=0"):
            log = log_line(f"[hq1] decode: hq-verify failed {failure}")
            self.assertEqual(
                "FAIL", statuses_with_songs(log, songs)[4].status, failure
            )

    def test_a_refused_or_empty_download_still_fails(self):
        for failure in ("at=download HTTP 403", "at=download bytes=0"):
            log = log_line(f"decode: hq-verify failed {failure}")
            self.assertEqual(
                "FAIL", statuses_with_songs(log, specs("hq1\thq"))[4].status, failure
            )

    def test_a_search_hq_row_keeps_lines_that_carry_an_id(self):
        log = log_line("[abcdefghijk] decode: hq-verify failed at=download HTTP 403")
        result = statuses_with_songs(log, specs("search:Some Song\thq"))[4]
        self.assertEqual("FAIL", result.status)

    def test_no_wav_rows_are_skipped_with_a_song_list(self):
        songs = specs("hq1\thq")
        log = log_line("hq-verify: 'Lemon Tang' no reference reason=no-wav")
        self.assertEqual("SKIP", statuses_with_songs(log, songs)[4].status)

    def test_one_accepted_row_passes_even_when_others_were_not_measured(self):
        log = "\n".join(
            [
                log_line("hq-verify: 'Looks Can Kill' no reference reason=no-wav"),
                log_line(
                    "hq-verify: Song Qobuz match=95% offset=0 -> accepted reference=wav"
                ),
            ]
        )
        result = statuses_with_songs(log, specs("hq1\thq"))[4]
        self.assertEqual("PASS", result.status)
        self.assertIn("no-wav=1", result.details[0])

    def test_a_row_the_play_moved_past_is_not_measured(self):
        """11-06 auto46-d: six no-wav rows and one `cancelled` failed the run, yet
        nothing was compared: the track changed before the reference was read."""
        log = "\n".join(
            [
                log_line("hq-verify: 'Animals' no reference reason=no-wav"),
                log_line("hq-verify: 'Animals' no reference reason=cancelled"),
            ]
        )
        result = statuses_with_songs(log, specs("hq1\thq"))[4]
        self.assertEqual("SKIP", result.status)
        self.assertIn("cancelled=1", result.details[0])

    def test_a_rejected_row_still_fails_next_to_a_cancelled_row(self):
        log = "\n".join(
            [
                log_line("hq-verify: 'Animals' no reference reason=cancelled"),
                log_line(
                    "hq-verify: Song Qobuz match=80% offset=0 -> rejected reference=wav"
                ),
            ]
        )
        self.assertEqual("FAIL", statuses_with_songs(log, specs("hq1\thq"))[4].status)

    def test_a_rejected_row_still_fails_next_to_no_wav_rows(self):
        log = "\n".join(
            [
                log_line("hq-verify: 'Looks Can Kill' no reference reason=no-wav"),
                log_line(
                    "hq-verify: Song Qobuz match=80% offset=0 -> rejected reference=wav"
                ),
            ]
        )
        result = statuses_with_songs(log, specs("hq1\thq"))[4]
        self.assertEqual("FAIL", result.status)
        self.assertIn("no-wav=1", result.details[0])

    def test_a_decode_failure_still_fails_next_to_no_wav_rows(self):
        log = "\n".join(
            [
                log_line("hq-verify: 'Looks Can Kill' no reference reason=no-wav"),
                log_line("hq-verify: 'Lemon Tang' no reference reason=decode-failed"),
            ]
        )
        result = statuses_with_songs(log, specs("hq1\thq"))[4]
        self.assertEqual("FAIL", result.status)
        self.assertIn("decode-failed=1", result.details[0])

    def test_a_403_rejection_is_not_treated_as_unmeasured(self):
        log = log_line(
            "hq-verify: Song Qobuz match=0% offset=0 -> rejected reference=wav "
            "reason=403"
        )
        self.assertEqual("FAIL", statuses_with_songs(log, specs("hq1\thq"))[4].status)

    def test_no_reference_without_a_reason_still_fails(self):
        log = log_line("hq-verify: 'Song' no reference")
        self.assertEqual("FAIL", statuses_with_songs(log, specs("hq1\thq"))[4].status)


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
    def test_a_kept_version_is_not_an_mv(self):
        # fa49493c7: the lookup also runs for rows of unknown type and keeps a
        # song version that already is one, so a "kept" line alone is no
        # evidence of an MV (10-26: Burn the House Down, AJR - Topic, was
        # failed by it). A real MV still fails through its title or the
        # "no official song match" line, below.
        log = "\n".join(
            [
                log_line("song version: mv1 kept (none)"),
                log_line(
                    "next-prep: Song version=mv1 lyrics=none hq=none sync=skipped "
                    "mix=none in 120ms"
                ),
            ]
        )
        self.assertEqual("PASS", statuses(log)[6].status)

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


class SongListTest(unittest.TestCase):
    def test_tabs_kinds_playlists_and_search_rows_are_read(self):
        songs = smokecheck.parse_songs(
            "# comment\n"
            "-RJSbO8UZVY\tmv\n"
            "cover1\tcover\tPL123\n"
            "search:Espresso Sabrina Carpenter\tplain\tplay by search\n"
            "\n"
        )
        self.assertEqual(3, len(songs))
        self.assertEqual("-RJSbO8UZVY", songs[0].key)
        self.assertEqual("mv", songs[0].kind)
        self.assertEqual("cover1", songs[1].video_id)
        self.assertEqual("PL123", songs[1].playlist)
        self.assertIsNone(songs[2].video_id)
        self.assertEqual("Espresso Sabrina Carpenter", songs[2].search)


class KindCheckTest(unittest.TestCase):
    def test_mv_kind_requires_a_version_swap(self):
        songs = specs("mv1\tmv")
        result = statuses_with_songs(summary("mv1", "Song", True), songs)[1]
        self.assertEqual("FAIL", result.status)
        self.assertIn("mv1", result.details[0])

    def test_mv_kind_passes_with_a_version_swap(self):
        log = "\n".join(
            [
                log_line("song version: mv1 -> aud1 via search before-audio"),
                summary("mv1", "Song", True),
            ]
        )
        self.assertEqual(
            "PASS", statuses_with_songs(log, specs("mv1\tmv"))[1].status
        )

    def test_hq_kind_needs_one_accepted_verification(self):
        songs = specs("hq1\thq")
        log = log_line(
            "hq-verify: Song Qobuz match=95% offset=0 -> accepted reference=wav"
        )
        self.assertEqual("PASS", statuses_with_songs(log, songs)[4].status)

    def test_hq_kind_fails_when_only_rejections_exist(self):
        songs = specs("hq1\thq")
        log = log_line(
            "hq-verify: Song Qobuz match=80% offset=0 -> rejected reference=wav"
        )
        self.assertEqual("FAIL", statuses_with_songs(log, songs)[4].status)

    def test_hq_kind_skips_without_settings(self):
        songs = specs("hq1\thq")
        self.assertEqual("SKIP", statuses_with_songs("", songs)[4].status)

    def test_hq_kind_skips_when_the_import_was_not_confirmed(self):
        songs = specs("hq1\thq")
        log = log_line(
            "hq-verify: Song Qobuz match=95% offset=0 -> accepted reference=wav"
        )
        checks = smokecheck.check_log(log, songs=songs, hq_configured=False)
        result = {check.number: check for check in checks}[4]
        self.assertEqual("SKIP", result.status)

    def test_cover_kind_passes_when_it_plays_as_itself(self):
        songs = specs("cover1\tcover")
        log = "\n".join(
            [
                log_line("song version: cover1 -> none via none (cover)"),
                summary("cover1", "Dance Monkey", True),
            ]
        )
        self.assertEqual("PASS", statuses_with_songs(log, songs)[7].status)

    def test_cover_kind_fails_when_replaced_by_an_official_song(self):
        songs = specs("cover1\tcover")
        log = "\n".join(
            [
                log_line("song version: cover1 -> aud1 via search before-audio"),
                summary("cover1", "Dance Monkey", True),
            ]
        )
        result = statuses_with_songs(log, songs)[7]
        self.assertEqual("FAIL", result.status)
        self.assertIn("aud1", result.details[0])

    def test_cover_kind_fails_without_a_summary(self):
        songs = specs("cover1\tcover")
        self.assertEqual("FAIL", statuses_with_songs("", songs)[7].status)

    def test_cover_no_match_is_not_a_catalogue_failure(self):
        songs = specs("cover1\tcover")
        log = "\n".join(
            [
                play("cover1", "Dance Monkey", "오화요"),
                log_line(
                    "audio switch: no official song match for 'Dance Monkey' by '오화요'"
                ),
                summary("cover1", "Dance Monkey", True),
            ]
        )
        self.assertEqual("PASS", statuses_with_songs(log, songs)[2].status)

    def test_cover_next_prep_keeps_its_own_id(self):
        songs = specs("cover1\tcover")
        log = "\n".join(
            [
                log_line("song version: cover1 kept (none)"),
                summary("cover1", "Dance Monkey", True),
                log_line(
                    "next-prep: Dance Monkey version=cover1 lyrics=none hq=none "
                    "sync=skipped mix=none in 120ms"
                ),
            ]
        )
        self.assertEqual("PASS", statuses_with_songs(log, songs)[6].status)

    def test_plain_kind_has_no_mv_or_cover_requirement(self):
        songs = specs("search:Espresso Sabrina Carpenter\tplain")
        checks = statuses_with_songs("", songs)
        self.assertEqual("SKIP", checks[1].status)
        self.assertEqual("SKIP", checks[4].status)
        self.assertEqual("SKIP", checks[7].status)


class NoAudioRunTest(unittest.TestCase):
    """11-05 rounds 34-36: the proxy got YouTube's bot check, every track ended
    `firstAudio=- played=0`, run.sh stopped with NO AUDIO after three songs and
    the last track never got its summary. The judge blamed the cover row for it.
    A row nobody could measure is unmeasured; a row that did not play while the
    rest of the run had audio still fails."""

    NO_AUDIO_LOG = "\n".join(
        [
            log_line("song version: -RJSbO8UZVY kept (timeout)"),
            log_line(
                "track-summary: -RJSbO8UZVY 'Youngblood' video=y "
                "source=shared_link:-RJSbO8UZVY resolve=direct firstAudio=- played=0 "
                "lyrics=lrc.red sync=none shift=- match=- manualOffset=0 ended=skipped"
            ),
            play("7PgA6kyqVTc", "instagram", "DEAN · 오화요 커버"),
            log_line("song version: 7PgA6kyqVTc kept (none)"),
        ]
    )

    def test_a_run_without_audio_is_not_judged_as_a_cover_failure(self):
        songs = specs("7PgA6kyqVTc\tcover")
        result = statuses_with_songs(self.NO_AUDIO_LOG, songs)[7]
        self.assertEqual("SKIP", result.status)
        self.assertIn("오디오 없음", result.details[0])

    def test_a_run_without_audio_keeps_a_played_row_failing(self):
        songs = specs("7PgA6kyqVTc\tcover")
        log = "\n".join(
            [
                summary("other1", "Some Song", False),
                play("7PgA6kyqVTc", "instagram", "DEAN · 오화요 커버"),
                log_line("song version: 7PgA6kyqVTc kept (none)"),
            ]
        )
        result = statuses_with_songs(log, songs)[7]
        self.assertEqual("FAIL", result.status)
        self.assertIn("summary 없음", result.details[0])

    def test_an_mv_row_without_a_summary_is_unmeasured_without_audio(self):
        songs = specs("mv1\tmv")
        self.assertEqual(
            "SKIP", statuses_with_songs(self.NO_AUDIO_LOG, songs)[1].status
        )

    def test_an_mv_row_without_a_summary_still_fails_with_audio(self):
        songs = specs("mv1\tmv")
        log = "\n".join([summary("other1", "Some Song", False), play("mv1", "S", "A")])
        self.assertEqual("FAIL", statuses_with_songs(log, songs)[1].status)

    def test_summaries_without_audio_detects_the_shape(self):
        self.assertTrue(smokecheck.summaries_without_audio(self.NO_AUDIO_LOG))
        self.assertFalse(smokecheck.summaries_without_audio(summary("v1", "S", False)))
        self.assertFalse(smokecheck.summaries_without_audio(log_line("no summary")))

    def test_main_fails_with_the_no_audio_reason(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "logcat.txt"
            songs = Path(directory) / "songs.txt"
            report = Path(directory) / "report.md"
            log.write_text(self.NO_AUDIO_LOG + "\n", encoding="utf-8")
            songs.write_text("7PgA6kyqVTc\tcover\n", encoding="utf-8")
            stdout = io.StringIO()
            stderr = io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = smokecheck.main(
                    [str(log), "--songs", str(songs), "--report", str(report)]
                )
            self.assertEqual(1, code)
            self.assertIn("INVALID: no audio in the whole run", stderr.getvalue())
            self.assertIn(
                "INVALID: no audio in the whole run",
                report.read_text(encoding="utf-8"),
            )


class NothingJudgedTest(unittest.TestCase):
    """11-05: set-c/set-d had a swapped song list, every kind check skipped and
    the run still reported success."""

    def test_all_kind_checks_skipped_is_invalid(self):
        songs = smokecheck.parse_songs(
            "mv\t-RJSbO8UZVY\nhq\tTuzfXgUpboA\ncover\tvRjfYdwM5L4\tPL123\n"
        )
        checks = smokecheck.check_log(log_line("nothing to judge"), songs=songs)
        self.assertTrue(smokecheck.nothing_judged(checks, songs))

    def test_one_judged_kind_check_is_not_invalid(self):
        songs = specs("mv1\tmv")
        checks = smokecheck.check_log("", songs=songs)
        self.assertFalse(smokecheck.nothing_judged(checks, songs))

    def test_a_list_of_plain_songs_is_not_invalid(self):
        songs = specs("a1\tplain\nb2\tplain")
        checks = smokecheck.check_log("", songs=songs)
        self.assertFalse(smokecheck.nothing_judged(checks, songs))

    def test_no_song_list_is_not_invalid(self):
        checks = smokecheck.check_log("")
        self.assertFalse(smokecheck.nothing_judged(checks, None))

    def test_main_fails_and_says_invalid_when_nothing_could_be_judged(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "logcat.txt"
            songs = Path(directory) / "songs.txt"
            report = Path(directory) / "report.md"
            log.write_text(log_line("nothing to judge") + "\n", encoding="utf-8")
            songs.write_text("mv\t-RJSbO8UZVY\n", encoding="utf-8")
            stdout = io.StringIO()
            stderr = io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = smokecheck.main(
                    [str(log), "--songs", str(songs), "--report", str(report)]
                )
            self.assertEqual(1, code)
            self.assertIn("INVALID: nothing could be judged", stderr.getvalue())
            self.assertIn(
                "INVALID: nothing could be judged", report.read_text(encoding="utf-8")
            )

    def test_main_keeps_judging_a_partly_skipped_list(self):
        # An HQ row without settings skips on purpose; the mv row still decides.
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "logcat.txt"
            songs = Path(directory) / "songs.txt"
            report = Path(directory) / "report.md"
            log.write_text(
                "\n".join(
                    [
                        log_line("song version: mv1 -> aud1 via search before-audio"),
                        summary("mv1", "Song", True),
                    ]
                )
                + "\n",
                encoding="utf-8",
            )
            songs.write_text("mv1\tmv\nhq1\thq\n", encoding="utf-8")
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                code = smokecheck.main(
                    [str(log), "--songs", str(songs), "--report", str(report)]
                )
            self.assertEqual(0, code)
            self.assertIn("결과: **PASS", report.read_text(encoding="utf-8"))


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
            log.write_text(log_line("nothing to judge") + "\n", encoding="utf-8")
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                code = smokecheck.main([str(log)])
            self.assertEqual(0, code)

    def test_main_returns_two_for_an_empty_log(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "logcat.txt"
            log.write_text("", encoding="utf-8")
            stdout = io.StringIO()
            stderr = io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = smokecheck.main([str(log)])
            self.assertEqual(2, code)
            self.assertIn("비어", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
