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
import argparse, csv, os, datetime, difflib, json, random, re, statistics, subprocess, sys, time, unicodedata, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]  # Desktop\BitChord
ADB = str(ROOT / "tools" / "android-sdk" / "platform-tools" / "adb.exe")
YTDLP = str(ROOT / "tools" / "bin" / "yt-dlp.exe")
PHONE_AUTO = ROOT / "tools" / "phone_auto.py"
KEY_FILE = Path(os.environ.get("SYNCTEST_KEY_FILE") or ROOT / "keys" / "deepgram_key.txt")
OUT = ROOT / "phone-out" / "synctest"
PKG = "com.music.bitchord"
APP_DIR = f"/sdcard/Android/data/{PKG}/files"
DEFAULT_SET = ["PL02cgYzp6Oi4kOisQAh691u0phBtC4PVD", "PL-ODED49YdZByxxHBLaIIOOePlhj8nWcc"]
DEFAULT_PER_PLAYLIST = 10

MIN_RATIO = 0.6       # how alike a line's opening must be to the heard words
SEARCH_S = 15.0       # a line is looked for this far either side of its displayed start
DIST_PENALTY_PER_S = 0.01  # tie-break toward the displayed time (a 10 s-away repeat must match 0.1 better)
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


# Searches outside the user's own playlists, which the phone has mostly played.
FRESH_SEARCHES = ["ytsearch40:노래 커버 cover", "ytsearch40:acoustic cover song", "ytsearch30:kpop vocal cover",
                  "ytsearch30:pop song cover live"]


def fresh_pool(playlists, seed):
    """Candidates for --fresh: search results first, then the given playlists, shuffled,
    videos only, minus every id an earlier test run on this PC already played."""
    played = {p.stem.split(".")[0] for p in OUT.parent.rglob("*.log")}
    ids = []
    for q in FRESH_SEARCHES:
        r = subprocess.run([YTDLP, "--flat-playlist", "--print", "%(id)s %(duration)s", q], capture_output=True, timeout=300)
        for l in r.stdout.decode("utf-8", "replace").splitlines():
            parts = l.split()
            # One song, not an hour-long compilation or a short: 1.5-7 minutes
            # (11-02 round 1 drew three playlist videos with no single lyric).
            if len(parts) == 2 and re.fullmatch(r"[\w-]{11}", parts[0]) and \
                    re.fullmatch(r"\d+(\.\d+)?", parts[1]) and 90 <= float(parts[1]) <= 420:
                ids.append(("search", parts[0]))
    for pid in playlists:
        ids += [(pid, v) for v in playlist_ids(pid)]
    random.Random(seed).shuffle(ids)
    seen, out = set(), []
    for pid, v in ids:
        if v in seen or v in played:
            continue
        seen.add(v)
        out.append((pid, v))
    out = out[:60]  # enough for 20-25 first plays; each is_video check is one yt-dlp call
    if ARGS.videos_only:
        out = [(pid, v) for pid, v in out if is_video(v)]
    log(f"fresh pool: {len(out)} candidates")
    return out


# ---------- app log ----------

def parse_dump(text, vid):
    """The last complete sync-dump block for vid: (header dict, [(n, start_ms, text)])."""
    blocks = parse_blocks(text, vid)
    return blocks[-1] if blocks else None


def parse_blocks(text, vid):
    """Every complete sync-dump block for vid, in log order: the provider's raw times,
    then each base the app switched to (ai, measured, ...). Scoring them all shows
    which method was right when the app replaced one answer with another."""
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
            head["lit"] = {}  # line number -> ms the sweep first lights it
            cur = (head, [])
        elif kind == "line" and cur is not None and len(parts) >= 4:
            rest = parts[4] if len(parts) > 4 else ""
            # `rep=<k>/<n> words=<w>` ahead of the text (11-04). Written for a
            # human reading the log — which time a chorus line came round, how
            # many words it holds — and stripped here so the text handed to the
            # aligner is the lyrics and nothing else. Older logs start straight
            # at the text and are read the same way. `lit=<ms>` (11-06) is when the
            # sweep first lights the line.
            while True:
                meta = re.match(r"(rep|words|lit)=(\S+)\s+", rest)
                if not meta:
                    break
                if meta.group(1) == "lit":
                    cur[0]["lit"][int(parts[2])] = int(meta.group(2))
                rest = rest[meta.end():]
            cur[1].append((int(parts[2]), int(parts[3]), rest))
        elif kind == "end" and cur is not None:
            blocks.append(cur)
            cur = None
    return blocks


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


def hear(key, path, mime, language, model):
    """Words heard in `path`, cached per audio and model so --dry-run re-scores for free."""
    cache = OUT / f"{key}.{model}.words.json"
    if cache.exists():
        return json.loads(cache.read_text(encoding="utf-8"))
    words = deepgram_words(path.read_bytes(), mime, language, model)
    if words is not None:
        cache.write_text(json.dumps(words, ensure_ascii=False), encoding="utf-8")
    return words


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


