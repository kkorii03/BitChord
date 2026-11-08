#!/usr/bin/env python3
"""Independent ground truth for the lyric-sync scorer (11-08, synctruth lane).

synctest.py judges the app's shown line starts against a Whisper transcript of the
recording. For a Gemini-transcript sheet that is circular, and the transcript's own
error hides small per-line fixes. This script fetches a HUMAN-timed lyric file
(AMLL TTML DB, github.com/amll-dev/amll-ttml-db) for a song when one exists, pairs its
lines with the app's sync-dump lines by normalised text, and reports the app's
per-line error against the human times.

Clock: the human file may be for a slightly different release, so ONE constant offset
(recording clock - file clock) is estimated from the recording itself (the cached
Whisper words, the file's lines aligned with synctest.align, median). The app's shown
times are then compared to file_time + offset, so a constant app bias stays visible.
The song is REFUSED when the file's lines do not sit on the recording tightly after
that offset (a different version / edit), and the reason is printed.

Lyric text is never printed: ids, counts and numbers only.

Usage (python -I; needs network the first time, then phone-out\\truth-cache):
  tools\\python\\python.exe -I scripts\\sync_truth.py <scored folder> [more folders ...]
  tools\\python\\python.exe -I scripts\\sync_truth.py --selftest
A scored folder is a synctest --out folder (rs9\\100-kkorii123-a, phone-1108-n2\\fresh,
or a parent of them: logs are searched recursively).
"""
import argparse, csv, difflib, functools, importlib.util, json, re, statistics, sys, time
import urllib.error, urllib.parse, urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent


def _load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


st = _load("synctest")
cal = _load("sync_calibrate")  # ttml_lines(), get(), itunes()
ROOT = st.ROOT
CACHE = ROOT / "phone-out" / "truth-cache"
INDEX_URL = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/refs/heads/main/metadata/raw-lyrics-index.jsonl"
INDEX_MAX_AGE_S = 7 * 86400
MAX_FILES_PER_SONG = 6     # candidate files downloaded and verified per song

MATCH_RATIO = 0.85         # fuzzy line pairing inside a gap (norm text ratio)
MIN_PAIRED = 8             # lines paired app<->file, else no verdict
MIN_PAIRED_SHARE = 0.5     # share of the app's lines (and the file's) that must pair, else other version
TIGHT_STD_S = 0.25         # after the offset the file's lines must sit this tightly on the recording ...
TIGHT_IN03 = 0.8           # ... and this share within 0.3 s
MIN_OFFSET_LINES = 8       # lines both Whisper models agree on, to estimate the offset


# ---------- fetching (cached) ----------

def fetch(url, cache_file):
    """Bytes of url, cached; a 404 is cached as an empty file so it is asked once.
    url None: cache_file is a file of the local DB checkout (see text_candidates)."""
    if url is None:
        return cache_file.read_bytes()
    if cache_file.exists():
        return cache_file.read_bytes() or None
    cache_file.parent.mkdir(parents=True, exist_ok=True)
    try:
        data = cal.get(url)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            cache_file.write_bytes(b"")
            return None
        raise
    cache_file.write_bytes(data)
    return data


def load_index():
    f = CACHE / "idx.jsonl"
    if not f.exists() or time.time() - f.stat().st_mtime > INDEX_MAX_AGE_S:
        CACHE.mkdir(parents=True, exist_ok=True)
        f.write_bytes(cal.get(INDEX_URL))
    out = []
    for raw in f.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            d = json.loads(raw)
        except ValueError:
            continue
        meta = {k: v for k, v in d.get("metadata", [])}
        out.append(dict(title=meta.get("musicName", [""])[0], artists=meta.get("artists", []),
                        apple=(meta.get("appleMusicId") or [""])[0], file=d.get("rawLyricFile", "")))
    return out


def clean_title(t):
    """Title without bracketed tags and feat./version suffixes: 'X (feat. Y) - Live' -> 'x'."""
    t = re.sub(r"[\(\[（【].*?[\)\]）】]", " ", t)
    t = re.split(r"\s[-–—]\s|\sfeat\.?\s|\sft\.?\s", t, maxsplit=1, flags=re.I)[0]
    return st.norm(t)


