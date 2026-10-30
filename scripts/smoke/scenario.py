#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

LINE_RE = re.compile(
    r"^(?P<ts>\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+\d+\s+\d+\s+[VDIWEF]\s+[^:]+:\s?(?P<msg>.*)$"
)
PLAY_RE = re.compile(
    r"play:\s*->\s+(?P<label>.*?)\s+\((?P<id>[^()]*)\)\s+idx=.*?\breason=(?P<reason>\S+)"
)
TRACK_SUMMARY_RE = re.compile(
    r"track-summary:\s+(?P<id>\S+)\s+'(?P<title>.*?)'\s+video=(?P<video>[yn])\b"
)
ANR_RE = re.compile(r"\bANR in\b")

ORDER = ["A1", "A2", "A3", "A4", "A9", "B3", "C2", "C5", "C6"]

CRITERIA = {
    "A1": "mode=video 이고 붙은 곡 >=5",
    "A2": "mode=video 이고 붙은 곡 >=5",
    "A3": "mode=music 이고 붙은 곡 제목에 cover/커버 0",
    "A4": "다음 곡 id 3회 모두 다름, reason=REPEAT 0",
    "A9": "crossfade start~다음 곡/finish 겹침이 fade±1초",
    "B3": "repeat off/all/one/off, shuffle on/off, autoplay on/off 로그 일치",
    "C2": "강제 종료 후 10분간 lifecycle: process start 0",
    "C5": "다음 곡까지 5초 이내, extraction attempt 2 of 3 없음",
    "C6": "FATAL/ANR 0",
}


@dataclass
class LogLine:
    ts: datetime | None
    msg: str


@dataclass
class Play:
    ts: datetime | None
    label: str
    video_id: str
    reason: str


@dataclass
class Result:
    name: str
    status: str
    actual: str

    @property
    def criterion(self) -> str:
        return CRITERIA[self.name]


def parse_ts(text: str) -> datetime | None:
    try:
        return datetime.strptime("2000-" + text, "%Y-%m-%d %H:%M:%S.%f")
    except ValueError:
        return None


def read_lines(path: Path) -> list[LogLine]:
    lines: list[LogLine] = []
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = LINE_RE.match(raw)
        if match:
            lines.append(LogLine(parse_ts(match.group("ts")), match.group("msg")))
        else:
            lines.append(LogLine(None, raw))
    return lines


def plays(lines: list[LogLine]) -> list[Play]:
    found: list[Play] = []
    for line in lines:
        match = PLAY_RE.search(line.msg)
        if match:
            found.append(
                Play(
                    line.ts,
                    match.group("label"),
                    match.group("id"),
                    match.group("reason"),
                )
            )
    return found


def clip(text: str, limit: int = 180) -> str:
    text = text.replace("\n", " ").replace("\r", " ").strip()
    return text if len(text) <= limit else text[: limit - 3] + "..."


def markdown(text: str) -> str:
    return clip(text).replace("|", "\\|")


def autoplay_titles(lines: list[LogLine]) -> list[str]:
    titles: list[str] = []
    for line in lines:
        msg = line.msg
        body = ""
        if "autoplay:" in msg and "(" in msg and msg.rstrip().endswith(")"):
            body = msg[msg.rfind("(") + 1 : msg.rfind(")")]
        elif "video-radio:" in msg:
            body = msg.split("video-radio:", 1)[1].strip()
            if "(" in body and body.rstrip().endswith(")"):
                body = body[body.rfind("(") + 1 : body.rfind(")")]
        else:
            continue
        for part in body.split("|"):
            part = part.strip()
            if part:
                titles.append(part)
    return list(dict.fromkeys(titles))


def mode_of(lines: list[LogLine], target_id: str | None) -> tuple[str | None, str]:
    for line in lines:
        match = re.search(r"\bmode[=:]\s*(video|music)\b", line.msg)
        if match:
            return match.group(1), "명시 로그"
    found = plays(lines)
    target = target_id or (found[0].video_id if found else None)
    for line in lines:
        match = TRACK_SUMMARY_RE.search(line.msg)
        if match and (target is None or match.group("id") == target):
            return ("video" if match.group("video") == "y" else "music"), "track-summary"
    return None, "로그 없음"


def attached(lines: list[LogLine]) -> tuple[list[str], str]:
    titles = autoplay_titles(lines)
    if titles:
        return titles, "autoplay/video-radio"
    found = plays(lines)
    if len(found) > 1:
        return [play.label for play in found[1:]], "play 줄"
    return [], "로그 없음"