MAX_CANDIDATES = 6     # places per line the whole-sheet alignment chooses between
AMBIGUOUS_RATIO = 0.05  # another place this close in likeness ...
AMBIGUOUS_ERR_S = 1.0   # ... and a chosen error this large: which repeat it was is unknowable


def candidates(key, shown, heard, times):
    """[(rank, ratio, i, t)] places the line's opening may have been sung: the best
    start in each stretch of matching characters, at most MAX_CANDIDATES."""
    found = []
    for i in range(len(heard)):
        t = times[i]
        if t < shown - SEARCH_S:
            continue
        if t > shown + SEARCH_S:
            break
        r = difflib.SequenceMatcher(None, key, heard[i:i + len(key)], autojunk=False).ratio()
        if heard[i] != key[0]:
            r -= 0.05  # prefer a match that starts on the line's first sound
        if r < MIN_RATIO:
            continue
        # A chorus repeats its words: among near-equal matches the one nearest the
        # displayed time is preferred (10-27: repeats were scored as 5 s errors).
        rank = r - DIST_PENALTY_PER_S * abs(t - shown)
        if found and i - found[-1][2] < len(key):
            if rank > found[-1][0]:
                found[-1] = (rank, r, i, t)  # same stretch: keep its best start
            continue
        found.append((rank, r, i, t))
    return sorted(found, reverse=True)[:MAX_CANDIDATES]


def align(lines, words):
    """[(n, shown_s, sung_s, ratio)] for every line found in the heard words, in order.

    The whole sheet is placed at once (11-02): the sung order must follow the sheet's
    order, and among the orders that do, the one with the best total likeness wins.
    The old greedy walk took each line's best place and then searched only after it,
    so one line matched to a later chorus dragged every following line onto that
    repeat as well: hand-timed sheets (AMLL) scored -9.3 s errors on Espresso and
    -7.5 s on APT. A line whose chosen place is far off while an almost-as-alike
    place exists elsewhere is dropped — which repeat was sung cannot be told."""
    heard, times = char_stream(words)
    if not heard:
        return []
    last_heard = times[-1]
    rows = []
    for n, start_ms, text in lines:
        shown = start_ms / 1000.0
        key = norm(text)[:PREFIX_CHARS]
        if len(key) < 2 or shown > last_heard + 1.0:
            continue
        cand = candidates(key, shown, heard, times)
        if cand:
            rows.append((n, shown, cand))
    # best[j][c] = best total ending with line j at its candidate c (indices rising).
    best, back = [], []
    for j, (_, _, cand) in enumerate(rows):
        bj, kj = [], []
        for rank, _, i, _ in cand:
            top, arg = 0.0, None
            for jj in range(j):
                for cc, (_, _, ii, _) in enumerate(rows[jj][2]):
                    if ii < i and best[jj][cc] > top:
                        top, arg = best[jj][cc], (jj, cc)
            bj.append(rank + top)
            kj.append(arg)
        best.append(bj)
        back.append(kj)
    if not rows:
        return []
    end = max(((j, c) for j in range(len(rows)) for c in range(len(rows[j][2]))),
              key=lambda jc: best[jc[0]][jc[1]])
    chosen = []
    while end is not None:
        chosen.append(end)
        end = back[end[0]][end[1]]
    out = []
    for j, c in reversed(chosen):
        n, shown, cand = rows[j]
        _, r, _, t = cand[c]
        if abs(t - shown) > AMBIGUOUS_ERR_S and any(
            other[1] >= r - AMBIGUOUS_RATIO and abs(other[3] - t) > AMBIGUOUS_ERR_S
            for k, other in enumerate(cand) if k != c
        ):
            continue
        out.append((n, shown, t, round(r, 2)))
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


# Strict pass (11-01/11-02 user: "엄격히", twice). Every rule must hold; `why` names the ones
# that failed. The old rule (median |err| <= 0.2 s + 90% within 0.3 s) is kept as
# old_verdict for comparing past runs. Rules are judged on the whole song (lines both
# hearing models agree on), so drift late in the song counts.
# coverage = lines either model heard near their shown time / lines that should be heard:
# hearing models catch ~50-80% of sung lines, wrong or far-off lyrics fall near 0.
STRICT = dict(median_abs=0.12, median_bias=0.08, in03=0.95, in02=0.85, max_abs=0.8, min_lines=8,
              drift_s_per_min=0.06, drift_min_span_s=90, coverage=0.4)


def drift_slope(points):
    """Error change per second over the song (least squares, lines > 0.5 s off the median left out)."""
    if len(points) < 4:
        return 0.0, 0.0
    med = statistics.median(e for _, e in points)
    pts = [(t, e) for t, e in points if abs(e - med) <= 0.5] or points
    ts = [t for t, _ in pts]
    mt, me = statistics.mean(ts), statistics.mean(e for _, e in pts)
    var = sum((t - mt) ** 2 for t in ts) or 1e-9
    return sum((t - mt) * (e - me) for t, e in pts) / var, max(ts) - min(ts)


