#!/usr/bin/env python3
"""Read a BitChord diagnostic log and score the features that exist but may not
actually be working.

The input is the text file the app writes from Settings -> Export diagnostic
log. Its lines look like:

    2026-10-28 19:34:39.620 I/BitChord next-prep: ...

with a header carrying `version:` and `git:` before the first log line.

Standard library only. From the repository root:

    python3 scripts/featurehealth.py <log> [--since 'YYYY-MM-DD HH:MM'] [--md out.md]

The table has one row per measurement (D1..D11), each with attempts, successes,
the rate, the criterion and up to two representative failures. Nothing here is
app code: this only reads the text the app already wrote.
"""

from __future__ import annotations

import argparse
import re
import statistics
import sys
import unicodedata
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable

# ---------------------------------------------------------------------------
# Log lines
# ---------------------------------------------------------------------------

# The exported text format: `2026-10-28 19:34:39.620 I/BitChord <message>`.
EXPORT_LINE_RE = re.compile(
    r"^(?P<at>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+"
    r"(?P<level>[VDIWEF])/(?P<tag>[^ ]+)\s?(?P<text>.*)$"
)
# The same line as logcat prints it, accepted so adb dumps also work.
LOGCAT_LINE_RE = re.compile(
    r"^(?P<at>\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+\d+\s+\d+\s+"
    r"(?P<level>[VDIWEF])\s+(?P<tag>[^:]+):\s?(?P<text>.*)$"
)
HEADER_RE = re.compile(r"^(?P<key>[A-Za-z][A-Za-z0-9 _-]*):\s*(?P<value>.*)$")

NEXT_PREP_RE = re.compile(
    r"^next-prep:\s+(?P<title>.*?)\s+version=(?P<version>\S+)\s+"
    r"lyrics=(?P<lyrics>\S+)\s+hq=(?P<hq>\S+)\s+"
    r"sync=(?P<sync>applied|skipped(?:\([^)]*\))?)\s+"
    r"mix=(?P<mix>\S+)\s+in\s+(?P<ms>\d+)ms"
)
TRACK_SUMMARY_RE = re.compile(
    r"^track-summary:\s+(?P<id>\S+)\s+'(?P<title>.*)'\s+"
    r"video=(?P<video>[yn])\s+source=(?P<source>.*?)\s+"
    r"resolve=(?P<resolve>\S+)\s+firstAudio=(?P<firstAudio>[-\d]+)\s+"
    r"played=(?P<played>\d+)\s+lyrics=(?P<lyrics>\S+)\s+"
    r"sync=(?P<sync>\S+)\s+shift=(?P<shift>[-\d]+)\s+"
    r"match=(?P<match>[-\d]+)\s+manualOffset=(?P<manualOffset>-?\d+)\s+"
    r"ended=(?P<ended>\S+)"
)
PLAY_RE = re.compile(
    r"^play:\s*->\s+(?P<title>.*?)\s+-\s+(?P<artist>.*?)\s+"
    r"\((?P<id>[^)]*)\)\s+idx=\d+/\d+\s+reason=(?P<reason>\S+)"
)
HQ_VERIFY_RE = re.compile(
    r"^hq-verify:\s+(?P<title>.*?)\s+(?P<source>\S+)\s+"
    r"match=\d+%\s+offset=-?\d+\s+->\s+"
    r"(?P<verdict>accepted|rejected|unmeasured)\b"
)
UPGRADE_AHEAD_RE = re.compile(
    r"^upgrade ahead:\s+'(?P<title>.*)'\s+ready at\s+(?P<format>.*?)(?:\s+from\s+.*)?$"
)
HQ_SWAP_RE = re.compile(
    r"^hq-swap:\s+(?P<id>\S+)\s+(?P<state>start|done|skipped)"
    r"(?:\s+(?P<reason>.*))?$"
)
HQ_AHEAD_RE = re.compile(
    r"^hq-ahead:\s+(?P<id>\S+)\s+re-prepared next player as FLAC\b"
)
ALREADY_PLAYING_RE = re.compile(
    r"'(?P<title>.*)'\s+is already playing\s+(?P<mime>audio/\S+)\s+from cache"
)
DECODE_RE = re.compile(r"^(?P<id>\S+)\s+<-\s+(?P<mime>\S+)")
FORMAT_TAG_RE = re.compile(r"\[(?P<player>player-[ab]):(?P<fmt>[A-Za-z0-9_-]+)\]")
POWER_FORMAT_RE = re.compile(
    r"(?P<player>player-[ab]):[^=\s]*(?P<fmt>flac|opus)[^=\s]*=\d+", re.I
)

AUTOPLAY_RADIO_RE = re.compile(
    r"^autoplay:\s+\+radio\s+(?P<radio>\d+)\s+\+similar\s+(?P<similar>\d+)\s+"
    r"\+fav\s+(?P<fav>\d+)"
)
AUTOPLAY_DROP_RE = re.compile(
    r"^autoplay:\s+dropped\s+(?P<count>\d+)\s+cover/karaoke rows"
)
MODE_REPEAT_RE = re.compile(r"^mode:\s+repeat=(?P<mode>\S+)\s+\(was\s+")
VIDEO_RADIO_RE = re.compile(
    r"^video-radio:\s+seed=(?P<seed>\S+)\s+mode=(?P<mode>\S+)\s+"
    r"picked=(?P<picked>\d+)\s+from=(?P<from>\S+)\s+dropped=.*\s+fallback=(?P<fallback>\S+)"
)
AUTOPLAY_APPEND_RE = re.compile(
    r"^queue:\s+autoplay-append\s+n=(?P<n>\d+)"
)
UNPLAYABLE_RE = re.compile(
    r"^unplayable:\s+(?P<id>\S+)\s+(?P<reason>.+?)\s+->\s+"
    r"(?P<action>alt\s+(?P<alt>\S+)|skip)\s*$"
)

SONG_VERSION_SWAP_RE = re.compile(
    r"^song version:\s+(?P<src>\S+)\s+->\s+(?P<dst>\S+)\s+via\s+(?P<via>\S+)"
)
SONG_VERSION_KEPT_RE = re.compile(
    r"^song version:\s+(?P<id>\S+)\s+kept\s+\((?P<reason>[^)]*)\)"
)

CROSSFADE_START_RE = re.compile(
    r"^crossfade:\s+start\s+out=(?P<out>\S+)\s+in=(?P<in>\S+)"
    r"(?:\s+fade=(?P<fade>-?\d+)ms)?"
)
CROSSFADE_SKIP_RE = re.compile(
    r"^crossfade:\s+skipped\s+\S+->\S+\s+reason=(?P<reason>\S+)"
)
CROSSFADE_END_RE = re.compile(
    r"^crossfade:\s+end\s+out=(?P<out>\S+)\s+in=(?P<in>\S+)"
    r"(?:\s+fade=(?P<fade>-?\d+)ms)?.*?"
    r"overlap=(?P<overlap>-?\d+)ms"
)
CROSSFADE_GAIN_RE = re.compile(
    r"\b(?P<quarter>25|50|75)%=(?P<in>-?\d+(?:\.\d+)?)/(?P<out>-?\d+(?:\.\d+)?)"
)
CROSSFADE_ABORT_RE = re.compile(
    r"^crossfade:\s+abort\b"
    r"(?:\s+out=(?P<out>\S+)\s+in=(?P<in>\S+))?"
)

FAILED_START_RE = re.compile(r"failed to start after\s+(?P<attempts>\d+)\s+attempts")
DOWNLOAD_FAILED_RE = re.compile(r"download failed for\s+(?P<id>\S+?)(?::|\s|$)")
HQ_LIBRARY_MISSING_RE = re.compile(r"^hq-library:\s+(?P<id>\S+)\s+did not land")
SERVICE_START_FAILED = "could not start the download service"