def judge_cover_video(lines: list[LogLine], name: str, seed_id: str | None) -> Result:
    found = plays(lines)
    target = seed_id or (found[0].video_id if found else None)
    mode, mode_source = mode_of(lines, target)
    titles, title_source = attached(lines)
    actual = (
        f"mode={mode or '?'}({mode_source}) attached={len(titles)}"
        f"({title_source}) [{clip(', '.join(titles))}]"
    )
    if mode is None or not titles:
        return Result(name, "SKIP", actual)
    status = "PASS" if mode == "video" and len(titles) >= 5 else "FAIL"
    return Result(name, status, actual)


def judge_a1(lines: list[LogLine]) -> Result:
    return judge_cover_video(lines, "A1", "k4b7dnA8z0I")


def judge_a2(lines: list[LogLine]) -> Result:
    return judge_cover_video(lines, "A2", None)


def judge_a3(lines: list[LogLine]) -> Result:
    found = plays(lines)
    target = found[0].video_id if found else None
    mode, mode_source = mode_of(lines, target)
    titles, title_source = attached(lines)
    cover = sum(
        1
        for title in titles
        if "cover" in title.casefold() or "커버" in title.casefold()
    )
    actual = (
        f"mode={mode or '?'}({mode_source}) attached={len(titles)}"
        f"({title_source}) cover={cover} [{clip(', '.join(titles))}]"
    )
    if mode is None or not titles:
        return Result("A3", "SKIP", actual)
    status = "PASS" if mode == "music" and cover == 0 else "FAIL"
    return Result("A3", status, actual)


def judge_a4(lines: list[LogLine]) -> Result:
    found = plays(lines)
    ids = [play.video_id for play in found]
    repeat = sum(1 for line in lines if "reason=REPEAT" in line.msg)
    actual = f"ids={ids[:3]} plays={len(ids)} repeat={repeat}"
    if not found:
        return Result("A4", "SKIP", actual)
    if len(ids) < 3:
        return Result("A4", "FAIL", actual)
    status = "PASS" if len(set(ids[:3])) == 3 and repeat == 0 else "FAIL"
    return Result("A4", status, actual)


def judge_a9(lines: list[LogLine]) -> Result:
    start: LogLine | None = None
    fade: int | None = None
    for line in lines:
        if "crossfade: start" in line.msg:
            start = line
            match = re.search(r"fade=(\d+)ms", line.msg)
            fade = int(match.group(1)) if match else None
            break
    if start is None:
        skipped = [line.msg for line in lines if "crossfade: skipped" in line.msg]
        return Result(
            "A9",
            "SKIP",
            clip("crossfade: start 없음 " + (skipped[0] if skipped else "")),
        )
    after = [
        line
        for line in lines
        if line.ts is not None and start.ts is not None and line.ts > start.ts
    ]
    found = plays(after)
    auto = [play for play in found if play.reason.upper() == "AUTO"]
    chosen = auto[0] if auto else (found[0] if found else None)
    play_delta = (
        (chosen.ts - start.ts).total_seconds()
        if chosen is not None and chosen.ts is not None and start.ts is not None
        else None
    )
    finish = next((line for line in after if "finish levels" in line.msg), None)
    finish_delta = (
        (finish.ts - start.ts).total_seconds()
        if finish is not None and finish.ts is not None and start.ts is not None
        else None
    )
    gains = [
        line.msg
        for line in after
        if "handoff levels" in line.msg
        or "finish levels" in line.msg
        or "gain" in line.msg.casefold()
    ]
    actual = (
        f"fade={fade}ms play={play_delta if play_delta is None else round(play_delta, 1)}s"
        f" reason={chosen.reason if chosen else '-'}"
        f" finish={finish_delta if finish_delta is None else round(finish_delta, 1)}s"
        f" gain={clip(' | '.join(gains[:3]))}"
    )
    if fade is None:
        return Result("A9", "SKIP", actual)
    play_ok = (
        chosen is not None
        and chosen.reason.upper() == "AUTO"
        and play_delta is not None
        and abs(play_delta - fade / 1000.0) <= 1.0
    )
    finish_ok = (
        finish_delta is not None and abs(finish_delta - fade / 1000.0) <= 1.0
    )
    if chosen is None and finish is None:
        return Result("A9", "SKIP", actual)
    return Result("A9", "PASS" if play_ok or finish_ok else "FAIL", actual)


