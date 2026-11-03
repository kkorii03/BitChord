#!/usr/bin/env python3
"""Read a real-use smoke run's logcat and report one line per track.

A real-use run opens a playlist (or starts a mood search), lets each track play
and advances with KEYCODE_MEDIA_NEXT. This reads the app's own lines - `play:`,
`track-summary:`, `song version:`, `holding`/`released` and `video:` - and says
what each track actually did.

    python3 scripts/smoke/realuse.py summary shots/smoke-logcat.txt \
        --expected 8 --report shots/smoke-report.md
    python3 scripts/smoke/realuse.py play-button shots/window.xml

The summary command exits 1 when any track got no audio, so the smoke run fails
on the bug this mode exists to catch. Standard library only.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

# `play: -> <title> - <artist> (<id>) idx=<n>/<total> ...`. The title may
# itself contain " - ", so split on the last separator before the id.
PLAY_RE = re.compile(
    r"play:\s*->\s+(?P<title>.*)\s+-\s+(?P<artist>.*?)\s+"
    r"\((?P<id>[^()]*)\)\s+idx=(?P<idx>\d+)/"
)
# `track-summary: <id> '<title>' video=y ... firstAudio=123|- ... ended=natural`
SUMMARY_RE = re.compile(
    r"track-summary:\s+(?P<id>\S+)\s+'(?P<title>.*?)'\s+video=(?P<video>[yn])"
    r".*?\bfirstAudio=(?P<first>-|\d+).*?\bended=(?P<ended>\S+)"
)
# `song version: <from> -> <to> via <how> (reason)`; the reason tells a
# cover apart from a row with no title/artist.
VERSION_RE = re.compile(
    r"song version:\s+(?P<src>\S+)\s+->\s+(?P<dst>\S+)\s+via\s+(?P<via>\S+)"
    r"(?:\s+\((?P<reason>[^)]*)\))?"
)
# `song version: <id> kept (timeout)` and the `kept (local file, none)` shape.
KEPT_RE = re.compile(r"song version:\s+(?P<id>\S+)\s+kept(?:\s*\((?P<why>[^)]*)\))?")
# `holding <id> until it is within 25s of playing` / `released <id>`
HOLD_RE = re.compile(r"holding\s+(?P<id>\S+)\s+until it is within")
RELEASE_RE = re.compile(r"released\s+(?P<id>\S+)\b")
# TrackLog puts an id in brackets when the message itself does not name it.
BRACKET_RE = re.compile(r"\[(?P<id>[^\]]+)\]\s+")
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


@dataclass
class Play:
    index: int
    id: str
    idx: int
    title: str
    artist: str
    line: int = 0


@dataclass
class Summary:
    id: str
    title: str
    video: bool
    first_audio: int | None
    ended: str
    line: int = 0


def _parse_log(text: str):
    plays: list[Play] = []
    # Events keep their log order and line number: a playlist may repeat a
    # video id, so a row is identified by its occurrence, not by the id alone.
    summaries: list[Summary] = []
    versions: list[tuple[int, str, str, str, str]] = []
    kept: list[tuple[int, str, str]] = []
    held: list[tuple[int, str]] = []
    released: list[tuple[int, str]] = []
    videos: list[tuple[int, str, str]] = []

    for line_no, raw in enumerate(text.splitlines()):
        line = raw.rstrip("\r")
        match = PLAY_RE.search(line)
        if match:
            play_id = match.group("id").strip()
            idx = int(match.group("idx"))
            # A version/source refresh may name the same row again; a real
            # repeat later in the queue has a different index.
            if not plays or plays[-1].id != play_id or plays[-1].idx != idx:
                plays.append(
                    Play(
                        index=len(plays) + 1,
                        id=play_id,
                        idx=idx,
                        title=match.group("title").strip(),
                        artist=match.group("artist").strip(),
                        line=line_no,
                    )
                )
            continue
        match = SUMMARY_RE.search(line)
        if match:
            summaries.append(
                Summary(
                    id=match.group("id"),
                    title=match.group("title"),
                    video=match.group("video") == "y",
                    first_audio=None
                    if match.group("first") == "-"
                    else int(match.group("first")),
                    ended=match.group("ended"),
                    line=line_no,
                )
            )
            continue
        match = VERSION_RE.search(line)
        if match:
            versions.append(
                (
                    line_no,
                    match.group("src"),
                    match.group("dst"),
                    match.group("via"),
                    match.group("reason") or "",
                )
            )
        match = KEPT_RE.search(line)
        if match:
            kept.append((line_no, match.group("id"), match.group("why") or ""))
        match = HOLD_RE.search(line)
        if match:
            held.append((line_no, match.group("id")))
        match = RELEASE_RE.search(line)
        if match:
            released.append((line_no, match.group("id")))
        if "video:" in line:
            bracket = BRACKET_RE.search(line)
            if bracket:
                videos.append((line_no, bracket.group("id"), line))
    return plays, summaries, versions, kept, held, released, videos


def _owner(plays: list[Play], line_no: int) -> int:
    """The play occurrence an event belongs to; before the first one, the first."""
    owner = 0
    for i, play in enumerate(plays):
        if play.line <= line_no:
            owner = i
        else:
            break
    return owner


def _chain(start: str, versions) -> set[str]:
    """The row's ids after any song-version swaps (both sides of each swap)."""
    ids = {start}
    changed = True
    while changed:
        changed = False
        for src, dst, _via, _reason in versions:
            if src in ids and dst not in ("none", "-") and dst not in ids:
                ids.add(dst)
                changed = True
    return ids