def title_ok(want, have):
    """Same song title once tags are cleaned (never a substring: 'star' is not 'busta rhymes')."""
    a, b = clean_title(want), clean_title(have)
    return len(a) >= 2 and a == b

def artist_ok(want, have_list):
    a = st.norm(want)
    if not a:
        return True
    return any((n := st.norm(h)) and (a in n or n in a) for h in have_list)


@functools.lru_cache(maxsize=1)
def am_titles():
    """{apple id: (title, artist)} for every am-lyrics/<id>.ttml in the DB. The DB has no
    titles for them, so Apple's lookup (batches by id; search is blocked) names them once;
    cached in truth-cache/am-titles.json. Ids Apple does not know stay unnamed."""
    f = CACHE / "am-titles.json"
    if f.exists() and time.time() - f.stat().st_mtime < INDEX_MAX_AGE_S:
        return json.loads(f.read_text(encoding="utf-8"))
    tree = json.loads(cal.get("https://api.github.com/repos/amll-dev/amll-ttml-db/git/trees/main?recursive=1"))["tree"]
    ids = sorted({p["path"][len("am-lyrics/"):-5] for p in tree if p["path"].startswith("am-lyrics/") and p["path"].endswith(".ttml")})
    out = {}
    for country in ("us", "kr"):
        todo = [i for i in ids if i not in out]
        for k in range(0, len(todo), 150):
            q = f"https://itunes.apple.com/lookup?id={','.join(todo[k:k + 150])}&country={country}"
            try:
                for h in json.loads(cal.get(q)).get("results", []):
                    out[str(h.get("trackId"))] = (h.get("trackName", ""), h.get("artistName", ""))
            except Exception:
                pass  # a batch Apple refuses is simply unnamed this time
    CACHE.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
    return out

def candidate_files(title, artist, index):
    """[(label, url, cache_file)] human-timed files that may be this song, best first:
    the AMLL index by title+artist, then Apple's id via iTunes (am-lyrics/<id>.ttml)."""
    found, seen = [], set()
    for e in index:
        if e["file"] and title_ok(title, e["title"]) and artist_ok(artist, e["artists"]):
            found.append((f"raw:{e['file'][:20]}", cal.RAW_BASE + e["file"], CACHE / "ttml" / e["file"]))
            seen.add(e["apple"])
    for tid, (tn, an) in am_titles().items():
        if tid not in seen and title_ok(title, tn) and artist_ok(artist, [an]):
            seen.add(tid)
            found.append((f"am:{tid}", cal.TTML_BASE + f"{tid}.ttml", CACHE / "ttml" / f"am-{tid}.ttml"))
    return found[:MAX_FILES_PER_SONG * 2]


# ---------- matching ----------

DB_DIR = CACHE / "db"          # sparse checkout of the DB's am-lyrics + raw-lyrics .ttml files
TEXT_MIN_CHARS = 6             # app/file lines shorter than this (normalised) say too little to identify a song
TEXT_MIN_SHARE = 0.4           # share of the app's identifying lines a file must contain to be a candidate


@functools.lru_cache(maxsize=1)
def text_index():
    """{normalised line: [file, ...]} over the DB checkout: finds a song whatever its title
    is called (Korean vs English, feat. tags) by what is sung. Built once, cached as json."""
    f = CACHE / "line-index.json"
    if not f.exists():
        out = {}
        for p in sorted(DB_DIR.glob("*-lyrics/*.ttml")) if DB_DIR.exists() else []:
            try:
                keys = {st.norm(t) for _, _, t in cal.ttml_lines(p.read_bytes())}
            except ET.ParseError:
                continue
            for k in keys:
                if len(k) >= TEXT_MIN_CHARS:
                    out.setdefault(k, []).append(f"{p.parent.name}/{p.name}")
        f.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
    return json.loads(f.read_text(encoding="utf-8"))


def text_candidates(app):
    """[(label, None, path)] DB files that contain at least TEXT_MIN_SHARE of the app's lines."""
    keys = {st.norm(t) for _, _, t in app}
    keys = {k for k in keys if len(k) >= TEXT_MIN_CHARS}
    if not keys:
        return []
    idx, votes = text_index(), {}
    for k in keys:
        for fl in idx.get(k, ()):
            votes[fl] = votes.get(fl, 0) + 1
    best = sorted(((v / len(keys), fl) for fl, v in votes.items() if v / len(keys) >= TEXT_MIN_SHARE), reverse=True)
    return [(f"db:{fl}", None, DB_DIR / fl) for _, fl in best]

