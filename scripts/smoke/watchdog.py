#!/usr/bin/env python3
"""Runs scripts/smoke/run.sh and stops it as soon as the run cannot give an answer.

A stop writes shots/smoke-abort.txt, an ::error line and a failed commit status
"STOP <category> <code> song=<id> | <log line>" (context smoke-progress/<run>,
the one run.sh and tools\\runwatch.ps1 already use), then judges what was reached
and exits 6 so the Upload step still runs. tools\\smoke-loop.py reads the status.

Categories: infra = emulator, adb, network (run again), app = the app failed (keep
the line), tool = key or measuring tool (running again will not help).

The limits are measured on 145 logs and 20 job logs in phone-out (11-06), see
ship\\lanes\\emu-watchdog.md; replay them with scripts/smoke/watchdog_replay.py.
"""
import json
import math
import os
import re
import signal
import subprocess
import sys
import time
import urllib.request

SETUP_S = 420  # start -> logcat: 75-91 s seen; two runs hung here for good on `adb wait-for-device`
STALL_S = 180  # no logcat line at all: longest gap in 145 logs is 57 s
APP_SILENT_S = 300  # no line from the app while the device logs: longest 136 s
SONG_MARGIN_S = 180  # past a song's wait: run.sh moves on every wait + ~5 s
QUIET_SONGS = 3  # songs in a row without sound; two happen in runs that then play fine (first sound took up to 285 s)
KEY_REJECTS = 2  # one refused answer may be a blip; the bench never saw one
BOT_CHECKS = 12  # before any sound: 59-70 in runs the proxy refused, 0-2 in every healthy run
NET_INFRA = 3  # network failures since the last sound; fewer and a silent run is the app's own failure
POST_S = 600  # last song -> report: 60-90 s seen
SCENARIO_HARD_S = 80 * 60  # review scenarios: only the hard limit, they force-stop and cut the net on purpose

OWN = r"com\.(?:dev\.|music\.)?bitchord"
CRASH = re.compile(rf"AndroidRuntime.*Process: {OWN}\b|>>> {OWN} <<<|Process {OWN} \(pid \d+\) has died")
ANR = re.compile(rf"ANR in {OWN}\b")
APP_TAG = re.compile(r" (?:BitChord|LyricsTiming|SongHearing)\s*: ")
KEY = re.compile(r"song-hear: sent .*http=(?:401|402|403)\b")
NET = re.compile(r"hit YouTube's bot check|retrying directly|Socket timeout has expired")
SOUND = re.compile(r"TIMING first audio: (\d+)ms")
HEARD = re.compile(r"song-hear: sent .*?http=(\d+)")
BASE = re.compile(r"base-apply: \S+ base=(\S+)")
SCRIPT = re.compile(r"^(.*) (?:VIEW|fallback|MEDIA_PLAY_FROM_SEARCH) (started|did not start)$")


