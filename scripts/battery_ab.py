# Real-device battery A/B for BitChord (one arm = one installed build).
#
# An arm: record the build's versionCode, reset batterystats, play a fixed song list
# with the screen OFF and media volume 0 for N minutes, then collect
# `dumpsys batterystats --checkin` + `dumpsys procstats --hours 1` and write
# phone-out\battery\<label>.json plus one summary line.
# `compare` prints control vs candidate side by side.
#
# THE NUMBERS ARE ESTIMATES: batterystats attributes power from kernel counters with
# a per-device model. Use only the relative difference between two arms measured
# on the same phone, same song list, same duration, same network; never quote mAh as absolute.
#
# Usage (from the BitChord-s-battery folder, phone on, unplugged, in the 23:00-08:00 window):
#   ..\tools\python\python.exe -I scripts\battery_ab.py run --label control-vc180 --minutes 25
#   ..\tools\python\python.exe -I scripts\battery_ab.py run --label cand-vc181 --minutes 25
#   ..\tools\python\python.exe -I scripts\battery_ab.py compare control-vc180 cand-vc181
#   ..\tools\python\python.exe -I scripts\battery_ab.py parse checkin.txt   (no phone: parser check)
#   ..\tools\python\python.exe -I scripts\battery_ab.py --selftest
import argparse, datetime, json, re, subprocess, sys, tempfile, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]  # Desktop\BitChord
ADB = str(ROOT / "tools" / "android-sdk" / "platform-tools" / "adb.exe")
OUT = ROOT / "phone-out" / "battery"
PKG = "com.music.bitchord"
SERIAL = "R3CX102TKWD"
# Fixed list, played in order and repeated: fast rap, long-held vocals, a pop song.
DEFAULT_SONGS = ["XbGs_qK2PQA", "YQHsXMglC9A", "q0hyYWKXF0Q"]
SONG_SECONDS = 200  # each song is opened this long before the next (the app keeps playing)

METRICS = [  # key, label, unit, lower is better
    ("est_mah", "Estimated app power", "mAh", True),
    ("wakelock_ms", "Partial wakelock time", "ms", True),
    ("mobile_radio_ms", "Mobile radio active", "ms", True),
    ("wifi_ms", "Wifi running/active", "ms", True),
    ("cpu_ms", "CPU time (user+system)", "ms", True),
    ("mobile_bytes", "Mobile rx+tx", "B", True),
    ("wifi_bytes", "Wifi rx+tx", "B", True),
    ("battery_drop_pct", "Whole-phone battery drop", "%", True),
]


# ---------- phone ----------

def adb(*args, timeout=120):
    try:
        r = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return ""
    return r.stdout.decode("utf-8", "replace")


def log(msg):
    print(f"{datetime.datetime.now():%H:%M:%S} {msg}", flush=True)


def version_code():
    m = re.search(r"versionCode=(\d+)", adb("shell", "dumpsys", "package", PKG))
    return int(m.group(1)) if m else None


def battery_info():
    t = adb("shell", "dumpsys", "battery")
    level = re.search(r"level:\s*(\d+)", t)
    powered = re.search(r"(AC|USB|Wireless) powered:\s*true", t)
    return dict(level=int(level.group(1)) if level else None, powered=bool(powered))


def media_volume():
    m = re.search(r"volume is (\d+)", adb("shell", "cmd", "media_session", "volume", "--stream", "3", "--get"))
    return int(m.group(1)) if m else None


def set_media_volume(v):
    adb("shell", "cmd", "media_session", "volume", "--stream", "3", "--set", str(v))


# ---------- parsing ----------

def _num(s, default=0.0):
    try:
        return float(s)
    except (TypeError, ValueError):
        return default