def pair_lines(app, truth):
    """[(app_n, truth_n)] lines with the same normalised text, in order on both sides.

    Exact text first (difflib's longest ordered common blocks, so a repeated chorus line
    pairs with its own repeat); then, inside the gaps between those, lines that read
    alike (ratio >= MATCH_RATIO) pair one-to-one in order. Lines under 2 characters
    after normalising are never paired."""
    ka = [st.norm(t) for _, _, t in app]
    kt = [st.norm(t) for _, _, t in truth]
    sm = difflib.SequenceMatcher(None, ka, kt, autojunk=False)
    blocks = [b for b in sm.get_matching_blocks()]
    pairs = []
    pa = pt = 0
    for b in blocks:  # the last block is the (len, len, 0) sentinel
        ga, gt = range(pa, b.a), range(pt, b.b)
        j = pt
        for i in ga:  # fuzzy pairs inside the gap, in order
            if len(ka[i]) < 2:
                continue
            for jj in range(j, b.b):
                if len(kt[jj]) >= 2 and difflib.SequenceMatcher(None, ka[i], kt[jj], autojunk=False).ratio() >= MATCH_RATIO:
                    pairs.append((app[i][0], truth[jj][0]))
                    j = jj + 1
                    break
        for k in range(b.size):
            if len(ka[b.a + k]) >= 2:
                pairs.append((app[b.a + k][0], truth[b.b + k][0]))
        pa, pt = b.a + b.size, b.b + b.size
    return sorted(pairs)


def tight(errs):
    """(ok, std_s, in03) for the file's lines against the recording after the offset."""
    if len(errs) < MIN_OFFSET_LINES:
        return False, float("nan"), float("nan")
    sd = statistics.pstdev(st.trimmed(errs))
    in03 = sum(abs(e) <= 0.3 for e in errs) / len(errs)
    return sd <= TIGHT_STD_S and in03 >= TIGHT_IN03, sd, in03


def estimate_offset(truth_lines, words_by_model):
    """(offset_s, residuals, n, why): recording clock - file clock, from the lines Whisper's
    two models agree on. offset = median(sung - file_start); residuals = that minus offset."""
    per = [st.align(truth_lines, w) for w in words_by_model]
    both, _, _ = st.agreed(per)
    both = [b for b in both if b[3] >= st.MIN_EVIDENCE_RATIO] or both
    if len(both) < MIN_OFFSET_LINES:
        return None, [], len(both), f"only {len(both)} file lines heard by both models (need {MIN_OFFSET_LINES})"
    d = [sung - shown for _, shown, sung, _ in both]  # `shown` is the file's start here
    off = statistics.median(d)
    return off, [x - off for x in d], len(both), ""


# ---------- one scored song ----------

def find_words(folder, vid, played, offset_app):
    """Whisper words of the recording in the APP's clock, as synctest.run_song builds
    them: YouTube's copy shifted by the fa-in offset, else the app's own WAV words."""
    models = st.MODELS
    got = [next(folder.glob(f"{played}.opus.{m}.words.json"), None) for m in models]
    if all(got):
        return [[(w, s - offset_app, e - offset_app) for w, s, e in json.loads(f.read_text(encoding="utf-8"))] for f in got], "youtube copy"
    got = [next(folder.glob(f"{vid}.app.{m}.words.json"), None) for m in models]
    if all(got):
        return [json.loads(f.read_text(encoding="utf-8")) for f in got], "app wav"
    return None, "no cached words"


def read_lines_csv(path):
    rows = []
    for r in csv.DictReader(path.open(encoding="utf-8")):
        rows.append((int(r["line"]), float(r["shown_s"]), float(r["sung_s"]), float(r["ratio"])))
    return rows


def title_artist(text, vid):
    m = re.search(r"play: -> (.+) \(" + re.escape(vid) + r"\) idx=", text)
    if not m:
        return "", ""
    t, _, a = m.group(1).rpartition(" - ")
    return (t, a) if t else (m.group(1), "")