def score(matched, eligible=None, heard=None):
    """matched = [(n, shown_s, sung_s, ratio)]; eligible = lines that should have been heard
    (shown inside the heard audio) and heard = lines any model found, for the coverage rule."""
    errs = [shown - sung for _, shown, sung, _ in matched]
    if not errs:
        return None
    a = [abs(e) for e in errs]
    within = {lim: sum(x <= lim for x in a) / len(a) for lim in (0.2, 0.3, 0.5)}
    med_signed = statistics.median(errs)
    med_abs = statistics.median(a)
    slope, span = drift_slope([(sung, shown - sung) for _, shown, sung, _ in matched])
    coverage = (heard if heard is not None else len(errs)) / eligible if eligible else None
    why = []
    if len(errs) < STRICT["min_lines"]:
        why.append(f"줄 {len(errs)}개뿐")
    if coverage is not None and coverage < STRICT["coverage"]:
        # Most shown lines are never sung near their time: wrong lyrics or far off.
        why.append(f"들린 줄 {coverage:.0%}")
    if span >= STRICT["drift_min_span_s"] and abs(slope * 60) > STRICT["drift_s_per_min"]:
        why.append(f"점점 {'늦어짐' if slope > 0 else '빨라짐'} {slope * 60:+.2f}초/분")
    if med_abs > STRICT["median_abs"]:
        why.append(f"중앙값 {med_abs:.2f}초")
    if abs(med_signed) > STRICT["median_bias"]:
        why.append(f"전체 {'늦음' if med_signed > 0 else '빠름'} {med_signed:+.2f}초")
    if within[0.3] < STRICT["in03"]:
        why.append(f"0.3초 이내 {within[0.3]:.0%}")
    if within[0.2] < STRICT["in02"]:
        why.append(f"0.2초 이내 {within[0.2]:.0%}")
    if max(a) > STRICT["max_abs"]:
        why.append(f"최대 {max(a):.1f}초 어긋난 줄")
    verdict = "PASS" if not why else "WARN" if med_abs <= 0.3 else "FAIL"
    old = "PASS" if med_abs <= 0.2 and within[0.3] >= 0.9 else "WARN" if med_abs <= 0.3 else "FAIL"
    kind, slope = shape([(sung, shown - sung) for _, shown, sung, _ in matched])
    return dict(matched=len(errs), median=round(med_signed, 3), median_abs=round(med_abs, 3),
                in02=round(within[0.2], 2), in03=round(within[0.3], 2), in05=round(within[0.5], 2),
                max_abs=round(max(a), 2), verdict=verdict, old_verdict=old, why=", ".join(why),
                shape=kind, slope_ms_min=slope, span_s=round(span),
                coverage=round(coverage, 2) if coverage is not None else "")


# ---------- whole-song hearing ----------

# Two hearing models; a line counts only where both put its start within this of
# each other, so one model's mishearing or coarse timestamp does not become an error.
# 11-02 measured on two covers: nova-2/nova-3 hear almost nothing in singing (0-21
# words for a whole song), whisper-large/medium ~100-280 words and agree to 0.06 s.
MODELS = ("whisper-large", "whisper-medium")
AGREE_S = 0.2


def offset_between(ref_words, other_words):
    """(offset_s, votes, share): other = ref + offset for the same recording. Each word
    heard in both (3+ letters, within 30 s) votes for its time difference; the densest
    0.1 s bin wins. `share` = voting words / ref words, to tell another recording apart."""
    diffs = []
    index = {}
    for w, t, _ in other_words:
        n = norm(w)
        if len(n) >= 3:
            index.setdefault(n, []).append(t)
    usable = 0
    for w, t, _ in ref_words:
        n = norm(w)
        if len(n) < 3:
            continue
        usable += 1
        diffs += [u - t for u in index.get(n, []) if abs(u - t) <= 30]
    if not diffs:
        return 0.0, 0, 0.0
    bins = {}
    for d in diffs:
        bins[round(d * 10)] = bins.get(round(d * 10), 0) + 1
    centre = max(bins, key=lambda b: bins[b] + bins.get(b - 1, 0) + bins.get(b + 1, 0)) / 10
    near = [d for d in diffs if abs(d - centre) <= 0.15]
    return statistics.median(near), len(near), len(near) / max(usable, 1)


def agreed(per_model):
    """Lines both models matched with starts within AGREE_S: [(n, shown, mean sung, min ratio)],
    plus every line either model matched (for coverage) and the models' median gap."""
    a, b = ({n: (shown, sung, r) for n, shown, sung, r in m} for m in per_model)
    both, gaps = [], []
    for n in sorted(set(a) & set(b)):
        gap = abs(a[n][1] - b[n][1])
        gaps.append(gap)
        if gap <= AGREE_S:
            both.append((n, a[n][0], (a[n][1] + b[n][1]) / 2, min(a[n][2], b[n][2])))
    return both, set(a) | set(b), (statistics.median(gaps) if gaps else None)


# ---------- one song ----------

