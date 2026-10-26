# Scores lyric sync on the phone: plays each song in BitChord with sync-test mode
# on, reads the app's `sync-dump:` block (the line starts as shown on screen) and
# the WAV the app's song check decoded, hears the words with Deepgram, and
# measures every line's displayed start against when it is actually sung.
#
# Error = displayed start - sung start, so a positive error means the lyric shows late.
# Usage (from the BitChord-merge folder):
#   ..\tools\python\python.exe -I scripts\synctest.py --default-set --seconds 75
#   ..\tools\python\python.exe -I scripts\synctest.py --playlist PL... --limit 5
#   ..\tools\python\python.exe -I scripts\synctest.py --dry-run      (re-score saved logs, no phone)
#   ..\tools\python\python.exe -I scripts\synctest.py --selftest     (checks the alignment maths)
import argparse, csv, datetime, difflib, json, random, re, statistics, subprocess, sys, time, unicodedata, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]  # Desktop\BitChord
ADB = str(ROOT / "tools" / "android-sdk" / "platform-tools" / "adb.exe")
YTDLP = str(ROOT / "tools" / "bin" / "yt-dlp.exe")
PHONE_AUTO = ROOT / "tools" / "phone_auto.py"
KEY_FILE = ROOT / "keys" / "deepgram_key.txt"
OUT = ROOT / "phone-out" / "synctest"
PKG = "com.music.bitchord"
APP_DIR = f"/sdcard/Android/data/{PKG}/files"
DEFAULT_SET = ["PL02cgYzp6Oi4kOisQAh691u0phBtC4PVD", "PL-ODED49YdZByxxHBLaIIOOePlhj8nWcc"]
DEFAULT_PER_PLAYLIST = 10

MIN_RATIO = 0.6       # how alike a line's opening must be to the heard words
SEARCH_S = 15.0       # a line is looked for this far either side of its displayed start
PREFIX_CHARS = 8      # characters of each line used to find where it starts
CONST_STD_S = 0.15


def log(msg):
    print(msg, flush=True)
    with open(OUT / "progress.txt", "a", encoding="utf-8") as f:
        f.write(f"{datetime.datetime.now():%H:%M:%S} {msg}\n")


# ---------- phone ----------