def analyse(folder, vid, index):
    """One result dict for the scored song in `folder`."""
    res = dict(folder=str(folder), vid=vid, status="", why="")
    text = (folder / f"{vid}.log").read_text(encoding="utf-8", errors="replace")
    dump_id = st.dumped_id(text, vid)
    dump = st.parse_dump(text, dump_id)
    if not dump:
        return dict(res, status="no-dump")
    head, app = dump
    res["source"] = head.get("source", "")
    res["gemini"] = res["source"].startswith("Gemini")
    res["lines"] = len(app)
    csvf = folder / f"{dump_id}.lines.csv"
    tr_rows = read_lines_csv(csvf) if csvf.exists() else []
    # synctest's verdict for the same lines (what a sweep would have reported)
    tr = st.score(tr_rows) if len(tr_rows) >= st.STRICT["min_lines"] else None
    if tr:
        res.update(tr_verdict=tr["verdict"], tr_median=tr["median"], tr_n=tr["matched"])
        errs = [s - g for _, s, g, _ in tr_rows]
        res["tr_std"] = round(statistics.pstdev(errs), 3)
    title, artist = title_artist(text, dump_id)
    played = head.get("playing", "") or dump_id
    fa = folder / f"{dump_id}.fa-in.json"
    off_app = json.loads(fa.read_text(encoding="utf-8")).get("offset", 0.0) if fa.exists() else 0.0
    words, wsrc = find_words(folder, dump_id, played if not played.startswith("src:") else dump_id, off_app)
    res["words"] = wsrc
    cands = text_candidates(app) + candidate_files(title, artist, index)
    if not cands:
        return dict(res, status="no-file", why="no human-timed file found")
    best = None
    refusals = []
    tried, done = 0, set()
    for label, url, cf in cands:
        if tried >= MAX_FILES_PER_SONG:
            break
        data = fetch(url, cf)
        if cf in done or not data:
            continue
        tried += 1
        done.add(cf)
        try:
            truth = cal.ttml_lines(data)
        except ET.ParseError:
            continue
        pairs = pair_lines(app, truth)
        share = len(pairs) / max(1, min(len(app), len(truth)))
        if len(pairs) < MIN_PAIRED or share < MIN_PAIRED_SHARE:
            refusals.append(f"{label}: only {len(pairs)} lines pair ({share:.0%}), other version/edit")
            continue
        tmap = {n: ms / 1000 for n, ms, _ in truth}
        if not words:
            refusals.append(f"{label}: no cached recording words to set the offset")
            continue
        off, resid, nlines, why = estimate_offset([(n, ms, t) for n, ms, t in truth], words)
        if off is None:
            refusals.append(f"{label}: {why}")
            continue
        ok, sd, in03 = tight(resid)
        if not ok:
            refusals.append(f"{label}: file not tight on recording after offset {off:+.2f}s (std {sd:.2f}s, in0.3 {in03:.0%}, {nlines} lines)")
            continue
        amap = {n: ms / 1000 for n, ms, _ in app}
        rows = [(a, amap[a], tmap[t] + off, 1.0) for a, t in pairs]
        s = st.score(rows)
        if best is None or len(pairs) > best["n"]:
            best = dict(label=label, offset=off, n=len(rows), score=s, rows=rows, sd=sd, in03=in03, nlines=nlines,
                        pairs=len(pairs), share=share)
    if best is None:
        return dict(res, status="refused", why="; ".join(refusals) or "no file could be fetched")
    s = best["score"]
    res.update(status="truth", file=best["label"], offset=round(best["offset"], 3), pairs=best["pairs"],
               file_tight_std=round(best["sd"], 3), tru_verdict=s["verdict"], tru_median=s["median"],
               tru_median_abs=s["median_abs"], tru_in03=s["in03"], tru_max=s["max_abs"], tru_n=s["matched"])
    errs = [shown - t for _, shown, t, _ in best["rows"]]
    res["tru_std"] = round(statistics.pstdev(errs), 3)
    # same lines, both references: the scorer's own bias and noise on this song
    tmapr = {n: (sh, sg) for n, sh, sg, _ in tr_rows}
    both = [(sh - sg) - (shown - t) for (n, shown, t, _) in best["rows"] if n in tmapr for sh, sg in [tmapr[n]]]
    if len(both) >= 4:
        res["scorer_bias"] = round(statistics.median(both), 3)  # transcript error - truth error, median
        res["scorer_sd"] = round(statistics.pstdev(both), 3)
        res["common"] = len(both)
    return res