class Judge:
    """Fed log and script lines with the time they were seen; `stop` is set on the first fatal one."""

    def __init__(self, t0, songs, wait, scenario=False):
        self.t0, self.songs, self.wait, self.scenario = t0, songs, wait, scenario
        self.hard = SCENARIO_HARD_S if scenario else SETUP_S + songs * (wait + 60) + POST_S
        self.stage = "setup"
        self.stop = self.stop_at = None
        self.log_at = self.app_at = self.script_at = None
        self.begun = self.quiet = self.unstarted = self.net = self.bot_checks = self.rejects = 0
        self.song, self.listening, self.window_sound, self.any_sound = "-", False, False, False
        self.sound = self.heard = self.base = self.crash_next = None

    def _stop(self, t, category, code, line=""):
        if not self.stop:
            self.stop, self.stop_at = (category, code, self.song, line), t

    def log(self, t, line):
        if "sync-dump" in line:  # lyrics, never read or quoted
            return
        if self.stage == "setup":
            self.stage = "play"
            self.app_at = self.script_at = t
        self.log_at = t
        if self.scenario:
            return
        if APP_TAG.search(line):
            self.app_at = t
        if self.crash_next:
            if "AndroidRuntime" in line and "Process:" not in line:
                self._stop(t, "app", "crash", line)
            elif "AndroidRuntime" not in line:
                self._stop(t, "app", "crash", self.crash_next)
        elif CRASH.search(line):
            if "AndroidRuntime" in line:  # the exception is on the next line
                self.crash_next = line
            else:
                self._stop(t, "app", "crash", line)
        if ANR.search(line):
            self._stop(t, "app", "anr", line)
        if KEY.search(line):
            self.rejects += 1
            if self.rejects >= KEY_REJECTS:
                self._stop(t, "tool", "key_rejected", line)
        if NET.search(line):
            self.net += 1
        if "hit YouTube's bot check" in line:  # the app's own words: the network, not the track
            self.bot_checks += 1
            if self.bot_checks >= BOT_CHECKS and not self.any_sound:
                self._stop(t, "infra", "bot_check", line)
        m = SOUND.search(line)
        if m:
            self.window_sound = self.any_sound = True
            self.net = 0
            self.sound = int(m.group(1)) // 1000
        m = HEARD.search(line)
        if m:
            self.heard = m.group(1)
        m = BASE.search(line)
        if m:
            self.base = m.group(1)

    def _song_result(self, t, sound):
        self.quiet = 0 if sound else self.quiet + 1
        if self.quiet >= QUIET_SONGS:
            code = "no_start" if self.unstarted >= QUIET_SONGS else "no_audio"
            self._stop(t, "infra" if self.net >= NET_INFRA else "app", code)

    def _close_song(self, t):
        if self.listening:
            self.listening = False
            self._song_result(t, self.window_sound)

    def script(self, t, line):
        self.script_at = t
        if self.scenario:
            return
        m = SCRIPT.match(line.strip())
        if m:
            self._close_song(t)
            self.begun += 1
            self.song, self.sound, self.heard, self.base = m.group(1), None, None, None
            if m.group(2) == "started":
                self.listening, self.window_sound, self.unstarted = True, False, 0
            else:
                self.unstarted += 1
                self._song_result(t, False)
        elif "did not start" not in line and self.stage != "post":  # "smoke: forcing one final..."
            self._close_song(t)
            self.stage = "post"

    def tick(self, t):
        if t - self.t0 > self.hard:
            self._stop(t, "tool", "over_budget")
        if self.scenario:
            return
        if self.stage == "setup":
            if t - self.t0 > SETUP_S:
                self._stop(t, "infra", "setup_hang")
            return
        if t - self.log_at > STALL_S:
            self._stop(t, "infra", "logcat_stall")
        if self.stage == "play":
            if t - self.app_at > APP_SILENT_S:
                self._stop(t, "app", "app_silent")
            if t - self.script_at > self.wait + SONG_MARGIN_S:
                self._stop(t, "infra", "song_stuck")

    def progress(self):
        if self.stage == "setup":
            return "setup"
        sound = "-" if self.sound is None else f"{self.sound}s"
        return f"s{self.begun}/{self.songs} {self.song} audio={sound} heard={self.heard or '-'} base={self.base or '-'}"


def clean(line):
    """The status and the annotation are public: drop the logcat prefix and anything key-shaped."""
    line = re.sub(r"^\d\d-\d\d \S+\s+\d+\s+\d+ \S ", "", line.strip())
    return re.sub(r"[A-Za-z0-9_-]{32,}", "***", line)


