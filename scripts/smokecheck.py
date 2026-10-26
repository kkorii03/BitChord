#!/usr/bin/env python3
"""Read a BitChord logcat dump and judge the three things a trial build must not
get wrong: music-video playback, song-version matching, lyric sync, quality
upgrades, crashes and next-track preparation.

Standard library only. The log lines matched here are the ones the app writes;
see the regexes below for their exact shape.

Usage:
    python3 scripts/smokecheck.py logcat.txt
    python3 scripts/smokecheck.py logcat.txt --report smoke.md
    python3 scripts/smokecheck.py logcat.txt --songs scripts/smoke/songs.txt

Exit code is 1 when any check fails, 0 when every check passes or is skipped,
and 2 when the log or the song list cannot be read (or the log is empty).
"""

from __future__ import annotations

import argparse
import re
import sys
import unicodedata
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

SHIFT_LIMIT_MS = 3000

# `track-summary: <id> '<title>' video=y source=... resolve=...`
TRACK_SUMMARY_RE = re.compile(
    r"track-summary:\s+(?P<id>\S+)\s+'(?P<title>.*?)'\s+video=(?P<video>[yn])\b"
)
# `song version: <from> -> <to> via <how>` (both with and without `before-audio`).
SONG_VERSION_RE = re.compile(
    r"song version:\s+(?P<src>\S+)\s+->\s+(?P<dst>\S+)\s+via\s+(?P<via>\S+)"
)
# `song version: <id> kept (none)`, `kept (timeout)`, `kept (local file, none)`.
SONG_VERSION_KEPT_RE = re.compile(r"song version:\s+(?P<id>\S+)\s+kept\b")
# A definitive "no catalogue song" answer from the pre-playback lookup. A
# downloaded cover says `kept (local file, none)`; both mean the same thing.
SONG_VERSION_KEPT_NONE_RE = re.compile(
    r"song version:\s+(?P<id>\S+)\s+kept \((?:local file, )?none\)"
)
# `song version: <id> -> none via none (cover)` — a cover plays as itself.
SONG_VERSION_COVER_RE = re.compile(
    r"song version:\s+(?P<id>\S+)\s+->\s+none\s+via\s+none\s+\(cover\)"
)
# `audio switch: no official song match for '<title>' by '<artist>'`
AUDIO_SWITCH_NO_MATCH_RE = re.compile(
    r"audio switch: no official song match for '(?P<title>.*?)' by '(?P<artist>.*)'"
)
# `hq-verify: <title> <source> match=..% offset=.. -> accepted|rejected`
HQ_VERDICT_RE = re.compile(r"hq-verify:.*?->\s*(?P<verdict>accepted|rejected)\b")
HQ_NO_REFERENCE_RE = re.compile(r"hq-verify:\s+.*?\bno reference\b")
# `decode: hq-verify failed at=... ...`
DECODE_HQ_FAIL_RE = re.compile(r"decode:\s+hq-verify\s+failed\b")
# `lyrics-sync: <id> source=.. shift=1234ms ...`
LYRICS_SYNC_SHIFT_RE = re.compile(
    r"lyrics-sync:\s+(?P<id>\S+).*?\bshift=(?P<shift>-?\d+|-)ms"
)
# The same line ends with `video=y|n`, another statement that the id is an MV.
LYRICS_SYNC_ID_RE = re.compile(r"lyrics-sync:\s+(?P<id>\S+)")
# `song-sync: <id> result=applied|refused-shift|... shift=1234 ...`
SONG_SYNC_RE = re.compile(
    r"song-sync:\s+(?P<id>\S+)\s+result=(?P<result>\S+).*?\bshift=(?P<shift>-?\d+)"
)
# `next-prep: <title> version=<id> lyrics=... hq=... sync=... mix=... in ..ms`
NEXT_PREP_RE = re.compile(r"next-prep:\s+(?P<title>.*?)\s+version=(?P<version>\S+)")
# `play: -> <title> - <artist> (<media id>) idx=...`
PLAY_RE = re.compile(r"play:\s*->\s+(?P<label>.*?)\s+\((?P<id>[^()]*)\)\s+idx=")
ANR_RE = re.compile(r"\bANR in\b")


@dataclass(frozen=True)
class Summary:
    video_id: str
    title: str
    video: bool


@dataclass(frozen=True)
class SongSpec:
    """One row of scripts/smoke/songs.txt."""

    key: str
    kind: str
    search: str | None = None
    playlist: str | None = None

    @property
    def video_id(self) -> str | None:
        return None if self.search else self.key


