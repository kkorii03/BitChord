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

# `play: -> <title> - <artist> (<id>) idx=<n>/<total> ...`
PLAY_RE = re.compile(
    r"play:\s*->\s+(?P<title>.*?)\s+-\s+(?P<artist>.*?)\s+"
    r"\((?P<id>[^()]*)\)\s+idx=(?P<idx>\d+)/"
)
# `track-summary: <id> '<title>' video=y ... firstAudio=123|- ... ended=natural`
SUMMARY_RE = re.compile(
    r"track-summary:\s+(?P<id>\S+)\s+'(?P<title>.*?)'\s+video=(?P<video>[yn])"
    r".*?\bfirstAudio=(?P<first>-|\d+).*?\bended=(?P<ended>\S+)"
)
# `song version: <from> -> <to> via <how>`
VERSION_RE = re.compile(
    r"song version:\s+(?P<src>\S+)\s+->\s+(?P<dst>\S+)\s+via\s+(?P<via>\S+)"
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


@dataclass
class Summary:
    id: str
    title: str
    video: bool
    first_audio: int | None
    ended: str


def _parse_log(text: str):
    plays: list[Play] = []
    summaries: dict[str, Summary] = {}
    versions: list[tuple[str, str, str]] = []
    kept: dict[str, str] = {}
    held: set[str] = set()
    released: set[str] = set()
    videos: dict[str, list[str]] = {}

    for raw in text.splitlines():
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
                    )
                )
            continue
        match = SUMMARY_RE.search(line)
        if match:
            summaries[match.group("id")] = Summary(
                id=match.group("id"),
                title=match.group("title"),
                video=match.group("video") == "y",
                first_audio=None
                if match.group("first") == "-"
                else int(match.group("first")),
                ended=match.group("ended"),
            )
            continue
        match = VERSION_RE.search(line)
        if match:
            versions.append(
                (match.group("src"), match.group("dst"), match.group("via"))
            )
        match = KEPT_RE.search(line)
        if match:
            kept[match.group("id")] = match.group("why") or ""
        match = HOLD_RE.search(line)
        if match:
            held.add(match.group("id"))
        match = RELEASE_RE.search(line)
        if match:
            released.add(match.group("id"))
        if "video:" in line:
            bracket = BRACKET_RE.search(line)
            if bracket:
                videos.setdefault(bracket.group("id"), []).append(line)
    return plays, summaries, versions, kept, held, released, videos


def _chain(start: str, versions: list[tuple[str, str, str]]) -> set[str]:
    """The row's ids after any song-version swaps (both sides of each swap)."""
    ids = {start}
    changed = True
    while changed:
        changed = False
        for src, dst, _via in versions:
            if src in ids and dst not in ("none", "-") and dst not in ids:
                ids.add(dst)
                changed = True
    return ids


def _version_result(ids: set[str], versions, kept, covers) -> str:
    relevant = [event for event in versions if event[0] in ids]
    if relevant:
        src, dst, _via = relevant[-1]
        if dst in ("none", "-"):
            return "none(cover)" if src in covers or src in ids else "none"
        return f"{src}->{dst}"
    if any(item in covers for item in ids):
        return "cover"
    for item in ids:
        if item in kept:
            why = kept[item]
            return f"kept({why})" if why else "kept"
    return "none"


def _video_result(ids: set[str], videos, summary, version: str) -> str:
    lines = [line for item in ids for line in videos.get(item, [])]
    if any("no picture" in line for line in lines):
        return "no-picture"
    if any("playback failed" in line for line in lines):
        return "failed"
    if "->" in version:
        return "song-version"
    if summary is not None:
        return "rendered" if summary.video else "song"
    return "unknown"


def _track_rows(text: str, expected: int):
    plays, summaries, versions, kept, held, released, videos = _parse_log(text)
    covers: set[str] = set()
    for src, dst, _via in versions:
        if dst in ("none", "-"):
            covers.add(src)
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
        ids = _chain(play.id, versions)
        summary = next((summaries[item] for item in ids if item in summaries), None)
        version = _version_result(ids, versions, kept, covers)
        gate_ids = sorted(item for item in ids if item in held and item not in released)
        gate = "HELD:" + ",".join(gate_ids) if gate_ids else "ok"
        video = _video_result(ids, videos, summary, version)
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
    return rows, summary_line


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

    rows, summary_line = _track_rows(text, args.expected)
    print("\n".join(rows))
    print(summary_line)
    if args.report:
        lines = [
            "# BitChord real-use smoke",
            "",
            f"- 로그: `{args.logcat}`",
            f"- 결과: **{'FAIL' if 'no-audio 0' not in summary_line else 'PASS'}**",
            "",
            "```",
            *rows,
            summary_line,
            "```",
            "",
        ]
        Path(args.report).write_text("\n".join(lines), encoding="utf-8")
    return 0 if "no-audio 0" in summary_line else 1


if __name__ == "__main__":
    raise SystemExit(main())