def judge_b3(lines: list[LogLine]) -> Result:
    repeat = [
        match.group(1)
        for line in lines
        if (match := re.search(r"mode: repeat=(\w+)", line.msg))
    ]
    shuffle = [
        match.group(1)
        for line in lines
        if (match := re.search(r"mode: shuffle=(on|off)", line.msg))
    ]
    autoplay = [
        match.group(1)
        for line in lines
        if (match := re.search(r"setting: autoplay = (true|false)", line.msg))
    ]
    actual = (
        f"repeat={repeat} shuffle={shuffle} autoplay={autoplay}"
    )
    if not repeat and not shuffle and not autoplay:
        return Result("B3", "SKIP", actual)

    def ordered(values: list[str], expected: list[str]) -> bool:
        index = 0
        for value in values:
            if index < len(expected) and value == expected[index]:
                index += 1
        return index == len(expected)

    repeat_ok = ordered(repeat, ["all", "one", "off"])
    shuffle_ok = "on" in shuffle and "off" in shuffle
    autoplay_ok = "true" in autoplay and "false" in autoplay
    status = "PASS" if repeat_ok and shuffle_ok and autoplay_ok else "FAIL"
    return Result("B3", status, actual)


def judge_c2(lines: list[LogLine]) -> Result:
    starts = [line.msg for line in lines if "lifecycle: process start" in line.msg]
    actual = f"process start={len(starts)}"
    if not lines:
        return Result("C2", "SKIP", actual)
    return Result("C2", "PASS" if not starts else "FAIL", actual)


def judge_c5(lines: list[LogLine]) -> Result:
    found = plays(lines)
    marker = next(
        (line for line in lines if "smoke-scenario: C5 start" in line.msg), None
    )
    seed = next((play for play in found if play.video_id == "7QihqRWMrz8"), None)
    start = marker or seed
    extraction = sum(
        1 for line in lines if "extraction attempt 2 of 3" in line.msg
    )
    if start is None or start.ts is None:
        return Result("C5", "SKIP", "시작 로그 없음")
    nxt = next(
        (
            play
            for play in found
            if play.ts is not None
            and play.ts > start.ts
            and play.video_id != "7QihqRWMrz8"
        ),
        None,
    )
    if nxt is None or nxt.ts is None:
        return Result(
            "C5", "FAIL", f"다음 곡 없음 extraction2={extraction}"
        )
    delta = (nxt.ts - start.ts).total_seconds()
    actual = (
        f"next={nxt.video_id} {delta:.1f}s extraction2={extraction}"
    )
    status = "PASS" if delta <= 5.0 and extraction == 0 else "FAIL"
    return Result("C5", status, actual)


def judge_c6(lines: list[LogLine]) -> Result:
    fatal = sum(1 for line in lines if "FATAL EXCEPTION" in line.msg)
    anr = sum(1 for line in lines if ANR_RE.search(line.msg))
    retry = sum(1 for line in lines if "retry" in line.msg.casefold())
    actual = f"FATAL={fatal} ANR={anr} retry={retry}"
    if not lines:
        return Result("C6", "SKIP", actual)
    return Result("C6", "PASS" if fatal == 0 and anr == 0 else "FAIL", actual)


JUDGES = {
    "A1": judge_a1,
    "A2": judge_a2,
    "A3": judge_a3,
    "A4": judge_a4,
    "A9": judge_a9,
    "B3": judge_b3,
    "C2": judge_c2,
    "C5": judge_c5,
    "C6": judge_c6,
}


def render(results: list[Result], directory: Path) -> str:
    lines = [
        "# BitChord 검사 시나리오",
        "",
        f"- 로그 구간: `{directory}`",
        "",
        "| 이름 | 합격 기준 | 실제 값 | 판정 |",
        "|---|---|---|---|",
    ]
    for result in results:
        lines.append(
            f"| {result.name} | {markdown(result.criterion)} | "
            f"{markdown(result.actual)} | {result.status} |"
        )
    failed = [result for result in results if result.status == "FAIL"]
    lines.extend(
        [
            "",
            f"결과: **{'FAIL' if failed else 'PASS'}** "
            f"({len(failed)}/{len(results)} 실패)",
        ]
    )
    if failed:
        lines.append("실패: " + ", ".join(result.name for result in failed))
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", default="shots/scenario")
    parser.add_argument("--report", default="shots/scenario-report.md")
    args = parser.parse_args(argv)

    directory = Path(args.dir)
    results: list[Result] = []
    for name in ORDER:
        path = directory / f"{name}.txt"
        if not path.is_file():
            results.append(Result(name, "SKIP", "로그 구간 없음"))
            continue
        lines = read_lines(path)
        if not lines:
            results.append(Result(name, "SKIP", "로그 구간 비어 있음"))
            continue
        results.append(JUDGES[name](lines))

    report = render(results, directory)
    print(report)
    try:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(report + "\n", encoding="utf-8")
    except OSError as error:
        print(f"scenario: 보고서를 쓸 수 없습니다: {error}", file=sys.stderr)
        return 2
    return 1 if any(result.status == "FAIL" for result in results) else 0


if __name__ == "__main__":
    sys.exit(main())