# ---------- report ----------

def folders_songs(roots):
    seen = []
    for root in roots:
        for log in sorted(Path(root).rglob("*.log")):
            if re.fullmatch(r"[\w-]{11}", log.stem):
                seen.append((log.parent, log.stem))
    return seen


def med(xs):
    return round(statistics.median(xs), 3) if xs else ""


def summarise(results):
    lines = []
    uniq = {}
    for r in results:
        uniq.setdefault(r["vid"], []).append(r)
    withfile = {v for v, rs in uniq.items() if any(r["status"] == "truth" for r in rs)}
    refused = {v for v, rs in uniq.items() if v not in withfile and any(r["status"] == "refused" for r in rs)}
    gem = {v for v, rs in uniq.items() if any(r.get("gemini") for r in rs)}
    lines.append(f"songs scored: {len(uniq)} unique ({len(results)} runs); with a usable human file: {len(withfile)}; "
                 f"file found but refused: {len(refused)}; none found: {len(uniq) - len(withfile) - len(refused)}")
    lines.append(f"gemini-transcript songs: {len(gem)}; of those with a human file: {len(gem & withfile)}")
    for name, pick in (("all sheets", lambda r: True), ("gemini sheets", lambda r: r.get("gemini")),
                       ("non-gemini sheets", lambda r: not r.get("gemini"))):
        rs = [r for r in results if r["status"] == "truth" and "tr_verdict" in r and pick(r)]
        if not rs:
            lines.append(f"{name}: no run has both verdicts")
            continue
        flips = [r for r in rs if r["tr_verdict"] != r["tru_verdict"]]
        sb = [r["scorer_bias"] for r in rs if "scorer_bias" in r]
        lines.append(
            f"{name}: runs with both={len(rs)}; median bias transcript {med([r['tr_median'] for r in rs])}s vs truth "
            f"{med([r['tru_median'] for r in rs])}s; median spread(std) transcript {med([r['tr_std'] for r in rs])}s vs truth "
            f"{med([r['tru_std'] for r in rs])}s; scorer bias on same lines (transcript err - truth err) median {med(sb)}s "
            f"over {len(sb)} runs; verdict flips {len(flips)}/{len(rs)}"
            + ("".join(f" [{r['vid']} {r['tr_verdict']}->{r['tru_verdict']}]" for r in flips) if flips else ""))
    return lines


FIELDS = ["folder", "vid", "status", "source", "gemini", "lines", "words", "file", "offset", "pairs", "file_tight_std",
          "tr_verdict", "tr_median", "tr_std", "tr_n", "tru_verdict", "tru_median", "tru_median_abs", "tru_std",
          "tru_in03", "tru_max", "tru_n", "scorer_bias", "scorer_sd", "common", "why"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folders", nargs="*", type=Path)
    ap.add_argument("--csv", type=Path, help="write one row per scored run here")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    index = load_index()
    results = []
    for folder, vid in folders_songs(a.folders):
        try:
            r = analyse(folder, vid, index)
        except Exception as e:  # one broken log must not stop a sweep
            r = dict(folder=str(folder), vid=vid, status="error", why=f"{type(e).__name__}: {e}")
        results.append(r)
        if r["status"] == "truth":
            print(f"{vid} {folder.name}: file={r['file']} offset={r['offset']:+.2f}s pairs={r['pairs']} "
                  f"truth={r['tru_verdict']} med={r['tru_median']:+.2f} std={r['tru_std']} | transcript="
                  f"{r.get('tr_verdict', '-')} med={r.get('tr_median', '-')} std={r.get('tr_std', '-')} "
                  f"scorer_bias={r.get('scorer_bias', '-')}{' [gemini]' if r.get('gemini') else ''}")
        elif r["status"] == "refused":
            print(f"{vid} {folder.name}: REFUSED {r['why']}")
    print("\n".join(summarise(results)))
    if a.csv:
        with a.csv.open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, FIELDS, extrasaction="ignore")
            w.writeheader()
            w.writerows(results)