PROCESS_START_RE = re.compile(r"^lifecycle:\s+process start\b")
FOREGROUND_RE = re.compile(r"^lifecycle:\s+foreground\b")
INNERTUBE_WARMED = "InnerTubeX warmed"
NETWORK_LINE_RE = re.compile(
    r"\b(network|connectivity|wifi|wi-fi|cellular|ethernet|vpn|online|offline)\b",
    re.IGNORECASE,
)

QUALITY_TRIGGER_RE = re.compile(
    r"manual upgrade unnecessary|upgrade reverted|falling back to YouTube audio|"
    r"\breverted\b",
    re.IGNORECASE,
)
COVER_TITLE_RE = re.compile(
    r"(?:\bcover\b|커버|\bclip\b|\bkaraoke\b|\btribute\b|\binstrumental\b|"
    r"\binst\b|\bmv\b|\bm/v\b)",
    re.IGNORECASE,
)

PASS_D1_SYNC = 0.80
PASS_D2_PLAYBACK = 0.90
FIRST_AUDIO_LIMIT_MS = 3000
LIFECYCLE_WINDOW_SECONDS = 10.0


@dataclass(frozen=True)
class LogLine:
    at: datetime
    level: str
    tag: str
    text: str

    @property
    def title(self) -> str:
        return self.text


@dataclass
class Log:
    header: dict[str, str]
    lines: list[LogLine]

    @property
    def version(self) -> str:
        return self.header.get("version", "unknown")

    @property
    def git(self) -> str:
        return self.header.get("git", "unknown")

    @property
    def period(self) -> tuple[datetime | None, datetime | None]:
        if not self.lines:
            return None, None
        return self.lines[0].at, self.lines[-1].at


@dataclass(frozen=True)
class Failure:
    at: datetime
    title: str
    detail: str = ""


@dataclass
class Metric:
    key: str
    label: str
    attempts: int
    successes: int | None
    criterion: str
    passed: bool | None = None
    failures: list[Failure] = field(default_factory=list)
    notes: str = ""

    @property
    def rate(self) -> float | None:
        if self.successes is None or self.attempts <= 0:
            return None
        return self.successes / self.attempts

    @property
    def verdict(self) -> str:
        if self.passed is None:
            return "—"
        return "PASS" if self.passed else "FAIL"


@dataclass
class Report:
    log: Log
    metrics: list[Metric]
    stats: dict[str, Any]

    @property
    def metrics_by_key(self) -> dict[str, Metric]:
        return {metric.key: metric for metric in self.metrics}

    @property
    def played_songs(self) -> int:
        return int(self.stats.get("played_songs", 0))

    @property
    def failures(self) -> list[Metric]:
        return [metric for metric in self.metrics if metric.passed is False]


# ---------------------------------------------------------------------------
# Reading
# ---------------------------------------------------------------------------


def parse_log_text(text: str, since: datetime | None = None) -> Log:
    header: dict[str, str] = {}
    lines: list[LogLine] = []
    for raw in text.splitlines():
        raw = raw.rstrip("\r")
        match = EXPORT_LINE_RE.match(raw)
        if match is None:
            match = LOGCAT_LINE_RE.match(raw)
            if match is not None:
                try:
                    at = datetime.strptime(
                        f"{datetime.now().year}-{match.group('at')}",
                        "%Y-%m-%d %H:%M:%S.%f",
                    )
                except ValueError:
                    continue
            else:
                if not lines:
                    header_match = HEADER_RE.match(raw)
                    if header_match is not None:
                        header[header_match.group("key").strip().lower()] = (
                            header_match.group("value").strip()
                        )
                continue
        else:
            at = datetime.strptime(match.group("at"), "%Y-%m-%d %H:%M:%S.%f")
        if since is not None and at < since:
            continue
        lines.append(
            LogLine(
                at=at,
                level=match.group("level"),
                tag=match.group("tag").strip(),
                text=match.group("text").strip(),
            )
        )
    return Log(header=header, lines=lines)


def parse_log_file(path: Path, since: datetime | None = None) -> Log:
    return parse_log_text(path.read_text(encoding="utf-8", errors="replace"), since)


# ---------------------------------------------------------------------------
# Small parsing helpers
# ---------------------------------------------------------------------------


def _norm_title(title: str) -> str:
    return " ".join(title.split()).casefold()


def _sync_skip_reason(sync: str) -> str:
    match = re.match(r"^skipped(?:\((?P<reason>[^)]*)\))?$", sync)
    if match is None:
        return sync
    return match.group("reason") or "skipped"


def _is_flac(value: str) -> bool:
    return "flac" in value.casefold()


def _is_opus(value: str) -> bool:
    return "opus" in value.casefold()


def _format_name(mime: str) -> str | None:
    lowered = mime.casefold()
    if "flac" in lowered:
        return "flac"
    if "opus" in lowered:
        return "opus"
    return None


def _cover_title(title: str) -> bool:
    return COVER_TITLE_RE.search(title) is not None


def _rate_metric(
    key: str,
    label: str,
    attempts: int,
    successes: int,
    criterion: str,
    threshold: float | None,
    failures: Iterable[Failure] = (),
    notes: str = "",
) -> Metric:
    passed: bool | None = None
    if threshold is not None and attempts > 0:
        passed = successes / attempts >= threshold
    return Metric(
        key=key,
        label=label,
        attempts=attempts,
        successes=successes,
        criterion=criterion,
        passed=passed,
        failures=list(failures),
        notes=notes,
    )


def _info_metric(
    key: str,
    label: str,
    attempts: int,
    criterion: str,
    successes: int | None = None,
    failures: Iterable[Failure] = (),
    notes: str = "",
) -> Metric:
    return Metric(
        key=key,
        label=label,
        attempts=attempts,
        successes=successes,
        criterion=criterion,
        passed=None,
        failures=list(failures),
        notes=notes,
    )


def _failure(at: datetime, title: str, detail: str = "") -> Failure:
    return Failure(at=at, title=title.strip() or "-", detail=detail)


# ---------------------------------------------------------------------------
# Analysis
# ---------------------------------------------------------------------------