def parse_checkin(text, pkg=PKG):
    """Metrics for pkg's UID out of `dumpsys batterystats --checkin`. Lines look like
    `9,<uid>,l,<category>,<fields...>` (`9` = checkin version). Missing categories give
    None, never 0, so an unparsed field is not mistaken for 'no usage'. The raw UID
    lines are kept in the result for a human to read."""
    uid = None
    for line in text.splitlines():
        p = line.strip().split(",")
        if len(p) >= 6 and p[2] == "i" and p[3] == "uid" and p[5] == pkg:
            uid = p[4]
    res = dict(uid=uid, est_mah=None, wakelock_ms=None, mobile_radio_ms=None, wifi_ms=None, cpu_ms=None,
               mobile_bytes=None, wifi_bytes=None, raw=[])
    if uid is None:
        return res
    for line in text.splitlines():
        p = line.strip().split(",")
        if len(p) < 5 or p[1] != uid or p[2] != "l":
            continue
        cat, f = p[3], p[4:]
        res["raw"].append(line.strip())
        if cat == "pwi" and len(f) >= 2:  # pwi,<uid|name>,<mAh>,...
            res["est_mah"] = _num(f[1])
        elif cat == "wl":  # wl,<name>,<full ms>,f,<n>,<partial ms>,p,<n>,<window ms>,w,<n>,...
            partial = _num(f[4]) if len(f) > 5 and f[5] == "p" else 0.0
            res["wakelock_ms"] = (res["wakelock_ms"] or 0.0) + partial
        elif cat == "cpu" and len(f) >= 2:  # cpu,<user ms>,<system ms>,<power>,...
            res["cpu_ms"] = _num(f[0]) + _num(f[1])
        elif cat == "nt" and len(f) >= 4:  # nt,<mobile rx>,<mobile tx>,<wifi rx>,<wifi tx>,...
            res["mobile_bytes"] = _num(f[0]) + _num(f[1])
            res["wifi_bytes"] = _num(f[2]) + _num(f[3])
        elif cat in ("mcd", "mr") and f:  # mobile radio active ms (first field)
            res["mobile_radio_ms"] = _num(f[0])
        elif cat == "wfl" and len(f) >= 3:  # wfl,<full ms>,<scan ms>,<running ms>,...
            res["wifi_ms"] = _num(f[2])
    return res


def _dur_ms(s):
    """'1h 2m 3s 400ms' -> ms."""
    unit = dict(d=86400000, h=3600000, m=60000, s=1000, ms=1)
    return sum(float(n) * unit[u] for n, u in re.findall(r"(\d+(?:\.\d+)?)(ms|d|h|m|s)", s))


def parse_package_text(text):
    """Fallback fields from `dumpsys batterystats <pkg>` when the checkin lacks them
    (radio/wifi categories differ across Android builds)."""
    res = {}
    m = re.search(r"Mobile radio active time:\s*([^()\n]+)", text)
    if m:
        res["mobile_radio_ms"] = _dur_ms(m.group(1))
    m = re.search(r"Wifi Running:\s*([^()\n]+)", text)
    if m:
        res["wifi_ms"] = _dur_ms(m.group(1))
    m = re.search(rf"UID [\w.]+:\s*([\d.]+)", text)  # 'Estimated power use (mAh)' table row
    if m:
        res["est_mah"] = float(m.group(1))
    return res


def merge(primary, fallback):
    for k, v in fallback.items():
        if primary.get(k) is None:
            primary[k] = v
    return primary


def parse_procstats(text, pkg=PKG):
    """Lines of the procstats summary that mention the package (resident/foreground share)."""
    return [l.strip() for l in text.splitlines() if pkg in l][:20]


# ---------- arm ----------

def play_screen_off(songs, minutes, song_seconds):
    end = time.time() + minutes * 60
    k = 0
    while time.time() < end:
        vid = songs[k % len(songs)]
        k += 1
        # Same launch as scripts/synctest.py. The screen must be awake only for the start.
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
            "-d", f"https://music.youtube.com/watch?v={vid}", "-p", PKG)
        time.sleep(8)
        adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
        log(f"song {k}: {vid}, screen off")
        time.sleep(max(0, min(song_seconds, end - time.time())))
    return k