def run_song(vid, seconds, dry):
    logf, wav = OUT / f"{vid}.log", OUT / f"{vid}.wav"
    if not dry:
        if not ensure_phone():
            return dict(error="phone not connected")
        adb("logcat", "-c")
        adb("shell", "rm", "-f", f"{APP_DIR}/synctest/{vid}.wav")
        # Screen on while it plays (11-02): lyrics are read with the screen on,
        # and the video measurement / AI timing deliberately wait for it, so a
        # dark-screen run scored lyrics no listener would ever see that way.
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
            "-d", f"https://music.youtube.com/watch?v={vid}", "-p", PKG)
        time.sleep(seconds)
        logf.write_bytes(adb("logcat", "-d", timeout=120, binary=True))
        wav.unlink(missing_ok=True)
        adb("pull", f"{APP_DIR}/synctest/{vid}.wav", str(wav), timeout=120)
        # A hi-res substitute playing: its own head, kept under the id it plays as.
        played = parse_dump(logf.read_text(encoding="utf-8", errors="replace"), vid)
        if played and played[0].get("playing", "").startswith("src:"):
            # The app writes ':' as '_' in file names (SyncTestMode.safeName).
            name = played[0]["playing"].replace(":", "_")
            adb("pull", f"{APP_DIR}/synctest/{name}.wav", str(OUT / f"{vid}.play.wav"), timeout=120)
    if not logf.exists():
        return dict(error="no log")
    text = logf.read_text(encoding="utf-8", errors="replace")
    dump_id = vid
    dump = parse_dump(text, vid)
    if not dump:
        # The app swapped the row to its song version before the first sound
        # ('song version: <vid> -> <id> via search'); lyrics, their dump and the
        # song check's WAV then belong to that id (11-02 round 3: three
        # "no lyrics" were this).
        m = re.findall(rf"song version: {re.escape(vid)} -> ([\w-]{{11}})", text)
        if m and parse_dump(text, m[-1]):
            dump_id = m[-1]
            dump = parse_dump(text, dump_id)
            if not dry and not (wav.exists() and wav.stat().st_size > 1000):
                adb("pull", f"{APP_DIR}/synctest/{dump_id}.wav", str(wav), timeout=120)
    if not dump:
        # No lyrics on screen is the user-facing failure itself, not a test gap.
        return dict(error="no sync-dump (no lyrics shown?)", app_fail=True)
    head, lines = dump
    # A downloaded song plays its stored file with its stored lyrics and timing,
    # which may themselves be wrong; scored the same way but reported apart.
    resolve = resolve_of(text, vid)
    stored = resolve == "cache" or head.get("source", "").startswith("file:") or "@unknown" in head.get("source", "")
    row = dict(source=head.get("source", ""), base=head.get("base", ""), shift=head.get("shift", ""),
               match=head.get("match", ""), playing=head.get("playing", ""), lines=len(lines),
               resolve=resolve, stored="y" if stored else "n", blocks=parse_blocks(text, dump_id),
               swapped=dump_id if dump_id != vid else "")
    if not lines:
        return dict(row, error="lyrics shown without timed lines", app_fail=True)
    lang = language_for(lines)
    if row["playing"].startswith("src:"):
        # A hi-res copy from another source is playing; the song check's WAV is the
        # YouTube reference, whose clock may differ by seconds. The app (sync-test
        # mode, 11-02 build on) keeps that copy's verified head under its own id.
        play = OUT / f"{vid}.play.wav"
        if not play.exists() or play.stat().st_size < 1000:
            return dict(row, error="hi-res substitute playing, its head was not kept (app before 11-02?)")
        per_model = [hear(f"{vid}.play", play, "audio/wav", lang, m) for m in MODELS]
        if None in per_model:
            return dict(row, error="deepgram failed")
        row["audio"] = "hi-res copy's own head (hq-verify, 90 s)"
        return finish(vid, row, lines, per_model)
    app_words = None
    if wav.exists() and wav.stat().st_size > 1000:
        app_words = hear(f"{vid}.app", wav, "audio/wav", lang, MODELS[0])
        if app_words is None:
            return dict(row, error="deepgram failed")
    elif stored:
        # The stored file may be another recording than YouTube's copy, so
        # hearing yt-dlp's copy would score the wrong audio.
        return dict(row, error="downloaded song, app kept no wav (song check skipped)")
    # Whole song (11-02): the app keeps only the first 90 s, so drift later in the
    # song was never seen. Hear YouTube's copy of the recording the phone actually
    # played, lined up on the app's own audio by the words both contain.
    played = row["playing"] or vid
    # The same Opus stream (itag 251) the app plays: the AAC copy (140) carries
    # its own encoder delay, and scoring on it put every streamed song ~0.1 s
    # early while downloaded songs, scored on the app's own audio, sat at 0.
    full = OUT / f"{played}.webm"
    if not full.exists():
        subprocess.run([YTDLP, "-f", "251/bestaudio[ext=webm]/bestaudio", "-o", str(full),
                        f"https://www.youtube.com/watch?v={played}"], capture_output=True, timeout=600)
    per_model = None
    if full.exists():
        full_words = [hear(f"{played}.opus", full, "audio/webm", lang, m) for m in MODELS]
        if None in full_words:
            return dict(row, error="deepgram failed")
        if app_words is None:
            per_model, row["audio"] = full_words, "whole song (YouTube copy, not checked against the app's audio)"
            row["fa_audio"], row["fa_off"] = played, 0.0
        else:
            off, votes, share = offset_between(app_words, full_words[0])
            if votes >= 8 and share >= 0.25:
                per_model = [[(w, s - off, e - off) for w, s, e in fw] for fw in full_words]
                row["audio"] = f"whole song (YouTube copy {off:+.2f}s from app audio, {votes} words)"
                row["fa_audio"], row["fa_off"] = played, off
    if per_model is None:
        if app_words is None:
            return dict(row, error="no audio")
        # YouTube's copy is missing or another recording than the phone's: the app's
        # 90 s is all that is certainly what was played.
        second = hear(f"{vid}.app", wav, "audio/wav", lang, MODELS[1])
        if second is None:
            return dict(row, error="deepgram failed")
        per_model, row["audio"] = [app_words, second], "app wav only (first 90 s; YouTube copy did not line up)"
    return finish(vid, row, lines, per_model)