def analyze(log: Log) -> Report:
    lines = log.lines

    next_preps: list[dict[str, Any]] = []
    summaries: list[dict[str, Any]] = []
    plays: list[dict[str, Any]] = []
    hq_verifies: list[dict[str, Any]] = []
    upgrade_aheads: list[dict[str, Any]] = []
    hq_swaps: list[dict[str, Any]] = []
    hq_aheads: list[dict[str, Any]] = []
    decodes: list[dict[str, Any]] = []
    already_playing: list[dict[str, Any]] = []
    autoplay_radios: list[dict[str, Any]] = []
    autoplay_drops: list[dict[str, Any]] = []
    autoplay_appends: list[dict[str, Any]] = []
    repeat_modes: list[dict[str, Any]] = []
    video_radios: list[dict[str, Any]] = []
    song_swaps: list[dict[str, Any]] = []
    song_kept: list[dict[str, Any]] = []
    crossfade_starts: list[dict[str, Any]] = []
    crossfade_ends: list[dict[str, Any]] = []
    crossfade_skips: list[dict[str, Any]] = []
    crossfade_aborts: list[dict[str, Any]] = []
    failed_starts: list[dict[str, Any]] = []
    unplayables: list[dict[str, Any]] = []
    download_failures: list[dict[str, Any]] = []
    hq_library_missing: list[dict[str, Any]] = []
    service_failures: list[LogLine] = []
    process_starts: list[LogLine] = []
    foregrounds: list[LogLine] = []
    bracket_formats: list[dict[str, Any]] = []
    title_by_id: dict[str, str] = {}

    for line in lines:
        text = line.text
        at = line.at

        match = NEXT_PREP_RE.match(text)
        if match:
            next_preps.append(
                {
                    "at": at,
                    "title": match.group("title"),
                    "version": match.group("version"),
                    "lyrics": match.group("lyrics"),
                    "hq": match.group("hq"),
                    "sync": match.group("sync"),
                    "mix": match.group("mix"),
                    "ms": int(match.group("ms")),
                }
            )
            continue

        match = TRACK_SUMMARY_RE.match(text)
        if match:
            summary = {
                "at": at,
                "id": match.group("id"),
                "title": match.group("title"),
                "video": match.group("video") == "y",
                "firstAudio": (
                    None
                    if match.group("firstAudio") == "-"
                    else int(match.group("firstAudio"))
                ),
                "played": int(match.group("played")),
                "lyrics": match.group("lyrics"),
                "sync": match.group("sync"),
                "ended": match.group("ended"),
            }
            summaries.append(summary)
            title_by_id[summary["id"]] = summary["title"]
            continue

        match = PLAY_RE.match(text)
        if match:
            play = {
                "at": at,
                "id": match.group("id").strip(),
                "title": match.group("title").strip(),
                "artist": match.group("artist").strip(),
                "reason": match.group("reason"),
            }
            plays.append(play)
            if play["id"]:
                title_by_id[play["id"]] = play["title"]
            for tag in FORMAT_TAG_RE.finditer(text):
                bracket_formats.append(
                    {
                        "at": at,
                        "id": play["id"],
                        "title": play["title"],
                        "player": tag.group("player"),
                        "format": tag.group("fmt").casefold(),
                    }
                )
            continue

        match = HQ_VERIFY_RE.match(text)
        if match:
            hq_verifies.append(
                {
                    "at": at,
                    "title": match.group("title"),
                    "source": match.group("source"),
                    "verdict": match.group("verdict"),
                }
            )
            continue

        match = UPGRADE_AHEAD_RE.match(text)
        if match:
            upgrade_aheads.append(
                {
                    "at": at,
                    "title": match.group("title"),
                    "format": match.group("format"),
                }
            )
            continue

        match = HQ_SWAP_RE.match(text)
        if match:
            hq_swaps.append(
                {
                    "at": at,
                    "id": match.group("id"),
                    "state": match.group("state"),
                    "reason": (match.group("reason") or "").strip(),
                }
            )
            continue

        match = HQ_AHEAD_RE.match(text)
        if match:
            hq_aheads.append({"at": at, "id": match.group("id")})
            continue

        match = ALREADY_PLAYING_RE.match(text)
        if match:
            already_playing.append(
                {
                    "at": at,
                    "title": match.group("title"),
                    "mime": match.group("mime"),
                }
            )
            continue

        if line.tag.upper() == "DECODE":
            match = DECODE_RE.match(text)
            if match:
                decodes.append(
                    {
                        "at": at,
                        "id": match.group("id"),
                        "mime": match.group("mime"),
                    }
                )
                continue

        match = AUTOPLAY_RADIO_RE.match(text)
        if match:
            autoplay_radios.append(
                {
                    "at": at,
                    "total": int(match.group("radio"))
                    + int(match.group("similar"))
                    + int(match.group("fav")),
                    "radio": int(match.group("radio")),
                    "similar": int(match.group("similar")),
                    "fav": int(match.group("fav")),
                }
            )
            continue

        match = AUTOPLAY_DROP_RE.match(text)
        if match:
            autoplay_drops.append({"at": at, "count": int(match.group("count"))})
            continue

        match = AUTOPLAY_APPEND_RE.match(text)
        if match:
            autoplay_appends.append({"at": at, "n": int(match.group("n"))})
            continue

        match = MODE_REPEAT_RE.match(text)
        if match:
            repeat_modes.append({"at": at, "mode": match.group("mode").casefold()})
            continue

        match = VIDEO_RADIO_RE.match(text)
        if match:
            video_radios.append(
                {
                    "at": at,
                    "seed": match.group("seed"),
                    "mode": match.group("mode").casefold(),
                    "picked": int(match.group("picked")),
                    "from": match.group("from"),
                    "fallback": match.group("fallback"),
                }
            )
            continue

        match = SONG_VERSION_SWAP_RE.match(text)
        if match:
            song_swaps.append(
                {
                    "at": at,
                    "src": match.group("src"),
                    "dst": match.group("dst"),
                    "via": match.group("via"),
                }
            )
            continue

        match = SONG_VERSION_KEPT_RE.match(text)
        if match:
            song_kept.append(
                {
                    "at": at,
                    "id": match.group("id"),
                    "reason": match.group("reason").casefold(),
                }
            )
            continue

        match = CROSSFADE_START_RE.match(text)
        if match:
            crossfade_starts.append(
                {
                    "at": at,
                    "out": match.group("out"),
                    "in": match.group("in"),
                    "fade": (
                        int(match.group("fade"))
                        if match.group("fade") is not None
                        else None
                    ),
                }
            )
            for tag in FORMAT_TAG_RE.finditer(text):
                bracket_formats.append(
                    {
                        "at": at,
                        "id": match.group("in"),
                        "title": title_by_id.get(match.group("in"), ""),
                        "player": tag.group("player"),
                        "format": tag.group("fmt").casefold(),
                    }
                )
            continue

        match = CROSSFADE_SKIP_RE.match(text)
        if match:
            crossfade_skips.append({"at": at, "reason": match.group("reason")})
            continue

        match = CROSSFADE_END_RE.match(text)
        if match:
            gains = {
                gain.group("quarter"): {
                    "in": float(gain.group("in")),
                    "out": float(gain.group("out")),
                }
                for gain in CROSSFADE_GAIN_RE.finditer(text)
            }
            crossfade_ends.append(
                {
                    "at": at,
                    "out": match.group("out"),
                    "in": match.group("in"),
                    "overlap": int(match.group("overlap")),
                    "fade": (
                        int(match.group("fade"))
                        if match.group("fade") is not None
                        else None
                    ),
                    "gains": gains,
                }
            )
            for tag in FORMAT_TAG_RE.finditer(text):
                bracket_formats.append(
                    {
                        "at": at,
                        "id": match.group("in"),
                        "title": title_by_id.get(match.group("in"), ""),
                        "player": tag.group("player"),
                        "format": tag.group("fmt").casefold(),
                    }
                )
            continue

        match = CROSSFADE_ABORT_RE.match(text)
        if match:
            crossfade_aborts.append(
                {
                    "at": at,
                    "out": match.group("out"),
                    "in": match.group("in"),
                }
            )
            continue

        match = UNPLAYABLE_RE.match(text)
        if match:
            action = match.group("action")
            unplayables.append(
                {
                    "at": at,
                    "id": match.group("id"),
                    "reason": match.group("reason").strip(),
                    "action": "alt" if action.startswith("alt") else "skip",
                    "alt": match.group("alt"),
                }
            )
            continue

        if FAILED_START_RE.search(text):
            failed_starts.append({"at": at, "text": text})
            continue

        match = DOWNLOAD_FAILED_RE.search(text)
        if match:
            download_failures.append({"at": at, "id": match.group("id")})
            continue

        match = HQ_LIBRARY_MISSING_RE.match(text)
        if match:
            hq_library_missing.append({"at": at, "id": match.group("id")})
            continue

        if SERVICE_START_FAILED in text:
            service_failures.append(line)
            continue

        if PROCESS_START_RE.match(text):
            process_starts.append(line)
            continue

        if FOREGROUND_RE.match(text):
            foregrounds.append(line)
            continue

        for tag in FORMAT_TAG_RE.finditer(text):
            bracket_formats.append(
                {
                    "at": at,
                    "id": None,
                    "title": "",
                    "player": tag.group("player"),
                    "format": tag.group("fmt").casefold(),
                }
            )

    metrics: list[Metric] = []
    stats: dict[str, Any] = {}

    # ---- D1: next track preparation -------------------------------------
    d1_total = len(next_preps)
    d1_lyrics = sum(1 for row in next_preps if row["lyrics"] != "none")
    d1_hq = sum(1 for row in next_preps if row["hq"] != "none")
    d1_mix = sum(1 for row in next_preps if row["mix"] == "ready")
    d1_with_lyrics = [row for row in next_preps if row["lyrics"] != "none"]
    d1_sync = sum(1 for row in d1_with_lyrics if row["sync"].startswith("applied"))
    d1_sync_skips = [
        row for row in next_preps if not row["sync"].startswith("applied")
    ]
    d1_sync_skip_reasons = Counter(_sync_skip_reason(row["sync"]) for row in d1_sync_skips)
    d1_sync_failures = [
        _failure(row["at"], row["title"], f"sync={row['sync']}")
        for row in d1_sync_skips
    ]
    d1_ms = [row["ms"] for row in next_preps]
    d1_avg = round(statistics.mean(d1_ms)) if d1_ms else None
    d1_max = max(d1_ms) if d1_ms else None
    d1_skipped_ratio = (
        (len(d1_with_lyrics) - d1_sync) / len(d1_with_lyrics)
        if d1_with_lyrics
        else None
    )
    metrics.extend(
        [
            _info_metric(
                "D1-lyrics",
                "D1 다음 곡 준비 · 가사",
                d1_total,
                "가사 제공률(참고)",
                successes=d1_lyrics,
            ),
            _info_metric(
                "D1-hq",
                "D1 다음 곡 준비 · HQ",
                d1_total,
                "HQ 탐색률(참고)",
                successes=d1_hq,
            ),
            _rate_metric(
                "D1-sync",
                "D1 다음 곡 준비 · 가사 싱크",
                len(d1_with_lyrics),
                d1_sync,
                "가사 있는 곡 sync applied ≥80%"
                + (
                    f" (skipped {d1_skipped_ratio:.0%})"
                    if d1_skipped_ratio is not None
                    else ""
                )
                + (
                    " · 사유 "
                    + ", ".join(
                        f"{name}={count}"
                        for name, count in sorted(d1_sync_skip_reasons.items())
                    )
                    if d1_sync_skip_reasons
                    else ""
                ),
                PASS_D1_SYNC,
                d1_sync_failures,
            ),
            _info_metric(
                "D1-sync-skip",
                "D1 다음 곡 준비 · 싱크 건너뜀 사유",
                len(d1_sync_skips),
                "skipped 사유 분포 "
                + (
                    ", ".join(
                        f"{name}={count}"
                        for name, count in sorted(d1_sync_skip_reasons.items())
                    )
                    or "없음"
                ),
                failures=d1_sync_failures,
            ),
            _info_metric(
                "D1-mix",
                "D1 다음 곡 준비 · 믹스",
                d1_total,
                "믹스 준비율(참고)",
                successes=d1_mix,
            ),
            _info_metric(
                "D1-time",
                "D1 다음 곡 준비 · 소요 시간",
                d1_total,
                f"평균 {d1_avg if d1_avg is not None else '—'}ms · "
                f"최대 {d1_max if d1_max is not None else '—'}ms",
            ),
        ]
    )
    stats["next_prep"] = {
        "total": d1_total,
        "lyrics": d1_lyrics,
        "hq": d1_hq,
        "sync": d1_sync,
        "sync_skipped": len(d1_sync_skips),
        "sync_skip_reasons": dict(d1_sync_skip_reasons),
        "with_lyrics": len(d1_with_lyrics),
        "mix": d1_mix,
        "avg_ms": d1_avg,
        "max_ms": d1_max,
    }

    # ---- D2: lossless actually played -----------------------------------
    title_to_ids: dict[str, set[str]] = defaultdict(set)
    for video_id, title in title_by_id.items():
        title_to_ids[_norm_title(title)].add(video_id)

    flac_evidence: list[dict[str, Any]] = []
    for row in decodes:
        if _is_flac(row["mime"]):
            video_id = row["id"]
            flac_evidence.append(
                {
                    "at": row["at"],
                    "id": video_id,
                    "title": title_by_id.get(video_id, ""),
                }
            )
    for row in hq_swaps:
        if row["state"] != "done":
            continue
        flac_evidence.append(
            {
                "at": row["at"],
                "id": row["id"],
                "title": title_by_id.get(row["id"], ""),
            }
        )
    for row in hq_aheads:
        flac_evidence.append(
            {
                "at": row["at"],
                "id": row["id"],
                "title": title_by_id.get(row["id"], ""),
            }
        )
    for row in already_playing:
        if _is_flac(row["mime"]):
            flac_evidence.append(
                {"at": row["at"], "id": None, "title": row["title"]}
            )
    for row in bracket_formats:
        if _is_flac(row["format"]):
            flac_evidence.append(
                {
                    "at": row["at"],
                    "id": row["id"],
                    "title": row["title"] or title_by_id.get(row["id"] or "", ""),
                }
            )

    d2_events: list[dict[str, Any]] = []
    for row in hq_verifies:
        if row["verdict"] == "accepted":
            d2_events.append(
                {"at": row["at"], "title": row["title"], "kind": "hq-verify"}
            )
    for row in upgrade_aheads:
        if _is_flac(row["format"]):
            d2_events.append(
                {"at": row["at"], "title": row["title"], "kind": "upgrade-ahead"}
            )

    d2_success = 0
    d2_failures: list[Failure] = []
    for event in d2_events:
        wanted = _norm_title(event["title"])
        ids = title_to_ids.get(wanted, set())
        played = any(
            evidence["at"] >= event["at"]
            and (
                _norm_title(evidence["title"]) == wanted
                or (evidence["id"] is not None and evidence["id"] in ids)
            )
            for evidence in flac_evidence
        )
        if played:
            d2_success += 1
        else:
            d2_failures.append(_failure(event["at"], event["title"], event["kind"]))

    kept_for_next = sum(
        1 for line in lines if "kept for next play; no mid-song swap" in line.text
    )
    manual_unnecessary = sum(
        1 for line in lines if "manual upgrade unnecessary" in line.text
    )
    swap_started = sum(1 for row in hq_swaps if row["state"] == "start")
    swap_done = sum(1 for row in hq_swaps if row["state"] == "done")
    swap_skipped = [row for row in hq_swaps if row["state"] == "skipped"]
    swap_skip_reasons = Counter(row["reason"] or "unknown" for row in swap_skipped)
    metrics.extend(
        [
            _rate_metric(
                "D2-playback",
                "D2 무손실 · 실제 FLAC 재생",
                len(d2_events),
                d2_success,
                "무손실 실제 재생 ≥90%",
                PASS_D2_PLAYBACK,
                d2_failures,
            ),
            _info_metric(
                "D2-kept",
                "D2 무손실 · 다음 재생 보류",
                kept_for_next,
                "kept for next play; no mid-song swap",
            ),
            _info_metric(
                "D2-manual",
                "D2 무손실 · 수동 업그레이드 불필요",
                manual_unnecessary,
                "manual upgrade unnecessary",
            ),
            _info_metric(
                "D2-swap",
                "D2 무손실 · 재생 중 교체",
                swap_started + len(swap_skipped),
                f"start {swap_started} · done {swap_done} · "
                f"skipped {len(swap_skipped)}"
                + (
                    " (사유 "
                    + ", ".join(
                        f"{name}={count}"
                        for name, count in sorted(swap_skip_reasons.items())
                    )
                    + ")"
                    if swap_skip_reasons
                    else ""
                ),
                successes=swap_done,
                failures=[
                    _failure(row["at"], row["id"], row["reason"] or "unknown")
                    for row in swap_skipped
                ],
            ),
        ]
    )
    stats["lossless"] = {
        "events": len(d2_events),
        "played": d2_success,
        "kept_for_next": kept_for_next,
        "manual_unnecessary": manual_unnecessary,
        "swap_started": swap_started,
        "swap_done": swap_done,
        "swap_skipped": len(swap_skipped),
        "swap_skip_reasons": dict(swap_skip_reasons),
        "hq_ahead": len(hq_aheads),
    }

    # ---- D3: autoplay refill and unwanted repeat -------------------------
    zero_refills: list[dict[str, Any]] = []
    for drop in autoplay_drops:
        refilled = any(
            0 <= (radio["at"] - drop["at"]).total_seconds() < 1.0
            for radio in autoplay_radios
        )
        if not refilled:
            zero_refills.append(drop)

    d3_append_success = sum(1 for row in autoplay_appends if row["n"] > 0)
    # A video-radio pass that falls back to the ordinary path logs the same
    # refill twice: the video line (fallback=normal) and the +radio/append that
    # actually filled the queue. Defer to the latter, and judge the rest by the
    # fallback stage: empty queued nothing, while none/same-mode/partial are
    # real video batches.
    d3_video_success_rows = [
        row for row in video_radios if row["fallback"] not in ("empty", "normal")
    ]
    d3_video_failure_rows = [
        row for row in video_radios if row["fallback"] == "empty"
    ]
    d3_video_deferred = [
        row for row in video_radios if row["fallback"] == "normal"
    ]
    d3_video_success = len(d3_video_success_rows)
    d3_attempts = (
        len(autoplay_radios)
        + len(zero_refills)
        + len(autoplay_appends)
        + d3_video_success
        + len(d3_video_failure_rows)
    )
    d3_success = (
        sum(1 for row in autoplay_radios if row["total"] > 0)
        + d3_append_success
        + d3_video_success
    )
    d3_failures = [
        _failure(row["at"], "autoplay", f"{row['count']} rows dropped, no +radio")
        for row in zero_refills
    ]
    d3_failures += [
        _failure(row["at"], "autoplay", "+radio 0 +similar 0 +fav 0")
        for row in autoplay_radios
        if row["total"] <= 0
    ]
    d3_failures += [
        _failure(row["at"], "autoplay", "queue: autoplay-append n=0")
        for row in autoplay_appends
        if row["n"] <= 0
    ]
    d3_failures += [
        _failure(
            row["at"],
            row["seed"],
            f"video-radio picked={row['picked']} from={row['from']} fallback=empty",
        )
        for row in d3_video_failure_rows
    ]
    d3_pass = None if d3_attempts == 0 else d3_success == d3_attempts

    repeat_plays: list[dict[str, Any]] = []
    last_mode: str | None = None
    mode_index = 0
    for line in lines:
        if mode_index < len(repeat_modes) and line.at == repeat_modes[mode_index]["at"]:
            last_mode = repeat_modes[mode_index]["mode"]
            mode_index += 1
        match = PLAY_RE.match(line.text)
        if match and match.group("reason") == "REPEAT":
            repeat_plays.append({"at": line.at, "title": match.group("title"), "mode": last_mode})
    unwanted_repeats = [row for row in repeat_plays if row["mode"] == "off"]
    d3_repeat_success = len(repeat_plays) - len(unwanted_repeats)
    d3_repeat_pass = None if not repeat_plays else not unwanted_repeats

    metrics.extend(
        [
            Metric(
                key="D3-refill",
                label="D3 자동 재생 · 보충",
                attempts=d3_attempts,
                successes=d3_success,
                criterion="보충 합>0 · append n>0 · video-radio fallback=empty 0건 · 0곡 보충 0건",
                passed=d3_pass,
                failures=d3_failures,
            ),
            Metric(
                key="D3-repeat",
                label="D3 자동 재생 · 원치 않는 반복",
                attempts=len(repeat_plays),
                successes=d3_repeat_success,
                criterion="repeat=off 상태 reason=REPEAT 0건",
                passed=d3_repeat_pass,
                failures=[
                    _failure(row["at"], row["title"], "repeat=off + reason=REPEAT")
                    for row in unwanted_repeats
                ],
            ),
        ]
    )
    stats["autoplay"] = {
        "zero_refill": len(zero_refills),
        "radio_lines": len(autoplay_radios),
        "radio_sums": [row["total"] for row in autoplay_radios],
        "append": len(autoplay_appends),
        "append_n": [row["n"] for row in autoplay_appends],
        "video_lines": len(video_radios),
        "video_refills": d3_video_success + len(d3_video_failure_rows),
        "video_deferred": len(d3_video_deferred),
        "video_fallback": dict(Counter(row["fallback"] for row in video_radios)),
        "repeat": len(repeat_plays),
        "unwanted_repeat": len(unwanted_repeats),
    }

    # ---- D4: video radio ------------------------------------------------
    video_mode = [row for row in video_radios if row["mode"] == "video"]
    from_counts = Counter(row["from"] for row in video_mode)
    d4_success = sum(1 for row in video_mode if row["picked"] > 0)
    mode_details: dict[str, dict[str, Any]] = {}
    for mode in sorted({row["mode"] for row in video_radios}):
        rows = [row for row in video_radios if row["mode"] == mode]
        mode_details[mode] = {
            "lines": len(rows),
            "picked": [row["picked"] for row in rows],
            "from": dict(Counter(row["from"] for row in rows)),
        }
    mode_criterion = "모드별 picked/from " + (
        " · ".join(
            f"{mode}: picked={sum(detail['picked'])} "
            f"from="
            + ",".join(
                f"{name}={count}"
                for name, count in sorted(detail["from"].items())
            )
            for mode, detail in mode_details.items()
        )
        or "없음"
    )
    metrics.extend(
        [
            _info_metric(
                "D4-video",
                "D4 비디오 라디오",
                len(video_mode),
                "picked/from 분포 "
                + (", ".join(f"{name}={count}" for name, count in sorted(from_counts.items())) or "없음"),
                successes=d4_success,
                failures=[
                    _failure(row["at"], row["seed"], f"picked=0 from={row['from']}")
                    for row in video_mode
                    if row["picked"] <= 0
                ],
            ),
            _info_metric(
                "D4-modes",
                "D4 비디오 라디오 · 모드별",
                len(video_radios),
                mode_criterion,
                successes=sum(1 for row in video_radios if row["picked"] > 0),
                failures=[
                    _failure(row["at"], row["seed"], f"mode={row['mode']} picked=0")
                    for row in video_radios
                    if row["picked"] <= 0
                ],
            ),
        ]
    )
    stats["video_radio"] = {
        "video_lines": len(video_mode),
        "from": dict(from_counts),
        "picked": [row["picked"] for row in video_mode],
        "modes": mode_details,
    }

    # ---- D5: song versions ---------------------------------------------
    d5_video = sum(1 for row in summaries if row["video"])
    cover_summaries = [row for row in summaries if _cover_title(row["title"])]
    d5_cover_video = sum(1 for row in cover_summaries if row["video"])
    kept_none = [row for row in song_kept if row["reason"] == "none" or row["reason"].endswith(", none")]
    kept_timeout = [
        row for row in song_kept if row["reason"] == "timeout" or row["reason"].endswith(", timeout")
    ]
    d5_version_attempts = len(song_swaps) + len(song_kept)
    metrics.extend(
        [
            _info_metric(
                "D5-video-ratio",
                "D5 노래 버전 · 비디오 비율",
                len(summaries),
                "video=y 비율(참고)",
                successes=d5_video,
            ),
            _info_metric(
                "D5-cover-video",
                "D5 노래 버전 · 커버/클립 비디오",
                len(cover_summaries),
                "커버/클립 제목 중 video=y(참고)",
                successes=d5_cover_video,
            ),
            _info_metric(
                "D5-version",
                "D5 노래 버전 · 교체",
                d5_version_attempts,
                f"교체 {len(song_swaps)} · kept(none) {len(kept_none)} · "
                f"kept(timeout) {len(kept_timeout)}",
                successes=len(song_swaps),
            ),
            _info_metric(
                "D5-kept-none",
                "D5 노래 버전 · kept(none)",
                len(kept_none),
                "kept (none) 건수(참고)",
                failures=[_failure(row["at"], row["id"], row["reason"]) for row in kept_none],
            ),
            _info_metric(
                "D5-kept-timeout",
                "D5 노래 버전 · kept(timeout)",
                len(kept_timeout),
                "kept (timeout) 건수(참고)",
                failures=[_failure(row["at"], row["id"], row["reason"]) for row in kept_timeout],
            ),
        ]
    )
    stats["song_version"] = {
        "summaries": len(summaries),
        "video": d5_video,
        "cover_summaries": len(cover_summaries),
        "cover_video": d5_cover_video,
        "swap": len(song_swaps),
        "kept_none": len(kept_none),
        "kept_timeout": len(kept_timeout),
    }

    # ---- D6: lyrics and sync -------------------------------------------
    with_lyrics = [row for row in summaries if row["lyrics"] != "none"]
    with_sync = [row for row in with_lyrics if row["sync"] != "none"]
    metrics.extend(
        [
            _info_metric(
                "D6-lyrics",
                "D6 가사 · 제공 비율",
                len(summaries),
                "track-summary lyrics!=none(참고)",
                successes=len(with_lyrics),
            ),
            _info_metric(
                "D6-sync",
                "D6 가사 · 싱크 비율",
                len(with_lyrics),
                "가사 있는 곡 sync!=none(참고)",
                successes=len(with_sync),
            ),
        ]
    )
    stats["lyrics"] = {
        "summaries": len(summaries),
        "with_lyrics": len(with_lyrics),
        "with_sync": len(with_sync),
    }

    # ---- D7: crossfade --------------------------------------------------
    skip_reasons = Counter(row["reason"] for row in crossfade_skips)
    overlaps = [row["overlap"] for row in crossfade_ends]
    remaining_ends = Counter((row["out"], row["in"]) for row in crossfade_ends)
    completed_fades = 0
    for row in crossfade_starts:
        key = (row["out"], row["in"])
        if remaining_ends[key] > 0:
            completed_fades += 1
            remaining_ends[key] -= 1
    completion_rate = (
        completed_fades / len(crossfade_starts) if crossfade_starts else None
    )
    # Pair each end with the preceding unmatched start for the same pair so a
    # finished a->b fade is never compared with an earlier aborted a->b fade.
    # An abort discards the most recent unmatched start it interrupted.
    planned_by_pair: dict[tuple[str, str], list[int | None]] = defaultdict(list)
    fade_events: list[tuple[datetime, int, dict[str, Any]]] = []
    fade_events.extend(
        (row["at"], 0, row) for row in crossfade_starts
    )
    fade_events.extend(
        (row["at"], 1, row) for row in crossfade_aborts
    )
    fade_events.extend(
        (row["at"], 2, row) for row in crossfade_ends
    )
    fade_events.sort(key=lambda event: (event[0], event[1]))
    overlap_pairs: list[tuple[int, int]] = []
    for _, kind, row in fade_events:
        key = (row["out"], row["in"])
        if kind == 0:
            planned_by_pair[key].append(row["fade"])
        elif kind == 1:
            pending = planned_by_pair.get(key)
            if pending:
                pending.pop()
        else:
            planned = None
            pending = planned_by_pair.get(key)
            if pending:
                planned = pending.pop(0)
            if planned is None:
                planned = row["fade"]
            if planned is not None and planned > 0:
                overlap_pairs.append((row["overlap"], planned))
    planned_fades = [planned for _, planned in overlap_pairs]
    overlap_ratios = [overlap / planned for overlap, planned in overlap_pairs]
    overlap_avg = round(statistics.mean(overlaps)) if overlaps else None
    planned_avg = (
        round(statistics.mean(planned_fades)) if planned_fades else None
    )
    ratio_avg = round(statistics.mean(overlap_ratios) * 100) if overlap_ratios else None
    gain_quarters = Counter(
        quarter for row in crossfade_ends for quarter in row["gains"]
    )
    metrics.extend(
        [
            _info_metric(
                "D7-crossfade",
                "D7 자동 믹스 · 시작/완료",
                len(crossfade_starts),
                f"start {len(crossfade_starts)} · end {len(crossfade_ends)} · "
                f"overlap 평균 {overlap_avg if overlap_avg is not None else '—'}ms",
                successes=completed_fades,
            ),
            _info_metric(
                "D7-complete",
                "D7 자동 믹스 · 완료율",
                len(crossfade_starts),
                f"완료 {completed_fades}/{len(crossfade_starts)}"
                + (
                    f" ({completion_rate:.0%})"
                    if completion_rate is not None
                    else ""
                ),
                successes=completed_fades,
            ),
            _info_metric(
                "D7-overlap",
                "D7 자동 믹스 · 실제 겹침",
                len(crossfade_ends),
                f"overlap 평균 {overlap_avg if overlap_avg is not None else '—'}ms · "
                f"계획 fade 평균 {planned_avg if planned_avg is not None else '—'}ms · "
                f"계획 대비 {ratio_avg if ratio_avg is not None else '—'}%",
            ),
            _info_metric(
                "D7-gains",
                "D7 자동 믹스 · 25/50/75 gain",
                sum(gain_quarters.values()),
                "gain 분포 "
                + (
                    ", ".join(
                        f"{quarter}%={gain_quarters[quarter]}"
                        for quarter in ("25", "50", "75")
                    )
                    or "없음"
                ),
            ),
            _info_metric(
                "D7-skipped",
                "D7 자동 믹스 · 건너뜀",
                len(crossfade_skips),
                "reason 분포 "
                + (", ".join(f"{name}={count}" for name, count in sorted(skip_reasons.items())) or "없음"),
            ),
            Metric(
                key="D7-abort",
                label="D7 자동 믹스 · 중단",
                attempts=len(crossfade_aborts),
                successes=0 if crossfade_aborts else None,
                criterion="crossfade abort 0건",
                passed=None if not crossfade_aborts else False,
                failures=[_failure(row["at"], "crossfade abort") for row in crossfade_aborts],
            ),
        ]
    )
    stats["crossfade"] = {
        "start": len(crossfade_starts),
        "end": len(crossfade_ends),
        "complete": completed_fades,
        "completion_rate": completion_rate,
        "skipped": len(crossfade_skips),
        "skip_reasons": dict(skip_reasons),
        "abort": len(crossfade_aborts),
        "overlaps": overlaps,
        "planned_fades": planned_fades,
        "overlap_ratios": overlap_ratios,
        "gain_quarters": dict(gain_quarters),
    }

    # ---- D8: playback start --------------------------------------------
    first_audio_values = [
        row["firstAudio"] for row in summaries if row["firstAudio"] is not None
    ]
    first_audio_median = (
        round(statistics.median(first_audio_values)) if first_audio_values else None
    )
    over_3s = sum(1 for value in first_audio_values if value > FIRST_AUDIO_LIMIT_MS)
    ended_errors = [row for row in summaries if row["ended"] == "error"]
    d8_errors = len(ended_errors)
    d8_error_success = len(summaries) - d8_errors
    failed_start_rows: list[dict[str, Any]] = []
    for failure in failed_starts:
        previous = None
        following = None
        for play in plays:
            if play["at"] <= failure["at"]:
                previous = play
            elif following is None:
                following = play
        gap = (
            (following["at"] - previous["at"]).total_seconds()
            if previous is not None and following is not None
            else None
        )
        failed_start_rows.append(
            {
                "at": failure["at"],
                "title": previous["title"] if previous is not None else failure["text"],
                "gap": gap,
            }
        )
    gaps = [row["gap"] for row in failed_start_rows if row["gap"] is not None]
    unplayable_rows: list[dict[str, Any]] = []
    for row in unplayables:
        following = next((play for play in plays if play["at"] >= row["at"]), None)
        unplayable_rows.append(
            {
                "at": row["at"],
                "id": row["id"],
                "reason": row["reason"],
                "action": row["action"],
                "alt": row["alt"],
                "title": title_by_id.get(row["id"], row["id"]),
                "gap": (
                    (following["at"] - row["at"]).total_seconds()
                    if following is not None
                    else None
                ),
            }
        )
    unplayable_alt = [row for row in unplayable_rows if row["action"] == "alt"]
    unplayable_skip = [row for row in unplayable_rows if row["action"] == "skip"]
    unplayable_gaps = [
        row["gap"] for row in unplayable_rows if row["gap"] is not None
    ]
    metrics.extend(
        [
            _info_metric(
                "D8-firstaudio",
                "D8 재생 시작 · 첫 소리",
                len(first_audio_values),
                f"중앙값 {first_audio_median if first_audio_median is not None else '—'}ms · "
                f"3초 초과 {(over_3s / len(first_audio_values)):.0%}"
                if first_audio_values
                else "firstAudio 값 없음",
                successes=len(first_audio_values) - over_3s,
            ),
            Metric(
                key="D8-error",
                label="D8 재생 시작 · ended=error",
                attempts=len(summaries),
                successes=d8_error_success if summaries else None,
                criterion="ended=error 0건",
                passed=None if not summaries else d8_errors == 0,
                failures=[_failure(row["at"], row["title"]) for row in ended_errors],
            ),
            Metric(
                key="D8-failed-start",
                label="D8 재생 시작 · 시작 실패",
                attempts=len(failed_start_rows),
                successes=0 if failed_start_rows else None,
                criterion="failed to start 0건",
                passed=None if not failed_start_rows else False,
                failures=[
                    _failure(
                        row["at"],
                        row["title"],
                        f"다음 play까지 {row['gap']:.1f}s" if row["gap"] is not None else "",
                    )
                    for row in failed_start_rows
                ],
                notes=(
                    f"평균 간격 {statistics.mean(gaps):.1f}s"
                    if gaps
                    else ""
                ),
            ),
            _info_metric(
                "D8-unplayable",
                "D8 재생 시작 · 재생 불가 대체",
                len(unplayable_rows),
                f"대체 성공 {len(unplayable_alt)} · 건너뜀 {len(unplayable_skip)}"
                + (
                    f" · 판정→다음 동작 평균 {statistics.mean(unplayable_gaps):.1f}s"
                    if unplayable_gaps
                    else ""
                ),
                successes=len(unplayable_alt),
                failures=[
                    _failure(
                        row["at"],
                        row["title"],
                        f"{row['reason']} -> skip"
                        + (
                            f" · 다음 play까지 {row['gap']:.1f}s"
                            if row["gap"] is not None
                            else ""
                        ),
                    )
                    for row in unplayable_skip
                ],
            ),
        ]
    )
    stats["first_audio"] = {
        "values": first_audio_values,
        "median_ms": first_audio_median,
        "over_3s": over_3s,
        "errors": d8_errors,
        "failed_start": len(failed_start_rows),
        "gaps": gaps,
        "unplayable": len(unplayable_rows),
        "unplayable_alt": len(unplayable_alt),
        "unplayable_skip": len(unplayable_skip),
        "unplayable_gaps": unplayable_gaps,
    }

    # ---- D9: downloads --------------------------------------------------
    failed_ids = Counter(row["id"] for row in download_failures)
    missing_ids = Counter(row["id"] for row in hq_library_missing)
    metrics.extend(
        [
            Metric(
                key="D9-download",
                label="D9 다운로드 · 실패",
                attempts=len(download_failures),
                successes=0 if download_failures else None,
                criterion="download failed 0건",
                passed=None if not download_failures else False,
                failures=[
                    _failure(
                        row["at"],
                        row["id"],
                        f"같은 id {failed_ids[row['id']]}회" if failed_ids[row["id"]] > 1 else "",
                    )
                    for row in download_failures[:2]
                ],
                notes=", ".join(f"{key}={value}" for key, value in sorted(failed_ids.items())),
            ),
            Metric(
                key="D9-hq-library",
                label="D9 다운로드 · HQ 미착지",
                attempts=len(hq_library_missing),
                successes=0 if hq_library_missing else None,
                criterion="hq-library did not land 0건",
                passed=None if not hq_library_missing else False,
                failures=[
                    _failure(
                        row["at"],
                        row["id"],
                        f"같은 id {missing_ids[row['id']]}회" if missing_ids[row["id"]] > 1 else "",
                    )
                    for row in hq_library_missing[:2]
                ],
                notes=", ".join(f"{key}={value}" for key, value in sorted(missing_ids.items())),
            ),
            Metric(
                key="D9-service",
                label="D9 다운로드 · 서비스 시작 실패",
                attempts=len(service_failures),
                successes=0 if service_failures else None,
                criterion="could not start the download service 0건",
                passed=None if not service_failures else False,
                failures=[_failure(row.at, "download service") for row in service_failures[:2]],
            ),
        ]
    )
    stats["downloads"] = {
        "failed": len(download_failures),
        "failed_ids": dict(failed_ids),
        "hq_missing": len(hq_library_missing),
        "hq_missing_ids": dict(missing_ids),
        "service_failed": len(service_failures),
    }

    # ---- D10: background starts ----------------------------------------
    orphan_starts: list[dict[str, Any]] = []
    warmed_in_orphan = 0
    network_in_orphan = 0
    for start in process_starts:
        next_foreground = next(
            (row for row in foregrounds if row.at >= start.at), None
        )
        orphan = (
            next_foreground is None
            or (next_foreground.at - start.at).total_seconds()
            > LIFECYCLE_WINDOW_SECONDS
        )
        if not orphan:
            continue
        window_end = start.at.timestamp() + LIFECYCLE_WINDOW_SECONDS
        orphan_starts.append(
            {
                "at": start.at,
                "title": start.text.split("pid=")[-1] if "pid=" in start.text else "process start",
            }
        )
        for line in lines:
            if not (start.at <= line.at <= datetime.fromtimestamp(window_end)):
                continue
            if INNERTUBE_WARMED in line.text:
                warmed_in_orphan += 1
            if NETWORK_LINE_RE.search(line.text):
                network_in_orphan += 1
    d10_success = len(process_starts) - len(orphan_starts)
    metrics.append(
        Metric(
            key="D10-lifecycle",
            label="D10 백그라운드 · 시작 후 foreground",
            attempts=len(process_starts),
            successes=d10_success if process_starts else None,
            criterion="10초 내 foreground 없는 시작 0건",
            passed=None if not process_starts else not orphan_starts,
            failures=[_failure(row["at"], row["title"]) for row in orphan_starts[:2]],
            notes=f"warmed {warmed_in_orphan} · network {network_in_orphan}",
        )
    )
    stats["lifecycle"] = {
        "process_starts": len(process_starts),
        "orphans": len(orphan_starts),
        "warmed_in_orphan": warmed_in_orphan,
        "network_in_orphan": network_in_orphan,
    }

    # ---- D11: quality label versus actual format ------------------------
    current_format: str | None = None
    last_play_title = ""
    last_play_id = ""
    quality_known = 0
    quality_bad = 0
    quality_failures: list[Failure] = []
    for line in lines:
        text = line.text
        if line.tag.upper() == "DECODE":
            match = DECODE_RE.match(text)
            if match:
                current_format = _format_name(match.group("mime"))
        match = ALREADY_PLAYING_RE.match(text)
        if match:
            current_format = _format_name(match.group("mime"))
        match = PLAY_RE.match(text)
        if match:
            play_id = match.group("id").strip()
            last_play_title = match.group("title").strip()
            tags = [tag.group("fmt").casefold() for tag in FORMAT_TAG_RE.finditer(text)]
            if tags:
                current_format = _format_name(tags[-1])
            elif play_id != last_play_id:
                current_format = None
            last_play_id = play_id
        if QUALITY_TRIGGER_RE.search(text):
            if current_format not in ("flac", "opus"):
                continue
            quality_known += 1
            if current_format == "opus":
                quality_bad += 1
                quality_failures.append(
                    _failure(line.at, last_play_title or "quality", "표시 시점 실제 opus")
                )
    metrics.append(
        Metric(
            key="D11-quality",
            label="D11 품질 표시 · 실제 형식",
            attempts=quality_known,
            successes=quality_known - quality_bad if quality_known else None,
            criterion="표시 시점 실제 opus 0건",
            passed=None if not quality_known else quality_bad == 0,
            failures=quality_failures[:2],
        )
    )
    stats["quality"] = {
        "known": quality_known,
        "opus": quality_bad,
    }

    stats["played_songs"] = len(plays) if plays else len(summaries)
    stats["play_lines"] = len(plays)
    stats["summaries"] = len(summaries)
    return Report(log=log, metrics=metrics, stats=stats)


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------