def adb(*args, timeout=60, binary=False):
    try:
        r = subprocess.run([ADB, *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return b"" if binary else ""
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def phone_connected():
    return any(l.split()[1:2] == ["device"] for l in adb("devices").splitlines()[1:])


def ensure_phone():
    if phone_connected():
        return True
    log("phone not connected - running phone_auto.py")
    subprocess.run([sys.executable, "-I", str(PHONE_AUTO)], timeout=900)
    return phone_connected()


def media_volume():
    m = re.search(r"volume is (\d+)", adb("shell", "cmd", "media_session", "volume", "--stream", "3", "--get"))
    return int(m.group(1)) if m else None


def set_media_volume(v):
    adb("shell", "cmd", "media_session", "volume", "--stream", "3", "--set", str(v))


# ---------- song list ----------

def playlist_ids(pid):
    r = subprocess.run([YTDLP, "--flat-playlist", "--print", "id", f"https://www.youtube.com/playlist?list={pid}"],
                       capture_output=True, timeout=300)
    return [l.strip() for l in r.stdout.decode("utf-8", "replace").splitlines() if re.fullmatch(r"[\w-]{11}", l.strip())]


def is_video(vid):
    """A real video (MV, live clip, cover), not the plain song copy YouTube files
    under an `<artist> - Topic` channel. The flat playlist only names the playlist
    owner, so each candidate is looked up on its own."""
    r = subprocess.run([YTDLP, "--skip-download", "--no-warnings", "--print", "%(channel)s",
                        f"https://www.youtube.com/watch?v={vid}"], capture_output=True, timeout=120)
    channel = r.stdout.decode("utf-8", "replace").strip()
    return bool(channel) and not channel.endswith("- Topic")


def pick_songs(playlists, limit, shuffle, seed):
    picked = []
    for pid in playlists:
        ids = playlist_ids(pid)
        if shuffle:
            random.Random(f"{seed}-{pid}").shuffle(ids)
        if ARGS.videos_only:
            videos = []
            for v in ids:
                if limit and len(videos) >= limit:
                    break
                if is_video(v):
                    videos.append(v)
            ids = videos
        ids = ids[:limit] if limit else ids
        log(f"playlist {pid}: {len(ids)} songs")
        picked += [(pid, v) for v in ids]
    return picked


# ---------- app log ----------

def parse_dump(text, vid):
    """The last complete sync-dump block for vid: (header dict, [(n, start_ms, text)])."""
    blocks, cur = [], None
    for raw in text.splitlines():
        i = raw.find("sync-dump: ")
        if i < 0:
            continue
        parts = raw[i + len("sync-dump: "):].rstrip("\r").split(" ", 4)
        if len(parts) < 2 or parts[1] != vid:
            continue
        kind = parts[0]
        if kind == "begin":
            head = dict(p.split("=", 1) for p in raw[i:].split()[3:] if "=" in p)
            cur = (head, [])
        elif kind == "line" and cur is not None and len(parts) >= 4:
            cur[1].append((int(parts[2]), int(parts[3]), parts[4] if len(parts) > 4 else ""))
        elif kind == "end" and cur is not None:
            blocks.append(cur)
            cur = None
    return blocks[-1] if blocks else None


def resolve_of(text, vid):
    """How the app got the audio (`track-summary: <id> ... resolve=<proxy|direct|cache|...>`), last one wins."""
    found = ""
    for raw in text.splitlines():
        i = raw.find("track-summary: ")
        if i >= 0 and raw[i + len("track-summary: "):].startswith(vid + " "):
            m = re.search(r" resolve=(\S+)", raw)
            found = m.group(1) if m else found
    return found


# ---------- hearing ----------

def language_for(lines):
    text = "".join(t for _, _, t in lines)
    hangul = sum("가" <= c <= "힣" for c in text)
    kana = sum("぀" <= c <= "ヿ" for c in text)
    letters = sum(c.isalpha() for c in text) or 1
    if hangul / letters > 0.2:
        return "ko"
    if kana / letters > 0.1:
        return "ja"
    return "en"


def deepgram_words(audio, mime, language, model):
    key = KEY_FILE.read_text(encoding="utf-8").strip()
    url = (f"https://api.deepgram.com/v1/listen?model={model}&language={language}"
           "&punctuate=false&smart_format=false&numerals=false")
    req = urllib.request.Request(url, data=audio, method="POST",
                                 headers={"Authorization": f"Token {key}", "Content-Type": mime})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                body = json.load(r)
            alt = body["results"]["channels"][0]["alternatives"][0]
            return [(w["word"], float(w["start"]), float(w["end"])) for w in alt.get("words", [])]
        except Exception as e:  # 429 / network: back off and retry
            log(f"  deepgram error ({e}); retry {attempt + 1}")
            time.sleep(10 * (attempt + 1))
    return None


# ---------- alignment ----------

def norm(s):
    s = unicodedata.normalize("NFKC", s).lower()
    return "".join(c for c in s if c.isalnum())


def char_stream(words):
    """Every heard character with the time it is sung, spread evenly across its word."""
    chars, times = [], []
    for w, start, end in words:
        n = norm(w)
        for k, c in enumerate(n):
            chars.append(c)
            times.append(start + (end - start) * k / max(len(n), 1))
    return "".join(chars), times


def align(lines, words):
    """[(n, shown_s, sung_s, ratio)] for every line found in the heard words, in order."""
    heard, times = char_stream(words)
    if not heard:
        return []
    out, floor = [], 0
    last_heard = times[-1]
    for n, start_ms, text in lines:
        shown = start_ms / 1000.0
        key = norm(text)[:PREFIX_CHARS]
        if len(key) < 2 or shown > last_heard + 1.0:
            continue
        best = None
        for i in range(floor, len(heard)):
            t = times[i]
            if t < shown - SEARCH_S:
                continue
            if t > shown + SEARCH_S:
                break
            r = difflib.SequenceMatcher(None, key, heard[i:i + len(key)], autojunk=False).ratio()
            if heard[i] != key[0]:
                r -= 0.05  # prefer a match that starts on the line's first sound
            if best is None or r > best[0] + 1e-9:
                best = (r, i)
        if best and best[0] >= MIN_RATIO:
            out.append((n, shown, times[best[1]], round(best[0], 2)))
            floor = best[1] + 1
    return out


def trimmed(values, frac=0.1):
    """Values with the worst `frac` dropped, so a misheard line does not set the shape."""
    if len(values) < 6:
        return values
    med = statistics.median(values)
    keep = sorted(values, key=lambda v: abs(v - med))
    return keep[: max(3, int(len(keep) * (1 - frac)))]


def shape(points):
    """const / drift / jump / scatter, plus slope in ms per minute for drift."""
    if len(points) < 4:
        return "few", 0.0
    errs = [e for _, e in points]
    if statistics.pstdev(trimmed(errs)) < CONST_STD_S:
        return "const", 0.0
    ts = [t for t, _ in points]
    mt, me = statistics.mean(ts), statistics.mean(errs)
    var = sum((t - mt) ** 2 for t in ts) or 1e-9
    slope = sum((t - mt) * (e - me) for t, e in points) / var
    resid = [e - (me + slope * (t - mt)) for t, e in points]
    if statistics.pstdev(trimmed(resid)) < CONST_STD_S and abs(slope * 60) >= 0.1:
        return "drift", round(slope * 60000)
    best = None
    for k in range(2, len(errs) - 1):
        a, b = errs[:k], errs[k:]
        spread = max(statistics.pstdev(trimmed(a)), statistics.pstdev(trimmed(b)))
        if best is None or spread < best[0]:
            best = (spread, abs(statistics.median(a) - statistics.median(b)))
    if best and best[0] < CONST_STD_S and best[1] >= 0.3:
        return "jump", 0.0
    return "scatter", 0.0


def score(matched):
    errs = [shown - sung for _, shown, sung, _ in matched]
    if not errs:
        return None
    a = [abs(e) for e in errs]
    within = {lim: sum(x <= lim for x in a) / len(a) for lim in (0.2, 0.3, 0.5)}
    med_signed = statistics.median(errs)
    med_abs = statistics.median(a)
    verdict = "PASS" if med_abs <= 0.2 and within[0.3] >= 0.9 else "WARN" if med_abs <= 0.3 else "FAIL"
    kind, slope = shape([(sung, shown - sung) for _, shown, sung, _ in matched])
    return dict(matched=len(errs), median=round(med_signed, 3), median_abs=round(med_abs, 3),
                in02=round(within[0.2], 2), in03=round(within[0.3], 2), in05=round(within[0.5], 2),
                verdict=verdict, shape=kind, slope_ms_min=slope)


# ---------- one song ----------

def run_song(vid, seconds, dry):
    logf, wav = OUT / f"{vid}.log", OUT / f"{vid}.wav"
    if not dry:
        if not ensure_phone():
            return dict(error="phone not connected")
        adb("logcat", "-c")
        adb("shell", "rm", "-f", f"{APP_DIR}/synctest/{vid}.wav")
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
            "-d", f"https://music.youtube.com/watch?v={vid}", "-p", PKG)
        time.sleep(seconds)
        logf.write_bytes(adb("logcat", "-d", timeout=120, binary=True))
        wav.unlink(missing_ok=True)
        adb("pull", f"{APP_DIR}/synctest/{vid}.wav", str(wav), timeout=120)
    if not logf.exists():
        return dict(error="no log")
    text = logf.read_text(encoding="utf-8", errors="replace")
    dump = parse_dump(text, vid)
    if not dump:
        return dict(error="no sync-dump (no lyrics shown?)")
    head, lines = dump
    # A downloaded song plays its stored file with its stored lyrics and timing,
    # which may themselves be wrong; scored the same way but reported apart.
    resolve = resolve_of(text, vid)
    stored = resolve == "cache" or head.get("source", "").startswith("file:") or "@unknown" in head.get("source", "")
    row = dict(source=head.get("source", ""), base=head.get("base", ""), shift=head.get("shift", ""),
               match=head.get("match", ""), playing=head.get("playing", ""), lines=len(lines),
               resolve=resolve, stored="y" if stored else "n")
    if not lines:
        return dict(row, error="no timed lines")
    audio_note = "app wav (first 90 s)"
    if wav.exists() and wav.stat().st_size > 1000:
        audio, mime = wav.read_bytes(), "audio/wav"
    elif stored:
        # The stored file may be another recording than YouTube's copy, so
        # hearing yt-dlp's copy would score the wrong audio.
        return dict(row, error="downloaded song, app kept no wav (song check skipped)")
    else:
        # No song check this time: hear the YouTube copy instead (whole song). Only
        # right when the phone played that same recording.
        alt = OUT / f"{vid}.m4a"
        if not alt.exists():
            subprocess.run([YTDLP, "-f", "bestaudio[ext=m4a]/bestaudio", "-o", str(alt),
                            f"https://www.youtube.com/watch?v={vid}"], capture_output=True, timeout=600)
        if not alt.exists():
            return dict(row, error="no audio")
        audio, mime = alt.read_bytes(), "audio/mp4"
        audio_note = "yt-dlp copy" + ("" if row["playing"] == vid else " (phone played another recording!)")
    row["audio"] = audio_note
    cache = OUT / f"{vid}.{ARGS.model}.words.json"
    words = json.loads(cache.read_text(encoding="utf-8")) if cache.exists() else None
    if words is None:
        words = deepgram_words(audio, mime, language_for(lines), ARGS.model)
        if words is None:
            return dict(row, error="deepgram failed")
        cache.write_text(json.dumps(words, ensure_ascii=False), encoding="utf-8")
    matched = align(lines, words)
    (OUT / f"{vid}.lines.csv").write_text(
        "line,shown_s,sung_s,error_s,ratio\n" + "".join(
            f"{n},{a:.3f},{b:.3f},{a - b:+.3f},{r}\n" for n, a, b, r in matched), encoding="utf-8")
    s = score(matched)
    if s is None:
        return dict(row, error="no line matched the heard words")
    return dict(row, **s)


# ---------- report ----------

def write_report(rows):
    fields = ["playlist", "video", "verdict", "shape", "slope_ms_min", "median", "median_abs", "in02", "in03",
              "in05", "matched", "lines", "stored", "resolve", "source", "base", "shift", "match", "playing",
              "audio", "error"]
    with open(OUT / "report.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    scored = [r for r in rows if r.get("verdict")]
    md = [f"# 싱크 채점 {datetime.datetime.now():%Y-%m-%d %H:%M} ({ARGS.model})", "",
          "오차 = 화면 표시 시각 - 실제 부른 시각 (+면 가사가 늦음). "
          "PASS = 중앙값 |오차| ≤ 0.2초 + 0.3초 이내 줄 ≥ 90%, WARN = 중앙값 ≤ 0.3초.", ""]
    shapes = {}
    for r in scored:
        shapes[r["shape"]] = shapes.get(r["shape"], 0) + 1
    md.append("모양별 곡 수: " + ", ".join(f"{k} {v}" for k, v in sorted(shapes.items())))
    for label, flag in (("다운로드된 곡(저장 음원·가사·싱크)", "y"), ("스트리밍", "n")):
        s = [r for r in scored if r.get("stored") == flag]
        if s:
            md.append(f"- {label}: 합격 {sum(r['verdict'] == 'PASS' for r in s)}/{len(s)}")
    md += ["", "| 판정 | 곡 | 저장 | 모양 | 중앙값(초) | 0.2/0.3/0.5초 이내 | 맞춘 줄 | 제공처 | base/shift/match | 오디오 |",
           "|---|---|---|---|---|---|---|---|---|---|"]
    order = {"FAIL": 0, "WARN": 1, "PASS": 2}
    for r in sorted(scored, key=lambda r: (order[r["verdict"]], -r["median_abs"])):
        sh = r["shape"] + (f" {r['slope_ms_min']:+}ms/분" if r["shape"] == "drift" else "")
        md.append(f"| {r['verdict']} | {r['video']} | {r.get('stored', '')} | {sh} | {r['median']:+.2f} | "
                  f"{r['in02']:.0%}/{r['in03']:.0%}/{r['in05']:.0%} | {r['matched']}/{r['lines']} | "
                  f"{r['source']} | {r['base']}/{r['shift']}/{r['match']} | {r['audio']} |")
    errors = [r for r in rows if r.get("error")]
    if errors:
        md += ["", "## 채점 못 한 곡"] + [f"- {r['video']}: {r['error']}" for r in errors]
    md.append("")
    for pid in dict.fromkeys(r["playlist"] for r in rows):
        s = [r for r in scored if r["playlist"] == pid]
        if s:
            md.append(f"- {pid}: 합격 {sum(r['verdict'] == 'PASS' for r in s)}/{len(s)}")
    passed = sum(r["verdict"] == "PASS" for r in scored)
    md.append(f"\n**합격률: {passed}/{len(scored)}** (채점 못 함 {len(errors)})")
    (OUT / "report.md").write_text("\n".join(md) + "\n", encoding="utf-8")


def selftest():
    # Heard words 0.4 s after the shown times; one line misheard.
    lines = [(1, 10000, "hello darkness my old friend"), (2, 14000, "i've come to talk with you again"),
             (3, 18000, "because a vision softly creeping"), (4, 22000, "left its seeds while i was sleeping"),
             (5, 26000, "and the vision that was planted")]
    words = []
    for _, ms, text in lines:
        t = ms / 1000 - 0.4
        for w in text.split():
            words.append((w, t, t + 0.3))
            t += 0.35
    m = align(lines, words)
    s = score(m)
    assert len(m) == 5 and abs(s["median"] - 0.4) < 0.01 and s["shape"] == "const", (m, s)
    drift = [(t, 0.01 * t) for t in range(0, 200, 10)]
    assert shape(drift)[0] == "drift", shape(drift)
    jump = [(t, 0.0 if t < 100 else 1.0) for t in range(0, 200, 10)]
    assert shape(jump)[0] == "jump", shape(jump)
    print("selftest ok")


def main():
    global ARGS
    ap = argparse.ArgumentParser()
    ap.add_argument("--playlist", action="append", default=[])
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--seconds", type=int, default=75)
    ap.add_argument("--shuffle", action="store_true")
    ap.add_argument("--seed", default=f"{datetime.date.today():%Y%m%d}")
    ap.add_argument("--default-set", action="store_true")
    ap.add_argument("--video", action="append", default=[], help="score these video ids only")
    ap.add_argument("--model", default="whisper-large", help="Deepgram model (the app itself uses nova-3)")
    # The user's call (10-26): test mostly video songs (MVs, covers, live clips),
    # where the song-version swap and sync go wrong; plain song copies rarely do.
    ap.add_argument("--all-tracks", dest="videos_only", action="store_false",
                    help="also pick plain song (Topic) copies; default is videos only")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    ARGS = ap.parse_args()
    if ARGS.selftest:
        return selftest()
    OUT.mkdir(parents=True, exist_ok=True)
    if ARGS.default_set:
        ARGS.playlist, ARGS.limit, ARGS.shuffle = DEFAULT_SET, ARGS.limit or DEFAULT_PER_PLAYLIST, True
    if ARGS.video:
        songs = [("manual", v) for v in ARGS.video]
    elif ARGS.dry_run and not ARGS.playlist:
        songs = [("saved", p.stem) for p in sorted(OUT.glob("*.log"))]
    else:
        songs = pick_songs(ARGS.playlist, ARGS.limit, ARGS.shuffle, ARGS.seed)
    if not songs:
        sys.exit("no songs")
    volume = None
    if not ARGS.dry_run:
        if not ensure_phone():
            sys.exit("phone NOT FOUND - hotspot + wireless debugging needed")
        volume = media_volume()
        set_media_volume(0)
        adb("shell", "mkdir", "-p", APP_DIR)
        adb("shell", "touch", f"{APP_DIR}/sync-test.flag")
    rows = []
    try:
        for k, (pid, vid) in enumerate(songs, 1):
            log(f"[{k}/{len(songs)}] {vid}")
            r = dict(playlist=pid, video=vid, **run_song(vid, ARGS.seconds, ARGS.dry_run))
            log(f"  -> {r.get('verdict') or r.get('error')} median={r.get('median')} shape={r.get('shape')}")
            rows.append(r)
            write_report(rows)
    finally:
        if not ARGS.dry_run:
            adb("shell", "rm", "-f", f"{APP_DIR}/sync-test.flag")
            adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE")
            if volume is not None:
                set_media_volume(volume)
    log(f"done: {OUT / 'report.md'}")


ARGS = None
if __name__ == "__main__":
    main()