# Precise onsets (11-02): scripts/syncjudge/judge.py on a runner re-times each line
# whisper placed, by forced alignment on the separated vocals (tools\syncjudge.ps1).
# Calibrated 11-02 on 14 AMLL hand-timed sheets (sync_calibrate.py): forced alignment on
# singing is no more precise than whisper (taken alone it passed 2-4/14), but the two
# err independently, so a line both put within FA_MAX_MOVE_S of each other is trusted
# and the rest is left out: 10/14 correct sheets pass the unchanged strict rules
# (whisper alone 5/14), 97% of their lines within 0.3 s.
FA_MIN_SCORE = 0.0      # MMS_FA letter probabilities on sung vocals run 0.01-0.36; not a usable gate
FA_MAX_MOVE_S = 0.1     # whisper and the aligner must agree this closely
MIN_EVIDENCE_RATIO = 0.8  # and the line's opening must read as the sheet's text this well


def write_fa_in(vid, audio, offset, lines, both):
    """What the runner needs to re-time `both`: the YouTube id it downloads, the offset to
    the app's clock (YouTube = app + offset), and per line its text and how long it lasts."""
    starts = sorted(ms / 1000 for _, ms, _ in lines)
    text_of = {n: t for n, _, t in lines}
    rows = []
    for n, shown, sung, _ in both:
        later = [s for s in starts if s > shown]
        span = (later[0] - shown) if later else 4.0
        rows.append(dict(n=n, sung=round(sung, 3), span=round(span, 3), text=text_of.get(n, "")))
    (OUT / f"{vid}.fa-in.json").write_text(
        json.dumps(dict(vid=vid, audio=audio, offset=offset, lines=rows), ensure_ascii=False), encoding="utf-8")


def refine_with_fa(vid, both):
    """`both` re-timed by the runner's forced alignment when its answer is here:
    (lines, label). Only lines the aligner timed confidently and near whisper's place
    are kept, so whisper's own timing error does not mix back in."""
    # Whisper alone, its well-read lines only (calibration: 7/14 correct sheets pass
    # against 5/14 on every line); all of them when too few read that well.
    strong = [b for b in both if b[3] >= MIN_EVIDENCE_RATIO]
    whisper = strong if len(strong) >= STRICT["min_lines"] else both
    path = OUT / f"{vid}.fa-out.json"
    if not path.exists():
        return whisper, "whisper"
    fa = {r["n"]: r for r in json.loads(path.read_text(encoding="utf-8")).get("lines", [])}
    out = []
    for n, shown, sung, ratio in both:
        r = fa.get(n)
        if ratio >= MIN_EVIDENCE_RATIO and r and r["score"] >= FA_MIN_SCORE and abs(r["fa"] - sung) <= FA_MAX_MOVE_S:
            out.append((n, shown, r["fa"], ratio))
    if len(out) < STRICT["min_lines"]:
        return whisper, f"whisper (fa agreed on {len(out)}/{len(both)})"
    return out, f"fa {len(out)}/{len(both)}"


def lit_scores(both, lit):
    """What the eye sees: first lit letter - sung start, on the lines scored. The stamp
    decides PASS; these columns show when the sweep tells a different story (old logs: {})."""
    errs = [lit[n] / 1000 - sung for n, _, sung, _ in both if n in lit]
    if not errs:
        return {}
    later = [lit[n] / 1000 - shown for n, shown, _, _ in both if n in lit]
    return dict(lit_median=round(statistics.median(errs), 3),
                lit_in03=round(sum(abs(e) <= 0.3 for e in errs) / len(errs), 2),
                lit_max=round(max(abs(e) for e in errs), 2),
                lit_later=round(sum(x >= 0.2 for x in later) / len(later), 2))