def _display_width(text: str) -> int:
    return sum(
        2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1 for ch in text
    )


def _clip(text: str, limit: int = 80) -> str:
    text = text.replace("\n", " ").replace("\r", " ").strip()
    if _display_width(text) <= limit:
        return text
    out: list[str] = []
    used = 0
    for ch in text:
        width = 2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1
        if used + width > limit - 3:
            break
        out.append(ch)
        used += width
    return "".join(out) + "..."


def _pad(text: str, width: int) -> str:
    return text + " " * max(0, width - _display_width(text))


def _rate_text(metric: Metric) -> str:
    rate = metric.rate
    return "—" if rate is None else f"{rate * 100:.1f}%"


def _attempts_text(metric: Metric) -> str:
    return str(metric.attempts)


def _successes_text(metric: Metric) -> str:
    return "—" if metric.successes is None else str(metric.successes)


def _failure_text(metric: Metric, limit: int | None = None) -> str:
    if not metric.failures:
        return ""
    pieces: list[str] = []
    for failure in metric.failures[:2]:
        text = f"{failure.at:%Y-%m-%d %H:%M:%S} {failure.title}"
        if failure.detail:
            text += f" ({failure.detail})"
        pieces.append(text)
    rendered = " · ".join(pieces)
    return _clip(rendered, limit) if limit is not None else rendered