def parse_songs(text: str) -> list[SongSpec]:
    """Reads the tab-separated song list, ignoring blank and `#` lines."""
    songs: list[SongSpec] = []
    for raw in text.splitlines():
        line = raw.rstrip("\r")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        columns = line.split("\t")
        if len(columns) < 2:
            continue
        key = columns[0].strip()
        kind = columns[1].strip().lower()
        if not key or not kind:
            continue
        playlist = columns[2].strip() if len(columns) > 2 else ""
        if key.startswith("search:"):
            query = key[len("search:") :].strip()
            if not query:
                continue
            songs.append(
                SongSpec(
                    key=key,
                    kind=kind,
                    search=query,
                    playlist=playlist or None,
                )
            )
        else:
            songs.append(SongSpec(key=key, kind=kind, playlist=playlist or None))
    return songs


def load_songs(path: str) -> list[SongSpec]:
    return parse_songs(Path(path).read_text(encoding="utf-8", errors="replace"))


@dataclass
class Check:
    number: int
    name: str
    status: str
    details: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return self.status in ("PASS", "SKIP")


def is_mv_title(title: str) -> bool:
    """The title markers the trial-build reports keep using for music videos."""
    text = title.casefold()
    if "official video" in text or "official music video" in text or "m/v" in text:
        return True
    # A standalone MV, as in `[MV]`, `(MV)` or `MV`, but not inside a word.
    return re.search(r"(?<![a-z0-9])mv(?![a-z0-9])", text) is not None


def _display_width(text: str) -> int:
    return sum(
        2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1 for ch in text
    )


def _clip(text: str, limit: int = 150) -> str:
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