def finish(vid, row, lines, per_model):
    """Scores the lines against what both hearing models heard in the played audio."""
    # Every answer the app showed for this song, scored the same way: which base
    # (provider's own, ai, measured, ...) was closest to what is sung.
    alts = []
    blocks = row.pop("blocks", [])
    lit = blocks[-1][0].get("lit", {}) if blocks else {}
    for head, blines in blocks:
        b, _, _ = agreed([align(blines, words) for words in per_model])
        if len(b) >= 4:
            e = [shown - sung for _, shown, sung, _ in b]
            in03 = sum(abs(x) <= 0.3 for x in e) / len(e)
            alts.append(f"{head.get('base', '?')}{head.get('shift', '')}:{statistics.median(e):+.2f}/{in03:.0%}")
    row["alts"] = " ".join(dict.fromkeys(alts))
    per = [align(lines, words) for words in per_model]
    both, either, gap = agreed(per)
    fa_audio, fa_off = row.pop("fa_audio", None), row.pop("fa_off", None)
    if fa_audio:
        write_fa_in(vid, fa_audio, fa_off, lines, both)
        whisper = score(both, None, None) if len(both) >= STRICT["min_lines"] else None
        both, row["timing"] = refine_with_fa(vid, both)
        row["whisper_verdict"] = whisper["verdict"] if whisper else ""
    else:
        row["timing"] = "whisper (no YouTube copy for fa)"
    heard_until = min(words[-1][1] for words in per_model if words) if all(per_model) else 0
    eligible = sum(1 for _, ms, t in lines if len(norm(t)[:PREFIX_CHARS]) >= 2 and ms / 1000 <= heard_until)
    row.update(eligible=eligible, model_gap=round(gap, 3) if gap is not None else "", **lit_scores(both, lit))
    (OUT / f"{vid}.lines.csv").write_text(
        "line,shown_s,sung_s,error_s,ratio,lit_s\n" + "".join(
            f"{n},{a:.3f},{b:.3f},{a - b:+.3f},{r},{lit[n] / 1000 if n in lit else ''}\n" for n, a, b, r in both),
        encoding="utf-8")
    if eligible >= 10 and len(either) < 0.25 * eligible:
        return dict(row, error=f"shown lyrics not heard ({len(either)}/{eligible} lines)", app_fail=True)
    if len(both) < STRICT["min_lines"]:
        # Too little agreed evidence to judge the app either way: a measuring gap,
        # counted against the 10% allowance, never a pass.
        return dict(row, error=f"too few lines both models agree on ({len(both)}, heard {len(either)}/{eligible})")
    return dict(row, **score(both, eligible, len(either)))


# ---------- report ----------

