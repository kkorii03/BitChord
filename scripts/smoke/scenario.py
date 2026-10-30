#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

LINE_RE = re.compile(
    r"^(?P<ts>\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+"
    r"(?P<pid>\d+)\s+(?P<tid>\d+)\s+[VDIWEF]\s+(?P<tag>[^:]+):\s?(?P<msg>.*)$"
)
PLAY_RE = re.compile(
    r"play:\s*->\s+(?P<label>.*?)\s+\((?P<id>[^()]*)\)\s+idx=.*?\breason=(?P<reason>\S+)"
)
VIDEO_RADIO_RE = re.compile(
    r"video-radio:\s+seed=(?P<seed>\S+)\s+mode=(?P<mode>video|music)\b"
    r"(?P<rest>.*)"
)
AUTOPLAY_TITLES_RE = re.compile(
    r"autoplay:\s*\+radio\s+\d+\s+\+similar\s+\d+\s+\+fav\s+\d+\s*\((?P<body>.*)\)\s*$"
)
VERSION_SWAP_RE = re.compile(
    r"song version:\s*(?P<from>\S+)\s*->\s*(?P<to>\S+)\s+via\b"
)
C6_PIDS_RE = re.compile(r"smoke-scenario:\s*C6\b.*?\bpids=(?P<pids>[0-9 ]*)")
C6_PACKAGE_RE = re.compile(r"smoke-scenario:\s*C6\b.*?\bpackage=(?P<package>\S+)")
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
    pid: int | None = None
    tag: str = ""


@dataclass
class VideoRadio:
    ts: datetime | None
    seed: str
    mode: str
    picked: int | None
    rest: str


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
            lines.append(
                LogLine(
                    parse_ts(match.group("ts")),
                    match.group("msg"),
                    int(match.group("pid")),
                    match.group("tag"),
                )
            )
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
        # `autoplay: dropped ... (cover/karaoke)` is a rejection line, not a
        # queue: counting it as attached hid an empty recommendation list.
        if "autoplay: dropped" in msg:
            continue
        match = AUTOPLAY_TITLES_RE.search(msg)
        if not match:
            continue
        for part in match.group("body").split("|"):
            part = part.strip()
            if part:
                titles.append(part)
    return list(dict.fromkeys(titles))


def video_radios(lines: list[LogLine]) -> list[VideoRadio]:
    found: list[VideoRadio] = []
    for line in lines:
        match = VIDEO_RADIO_RE.search(line.msg)
        if not match:
            continue
        picked = re.search(r"\bpicked=(\d+)", match.group("rest"))
        found.append(
            VideoRadio(
                line.ts,
                match.group("seed"),
                match.group("mode"),
                int(picked.group(1)) if picked else None,
                match.group("rest").strip(),
            )
        )
    return found


def mode_of(lines: list[LogLine], seed_id: str | None) -> tuple[str | None, str]:
    """The current track's mode, from the video-radio decision line.

    The app writes this line when it fills autoplay, so it names the seed and
    the mode before the seed's own `track-summary` exists. Waiting for the
    summary only worked once a track ended, which a 60 s scenario never does.
    """
    radios = video_radios(lines)
    if seed_id is not None:
        for radio in radios:
            if radio.seed == seed_id:
                return radio.mode, f"video-radio seed={radio.seed}"
        return None, "no video-radio line for seed"
    if radios:
        return radios[0].mode, f"video-radio seed={radios[0].seed}"
    return None, "no video-radio line"


def attached(lines: list[LogLine]) -> tuple[list[str], str]:
    titles = autoplay_titles(lines)
    if titles:
        return titles, "autoplay"
    # The video-radio path appends without an `autoplay: +radio` title list;
    # its queue line carries the count and the tracks then played carry the
    # titles (10-31: A1 appended 10 and was still judged "0").
    appended = sum(
        int(match.group(1))
        for line in lines
        if (match := re.search(r"queue: autoplay-append n=(\d+)", line.msg))
    )
    if appended:
        played = [
            match.group(1)
            for line in lines
            if (match := re.search(r"play: -> (.+?) \([\w-]{11}\) idx=(?!0/)", line.msg))
        ]
        return (played + ["?"] * appended)[:max(appended, len(played))], "autoplay-append"
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
    if mode is None:
        return Result(name, "SKIP", actual)
    # Once the mode is known an empty list is a regression, not something to
    # skip: A1/A2 require five attached tracks.
    if not titles:
        return Result(name, "FAIL", actual)
    status = "PASS" if mode == "video" and len(titles) >= 5 else "FAIL"
    return Result(name, status, actual)