def _header_lines(report: Report) -> list[str]:
    start, end = report.log.period
    period = (
        f"{start:%Y-%m-%d %H:%M:%S} ~ {end:%Y-%m-%d %H:%M:%S}"
        if start is not None and end is not None
        else "로그 없음"
    )
    return [
        f"버전: {report.log.version}",
        f"git: {report.log.git}",
        f"기간: {period}",
        f"재생 곡 수: {report.played_songs}",
    ]


def render_console(report: Report) -> str:
    headers = ["기능", "시도", "성공", "성공률", "기준", "판정", "대표 실패 예"]
    rows: list[list[str]] = []
    for metric in report.metrics:
        rows.append(
            [
                metric.label,
                _attempts_text(metric),
                _successes_text(metric),
                _rate_text(metric),
                metric.criterion,
                metric.verdict,
                _failure_text(metric, limit=80),
            ]
        )
    widths = [_display_width(header) for header in headers]
    for row in rows:
        for index, cell in enumerate(row):
            widths[index] = max(widths[index], _display_width(cell))
    out = ["BitChord 기능 건강 검사", *_header_lines(report), ""]
    out.append("  ".join(_pad(header, widths[index]) for index, header in enumerate(headers)))
    out.append("  ".join("-" * widths[index] for index in range(len(headers))))
    for row in rows:
        out.append("  ".join(_pad(cell, widths[index]) for index, cell in enumerate(row)))
    failed = report.failures
    out.append("")
    out.append(
        "요약: "
        + (
            f"FAIL {len(failed)}개 — " + ", ".join(metric.key for metric in failed)
            if failed
            else "FAIL 없음"
        )
    )
    return "\n".join(out)