def _version_result(ids: set[str], versions, kept) -> str:
    relevant = [event for event in versions if event[0] in ids]
    if relevant:
        src, dst, via, reason = relevant[-1]
        if dst in ("none", "-"):
            why = f"{via} {reason}".lower()
            return "none(cover)" if "cover" in why else "none"
        return f"{src}->{dst}"
    for item, why in reversed(kept):
        if item in ids:
            return f"kept({why})" if why else "kept"
    return "none"


def _video_result(ids: set[str], videos, summary, version: str) -> str:
    lines = [line for item, line in videos if item in ids]
    if any("no picture" in line for line in lines):
        return "no-picture"
    if any("playback failed" in line for line in lines):
        return "failed"
    if "->" in version:
        return "song-version"
    if summary is not None:
        return "rendered" if summary.video else "song"
    return "unknown"


def _occurrences(plays, summaries, versions, kept, held, released, videos):
    """Attach every event to the play occurrence it happened in."""
    count = len(plays)
    occ_versions: list[list] = [[] for _ in range(count)]
    occ_kept: list[list] = [[] for _ in range(count)]
    occ_held: list[list] = [[] for _ in range(count)]
    occ_released: list[list] = [[] for _ in range(count)]
    occ_videos: list[list] = [[] for _ in range(count)]
    occ_summary: list[Summary | None] = [None] * count
    if count == 0:
        return occ_summary, occ_versions, occ_kept, occ_held, occ_released, occ_videos
    for line_no, src, dst, via, reason in versions:
        occ_versions[_owner(plays, line_no)].append((src, dst, via, reason))
    for line_no, item, why in kept:
        occ_kept[_owner(plays, line_no)].append((item, why))
    for line_no, item in held:
        occ_held[_owner(plays, line_no)].append(item)
    for line_no, item in released:
        occ_released[_owner(plays, line_no)].append(item)
    for line_no, item, line in videos:
        occ_videos[_owner(plays, line_no)].append((item, line))
    chains = [_chain(play.id, occ_versions[i]) for i, play in enumerate(plays)]
    for summary in summaries:
        owner = _owner(plays, summary.line)
        # The app writes the outgoing summary before the next `play:` line, so
        # the latest unclaimed occurrence at or before it is the right one.
        target = next(
            (
                i
                for i in range(owner, -1, -1)
                if occ_summary[i] is None
                and (summary.id == plays[i].id or summary.id in chains[i])
            ),
            None,
        )
        if target is None:
            target = next(
                (
                    i
                    for i in range(owner + 1, count)
                    if occ_summary[i] is None
                    and (summary.id == plays[i].id or summary.id in chains[i])
                ),
                None,
            )
        if target is not None:
            occ_summary[target] = summary
    return occ_summary, occ_versions, occ_kept, occ_held, occ_released, occ_videos