def post(desc, state="pending"):
    token, repo, sha = (os.environ.get(k) for k in ("GITHUB_TOKEN", "GITHUB_REPOSITORY", "GITHUB_SHA"))
    if not (token and repo and sha):
        return
    body = {"state": state, "context": f"smoke-progress/{os.environ.get('GITHUB_RUN_ID', '0')}", "description": desc[:140]}
    req = urllib.request.Request(f"https://api.github.com/repos/{repo}/statuses/{sha}", json.dumps(body).encode(), method="POST",
                                 headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
    try:
        urllib.request.urlopen(req, timeout=10).close()
    except Exception as e:  # a failed post never stops the run
        print(f"smoke: progress status failed: {e}", file=sys.stderr, flush=True)


def is_scenario():
    ref = os.environ.get("GITHUB_REF_NAME", "")
    return os.environ.get("SMOKE_SCENARIO", "").lower() == "review" or "review" in ref


def songs_file():  # the same choice as run.sh
    if not is_scenario() and "mv" in os.environ.get("GITHUB_REF_NAME", ""):
        return "scripts/smoke/songs-mv.txt"
    return os.environ.get("SMOKE_SONGS_FILE", "scripts/smoke/songs.txt")


def count_songs():
    with open(songs_file(), encoding="utf-8") as f:
        return sum(1 for s in f if s.strip() and not s.lstrip().startswith("#"))


def new_judge(t0):
    return Judge(t0, count_songs(), int(os.environ.get("SMOKE_WAIT_S", "150")), is_scenario())


class Tail:
    def __init__(self, path, feed):
        self.path, self.feed, self.pos, self.rest = path, feed, 0, b""

    def read(self, now):
        try:
            with open(self.path, "rb") as f:
                f.seek(self.pos)
                data = f.read()
                self.pos = f.tell()
        except OSError:
            return
        lines = (self.rest + data).split(b"\n")
        self.rest = lines.pop()
        for line in lines:
            self.feed(now, line.decode("utf-8", "replace"))


def wrap_up():
    # What run.sh does at its end, each adb call bounded because adb may be what hung.
    subprocess.run(["bash", "-c", r"""
      D=/sdcard/Android/data/com.dev.bitchord/files/synctest; mkdir -p shots/synctest
      for n in $(timeout 20 adb shell ls "$D" 2>/dev/null | tr -d '\r'); do
        timeout 60 adb pull "$D/$n" "shots/synctest/${n//:/_}" >/dev/null 2>&1
      done
      timeout 120 python3 scripts/smokecheck.py shots/smoke-logcat.txt --report shots/smoke-report.md --songs "$0"
    """, songs_file()], check=False)


def report_stop(stop, elapsed, ps):
    category, code, song, line = stop
    line = clean(line)
    desc = f"STOP {category} {code} song={song}" + (f" | {line}" if line else "")
    with open("shots/smoke-abort.txt", "w", encoding="utf-8") as f:
        f.write(f"category={category}\ncode={code}\nsong={song}\nelapsed_s={int(elapsed)}\nline={line}\n\nwaiting on:\n{ps}")
    print(f"::error title=smoke-abort::{desc}", flush=True)
    post(desc, "failure")


# run.sh's own early exits, so the loops read them the same way.
RUN_SH_STOPS = {2: ("infra", "setup_failed"), 3: ("infra", "no_audio"), 4: ("app", "crash"), 5: ("tool", "no_sync_engine")}


def main():
    if "--minutes" in sys.argv:  # the step limit: the hard limit + emulator boot and shutdown
        print(math.ceil(new_judge(0).hard / 60) + 10)
        return 0
    os.makedirs("shots", exist_ok=True)
    t0 = time.time()
    judge = new_judge(t0)
    run = subprocess.Popen(["bash", "scripts/smoke/run.sh"], start_new_session=True)
    tails = [Tail("shots/smoke-logcat.txt", judge.log), Tail("shots/smoke-playback.txt", judge.script)]
    said = None
    while run.poll() is None and not judge.stop:
        now = time.time()
        for tail in tails:
            tail.read(now)
        judge.tick(now)
        if judge.progress() != said:
            said = judge.progress()
            post(said)
        time.sleep(5)

    stop, waiting = judge.stop, ""
    if stop:
        print(f"smoke: watchdog stop {stop[:3]}", flush=True)
        waiting = subprocess.run("ps -eo pid,etimes,stat,cmd --forest | grep -E 'adb|sleep|run.sh' | grep -v grep",
                                 shell=True, capture_output=True, text=True).stdout
        for sig in (signal.SIGTERM, signal.SIGKILL):
            try:
                os.killpg(run.pid, sig)
            except ProcessLookupError:
                pass
            time.sleep(3)
    rc = run.wait()
    if not stop and rc in RUN_SH_STOPS:
        stop = RUN_SH_STOPS[rc] + (judge.song, "")
    if stop:
        report_stop(stop, time.time() - t0, waiting)
        wrap_up()
    # 42144470462 passed, then qemu ignored `emu kill` and the step sat 63 min on its open pipe.
    subprocess.Popen(["sh", "-c", "sleep 120; pkill -9 -f 'qemu-system|netsimd|crashpad_handler'"], start_new_session=True,
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return 6 if judge.stop else rc


if __name__ == "__main__":
    sys.exit(main())
