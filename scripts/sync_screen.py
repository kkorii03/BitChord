# How far off are the lyrics as actually drawn? Everything else in this repository
# trusts the app's own numbers for what the screen shows; this watches the screen.
#
# Records the phone while a song plays, finds the moments the lyric area changes,
# and puts them on the song's position through the media session's own clock:
#
#   frame time -> device uptime (the recording's end is timed) -> playback position
#
# Printed: how far the drawn change sits from the line start the app reports
# (sync-dump). scripts/synctest.py --dry-run --out <dir> then scores that line start
# against the sung words, so drawn error = drawn offset + scored error.
#
# Numbers and ids only: lyric text stays in the local log file and is never printed.
# Usage:
#   tools\python\python.exe -I scripts\sync_screen.py --video <id> --seconds 150 [--tap X,Y ...]
#   tools\python\python.exe -I scripts\sync_screen.py --score-only <dir>
#   tools\python\python.exe -I scripts\sync_screen.py --selftest
import argparse, json, re, statistics, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]  # Desktop\BitChord
ADB = str(ROOT / "tools" / "android-sdk" / "platform-tools" / "adb.exe")
FFMPEG = str(ROOT / "tools" / "bin" / "ffmpeg.exe")
PKG = "com.music.bitchord"
APP_DIR = f"/sdcard/Android/data/{PKG}/files"
REMOTE = "/sdcard/sync-screen.mp4"

CHANGE_SCORE = 0.004    # a frame this different from the last is the lyric area changing
QUIET_S = 0.25          # a new change starts after this long without one
MATCH_WINDOW_S = 2.0    # a drawn change this close to a line start may be that line's


def adb(*args, timeout=60):
    r = subprocess.run([ADB, *args], capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "replace")


def uptime_ms():
    return float(adb("shell", "cat", "/proc/uptime").split()[0]) * 1000


def session_position():
    """(position ms, the uptime ms it was true at, speed) of the app's playing session, or None."""
    text = adb("shell", "dumpsys", "media_session")
    at = text.find(f"package={PKG}")
    if at < 0:
        at = 0
    m = re.search(r"state=PlaybackState \{state=(\d+), position=(-?\d+).*?speed=([\d.]+), updated=(\d+)", text[at:], re.S)
    if not m or m.group(1) != "3":
        return None
    return int(m.group(2)), int(m.group(4)), float(m.group(3))


def position_at(anchors, uptime):
    """The song's position at device [uptime] ms, by the session's nearest report."""
    position, updated, speed = min(anchors, key=lambda a: abs(a[1] - uptime))
    return position + (uptime - updated) * speed


def frame_scores(video):
    """(seconds into the recording, how much that frame changed) for the lyric area."""
    vf = "crop=iw:ih*0.62:0:ih*0.16,scale=240:-1,format=gray,select='gte(scene,0)',metadata=print:file=-"
    r = subprocess.run([FFMPEG, "-v", "error", "-i", str(video), "-vf", vf, "-an", "-f", "null", "-"],
                       capture_output=True, timeout=600)
    out, pts = [], None
    for line in r.stdout.decode("utf-8", "replace").splitlines():
        m = re.search(r"pts_time:([\d.]+)", line)
        if m:
            pts = float(m.group(1))
        m = re.search(r"lavfi\.scene_score=([\d.]+)", line)
        if m and pts is not None:
            out.append((pts, float(m.group(1))))
    return out


def changes(scores, threshold=CHANGE_SCORE, quiet=QUIET_S):
    """The onsets: a frame over [threshold] with none over it for [quiet] before."""
    onsets, last = [], None
    for t, score in scores:
        if score < threshold:
            continue
        if last is None or t - last >= quiet:
            onsets.append(t)
        last = t
    return onsets


def offsets(onset_positions_ms, line_starts_ms, window_s=MATCH_WINDOW_S):
    """For each line start, the drawn change nearest it (ms, + = drawn after the line's start)."""
    out = []
    for start in line_starts_ms:
        near = [p - start for p in onset_positions_ms if abs(p - start) <= window_s * 1000]
        if near:
            out.append(min(near, key=abs))
    return out


def line_starts(log_text, vid):
    """Line starts in the last sync-dump block for [vid]; never the text."""
    blocks = log_text.split(f"sync-dump: begin {vid}")
    if len(blocks) < 2:
        return []
    return [int(m.group(1)) for m in re.finditer(rf"sync-dump: line {re.escape(vid)} \d+ (\d+) ", blocks[-1])]