def _track_rows(text: str, expected: int):
    plays, summaries, versions, kept, held, released, videos = _parse_log(text)
    (
        occ_summary,
        occ_versions,
        occ_kept,
        occ_held,
        occ_released,
        occ_videos,
    ) = _occurrences(plays, summaries, versions, kept, held, released, videos)
    total = expected if expected > 0 else len(plays)
    rows: list[str] = []
    audible = held_tracks = mv_kept = 0
    for offset in range(total):
        if offset >= len(plays):
            rows.append(
                f"REALUSE {offset + 1}/{total} id=- title=- artist=- "
                "firstAudio=NO AUDIO version=none gate=ok ended=not-started video=unknown"
            )
            continue
        play = plays[offset]
        row_versions = occ_versions[offset]
        ids = _chain(play.id, row_versions)
        summary = occ_summary[offset]
        version = _version_result(ids, row_versions, occ_kept[offset])
        gate_ids = sorted(
            item
            for item in occ_held[offset]
            if item in ids and item not in occ_released[offset]
        )
        gate = "HELD:" + ",".join(gate_ids) if gate_ids else "ok"
        video = _video_result(ids, occ_videos[offset], summary, version)
        title = summary.title if summary is not None else play.title
        if summary is not None and summary.first_audio is not None:
            first = f"{summary.first_audio}ms"
            audible += 1
        else:
            first = "NO AUDIO"
        if gate_ids:
            held_tracks += 1
        if summary is not None and summary.video and version.startswith("kept"):
            mv_kept += 1
        ended = summary.ended if summary is not None else "not-started"
        rows.append(
            f"REALUSE {offset + 1}/{total} id={play.id} "
            f"title={json.dumps(title, ensure_ascii=False)} "
            f"artist={json.dumps(play.artist, ensure_ascii=False)} "
            f"firstAudio={first} version={version} gate={gate} "
            f"ended={ended} video={video}"
        )
    summary_line = (
        f"REALUSE played {audible}/{total}, no-audio {total - audible}, "
        f"held {held_tracks}, mv-kept {mv_kept}"
    )
    return rows, summary_line, total


def play_button(xml_text: str) -> str | None:
    """The centre of the largest node labelled 재생/Play, or None."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return None
    found: list[tuple[int, int, int]] = []
    for node in root.iter("node"):
        labels = {(node.get("text") or "").strip(), (node.get("content-desc") or "").strip()}
        if not labels & {"재생", "Play"}:
            continue
        match = BOUNDS_RE.search(node.get("bounds") or "")
        if not match:
            continue
        left, top, right, bottom = (int(value) for value in match.groups())
        if right <= left or bottom <= top:
            continue
        found.append(((right - left) * (bottom - top), (left + right) // 2, (top + bottom) // 2))
    if not found:
        return None
    _area, x, y = max(found)
    return f"{x} {y}"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Judge a real-use smoke run.")
    sub = parser.add_subparsers(dest="command", required=True)
    summary = sub.add_parser("summary", help="one line per played track, plus the totals")
    summary.add_argument("logcat", help="logcat 파일 경로, 또는 - (표준 입력)")
    summary.add_argument("--report", help="마크다운 요약을 쓸 경로")
    summary.add_argument("--expected", type=int, default=0, help="재생을 시도한 곡 수")
    button = sub.add_parser("play-button", help="재생/Play 버튼의 탭 좌표를 출력")
    button.add_argument("xml", help="uiautomator dump 파일")
    args = parser.parse_args(argv)

    if args.command == "play-button":
        try:
            text = Path(args.xml).read_text(encoding="utf-8", errors="replace")
        except OSError as error:
            print(f"realuse: {error}", file=sys.stderr)
            return 2
        coords = play_button(text)
        if coords is None:
            print("realuse: no 재생/Play button", file=sys.stderr)
            return 3
        print(coords)
        return 0

    try:
        if args.logcat == "-":
            text = sys.stdin.read()
        else:
            text = Path(args.logcat).read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        print(f"realuse: 로그를 읽을 수 없습니다: {error}", file=sys.stderr)
        return 2

    rows, summary_line, total = _track_rows(text, args.expected)
    ok = total > 0 and "no-audio 0" in summary_line
    print("\n".join(rows))
    print(summary_line)
    if args.report:
        lines = [
            "# BitChord real-use smoke",
            "",
            f"- 로그: `{args.logcat}`",
            f"- 결과: **{'PASS' if ok else 'FAIL'}**",
            "",
            "```",
            *rows,
            summary_line,
            "```",
            "",
        ]
        Path(args.report).write_text("\n".join(lines), encoding="utf-8")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