def judge_a1(lines: list[LogLine]) -> Result:
    return judge_cover_video(lines, "A1", "k4b7dnA8z0I")


def judge_a2(lines: list[LogLine]) -> Result:
    return judge_cover_video(lines, "A2", None)


def judge_a3(lines: list[LogLine]) -> Result:
    mode, mode_source = mode_of(lines, None)
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
    if mode is None:
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
    # A prior scenario's crossfade may sit in the same logcat file; the local
    # playback command is the boundary that makes this slice A9's own. Take the
    # last one so an earlier A9 attempt cannot supply the boundary either.
    locals_ = [
        line for line in lines if "debug-scenario: play-local" in line.msg
    ]
    local = locals_[-1] if locals_ else None
    if local is None or local.ts is None:
        return Result("A9", "SKIP", "play-local 로그 없음")
    after_local = [
        line
        for line in lines
        if line.ts is not None and line.ts >= local.ts
    ]
    start: LogLine | None = None
    fade: int | None = None
    for line in after_local:
        if "crossfade: start" in line.msg:
            start = line
            match = re.search(r"fade=(\d+)ms", line.msg)
            fade = int(match.group(1)) if match else None
            break
    if start is None:
        skipped = [line.msg for line in after_local if "crossfade: skipped" in line.msg]
        return Result(
            "A9",
            "SKIP",
            clip("crossfade: start 없음 " + (skipped[0] if skipped else "")),
        )
    if start.ts is None:
        return Result("A9", "SKIP", "crossfade: start 시각 없음")
    after = [
        line
        for line in after_local
        if line.ts is not None and line.ts >= start.ts
    ]
    local_plays = plays(after_local)
    first_local = local_plays[0] if local_plays else None
    found = plays(after)
    auto = [play for play in found if play.reason.upper() == "AUTO"]
    chosen = next(
        (
            play
            for play in auto
            if first_local is None or play.video_id != first_local.video_id
        ),
        None,
    )
    play_delta = (
        (chosen.ts - start.ts).total_seconds()
        if chosen is not None and chosen.ts is not None
        else None
    )
    finish = next((line for line in after if "finish levels" in line.msg), None)
    finish_delta = (
        (finish.ts - start.ts).total_seconds()
        if finish is not None and finish.ts is not None
        else None
    )
    gains = [
        line.msg
        for line in after
        if "handoff levels" in line.msg
        or "finish levels" in line.msg
        or "gain" in line.msg.casefold()
    ]
    marker = next(
        (
            match.group("files")
            for line in lines
            if (
                match := re.search(
                    r"smoke-scenario:\s*A9\b.*?\bfiles=(?P<files>\S+)", line.msg
                )
            )
        ),
        None,
    )
    expected = [name for name in re.split(r"[|,]", marker or "") if name]
    pushed_ok = True
    if chosen is not None:
        if expected:
            pushed_ok = any(
                name in chosen.label or name in chosen.video_id for name in expected
            )
        else:
            pushed_ok = chosen.video_id.startswith(("file://", "content://"))
    actual = (
        f"fade={fade}ms play={play_delta if play_delta is None else round(play_delta, 1)}s"
        f" reason={chosen.reason if chosen else '-'}"
        f" pushed={pushed_ok}"
        f" finish={finish_delta if finish_delta is None else round(finish_delta, 1)}s"
        f" gain={clip(' | '.join(gains[:3]))}"
    )
    if fade is None:
        return Result("A9", "SKIP", actual)
    if chosen is None:
        return Result("A9", "FAIL", actual)
    # The queue hands over to the next track mid-blend by design (10-31 run:
    # play at 4.3 s of an 8 s fade); the overlap is start -> finish, so that
    # is what must match the planned fade. The AUTO play only has to exist.
    play_ok = (
        play_delta is not None
        and finish_delta is not None
        and abs(finish_delta - fade / 1000.0) <= 1.0
    )
    return Result("A9", "PASS" if play_ok and pushed_ok else "FAIL", actual)


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

    # run.sh sends repeat modes 0,1,2,0 (Player.REPEAT_MODE_OFF/ONE/ALL/OFF),
    # so the app must report one, all, off in that order.
    repeat_ok = ordered(repeat, ["one", "all", "off"])
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
    # The blocked track's own play line is the clock. The scenario marker sits
    # before the VIEW intent, so measuring from it counted the launch latency
    # as recovery time. A prior play before the marker must not become "next".
    start = seed or marker
    extraction = sum(
        1 for line in lines if "extraction attempt 2 of 3" in line.msg
    )
    if start is None or start.ts is None:
        return Result("C5", "SKIP", "시작 로그 없음")
    # A version swap is the same queue row under another audio id, not the
    # queue advancing. Exclude the ids it names so it cannot fake recovery.
    swapped = {
        match.group("to")
        for line in lines
        if (match := VERSION_SWAP_RE.search(line.msg))
        and match.group("from") == "7QihqRWMrz8"
    }
    nxt = next(
        (
            play
            for play in found
            if play.ts is not None
            and play.ts > start.ts
            and play.video_id != "7QihqRWMrz8"
            and play.video_id not in swapped
        ),
        None,
    )
    if nxt is None or nxt.ts is None:
        # Recovering to the same song's playable copy is the intended outcome
        # (10-31: swapped to its song version); on runners that copy cannot
        # sound either, so judge the swap's timing, not a later play.
        swap_at = next(
            (
                line.ts
                for line in lines
                if line.ts is not None
                and line.ts > start.ts
                and (
                    (VERSION_SWAP_RE.search(line.msg) and "7QihqRWMrz8" in line.msg)
                    or ("unplayable: 7QihqRWMrz8" in line.msg and "-> alt" in line.msg)
                )
            ),
            None,
        )
        if swap_at is not None:
            delta = (swap_at - start.ts).total_seconds()
            status = "PASS" if delta <= 5.0 and extraction == 0 else "FAIL"
            return Result(
                "C5", status, f"swap {sorted(swapped)} {delta:.1f}s extraction2={extraction}"
            )
        return Result(
            "C5", "FAIL", f"다음 곡 없음 extraction2={extraction} swapped={sorted(swapped)}"
        )
    delta = (nxt.ts - start.ts).total_seconds()
    actual = (
        f"next={nxt.video_id} {delta:.1f}s extraction2={extraction}"
    )
    status = "PASS" if delta <= 5.0 and extraction == 0 else "FAIL"
    return Result("C5", status, actual)