def report(onsets_ms, starts, label):
    got = offsets(onsets_ms, starts)
    if not got:
        print(f"{label}: no drawn change near any of {len(starts)} line starts")
        return None
    med = statistics.median(got)
    spread = [abs(x - med) for x in got]
    row = dict(lines=len(starts), matched=len(got), median_ms=round(med),
               within_300ms=round(100 * sum(1 for x in got if abs(x) <= 300) / len(got)),
               spread_p80_ms=round(sorted(spread)[int(len(spread) * .8)]))
    print(label, json.dumps(row))
    return row


def score(out):
    meta = json.loads((out / "meta.json").read_text(encoding="utf-8"))
    vid, anchors = meta["video"], [tuple(a) for a in meta["anchors"]]
    scores = frame_scores(out / "screen.mp4")
    if not scores:
        print("no frames read from the recording")
        return
    onsets = changes(scores)
    # The recording's last frame is at its end, which was timed on the device.
    end_pts = scores[-1][0]
    ms = [position_at(anchors, meta["end_uptime_ms"] - (end_pts - t) * 1000) for t in onsets]
    text = (out / f"{vid}.log").read_text(encoding="utf-8", errors="replace")
    starts = line_starts(text, vid)
    first, last = min(a[0] for a in anchors), max(a[0] for a in anchors)
    starts = [s for s in starts if first - 3000 <= s <= last + 3000]
    print(f"frames {len(scores)}, drawn changes {len(onsets)}, line starts in range {len(starts)}, "
          f"position {first / 1000:.0f}-{last / 1000:.0f}s")
    report(ms, starts, "drawn change vs app's line start:")


def selftest():
    scores = [(0.0, 0), (0.5, .01), (0.53, .01), (0.56, 0), (1.5, .02), (1.52, .001)]
    assert changes(scores) == [0.5, 1.5], changes(scores)
    assert offsets([1000, 5100], [1000, 5000, 20000]) == [0, 100]
    anchors = [(10000, 100000, 1.0), (40000, 130000, 1.0)]
    assert position_at(anchors, 101000) == 11000
    assert position_at(anchors, 131000) == 41000
    log = "x sync-dump: begin abcdefghijk p\nx sync-dump: line abcdefghijk 1 1500 rep=1/1\nx sync-dump: line abcdefghijk 2 3000 z\n"
    assert line_starts(log, "abcdefghijk") == [1500, 3000]
    print("ok")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--video")
    ap.add_argument("--seconds", type=int, default=150)
    ap.add_argument("--tap", action="append", default=[], help="X,Y to tap once playing, to open the lyrics")
    ap.add_argument("--settle", type=int, default=8, help="seconds of play before the recording starts")
    ap.add_argument("--no-start", action="store_true", help="the song is already playing with the lyrics open")
    ap.add_argument("--score-only", type=Path)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    if args.score_only:
        return score(args.score_only)
    vid = args.video
    out = ROOT / "phone-out" / f"screen-{vid}-{time.strftime('%H%M')}"
    out.mkdir(parents=True, exist_ok=True)
    adb("shell", "touch", f"{APP_DIR}/sync-test.flag")
    adb("logcat", "-c")
    if not args.no_start:
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
            "-d", f"https://music.youtube.com/watch?v={vid}", "-p", PKG)
        time.sleep(args.settle)
        for tap in args.tap:
            x, y = tap.split(",")
            adb("shell", "input", "tap", x, y)
            time.sleep(1.5)
    anchors = []
    recorder = subprocess.Popen([ADB, "shell", "screenrecord", "--time-limit", str(args.seconds),
                                 "--size", "720x1560", "--bit-rate", "3000000", REMOTE])
    began = time.time()
    while recorder.poll() is None and time.time() - began < args.seconds + 20:
        p = session_position()
        if p:
            anchors.append(p)
        time.sleep(10)
    recorder.wait(timeout=60)
    end = uptime_ms()
    p = session_position()
    if p:
        anchors.append(p)
    (out / "meta.json").write_text(json.dumps(dict(video=vid, anchors=anchors, end_uptime_ms=end)), encoding="utf-8")
    adb("pull", REMOTE, str(out / "screen.mp4"), timeout=300)
    (out / f"{vid}.log").write_bytes(subprocess.run([ADB, "logcat", "-d"], capture_output=True, timeout=120).stdout)
    adb("pull", f"{APP_DIR}/synctest/{vid}.wav", str(out / f"{vid}.wav"), timeout=120)
    adb("shell", "rm", "-f", f"{APP_DIR}/sync-test.flag", REMOTE)
    if not anchors:
        print("the media session reported no playing position: nothing to place the frames with")
        return
    score(out)
    print("then: tools\\python\\python.exe -I scripts\\synctest.py --dry-run --out", out)


if __name__ == "__main__":
    main()