def run_arm(a):
    OUT.mkdir(parents=True, exist_ok=True)
    if SERIAL not in adb("devices") or "device" not in adb("devices").split(SERIAL, 1)[1][:12]:
        sys.exit("phone NOT FOUND (run tools\\phone.ps1 auto; hotspot + wireless debugging needed)")
    rot = adb("shell", "settings", "get", "system", "accelerometer_rotation").strip()  # read only, never set
    vc = version_code()
    start = battery_info()
    if start["powered"]:
        log("WARNING: phone is charging; batterystats only counts battery time. Unplug (wireless adb) first.")
    log(f"arm {a.label}: versionCode={vc} battery={start['level']}% accelerometer_rotation={rot}")
    volume = media_volume()
    set_media_volume(0)
    adb("shell", "dumpsys", "batterystats", "--reset")
    t0 = time.time()
    played = 0
    try:
        played = play_screen_off(a.songs, a.minutes, a.song_seconds)
    finally:
        adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE")
        adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
        if volume is not None:
            set_media_volume(volume)
    elapsed = time.time() - t0
    end = battery_info()
    checkin = adb("shell", "dumpsys", "batterystats", "--checkin", timeout=300)
    pkgtext = adb("shell", "dumpsys", "batterystats", PKG, timeout=300)
    proc = adb("shell", "dumpsys", "procstats", "--hours", "1", timeout=300)
    (OUT / f"{a.label}.checkin.txt").write_text(checkin, encoding="utf-8")
    result = finish(a.label, vc, elapsed, played, a.songs, start, end, rot, checkin, pkgtext, proc)
    log(summary_line(result))


def finish(label, vc, elapsed, played, songs, start, end, rot, checkin, pkgtext, proc):
    m = merge(parse_checkin(checkin), parse_package_text(pkgtext))
    drop = (start["level"] - end["level"]) if None not in (start["level"], end["level"]) else None
    result = dict(label=label, version_code=vc, finished=datetime.datetime.now().isoformat(timespec="seconds"),
                  elapsed_s=round(elapsed), songs_started=played, songs=songs, accelerometer_rotation=rot,
                  battery_start=start, battery_end=end, battery_drop_pct=drop, metrics=m,
                  procstats=parse_procstats(proc), note="batterystats numbers are estimates; compare arms only")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{label}.json").write_text(json.dumps(result, indent=1, ensure_ascii=False), encoding="utf-8")
    return result


def summary_line(r):
    m = r["metrics"]
    f = lambda v, u="": "n/a" if v is None else f"{v:g}{u}"
    return (f"{r['label']} vc={r['version_code']} {r['elapsed_s'] // 60}min est={f(m['est_mah'], 'mAh')} "
            f"wake={f(m['wakelock_ms'], 'ms')} mobile={f(m['mobile_radio_ms'], 'ms')} "
            f"wifi={f(m['wifi_ms'], 'ms')} cpu={f(m['cpu_ms'], 'ms')} drop={f(r['battery_drop_pct'], '%')}")


# ---------- compare ----------

def metric_value(r, key):
    return r.get(key) if key == "battery_drop_pct" else r["metrics"].get(key)


def compare(a, b):
    ra, rb = (json.loads(p.read_text(encoding="utf-8")) for p in (a, b))
    print("NOTE: batterystats numbers are ESTIMATES from a per-device model. Only the relative")
    print("difference between two arms on the same phone/song list/duration means anything.\n")
    print(f"control   {ra['label']}  vc={ra['version_code']}  {ra['elapsed_s'] // 60} min  songs started {ra['songs_started']}")
    print(f"candidate {rb['label']}  vc={rb['version_code']}  {rb['elapsed_s'] // 60} min  songs started {rb['songs_started']}")
    if abs(ra["elapsed_s"] - rb["elapsed_s"]) > 120:
        print("WARNING: durations differ by more than 2 minutes; compare the per-hour numbers with care.")
    print(f"\n{'metric':28}{'control':>14}{'candidate':>14}{'diff':>10}")
    lines = []
    for key, label, unit, _ in METRICS:
        va, vb = metric_value(ra, key), metric_value(rb, key)
        if va is None or vb is None:
            rel = "n/a"
        elif va == 0:
            rel = "n/a" if vb == 0 else "new"
        else:
            rel = f"{(vb - va) / va * 100:+.1f}%"
        sa, sb = ("n/a" if v is None else f"{v:g}" for v in (va, vb))
        lines.append(f"{label + ' (' + unit + ')':28}{sa:>14}{sb:>14}{rel:>10}")
    print("\n".join(lines))
    print("\nRule of thumb from the plan: candidate within +/-5% of control = no regression; a repeat run of the")
    print("same build shows the noise floor. Lower is better for every row.")