def check_log(
    text: str,
    songs: list[SongSpec] | None = None,
    hq_configured: bool | None = None,
) -> list[Check]:
    """Run every check against one logcat dump.

    When [songs] is given, the MV, HQ and cover checks judge the entries of
    that list by kind instead of inferring them from the log alone.
    """
    summaries: list[Summary] = []
    replacements: list[tuple[str, str]] = []
    version_subjects: set[str] = set()
    cover_ids: set[str] = set()
    no_version_ids: set[str] = set()
    audio_switch_failures: list[tuple[str, str]] = []
    play_ids_by_label: dict[str, set[str]] = {}
    hq_accepted = 0
    hq_rejected = 0
    hq_decode_failed = 0
    hq_no_reference = 0
    sync_warnings: list[tuple[str, str, str]] = []
    seen_sync: set[tuple[str, str, str]] = set()
    video_flag_ids: set[str] = set()
    crashes: list[str] = []
    next_preps: list[tuple[str, str]] = []

    for raw in text.splitlines():
        line = raw.rstrip("\r")

        if "play: ->" in line:
            match = PLAY_RE.search(line)
            if match:
                play_ids_by_label.setdefault(match.group("label"), set()).add(
                    match.group("id")
                )

        if "track-summary:" in line:
            match = TRACK_SUMMARY_RE.search(line)
            if match:
                summaries.append(
                    Summary(
                        video_id=match.group("id"),
                        title=match.group("title"),
                        video=match.group("video") == "y",
                    )
                )

        if "song version:" in line:
            cover = SONG_VERSION_COVER_RE.search(line)
            if cover:
                cover_ids.add(cover.group("id"))
            kept_none = SONG_VERSION_KEPT_NONE_RE.search(line)
            if kept_none:
                no_version_ids.add(kept_none.group("id"))
            match = SONG_VERSION_RE.search(line)
            if match:
                source = match.group("src")
                target = match.group("dst")
                replacements.append((source, target))
                version_subjects.add(source)
            # A "kept" line is not evidence of an MV: the lookup also runs for
            # rows of unknown type and keeps a song version that is already one
            # (10-26: Burn the House Down, Wbxh1fNakos = AJR - Topic, was failed
            # as an MV). A real MV left playing shows as "no official song match".

        if "audio switch:" in line:
            match = AUDIO_SWITCH_NO_MATCH_RE.search(line)
            if match:
                audio_switch_failures.append(
                    (match.group("title"), match.group("artist"))
                )

        if "hq-verify:" in line:
            if HQ_NO_REFERENCE_RE.search(line):
                hq_no_reference += 1
            else:
                match = HQ_VERDICT_RE.search(line)
                if match:
                    if match.group("verdict") == "accepted":
                        hq_accepted += 1
                    else:
                        hq_rejected += 1

        if DECODE_HQ_FAIL_RE.search(line):
            hq_decode_failed += 1

        if "lyrics-sync:" in line:
            if "video=y" in line:
                identified = LYRICS_SYNC_ID_RE.search(line)
                if identified:
                    video_flag_ids.add(identified.group("id"))
            match = LYRICS_SYNC_SHIFT_RE.search(line)
            if match and match.group("shift") != "-":
                shift = int(match.group("shift"))
                if abs(shift) > SHIFT_LIMIT_MS:
                    key = ("lyrics-sync", match.group("id"), str(shift))
                    if key not in seen_sync:
                        seen_sync.add(key)
                        sync_warnings.append(key)

        if "song-sync:" in line:
            match = SONG_SYNC_RE.search(line)
            if match:
                result = match.group("result")
                shift = int(match.group("shift"))
                if result == "refused-shift" or abs(shift) > SHIFT_LIMIT_MS:
                    key = ("song-sync", match.group("id"), result)
                    if key not in seen_sync:
                        seen_sync.add(key)
                        sync_warnings.append(key)

        if "FATAL EXCEPTION" in line or ANR_RE.search(line):
            crashes.append(line.strip())

        if "next-prep:" in line:
            match = NEXT_PREP_RE.search(line)
            if match:
                next_preps.append((match.group("title"), match.group("version")))

    replaced_sources = {src for src, dst in replacements if dst not in ("none", "-")}
    replaced_targets = {dst for _src, dst in replacements if dst not in ("none", "-")}
    mv_ids = {
        summary.video_id
        for summary in summaries
        if summary.video or is_mv_title(summary.title)
    }
    mv_ids.update(version_subjects)
    mv_ids.update(video_flag_ids)
    title_by_id = {summary.video_id: summary.title for summary in summaries}

    checks: list[Check] = []

    # 1. A music video must have been swapped for its song version. A cover or
    #    song upload (`video=y` with no MV title marker) is allowed to play as
    #    itself when the catalogue search found no song, or when the app already
    #    recognised it as a cover.
    def play_ids_for(title: str, artist: str) -> set[str]:
        # The play line truncates both fields to 80 characters; the switch line
        # does not. Match either shape so the failure stays tied to this id.
        ids: set[str] = set()
        for label in (f"{title} - {artist}", f"{title[:80]} - {artist[:80]}"):
            ids.update(play_ids_by_label.get(label, ()))
        return ids

    no_match_ids: set[str] = set()
    for title, artist in audio_switch_failures:
        no_match_ids.update(play_ids_for(title, artist))
    cover_spec_ids = (
        set()
        if songs is None
        else {spec.video_id for spec in songs if spec.kind == "cover" and spec.video_id}
    )
    mv_failures: list[str] = []
    seen_mv: set[str] = set()
    if songs is not None:
        mv_specs = [spec for spec in songs if spec.kind == "mv" and spec.video_id]
        for spec in mv_specs:
            video_id = spec.video_id
            if video_id is None:
                continue
            if video_id in replaced_sources or video_id in replaced_targets:
                continue
            if video_id in seen_mv:
                continue
            seen_mv.add(video_id)
            title = title_by_id.get(video_id)
            mv_failures.append(
                f"{title} ({video_id})" if title else f"{video_id} (summary 없음)"
            )
        mv_status = "SKIP" if not mv_specs else ("FAIL" if mv_failures else "PASS")
        mv_details = (
            ["mv 곡 없음"]
            if not mv_specs
            else mv_failures
            or ["mv 곡이 모두 song version 으로 교체됨"]
        )
    else:
        for summary in summaries:
            has_marker = is_mv_title(summary.title)
            if not (summary.video or has_marker):
                continue
            if (
                summary.video_id in replaced_sources
                or summary.video_id in replaced_targets
            ):
                continue
            if not has_marker and (
                summary.video_id in cover_ids
                or summary.video_id in no_version_ids
                or summary.video_id in no_match_ids
            ):
                continue
            if summary.video_id in seen_mv:
                continue
            seen_mv.add(summary.video_id)
            mv_failures.append(f"{summary.title} ({summary.video_id})")
        mv_status = "FAIL" if mv_failures else "PASS"
        mv_details = mv_failures or ["MV/비디오 행이 모두 song version 으로 교체됨"]
    checks.append(Check(1, "MV 재생", mv_status, mv_details))

    # 2. The catalogue search found no official song for a video. A cover that
    #    was classified as a cover by the song list is not a catalogue miss.
    switch_failures: list[tuple[str, str]] = []
    for failure in audio_switch_failures:
        ids = play_ids_for(*failure)
        if ids and ids <= cover_spec_ids:
            continue
        switch_failures.append(failure)
    switch_counts = Counter(switch_failures)
    switch_details = [
        f"{title} - {artist} (x{count})" if count > 1 else f"{title} - {artist}"
        for (title, artist), count in switch_counts.items()
    ]
    checks.append(
        Check(
            2,
            "노래 버전 판정",
            "FAIL" if switch_failures else "PASS",
            (
                [f"no official song match {len(switch_failures)}건"]
                + switch_details
            )
            if switch_failures
            else ["no official song match 없음"],
        )
    )

    # 3. A shift beyond three seconds, or a refused shift, smells like MV sound.
    sync_details: list[str] = []
    for source, video_id, value in sync_warnings:
        title = title_by_id.get(video_id)
        label = f"{video_id} ({title})" if title else video_id
        if source == "lyrics-sync":
            sync_details.append(f"WARN lyrics-sync {label} shift={value}ms")
        else:
            sync_details.append(f"WARN song-sync {label} result={value}")
    checks.append(
        Check(
            3,
            "가사 싱크",
            "FAIL" if sync_details else "PASS",
            sync_details
            or [f"|shift| > {SHIFT_LIMIT_MS}ms 또는 refused-shift 없음"],
        )
    )

    # 4. HQ songs need at least one accepted verification. Without HQ settings
    #    there is nothing to verify, so the check is skipped rather than failed.
    candidates = hq_accepted + hq_rejected + hq_decode_failed + hq_no_reference
    hq_counts = (
        f"accepted={hq_accepted} rejected={hq_rejected} "
        f"decode-failed={hq_decode_failed} no-reference={hq_no_reference}"
    )
    if songs is not None:
        hq_specs = [spec for spec in songs if spec.kind == "hq"]
        configured = hq_configured if hq_configured is not None else candidates > 0
        if not hq_specs:
            hq_status = "SKIP"
            hq_details = ["hq 곡 없음"]
        elif not configured:
            hq_status = "SKIP"
            hq_details = ["HQ 설정 없음 (SMOKE_SETTINGS_FILE 없음)"]
        elif hq_accepted >= 1:
            hq_status = "PASS"
            hq_details = [hq_counts]
        else:
            hq_status = "FAIL"
            hq_details = [hq_counts, "hq-verify accepted 가 0"]
    else:
        hq_failed = hq_accepted == 0 and candidates >= 1
        hq_status = "FAIL" if hq_failed else "PASS"
        hq_details = [hq_counts] + (
            ["후보가 있었지만 accepted 가 0"] if hq_failed else []
        )
    checks.append(Check(4, "품질 업그레이드", hq_status, hq_details))

    # 5. A crash or an ANR fails the whole run.
    fatal = sum(1 for line in crashes if "FATAL EXCEPTION" in line)
    anr = sum(1 for line in crashes if ANR_RE.search(line))
    crash_details = [f"FATAL EXCEPTION={fatal} ANR={anr}"]
    crash_details.extend(crashes[:10])
    if len(crashes) > 10:
        crash_details.append(f"... 외 {len(crashes) - 10}줄")
    checks.append(
        Check(
            5,
            "튕김/ANR",
            "FAIL" if crashes else "PASS",
            crash_details if crashes else ["FATAL EXCEPTION / ANR 없음"],
        )
    )

    # 6. Next-track preparation must not still be pinned to the MV id.
    prep_failures: list[str] = []
    seen_prep: set[tuple[str, str]] = set()
    for title, version in next_preps:
        if version in cover_spec_ids:
            continue  # a cover correctly keeps its own id
        if version in replaced_targets:
            continue  # it is the resolved song version
        if version not in mv_ids and not is_mv_title(title):
            continue
        key = (title, version)
        if key in seen_prep:
            continue
        seen_prep.add(key)
        prep_failures.append(f"{title} version={version}")
    checks.append(
        Check(
            6,
            "다음 곡 준비",
            "FAIL" if prep_failures else "PASS",
            prep_failures or ["next-prep version 이 MV id 로 남은 행 없음"],
        )
    )

    # 7. A cover must keep playing as itself, not be replaced by the official
    #    song. Only checked when the song list says which rows are covers.
    if songs is not None:
        cover_specs = [
            spec for spec in songs if spec.kind == "cover" and spec.video_id
        ]
        cover_failures: list[str] = []
        seen_cover: set[str] = set()
        for spec in cover_specs:
            video_id = spec.video_id
            if video_id is None or video_id in seen_cover:
                continue
            seen_cover.add(video_id)
            title = title_by_id.get(video_id)
            label = f"{title} ({video_id})" if title else video_id
            replaced = [
                target
                for source, target in replacements
                if source == video_id and target not in ("none", "-")
            ]
            if replaced:
                cover_failures.append(f"{label} -> {replaced[0]} 교체됨")
            elif video_id not in title_by_id:
                cover_failures.append(f"{label} (summary 없음)")
        if not cover_specs:
            cover_status = "SKIP"
            cover_details = ["cover 곡 없음"]
        else:
            cover_status = "FAIL" if cover_failures else "PASS"
            cover_details = cover_failures or ["cover 곡이 그대로 재생됨"]
        checks.append(Check(7, "커버 재생", cover_status, cover_details))

    return checks


