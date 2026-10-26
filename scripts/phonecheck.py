#!/usr/bin/env python3
"""One-shot phone verification for BitChord.

Plays scripts/smoke/songs.txt on the connected phone, keeps the whole logcat
stream, pulls the sync-test WAVs, then writes one report with the smoke checks
(MV, HQ, cover, crash) and the lyric-sync scores.

Standard library only. From the BitChord-merge folder:

    tools\\python\\python.exe -I scripts\\phonecheck.py --apk <apk>
    tools\\python\\python.exe -I scripts\\phonecheck.py --no-install

The run is about 40 minutes because the phone plays every row in order.
Result: phone-out/phonecheck/logcat.txt, phone-out/phonecheck/synctest/*.wav
and phone-out/phonecheck/report.md.
"""

from __future__ import annotations

import argparse
import datetime
import json
import re
import shlex
import statistics
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import smokecheck
import synctest

PKG = "com.music.bitchord"
APP_DIR = f"/sdcard/Android/data/{PKG}/files"
SONGS_FILE = ROOT / "scripts" / "smoke" / "songs.txt"
OUT = ROOT / "phone-out" / "phonecheck"
WAIT_SECONDS = {"mv": 45, "hq": 90, "cover": 75, "plain": 75}
DEFAULT_WAIT = 75
MODEL = "whisper-large"
SYNC_DUMP_BEGIN_RE = re.compile(r"sync-dump:\s+begin\s+(\S+)")


def log(msg: str) -> None:
    print(msg, flush=True)


# ---------- phone ----------


def ensure_phone() -> bool:
    # synctest.ensure_phone writes its own progress file, so its output folder
    # has to exist before the first log() call.
    synctest.OUT.mkdir(parents=True, exist_ok=True)
    return synctest.ensure_phone()


def install_apk(path: Path) -> bool:
    result = synctest.adb("install", "-r", str(path), timeout=600)
    log(result.strip() or "adb install returned no output")
    return "Success" in result


def package_installed() -> bool:
    return "package:" in synctest.adb("shell", "pm", "path", PKG)


def play_song(spec: smokecheck.SongSpec) -> None:
    if spec.search:
        query = shlex.quote(spec.search)
        synctest.adb(
            "shell",
            f"am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH "
            f"-p {shlex.quote(PKG)} --es query {query}",
        )
    else:
        synctest.adb(
            "shell",
            f"am start -a android.intent.action.VIEW "
            f"-d 'https://music.youtube.com/watch?v={spec.video_id}' "
            f"-p {shlex.quote(PKG)}",
        )