def write_report(rows):
    fields = ["playlist", "video", "verdict", "why", "old_verdict", "shape", "slope_ms_min", "span_s", "median",
              "median_abs", "in02", "in03", "in05", "max_abs", "matched", "eligible", "coverage", "model_gap",
              "lines", "stored", "resolve", "source", "base", "shift", "match", "playing", "audio", "error",
              "app_fail", "alts", "swapped", "timing", "whisper_verdict", "lit_median", "lit_in03", "lit_max", "lit_later"]
    with open(OUT / "report.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    scored = [r for r in rows if r.get("verdict")]
    md = [f"# 싱크 채점 {datetime.datetime.now():%Y-%m-%d %H:%M} ({' + '.join(MODELS)})", "",
          "오차 = 화면 표시 시각 - 실제 부른 시각 (+면 가사가 늦음). 곡 전체를 두 음성인식 모델로 듣고, "
          f"두 모델이 {AGREE_S}초 안으로 같게 들은 줄만 채점(모델차 = 두 모델 시각 차 중앙값 = 측정 잡음). "
          f"PASS(엄격) = 중앙값 |오차| ≤ {STRICT['median_abs']}초, 전체 치우침 ≤ ±{STRICT['median_bias']}초, "
          f"0.3초 이내 줄 ≥ {STRICT['in03']:.0%}, 0.2초 이내 ≥ {STRICT['in02']:.0%}, 어느 줄도 {STRICT['max_abs']}초 넘게 "
          f"어긋나지 않음, 점점 벌어짐 ≤ {STRICT['drift_s_per_min']}초/분, 채점 줄 ≥ {STRICT['min_lines']}개, "
          f"들린 줄 ≥ {STRICT['coverage']:.0%}. WARN = 중앙값 ≤ 0.3초. "
          "전체 합격 = 채점한 곡 모두 PASS + 앱 문제(가사 안 뜸·가사가 소리와 다름) 0곡 + 측정 실패 10% 이하. "
          "'이전' = 예전 기준(중앙값 ≤ 0.2초 + 0.3초 이내 90%). "
          "'화면' = 첫 글자가 켜지는 시각 기준 중앙값/0.3초 이내/최대, 판정에는 안 쓴다.", ""]
    shapes = {}
    for r in scored:
        shapes[r["shape"]] = shapes.get(r["shape"], 0) + 1
    md.append("모양별 곡 수: " + ", ".join(f"{k} {v}" for k, v in sorted(shapes.items())))
    for label, flag in (("다운로드된 곡(저장 음원·가사·싱크)", "y"), ("스트리밍", "n")):
        s = [r for r in scored if r.get("stored") == flag]
        if s:
            md.append(f"- {label}: 합격 {sum(r['verdict'] == 'PASS' for r in s)}/{len(s)}")
    md += ["", "| 판정 | 이전 | 못 미친 기준 | 곡 | 저장 | 모양 | 중앙값(초) | 최대(초) | 0.2/0.3/0.5초 이내 | 화면 중앙값/0.3초/최대 | 채점 줄/들려야 할 줄 | 모델차(초) | 제공처 | base/shift/match | 앱이 보인 답 차례(기준shift:중앙값/0.3초 이내) | 오디오 | 시각 기준(whisper 판정) |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    order = {"FAIL": 0, "WARN": 1, "PASS": 2}
    for r in sorted(scored, key=lambda r: (order[r["verdict"]], -r["median_abs"])):
        sh = r["shape"] + (f" {r['slope_ms_min']:+}ms/분" if r["shape"] == "drift" else "")
        seen = f"{r['lit_median']:+.2f}/{r['lit_in03']:.0%}/{r['lit_max']}" if "lit_median" in r else ""
        md.append(f"| {r['verdict']} | {r.get('old_verdict', '')} | {r.get('why', '')} | {r['video']} | "
                  f"{r.get('stored', '')} | {sh} | {r['median']:+.2f} | {r.get('max_abs', '')} | "
                  f"{r['in02']:.0%}/{r['in03']:.0%}/{r['in05']:.0%} | {seen} | {r['matched']}/{r.get('eligible', '')} | "
                  f"{r.get('model_gap', '')} | {r['source']} | {r['base']}/{r['shift']}/{r['match']} | "
                  f"{r.get('alts', '')} | {r['audio']} | {r.get('timing', '')} ({r.get('whisper_verdict', '')}) |")
    errors = [r for r in rows if r.get("error") and not r.get("app_fail")]
    app_fails = [r for r in rows if r.get("app_fail")]
    if app_fails:
        md += ["", "## 앱 문제 (불합격으로 셈)"] + [f"- {r['video']}: {r['error']}" for r in app_fails]
    if errors:
        md += ["", "## 측정 실패 (채점 못 함)"] + [f"- {r['video']}: {r['error']}" for r in errors]
    md.append("")
    for pid in dict.fromkeys(r["playlist"] for r in rows):
        s = [r for r in scored if r["playlist"] == pid]
        if s:
            md.append(f"- {pid}: 합격 {sum(r['verdict'] == 'PASS' for r in s)}/{len(s)}")
    passed = sum(r["verdict"] == "PASS" for r in scored)
    old_passed = sum(r.get("old_verdict") == "PASS" for r in scored)
    overall = scored and passed == len(scored) and not app_fails and len(errors) <= 0.1 * len(rows)
    md.append(f"\n**합격률(엄격): {passed}/{len(scored) + len(app_fails)}** (앱 문제 {len(app_fails)}곡 포함, "
              f"이전 기준 {old_passed}/{len(scored)}, 측정 실패 {len(errors)}) → **전체 {'합격' if overall else '불합격'}**")
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
    assert s["verdict"] == "FAIL" and s["old_verdict"] == "FAIL", s
    # Strict rules: 10 lines 0.05 s late pass; one line 1.5 s off fails strict but passes the old rule.
    good = [(n, 10.0 * n + 0.05, 10.0 * n, 1.0) for n in range(10)]
    assert score(good)["verdict"] == "PASS", score(good)
    one_bad = good[:9] + [(9, 91.5, 90.0, 1.0)]
    s = score(one_bad)
    assert s["verdict"] == "WARN" and s["old_verdict"] == "PASS" and "최대" in s["why"], s
    late = [(n, 10.0 * n + 0.13, 10.0 * n, 1.0) for n in range(10)]
    s = score(late)
    assert s["verdict"] == "WARN" and "늦음" in s["why"] and s["old_verdict"] == "PASS", s
    assert score(good[:4])["verdict"] != "PASS"
    # Whole-song rules: 0.1 s/min drift over 4 min fails though the median is small;
    # half the shown lines never heard fails coverage.
    creep = [(n, 12.0 * n + (-0.08 + 0.1 * 12.0 * n / 60), 12.0 * n, 1.0) for n in range(20)]
    s = score(creep)
    assert s["verdict"] != "PASS" and "점점 늦어짐" in s["why"], s
    assert "들린 줄" in score(good, eligible=30)["why"]
    assert "들린 줄" not in score(good, eligible=30, heard=15)["why"]
    assert score(good, eligible=12)["verdict"] == "PASS"
    # Lining YouTube's copy up on the app's audio: same words 3.2 s later, plus noise.
    ref = [(w, 5.0 + i * 0.6, 5.3 + i * 0.6) for i, w in enumerate("hello darkness my old friend come talk with again vision softly creeping".split())]
    other = [(w, s + 3.2, e + 3.2) for w, s, e in ref] + [("hello", 60.0, 60.3), ("darkness", 61.0, 61.3)]
    off, votes, share = offset_between(ref, other)
    assert abs(off - 3.2) < 0.06 and votes >= 8 and share > 0.8, (off, votes, share)
    assert offset_between(ref, [("unrelated", 1.0, 1.2)])[1] == 0
    # Two models: line 2 heard 0.5 s apart is dropped, the rest averaged.
    a = [(1, 10.0, 10.1, 0.9), (2, 20.0, 20.0, 0.9), (3, 30.0, 30.2, 0.8)]
    b = [(1, 10.0, 10.0, 0.8), (2, 20.0, 20.5, 0.9), (4, 40.0, 40.0, 0.9)]
    both, either, gap = agreed([a, b])
    assert [n for n, *_ in both] == [1] and abs(both[0][2] - 10.05) < 1e-9 and either == {1, 2, 3, 4}, (both, either)
    drift = [(t, 0.01 * t) for t in range(0, 200, 10)]
    assert shape(drift)[0] == "drift", shape(drift)
    jump = [(t, 0.0 if t < 100 else 1.0) for t in range(0, 200, 10)]
    assert shape(jump)[0] == "jump", shape(jump)
    # The dump's `lit=`: read off the line, never left in the text; old lines have none.
    log = ("x sync-dump: begin abc playing=abc source=s base=none shift=0 match=0 lines=2\n"
           "x sync-dump: line abc 1 1000 rep=1/1 words=2 lit=1400 two words\n"
           "x sync-dump: line abc 2 3000 rep=1/1 words=1 old\n"
           "x sync-dump: end abc\n")
    head, parsed = parse_blocks(log, "abc")[-1]
    assert parsed == [(1, 1000, "two words"), (2, 3000, "old")] and head["lit"] == {1: 1400}, (head, parsed)
    assert lit_scores([(1, 1.0, 1.0, 1.0), (2, 3.0, 3.0, 1.0)], head["lit"]) == dict(
        lit_median=0.4, lit_in03=0.0, lit_max=0.4, lit_later=1.0)
    assert lit_scores([(2, 3.0, 3.0, 1.0)], head["lit"]) == {}
    print("selftest ok")


def main():
    global ARGS, OUT
    ap = argparse.ArgumentParser()
    ap.add_argument("--playlist", action="append", default=[])
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--seconds", type=int, default=75)
    ap.add_argument("--shuffle", action="store_true")
    ap.add_argument("--seed", default=f"{datetime.date.today():%Y%m%d}")
    ap.add_argument("--default-set", action="store_true")
    ap.add_argument("--video", action="append", default=[], help="score these video ids only")
    # The user's call (10-26): test mostly video songs (MVs, covers, live clips),
    # where the song-version swap and sync go wrong; plain song copies rarely do.
    ap.add_argument("--all-tracks", dest="videos_only", action="store_false",
                    help="also pick plain song (Topic) copies; default is videos only")
    ap.add_argument("--fresh", type=int, default=0,
                    help="score this many songs the phone never played (cached/downloaded ones skipped); "
                         "pool = --playlist ids plus YouTube cover searches, shuffled")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--out", type=Path, default=OUT, help="results folder (default phone-out\\synctest)")
    ap.add_argument("--selftest", action="store_true")
    ARGS = ap.parse_args()
    if ARGS.selftest:
        return selftest()
    OUT = ARGS.out.resolve()
    OUT.mkdir(parents=True, exist_ok=True)
    if ARGS.default_set:
        ARGS.playlist, ARGS.limit, ARGS.shuffle = DEFAULT_SET, ARGS.limit or DEFAULT_PER_PLAYLIST, True
    if ARGS.fresh:
        songs = fresh_pool(ARGS.playlist, ARGS.seed)
    elif ARGS.video:
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
            if ARGS.fresh and sum(1 for r in rows if not r.get("played_before")) >= ARGS.fresh:
                break
            log(f"[{k}/{len(songs)}] {vid}")
            r = dict(playlist=pid, video=vid, **run_song(vid, ARGS.seconds, ARGS.dry_run))
            if ARGS.fresh and (r.get("resolve") == "cache" or r.get("stored") == "y"):
                # 11-02 user: measure songs the phone plays for the first time, not
                # ones already downloaded or cached (stored lyrics/timing hide first-play
                # behaviour). The app's own resolve=cache is the proof it played before.
                log("  -> played before on this phone, not counted")
                continue
            log(f"  -> {r.get('verdict') or r.get('error')} median={r.get('median')} shape={r.get('shape')}"
                + (f" ({r['why']})" if r.get("why") else ""))
            rows.append(r)
            write_report(rows)
    finally:
        if not ARGS.dry_run:
            adb("shell", "rm", "-f", f"{APP_DIR}/sync-test.flag")
            adb("shell", "input", "keyevent", "KEYCODE_MEDIA_PAUSE")
            adb("shell", "input", "keyevent", "KEYCODE_SLEEP")  # screen back off after the run
            if volume is not None:
                set_media_volume(volume)
    log(f"done: {OUT / 'report.md'}")


ARGS = None
if __name__ == "__main__":
    main()
