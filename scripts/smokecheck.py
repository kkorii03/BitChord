#!/usr/bin/env python3
"""Read a BitChord logcat dump and judge the three things a trial build must not
get wrong: music-video playback, song-version matching, lyric sync, quality
upgrades, crashes and next-track preparation.

Standard library only. The log lines matched here are the ones the app writes;
see the regexes below for their exact shape.

Usage:
    python3 scripts/smokecheck.py logcat.txt
    python3 scripts/smokecheck.py logcat.txt --report smoke.md

Exit code is 1 when any check fails, 0 otherwise, 2 when the log cannot be read.
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
# `sync-report: <id> line=3/20 shown=12345 tapped=10545 error=1800 source=.. ...`
SYNC_REPORT_RE = re.compile(
    r"sync-report:\s+(?P<id>\S+)\s+line=(?P<line>\d+)/(?P<total>\d+)\s+"
    r"shown=(?P<shown>-?\d+)(?:ms)?\s+tapped=(?P<tapped>-?\d+)(?:ms)?\s+"
    r"error=(?P<error>-?\d+)(?:ms)?"
)
# A drift this steep, in ms per lyric line, is worth calling out as widening.
SYNC_SLOPE_WIDENING_MS = 50.0
# `song-sync: <id> result=applied|refused-shift|... shift=1234 ...`
SONG_SYNC_RE = re.compile(
    r"song-sync:\s+(?P<id>\S+)\s+result=(?P<result>\S+).*?\bshift=(?P<shift>-?\d+)"
)
# `next-prep: <title> version=<id> lyrics=... hq=... sync=... mix=... in ..ms`
NEXT_PREP_RE = re.compile(r"next-prep:\s+(?P<title>.*?)\s+version=(?P<version>\S+)")
ANR_RE = re.compile(r"\bANR in\b")


@dataclass(frozen=True)
class Summary:
    video_id: str
    title: str
    video: bool


@dataclass(frozen=True)
class SyncReport:
    video_id: str
    line: int
    total: int
    shown: int
    tapped: int
    error: int


def _slope(points: list[tuple[float, float]]) -> float:
    """Least-squares slope of y against x; zero when x never moves."""
    if len(points) < 2:
        return 0.0
    mean_x = sum(x for x, _ in points) / len(points)
    mean_y = sum(y for _, y in points) / len(points)
    spread = sum((x - mean_x) ** 2 for x, _ in points)
    if spread == 0:
        return 0.0
    return sum((x - mean_x) * (y - mean_y) for x, y in points) / spread


def sync_report_details(
    reports: list[SyncReport],
    title_by_id: dict[str, str],
) -> list[str]:
    """Per-song error average, range and, from three reports on, its drift."""
    by_song: dict[str, list[SyncReport]] = {}
    for report in reports:
        by_song.setdefault(report.video_id, []).append(report)
    details: list[str] = []
    for video_id, song_reports in by_song.items():
        song_reports.sort(key=lambda report: report.line)
        errors = [report.error for report in song_reports]
        average = sum(errors) / len(errors)
        title = title_by_id.get(video_id)
        label = f"{video_id} ({title})" if title else video_id
        detail = (
            f"sync-report {label} n={len(errors)} avg={average:+.0f}ms "
            f"range={min(errors):+d}..{max(errors):+d}ms"
        )
        if len(song_reports) >= 3:
            # Widening is about the size of the miss, whichever way it points:
            # 500 → 300 → 100 ms late is closing, not drifting apart.
            magnitude_slope = _slope(
                [(report.line, abs(report.error)) for report in song_reports]
            )
            if magnitude_slope >= SYNC_SLOPE_WIDENING_MS:
                trend = "점점 벌어짐"
            elif magnitude_slope <= -SYNC_SLOPE_WIDENING_MS:
                trend = "점점 좁아짐"
            else:
                trend = "일정"
            # The verdict is about the size of the miss, so the printed
            # slope is the magnitude one it was decided from — not the signed
            # one, which would read as closing while the gap widens.
            detail += f" slope={magnitude_slope:+.1f}ms/줄 ({trend})"
        details.append(detail)
    return details


@dataclass
class Check:
    number: int
    name: str
    status: str
    details: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return self.status == "PASS"


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


def check_log(text: str) -> list[Check]:
    """Run every check against one logcat dump."""
    summaries: list[Summary] = []
    replacements: list[tuple[str, str]] = []
    version_subjects: set[str] = set()
    audio_switch_failures: list[tuple[str, str]] = []
    hq_accepted = 0
    hq_rejected = 0
    hq_decode_failed = 0
    hq_no_reference = 0
    sync_warnings: list[tuple[str, str, str]] = []
    seen_sync: set[tuple[str, str, str]] = set()
    video_flag_ids: set[str] = set()
    sync_reports: list[SyncReport] = []
    crashes: list[str] = []
    next_preps: list[tuple[str, str]] = []

    for raw in text.splitlines():
        line = raw.rstrip("\r")

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
            match = SONG_VERSION_RE.search(line)
            if match:
                source = match.group("src")
                target = match.group("dst")
                replacements.append((source, target))
                version_subjects.add(source)
            else:
                kept = SONG_VERSION_KEPT_RE.search(line)
                if kept:
                    version_subjects.add(kept.group("id"))

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

        if "sync-report:" in line:
            match = SYNC_REPORT_RE.search(line)
            if match:
                sync_reports.append(
                    SyncReport(
                        video_id=match.group("id"),
                        line=int(match.group("line")),
                        total=int(match.group("total")),
                        shown=int(match.group("shown")),
                        tapped=int(match.group("tapped")),
                        error=int(match.group("error")),
                    )
                )

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

    # 1. A music video must have been swapped for its song version.
    mv_failures: list[str] = []
    seen_mv: set[str] = set()
    for summary in summaries:
        if not (summary.video or is_mv_title(summary.title)):
            continue
        if summary.video_id in replaced_sources or summary.video_id in replaced_targets:
            continue
        if summary.video_id in seen_mv:
            continue
        seen_mv.add(summary.video_id)
        mv_failures.append(f"{summary.title} ({summary.video_id})")
    checks.append(
        Check(
            1,
            "MV 재생",
            "FAIL" if mv_failures else "PASS",
            mv_failures
            or ["MV/비디오 행이 모두 song version 으로 교체됨"],
        )
    )

    # 2. The catalogue search found no official song for a video.
    switch_counts = Counter(audio_switch_failures)
    switch_details = [
        f"{title} - {artist} (x{count})" if count > 1 else f"{title} - {artist}"
        for (title, artist), count in switch_counts.items()
    ]
    checks.append(
        Check(
            2,
            "노래 버전 판정",
            "FAIL" if audio_switch_failures else "PASS",
            (
                [f"no official song match {len(audio_switch_failures)}건"]
                + switch_details
            )
            if audio_switch_failures
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
            (
                sync_details
                if sync_details
                else [f"|shift| > {SHIFT_LIMIT_MS}ms 또는 refused-shift 없음"]
            )
            + sync_report_details(sync_reports, title_by_id),
        )
    )

    # 4. A candidate existed but none was ever accepted.
    candidates = hq_accepted + hq_rejected + hq_decode_failed + hq_no_reference
    hq_failed = hq_accepted == 0 and candidates >= 1
    checks.append(
        Check(
            4,
            "품질 업그레이드",
            "FAIL" if hq_failed else "PASS",
            [
                f"accepted={hq_accepted} rejected={hq_rejected} "
                f"decode-failed={hq_decode_failed} no-reference={hq_no_reference}"
            ]
            + (["후보가 있었지만 accepted 가 0"] if hq_failed else []),
        )
    )

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
    args = parser.parse_args(argv)

    try:
        if args.logcat == "-":
            text = sys.stdin.read()
        else:
            text = Path(args.logcat).read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        print(f"smokecheck: 로그를 읽을 수 없습니다: {error}", file=sys.stderr)
        return 2

    checks = check_log(text)
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