def render_markdown(report: Report) -> str:
    out = ["# BitChord 기능 건강 검사", ""]
    out.extend(f"- {line}" for line in _header_lines(report))
    out.append("")
    out.append("| 기능 | 시도 | 성공 | 성공률 | 기준 | 판정 | 대표 실패 예 |")
    out.append("| --- | ---: | ---: | ---: | --- | --- | --- |")
    for metric in report.metrics:
        cells = [
            metric.label,
            _attempts_text(metric),
            _successes_text(metric),
            _rate_text(metric),
            metric.criterion,
            metric.verdict,
            _failure_text(metric).replace("|", "\\|"),
        ]
        out.append("| " + " | ".join(cells) + " |")
    failed = report.failures
    out.append("")
    out.append(
        "요약: "
        + (
            f"FAIL {len(failed)}개 — " + ", ".join(metric.key for metric in failed)
            if failed
            else "FAIL 없음"
        )
    )
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _parse_since(value: str) -> datetime:
    try:
        return datetime.strptime(value, "%Y-%m-%d %H:%M")
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            "expected 'YYYY-MM-DD HH:MM'"
        ) from error


def main(argv: list[str] | None = None) -> int:
    # The Windows console defaults to cp949 here and -I ignores PYTHONIOENCODING.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(
        description="Read a BitChord diagnostic log and score D1..D11 by success rate."
    )
    parser.add_argument("log", type=Path, help="exported BitChord diagnostic log")
    parser.add_argument(
        "--since",
        type=_parse_since,
        default=None,
        help="only lines at or after this local time ('YYYY-MM-DD HH:MM')",
    )
    parser.add_argument(
        "--md",
        type=Path,
        default=None,
        help="also write the report as a Markdown table here",
    )
    args = parser.parse_args(argv)

    try:
        log = parse_log_file(args.log, args.since)
    except OSError as error:
        print(f"로그를 읽을 수 없습니다: {error}", file=sys.stderr)
        return 2
    if not log.lines:
        print("로그에 읽을 수 있는 줄이 없습니다.", file=sys.stderr)
        return 2

    report = analyze(log)
    console = render_console(report)
    print(console)
    if args.md is not None:
        args.md.write_text(render_markdown(report), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