def judge_c6(lines: list[LogLine]) -> Result:
    pids: set[int] = set()
    package = ""
    for line in lines:
        if match := C6_PIDS_RE.search(line.msg):
            pids = {int(pid) for pid in match.group("pids").split() if pid}
        if match := C6_PACKAGE_RE.search(line.msg):
            package = match.group("package")

    def ours(line: LogLine) -> bool:
        # The system log also carries other processes' crashes. Keep only the
        # app's own pid (marked by run.sh) or a line that names its package.
        if line.pid is not None and line.pid in pids:
            return True
        if package and package in line.msg:
            return True
        if not pids and line.tag == "BitChord":
            return True
        return False

    fatal = sum(1 for line in lines if ours(line) and "FATAL EXCEPTION" in line.msg)
    anr = sum(1 for line in lines if ours(line) and ANR_RE.search(line.msg))
    retry = sum(1 for line in lines if ours(line) and "retry" in line.msg.casefold())
    actual = f"FATAL={fatal} ANR={anr} retry={retry} pids={sorted(pids)}"
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
    passed = [result for result in results if result.status == "PASS"]
    skipped = [result for result in results if result.status == "SKIP"]
    if failed:
        verdict = "FAIL"
    elif passed:
        verdict = "PASS"
    else:
        # Nothing verified is not a green run: CI must not pass on an all-SKIP
        # report just because no scenario failed.
        verdict = "SKIP"
    lines.extend(
        [
            "",
            f"결과: **{verdict}** "
            f"(PASS {len(passed)}, FAIL {len(failed)}, SKIP {len(skipped)})",
        ]
    )
    if failed:
        lines.append("실패: " + ", ".join(result.name for result in failed))
    return "\n".join(lines)


def exit_code(results: list[Result]) -> int:
    if any(result.status == "FAIL" for result in results):
        return 1
    if not any(result.status == "PASS" for result in results):
        return 3
    return 0


def main(argv: list[str] | None = None) -> int:
    # Also run on the Windows PC (cp949 console) to re-judge downloaded logs.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
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
    return exit_code(results)


if __name__ == "__main__":
    sys.exit(main())