# ---------- self test ----------

FIXTURE = """\
9,0,i,vers,35,1,AP3A,AP3A
9,0,i,uid,1000,android
9,0,i,uid,10234,com.music.bitchord
9,0,i,uid,10235,com.other.app
9,10234,l,pwi,uid,12.5,0,0,0,0
9,10234,l,wl,MediaPlayback,0,f,0,60000,p,3,0,w,0,0,0,0
9,10234,l,wl,Download,0,f,0,15000,p,2,0,w,0,0,0,0
9,10234,l,cpu,40000,12000,0
9,10234,l,nt,1000,2000,30000,40000,0,0,0,0,0,0
9,10234,l,mcd,45000
9,10234,l,wfl,0,0,90000,0,0
9,10235,l,pwi,uid,99.0
"""
PKG_TEXT = "    Mobile radio active time: 1m 2s 500ms (3.0%) 4x\n    Wifi Running: 2m 0s 0ms (5.0%)\n"


def selftest():
    m = parse_checkin(FIXTURE)
    assert m["uid"] == "10234", m
    assert m["est_mah"] == 12.5, m
    assert m["wakelock_ms"] == 75000.0, m
    assert m["cpu_ms"] == 52000.0, m
    assert m["mobile_bytes"] == 3000.0 and m["wifi_bytes"] == 70000.0, m
    assert m["mobile_radio_ms"] == 45000.0 and m["wifi_ms"] == 90000.0, m
    assert parse_checkin("9,0,i,uid,1,x")["est_mah"] is None  # absent package -> None, not 0
    fb = merge(dict(est_mah=None, mobile_radio_ms=None), parse_package_text(PKG_TEXT))
    assert fb["mobile_radio_ms"] == 62500.0, fb
    assert _dur_ms("1h 2m 3s 400ms") == 3723400
    global OUT
    with tempfile.TemporaryDirectory() as d:
        OUT = Path(d)
        s, e = dict(level=80, powered=False), dict(level=78, powered=False)
        ra = finish("a", 180, 1500, 8, ["x"], s, e, "0", FIXTURE, "", "")
        rb = finish("b", 181, 1500, 8, ["x"], s, e, "0", FIXTURE.replace("12.5", "11.0"), "", "")
        assert ra["battery_drop_pct"] == 2 and "est=12.5mAh" in summary_line(ra), summary_line(ra)
        compare(OUT / "a.json", OUT / "b.json")
    print("selftest OK")


def main():
    global OUT
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    sub = ap.add_subparsers(dest="cmd")
    r = sub.add_parser("run")
    r.add_argument("--label", required=True)
    r.add_argument("--minutes", type=int, default=25)
    r.add_argument("--song-seconds", type=int, default=SONG_SECONDS)
    r.add_argument("--songs", nargs="+", default=DEFAULT_SONGS, help="video ids, same list for both arms")
    c = sub.add_parser("compare")
    c.add_argument("control")
    c.add_argument("candidate")
    d = sub.add_parser("parse", help="--dry-run: parse a saved checkin text file, no phone")
    d.add_argument("file", type=Path)
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    if a.cmd == "run":
        return run_arm(a)
    if a.cmd == "compare":
        def p(n):
            return Path(n) if Path(n).suffix == ".json" else OUT / f"{n}.json"
        return compare(p(a.control), p(a.candidate))
    if a.cmd == "parse":
        m = parse_checkin(a.file.read_text(encoding="utf-8", errors="replace"))
        print(json.dumps({k: v for k, v in m.items() if k != "raw"}, indent=1))
        return
    ap.print_help()


if __name__ == "__main__":
    main()