def selftest():
    mk = lambda ts: [(i, i * 4000, t) for i, t in enumerate(ts)]
    app = mk(["Hello darkness", "my old friend", "I've come to talk", "with you again", "my old friend", "Hello darkness"])
    # same sheet, one line split differently, a repeat present on both sides
    truth = [(0, 1000, "hello darkness"), (1, 5000, "My old friend!"), (2, 9000, "Ive come to talk"),
             (3, 13000, "With you again"), (4, 17000, "my old friend"), (5, 21000, "Hello, darkness")]
    p = pair_lines(app, truth)
    assert p == [(i, i) for i in range(6)], p
    # a truth file with an extra intro line and a missing app line pairs the rest in order
    t2 = [(0, 0, "instrumental"), (1, 1000, "hello darkness"), (2, 5000, "my old friend"), (3, 13000, "with you again")]
    assert pair_lines(app, t2) == [(0, 1), (1, 2), (3, 3)], pair_lines(app, t2)
    # fuzzy: a one-word difference still pairs; a different line does not
    t3 = [(0, 0, "hello darkness"), (1, 4, "my old friends"), (2, 8, "something else entirely")]
    assert pair_lines(app, t3) == [(0, 0), (1, 1)], pair_lines(app, t3)
    # repeated line pairs in order, never crossing
    app2 = mk(["la la la", "verse", "la la la"])
    t4 = [(0, 0, "la la la"), (1, 1, "verse"), (2, 2, "la la la")]
    assert pair_lines(app2, t4) == [(0, 0), (1, 1), (2, 2)]
    # short/empty keys never pair
    assert pair_lines(mk(["!", "ok line"]), [(0, 0, "!"), (1, 1, "ok line")]) == [(1, 1)]
    # tightness: a clean file is accepted, a scattered one refused
    assert tight([0.01 * (i % 5) for i in range(12)])[0]
    assert not tight([(-1) ** i * 0.8 for i in range(12)])[0]
    assert not tight([0.0] * 5)[0]
    # titles
    assert title_ok("Dance Monkey", "Dance Monkey (Live)") and title_ok("Hero (feat. Y)", "hero - Remastered") and not title_ok("Star", "Look At Me Now (feat. Busta Rhymes)")
    assert title_artist("x play: -> A - B - Artist (abcdefghijk) idx=0/1", "abcdefghijk") == ("A - B", "Artist")
    # end to end on a synthetic song: the file's clock is 2.0 s behind the recording, the app shows
    # every line 0.10 s late, the "human" file has a different release's intro line.
    words_txt = ["moonlight over the river", "carry me across tonight", "silver waves keep calling", "I follow every echo",
                 "burning stars above us", "never let the lantern fade", "whisper through the willow", "holding on to morning",
                 "thunder in the distance", "dancing with the shadows", "stay until the sunrise", "falling into rhythm"]
    ttml = [(i, 5000 + i * 4000, t) for i, t in enumerate(words_txt)]
    sung = [ms / 1000 + 2.0 for _, ms, _ in ttml]
    heard = []
    for (_, _, t), s0 in zip(ttml, sung):
        ws = t.split()
        heard += [(w, s0 + 0.4 * k, s0 + 0.4 * k + 0.35) for k, w in enumerate(ws)]
    app_lines = [(i, int(s0 * 1000 + 100), t) for i, ((_, _, t), s0) in enumerate(zip(ttml, sung))]
    off, resid, n, why = estimate_offset(ttml, [heard, heard])
    assert off is not None and abs(off - 2.0) < 0.05 and tight(resid)[0], (off, why)
    pr = pair_lines(app_lines, ttml)
    assert len(pr) == 12
    s = st.score([(a, app_lines[a][1] / 1000, ttml[b][1] / 1000 + off, 1.0) for a, b in pr])
    assert abs(s["median"] - 0.1) < 0.05, s
    # a file that is another release (every second line stretched) is refused as not tight
    skew = [(i, 5000 + i * 4000 + (900 if i % 2 else -900) * 1, t) for i, _, t in ttml]
    assert not tight(estimate_offset(skew, [heard, heard])[1])[0]
    print("selftest ok")


if __name__ == "__main__":
    main()