def render_table(checks: list[Check], log_path: str) -> str:
    headers = ["#", "항목", "판정", "상세"]
    rows = [
        [
            str(check.number),
            check.name,
            check.status,
            _clip("; ".join(check.details) or "-"),
        ]
        for check in checks
    ]
    widths = [
        max(_display_width(row[index]) for row in [headers] + rows)
        for index in range(len(headers))
    ]
    separator = "+" + "+".join("-" * (width + 2) for width in widths) + "+"

    def render(row: list[str]) -> str:
        return "| " + " | ".join(_pad(cell, widths[i]) for i, cell in enumerate(row)) + " |"

    lines = [f"BitChord smoke check: {log_path}", separator, render(headers), separator]
    lines.extend(render(row) for row in rows)
    lines.append(separator)
    failed = [check for check in checks if not check.passed]
    lines.append(
        f"결과: {'FAIL' if failed else 'PASS'} "
        f"({len(failed)}/{len(checks)} 실패)"
    )
    if failed:
        lines.append(
            "실패: " + ", ".join(f"{check.number}. {check.name}" for check in failed)
        )
    return "\n".join(lines)


def _markdown(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ").replace("\r", " ")


def render_markdown(checks: list[Check], log_path: str) -> str:
    failed = [check for check in checks if not check.passed]
    lines = [
        "# BitChord smoke check",
        "",
        f"- 로그: `{log_path}`",
        f"- 결과: **{'FAIL' if failed else 'PASS'}** "
        f"({len(failed)}/{len(checks)} 실패)",
        "",
        "| # | 항목 | 판정 | 상세 |",
        "|---|---|---|---|",
    ]
    for check in checks:
        details = "<br>".join(_markdown(detail) for detail in check.details) or "-"
        lines.append(
            f"| {check.number} | {_markdown(check.name)} | {check.status} | {details} |"
        )
    lines.extend(["", "## 항목별 상세", ""])
    for check in checks:
        lines.append(f"### {check.number}. {check.name} - {check.status}")
        lines.extend(f"- {detail}" for detail in check.details)
        if not check.details:
            lines.append("- 없음")
        lines.append("")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Judge a BitChord logcat dump (MV, song version, sync, HQ, crash, next-prep)."
    )
    parser.add_argument("logcat", help="logcat 파일 경로, 또는 - (표준 입력)")
    parser.add_argument("--report", help="마크다운 요약을 쓸 경로")
    parser.add_argument(
        "--songs",
        help="scripts/smoke/songs.txt 형식의 곡 목록; 있으면 종류별로 판정한다",
    )
    parser.add_argument(
        "--hq-configured",
        action="store_true",
        default=None,
        help="HQ 설정이 실제로 들어왔음 (없으면 hq-verify 로그로 추론)",
    )
    args = parser.parse_args(argv)

    try:
        if args.logcat == "-":
            text = sys.stdin.read()
        else:
            text = Path(args.logcat).read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        print(f"smokecheck: 로그를 읽을 수 없습니다: {error}", file=sys.stderr)
        return 2
    if not text.strip():
        print("smokecheck: 로그가 비어 있습니다", file=sys.stderr)
        return 2

    songs = None
    if args.songs:
        try:
            songs = load_songs(args.songs)
        except OSError as error:
            print(f"smokecheck: 곡 목록을 읽을 수 없습니다: {error}", file=sys.stderr)
            return 2

    checks = check_log(text, songs=songs, hq_configured=args.hq_configured)
    print(render_table(checks, args.logcat))

    if args.report:
        report = Path(args.report)
        try:
            report.write_text(
                render_markdown(checks, args.logcat), encoding="utf-8"
            )
        except OSError as error:
            print(f"smokecheck: 보고서를 쓸 수 없습니다: {error}", file=sys.stderr)
            return 2
        print(f"보고서: {report}")

    return 1 if any(not check.passed for check in checks) else 0


if __name__ == "__main__":
    sys.exit(main())