class LogcatStream:
    """Keep adb logcat running for the whole playback, not just a final dump.

    The default ring buffer wraps during a 40-minute list, so an end-of-run
    `logcat -d` would lose the early track-summary/play lines.
    """

    def __init__(self, path: Path):
        self.path = path
        self.proc: subprocess.Popen[bytes] | None = None
        self.file = None

    def start(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        synctest.adb("logcat", "-c")
        self.file = open(self.path, "wb")
        self.proc = subprocess.Popen(
            [synctest.ADB, "logcat", "-v", "threadtime"],
            stdout=self.file,
            stderr=subprocess.DEVNULL,
        )

    def stop(self) -> None:
        if self.proc is not None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait()
            self.proc = None
        if self.file is not None:
            self.file.flush()
            self.file.close()
            self.file = None


def pull_wavs() -> int:
    dest = OUT / "synctest"
    dest.mkdir(parents=True, exist_ok=True)
    listing = synctest.adb("shell", "ls", f"{APP_DIR}/synctest")
    pulled = 0
    for raw in listing.splitlines():
        name = raw.strip().rstrip("\r")
        if not name.endswith(".wav"):
            continue
        synctest.adb("pull", f"{APP_DIR}/synctest/{name}", str(dest / name), timeout=180)
        pulled += 1
    return pulled


# ---------- song list ----------


def load_songs(path: Path = SONGS_FILE) -> list[smokecheck.SongSpec]:
    return smokecheck.parse_songs(Path(path).read_text(encoding="utf-8", errors="replace"))


def wait_seconds(kind: str) -> int:
    return WAIT_SECONDS.get(kind, DEFAULT_WAIT)


# ---------- judgement ----------


def mv_progress(text: str, songs: list[smokecheck.SongSpec]) -> tuple[int, int]:
    """(MV rows that were swapped for a song version, unique MV rows)."""
    mv_specs = [spec for spec in songs if spec.kind == "mv" and spec.video_id]
    total = len(dict.fromkeys(spec.video_id for spec in mv_specs))
    if not total:
        return 0, 0
    checks = smokecheck.check_log(text, songs=songs, hq_configured=True)
    check = next(check for check in checks if check.number == 1)
    failed = len(check.details) if check.status == "FAIL" else 0
    return max(0, total - failed), total


def hq_counts(text: str) -> dict[str, int]:
    accepted = rejected = decode_failed = no_reference = 0
    for line in text.splitlines():
        if "hq-verify:" in line:
            if smokecheck.HQ_NO_REFERENCE_RE.search(line):
                no_reference += 1
            else:
                match = smokecheck.HQ_VERDICT_RE.search(line)
                if match:
                    if match.group("verdict") == "accepted":
                        accepted += 1
                    else:
                        rejected += 1
        if smokecheck.DECODE_HQ_FAIL_RE.search(line):
            decode_failed += 1
    return dict(
        accepted=accepted,
        rejected=rejected,
        decode_failed=decode_failed,
        no_reference=no_reference,
    )


def sync_dump_ids(text: str) -> list[str]:
    found: list[str] = []
    for raw in text.splitlines():
        match = SYNC_DUMP_BEGIN_RE.search(raw)
        if match and match.group(1) not in found:
            found.append(match.group(1))
    return found


def sync_candidates(
    songs: list[smokecheck.SongSpec], text: str
) -> list[tuple[str, str, str]]:
    """(label, kind, video id) for every cover/plain row that has an id.

    A `search:` row has no id until it plays. Its sync-dump ids are taken in
    log order from the ids that no explicit row already claims.
    """
    rows: list[tuple[str, str, str]] = []
    seen: set[str] = set()
    for spec in songs:
        if spec.kind not in ("cover", "plain") or not spec.video_id:
            continue
        if spec.video_id in seen:
            continue
        seen.add(spec.video_id)
        rows.append((spec.key, spec.kind, spec.video_id))

    search_specs = [
        spec for spec in songs if spec.kind in ("cover", "plain") and spec.search
    ]
    if search_specs:
        # The id each search actually played, read from the log right after it
        # played (SEARCH_IDS); guessing from unclaimed sync-dump ids handed MV
        # replacement ids to the search rows.
        for spec in search_specs:
            vid = SEARCH_IDS.get(spec.key, "") or played_id_for_search(spec.key, text)
            rows.append((spec.key, spec.kind, vid))
    return rows


def played_id_for_search(key: str, text: str) -> str:
    """For --score-only: the id the log played for a `search:<title> <artist>` row.

    The live run records it right after playing; a re-score has only the log,
    so the last `play: ->` line whose label starts with the query's first word
    (the title) is taken.
    """
    query = key.split(":", 1)[-1].strip()
    first = query.split()[0].lower() if query else ""
    if not first:
        return ""
    found = ""
    for match in re.finditer(r"play: -> (.*?) \(([\w-]{11})\) idx=", text):
        if match.group(1).lower().startswith(first):
            found = match.group(2)
    return found


# key of a search: row -> the video id it played (filled while playing)
SEARCH_IDS: dict[str, str] = {}


def read_deepgram_key() -> str:
    # The Windows layout keeps keys next to the BitChord folder (synctest.KEY_FILE);
    # a checkout may also carry keys/ at its own root. Read-only either way.
    for path in (ROOT / "keys" / "deepgram_key.txt", synctest.KEY_FILE):
        try:
            key = path.read_text(encoding="utf-8").strip()
        except OSError:
            continue
        if key:
            return key
    return ""



def fetch_reference_wav(video_id: str, wav: Path) -> None:
    """First 100 s of the YouTube upload as 16 kHz mono WAV (yt-dlp + ffmpeg from tools\\bin)."""
    tools = Path(synctest.YTDLP).parent  # Desktop\BitChord\tools\bin, outside the checkout
    wav.parent.mkdir(parents=True, exist_ok=True)
    raw = wav.with_suffix(".m4a")
    try:
        if not raw.exists():
            subprocess.run(
                [str(tools / "yt-dlp.exe"), "-f", "bestaudio", "-o", str(raw),
                 f"https://www.youtube.com/watch?v={video_id}"],
                capture_output=True, timeout=600,
            )
        if raw.exists():
            subprocess.run(
                [str(tools / "ffmpeg.exe"), "-y", "-loglevel", "error", "-i", str(raw),
                 "-t", "100", "-ac", "1", "-ar", "16000", str(wav)],
                capture_output=True, timeout=300,
            )
    except (OSError, subprocess.SubprocessError):
        pass

def deepgram_wav(path: Path, language: str, model: str, key: str) -> list | None:
    """Send the WAV as a stream; never load the whole recording into memory."""
    if not key:
        return None
    url = (
        f"https://api.deepgram.com/v1/listen?model={model}&language={language}"
        "&punctuate=false&smart_format=false&numerals=false"
    )
    for attempt in range(3):
        try:
            with open(path, "rb") as audio:
                request = urllib.request.Request(
                    url,
                    data=audio,
                    method="POST",
                    headers={
                        "Authorization": f"Token {key}",
                        "Content-Type": "audio/wav",
                        "Content-Length": str(path.stat().st_size),
                    },
                )
                with urllib.request.urlopen(request, timeout=300) as response:
                    body = json.load(response)
            alternative = body["results"]["channels"][0]["alternatives"][0]
            return [
                (word["word"], float(word["start"]), float(word["end"]))
                for word in alternative.get("words", [])
            ]
        except Exception as error:  # 429 / network: back off and retry
            log(f"  deepgram error ({error}); retry {attempt + 1}")
            time.sleep(10 * (attempt + 1))
    return None


def sync_row(
    text: str,
    label: str,
    kind: str,
    video_id: str,
    wav_dir: Path,
    transcribe,
) -> dict:
    row: dict = dict(label=label, kind=kind, video=video_id, errors=[])
    if not video_id:
        row["error"] = "검색 곡 id 없음 (sync-dump 없음)"
        return row
    dump = synctest.parse_dump(text, video_id)
    if not dump:
        row["error"] = "no sync-dump (가사가 표시되지 않음?)"
        return row
    _head, lines = dump
    row["lines"] = len(lines)
    if not lines:
        row["error"] = "no timed lines"
        return row

    wav = wav_dir / f"{video_id}.wav"
    if (not wav.exists() or wav.stat().st_size <= 1000) and kind in ("cover", "plain"):
        # The app kept no WAV (no song check ran - an emulator without audio or
        # Deepgram, or a song version, which never needs one). A cover plays as
        # itself and a plain row plays its song version (an HQ copy is verified
        # to within a few ms of it), so YouTube's own copy of the played id is
        # the same recording: judge the timing the app displayed against it.
        fetch_reference_wav(video_id, wav)
    if not wav.exists() or wav.stat().st_size <= 1000:
        row["error"] = "no wav (song check skipped)"
        return row

    try:
        words = transcribe(wav, synctest.language_for(lines), MODEL)
    except Exception as error:
        row["error"] = f"deepgram failed: {error}"
        return row
    if words is None:
        row["error"] = "deepgram failed"
        return row

    matched = synctest.align(lines, words)
    score = synctest.score(matched)
    if score is None:
        row["error"] = "no line matched the heard words"
        return row
    row.update(score)
    row["errors"] = [shown - sung for _, shown, sung, _ in matched]
    row["audio"] = "app wav"
    return row


def score_sync(
    text: str,
    songs: list[smokecheck.SongSpec],
    wav_dir: Path,
    transcribe=None,
) -> list[dict]:
    key = read_deepgram_key() if transcribe is None else ""
    if transcribe is None:
        if not key:
            def transcribe(_path, _language, _model):
                return None
        else:
            def transcribe(path, language, model):
                return deepgram_wav(path, language, model, key)

    rows = []
    for label, kind, video_id in sync_candidates(songs, text):
        rows.append(sync_row(text, label, kind, video_id, wav_dir, transcribe))
    return rows


def sync_summary(rows: list[dict]) -> dict | None:
    errors = [error for row in rows for error in row.get("errors", [])]
    if not errors:
        return None
    absolute = [abs(error) for error in errors]
    return dict(
        lines=len(errors),
        songs=sum(1 for row in rows if row.get("verdict")),
        median=round(statistics.median(errors), 3),
        median_abs=round(statistics.median(absolute), 3),
        in03=round(sum(value <= 0.3 for value in absolute) / len(absolute), 3),
    )


# ---------- report ----------


def _song_label(row: dict) -> str:
    label = row.get("label", row.get("video", ""))
    video = row.get("video", "")
    if video and label != video:
        return f"{label} ({video})"
    return label


def render_sync_table(rows: list[dict]) -> list[str]:
    lines = [
        "| 곡 | 종류 | 판정 | 중앙값(초) | 0.3초 이내 | 맞춘 줄 | 모양 | 비고 |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for row in rows:
        label = _song_label(row)
        if row.get("error"):
            lines.append(
                f"| {label} | {row.get('kind', '')} | - | - | - | "
                f"0/{row.get('lines', 0)} | - | {row['error']} |"
            )
            continue
        shape = row.get("shape", "")
        if shape == "drift":
            shape += f" {row.get('slope_ms_min', 0):+}ms/분"
        lines.append(
            f"| {label} | {row.get('kind', '')} | {row.get('verdict', '')} | "
            f"{row.get('median', 0):+.2f} | {row.get('in03', 0):.0%} | "
            f"{row.get('matched', 0)}/{row.get('lines', 0)} | {shape} | "
            f"{row.get('audio', '')} |"
        )
    return lines


def build_report(
    text: str,
    songs: list[smokecheck.SongSpec],
    log_path: Path,
    sync_rows: list[dict],
    smoke_checks: list[smokecheck.Check],
    started: datetime.datetime | None = None,
) -> str:
    started = started or datetime.datetime.now()
    mv_passed, mv_total = mv_progress(text, songs)
    hq = hq_counts(text)
    summary = sync_summary(sync_rows)

    lines = [
        "# 폰 검증 한 번에 — BitChord",
        "",
        f"- **MV {mv_passed}/{mv_total} 통과**",
        f"- **HQ accepted {hq['accepted']}** "
        f"(rejected {hq['rejected']}, decode-failed {hq['decode_failed']}, "
        f"no-reference {hq['no_reference']})",
    ]
    if summary:
        lines.append(
            f"- **싱크 중앙값 {summary['median']:+.2f}초** · "
            f"|오차| 중앙값 {summary['median_abs']:.2f}초 · "
            f"**0.3초 이내 {summary['in03']:.0%}** "
            f"(줄 {summary['lines']}개, 곡 {summary['songs']}개)"
        )
    else:
        lines.append("- **싱크 채점 곡 없음** (cover/plain WAV 없음)")
    lines += [
        f"- 시작: {started:%Y-%m-%d %H:%M} · 모델: {MODEL}",
        f"- 로그: `{log_path}` · WAV: `{OUT / 'synctest'}`",
        "",
        "## 싱크 곡별",
        "",
    ]
    lines.extend(render_sync_table(sync_rows) if sync_rows else ["채점한 곡이 없습니다."])
    lines += ["", "## 스모크 판정 (scripts/smokecheck.py)", ""]
    lines.append(smokecheck.render_markdown(smoke_checks, str(log_path)).strip())
    lines.append("")
    return "\n".join(lines)


# ---------- run ----------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Play scripts/smoke/songs.txt on the phone and write one report."
    )
    install = parser.add_mutually_exclusive_group(required=True)
    install.add_argument("--apk", help="APK to install with adb install -r (keeps settings)")
    install.add_argument(
        "--no-install",
        action="store_true",
        help="use the package already installed on the phone",
    )
    install.add_argument(
        "--score-only",
        metavar="LOGCAT",
        help="judge an existing log (e.g. an emulator smoke-play artifact) without a phone",
    )
    parser.add_argument("--wav-dir", help="with --score-only: the pulled synctest WAVs")
    parser.add_argument("--report", help="with --score-only: where to write the report")
    parser.add_argument(
        "--songs",
        default=str(SONGS_FILE),
        help="song list (default: scripts/smoke/songs.txt)",
    )
    parser.add_argument(
        "--model",
        help="Deepgram model for the judge (default whisper-large; nova-3 cross-checks the app's own whisper refine)",
    )
    parser.add_argument("--out", help="output folder (default: phone-out/phonecheck); use a new one to resume a cut-off run")
    args = parser.parse_args(argv)
    global OUT, MODEL
    if args.out:
        OUT = Path(args.out)
    if args.model:
        MODEL = args.model

    if args.score_only:
        # The emulator run produces the same log and WAVs; score them with the
        # same judge so a phone-less evening still gets real numbers.
        log_file = Path(args.score_only)
        text = log_file.read_text(encoding="utf-8", errors="replace")
        songs = load_songs(Path(args.songs))
        wav_dir = Path(args.wav_dir) if args.wav_dir else log_file.parent / "synctest"
        smoke_checks = smokecheck.check_log(text, songs=songs, hq_configured=True)
        sync_rows = score_sync(text, songs, wav_dir)
        report = build_report(text, songs, log_file, sync_rows, smoke_checks, datetime.datetime.now())
        target = Path(args.report) if args.report else log_file.parent / "score.md"
        target.write_text(report, encoding="utf-8")
        sys.stdout.buffer.write(("\n".join(report.splitlines()[:25]) + "\n").encode("utf-8"))
        return 0

    log("약 40분 소요")
    OUT.mkdir(parents=True, exist_ok=True)
    started = datetime.datetime.now()
    log_path = OUT / "logcat.txt"

    try:
        songs = load_songs(Path(args.songs))
    except OSError as error:
        log(f"곡 목록을 읽을 수 없습니다: {error}")
        return 2
    if not songs:
        log("곡 목록이 비어 있습니다")
        return 2
    if not ensure_phone():
        log("phone NOT FOUND - hotspot + wireless debugging needed")
        return 2

    if args.apk:
        apk = Path(args.apk)
        if not apk.exists():
            log(f"APK not found: {apk}")
            return 2
        if not install_apk(apk):
            log("APK install failed")
            return 2
    if not package_installed():
        log(f"{PKG} is not installed")
        return 2

    volume = None
    stream = LogcatStream(log_path)
    try:
        volume = synctest.media_volume()
        synctest.set_media_volume(0)
        synctest.adb("shell", "mkdir", "-p", APP_DIR)
        synctest.adb("shell", "rm", "-rf", f"{APP_DIR}/synctest")
        synctest.adb("shell", "touch", f"{APP_DIR}/sync-test.flag")

        stream.start()
        # The PC hotspot (MediaTek RZ616) drops a quiet client with reason 4,
        # inactivity, and Android then switches wireless debugging off: the
        # 10-26 22:32 run lost the phone that way. A tiny adb round trip every
        # 20 s keeps the link from ever looking idle. PC side, test runs only.
        keepalive_stop = threading.Event()

        def keepalive() -> None:
            while not keepalive_stop.wait(20):
                synctest.adb("shell", "true", timeout=15)

        threading.Thread(target=keepalive, daemon=True).start()
        for index, spec in enumerate(songs, 1):
            log(f"[{index}/{len(songs)}] {spec.key} ({spec.kind})")
            play_song(spec)
            time.sleep(wait_seconds(spec.kind))
            if spec.search:
                played = re.findall(
                    r"play: -> .*\(([\w-]{11})\)",
                    log_path.read_text(encoding="utf-8", errors="replace"),
                )
                if played:
                    SEARCH_IDS[spec.key] = played[-1]
    finally:
        if "keepalive_stop" in locals():
            keepalive_stop.set()
        stream.stop()
        synctest.adb("shell", "rm", "-f", f"{APP_DIR}/sync-test.flag")
        synctest.adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE")
        if volume is not None:
            synctest.set_media_volume(volume)

    wavs = pull_wavs()
    log(f"WAV {wavs}개 pull")
    text = log_path.read_text(encoding="utf-8", errors="replace")
    smoke_checks = smokecheck.check_log(text, songs=songs, hq_configured=True)
    sync_rows = score_sync(text, songs, OUT / "synctest")
    report = build_report(text, songs, log_path, sync_rows, smoke_checks, started)
    (OUT / "report.md").write_text(report, encoding="utf-8")

    mv_passed, mv_total = mv_progress(text, songs)
    hq = hq_counts(text)
    summary = sync_summary(sync_rows)
    sync_line = (
        f"싱크 중앙값 {summary['median']:+.2f}초, 0.3초 이내 {summary['in03']:.0%}"
        if summary
        else "싱크 채점 곡 없음"
    )
    failed = [check for check in smoke_checks if not check.passed]
    log(
        f"요약: MV {mv_passed}/{mv_total} · HQ accepted {hq['accepted']} · "
        f"{sync_line} · 스모크 실패 {len(failed)}/{len(smoke_checks)}"
    )
    log(f"보고서: {OUT / 'report.md'}")
    return 1 if failed or (summary and any(row.get("verdict") == "FAIL" for row in sync_rows)) else 0


if __name__ == "__main__":
    sys.exit(main())
