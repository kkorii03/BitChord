#!/usr/bin/env python3
"""Lyrics-sync bench on JamendoLyrics (word/line ground truth). Numbers only are written out.

Candidates
  A  first 90 s, Deepgram whisper-large, app's LyricsWordAlign + RefinePolicy (as the app's refine listen)
  B  whole song, same ASR + same app code
  C  B with ffmpeg highpass 180 Hz + lowpass 4 kHz
  D  B on Demucs (htdemucs) vocals
  E  ctc-forced-aligner (MMS-300m-1130) on the mix, lyrics known
  F  ctc-forced-aligner on Demucs vocals
Audio, lyric text and transcripts stay in the work dir; results*.json hold ids, counts and ms errors.
"""
import argparse, csv, hashlib, io, json, os, statistics, subprocess, sys, time, urllib.parse, urllib.request

BASE = "https://huggingface.co/datasets/jamendolyrics/jamendolyrics/resolve/main"
LANGS = ["en", "de", "es", "fr"]
LANG_NAME = {"English": "en", "German": "de", "Spanish": "es", "French": "fr"}
ISO3 = {"en": "eng", "de": "deu", "es": "spa", "fr": "fra"}
OFFSETS_MS = [800, -2500, 6000]
WINDOW_S = 90


def get(url, retries=5, timeout=300):
    last = None
    for i in range(retries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "sync-bench"}), timeout=timeout) as r:
                return r.read()
        except Exception as e:  # noqa
            last = e
            time.sleep(2 + 3 * i)
    raise RuntimeError("download failed: %s" % type(last).__name__)


def pick_songs(limit):
    rows = list(csv.DictReader(io.StringIO(get(BASE + "/JamendoLyrics.csv").decode("utf-8"))))
    by = {l: [] for l in LANGS}
    for r in rows:
        l = LANG_NAME.get(r["Language"].strip())
        if l:
            by[l].append(r)
    for l in LANGS:
        by[l].sort(key=lambda r: hashlib.md5(r["Filepath"].encode()).hexdigest())
    order, i = [], 0
    while len(order) < limit and any(i < len(by[l]) for l in LANGS):
        for l in LANGS:
            if i < len(by[l]) and len(order) < limit:
                order.append((l, by[l][i]))
        i += 1
    return order


def fetch_song(work, lang, row):
    name = row["Filepath"][:-4]
    tid = row["URL"].split("/track/")[1].split("/")[0]
    d = os.path.join(work, tid)
    os.makedirs(d, exist_ok=True)
    q = urllib.parse.quote
    with open(os.path.join(d, "src.mp3"), "wb") as f:
        f.write(get("%s/subsets/%s/mp3/%s" % (BASE, lang, q(row["Filepath"])), timeout=600))
    words_csv = list(csv.DictReader(io.StringIO(get("%s/annotations/words/%s.csv" % (BASE, q(name))).decode("utf-8"))))
    lines_csv = list(csv.DictReader(io.StringIO(get("%s/annotations/lines/%s.csv" % (BASE, q(name))).decode("utf-8"))))
    words_txt = [w for w in get("%s/lyrics/%s.words.txt" % (BASE, q(name))).decode("utf-8").split("\n") if w.strip()]
    gt = {
        "line_start_ms": [round(float(l["start_time"]) * 1000) for l in lines_csv],
        "line_text": [l["lyrics_line"] for l in lines_csv],
        "word_start_ms": [round(float(w["word_start"]) * 1000) for w in words_csv],
        "word_line_end": [w["line_end"] not in ("nan", "", None) for w in words_csv],
        "words": words_txt,
    }
    meta = {"id": tid, "lang": lang, "license": row["LicenseType"].strip(),
            "overlap": row["LyricOverlap"], "polyphonic": row["Polyphonic"], "nonlexical": row["NonLexical"]}
    return d, gt, meta


def run(cmd, **kw):
    r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kw)
    if r.returncode != 0:
        raise RuntimeError("%s failed rc=%d: %s" % (cmd[0], r.returncode, r.stderr.decode("utf-8", "replace")[-300:]))
    return r


def ffwav(src, dst, extra_in=(), af=None):
    cmd = ["ffmpeg", "-y", "-loglevel", "error", *extra_in, "-i", src, "-ac", "1", "-ar", "16000"]
    if af:
        cmd += ["-af", af]
    run(cmd + ["-c:a", "pcm_s16le", dst])


def wav_seconds(path):
    return max(0, os.path.getsize(path) - 44) / 32000.0


def deepgram(wav, lang):
    key = os.environ["DEEPGRAM_API_KEY"].strip()
    spoken = "language=en" if lang == "en" else "detect_language=true"  # the app's languageFor() -> "en" / "multi"
    url = "https://api.deepgram.com/v1/listen?model=whisper-large&%s&punctuate=false&smart_format=false&numerals=false" % spoken
    data = open(wav, "rb").read()
    last = None
    for i in range(4):
        try:
            req = urllib.request.Request(url, data=data, headers={"Authorization": "Token " + key, "Content-Type": "audio/wav"})
            with urllib.request.urlopen(req, timeout=900) as r:
                body = json.loads(r.read())
            words = body["results"]["channels"][0]["alternatives"][0]["words"]
            return [(w["word"], round(w["start"] * 1000), round(w.get("end", w["start"]) * 1000)) for w in words]
        except urllib.error.HTTPError as e:
            last = "http%d" % e.code
            if e.code in (401, 402, 403):
                raise RuntimeError("deepgram rejected: " + last)
        except Exception as e:  # noqa
            last = type(e).__name__
        time.sleep(5 + 10 * i)
    raise RuntimeError("deepgram failed: " + str(last))


def kotlin_place(jar, d, gt, heard):
    with open(os.path.join(d, "lines.tsv"), "w", encoding="utf-8") as f:
        for ms, t in zip(gt["line_start_ms"], gt["line_text"]):
            f.write("%d\t%s\n" % (ms, " ".join(t.split())))
    with open(os.path.join(d, "heard.tsv"), "w", encoding="utf-8") as f:
        for w, s, e in heard:
            f.write("%s\t%d\t%d\n" % (w.replace("\t", " ").replace("\n", " "), s, e))
    run(["java", "-jar", jar, d] + [str(o) for o in OFFSETS_MS])
    place, refine, attempted = [], [], None
    for row in open(os.path.join(d, "out.tsv"), encoding="utf-8").read().splitlines():
        p = row.split("\t")
        if p[0] == "A":
            attempted = p[1] == "true"
        elif p[0] == "P":
            place.append(None if p[2] == "null" else int(p[2]))
        elif p[0] == "R":
            refine.append({"offset_ms": int(p[1]), "applied": p[2] == "true", "median_ms": int(p[3]),
                           "mad_ms": int(p[4]), "model": p[5], "lines": int(p[6])})
    return place, refine, attempted


def line_result(pred, gt):
    return {"line_err_ms": [None if p is None else abs(p - g) for p, g in zip(pred, gt["line_start_ms"])],
            "line_signed_ms": [None if p is None else p - g for p, g in zip(pred, gt["line_start_ms"])]}


def asr_candidate(cand, d, gt, meta, jar):
    src = os.path.join(d, "src.mp3")
    t0 = time.time()
    if cand == "A":
        wav = os.path.join(d, "A.wav"); ffwav(src, wav, extra_in=("-t", str(WINDOW_S)))
    elif cand == "B":
        wav = os.path.join(d, "B.wav"); ffwav(src, wav)
    elif cand == "C":
        wav = os.path.join(d, "C.wav"); ffwav(src, wav, af="highpass=f=180,lowpass=f=4000")
    elif cand == "D":
        wav = os.path.join(d, "D.wav")
        ffwav(os.path.join(d, "vocals.wav"), wav)
    t_prep = time.time() - t0
    secs = wav_seconds(wav)
    t1 = time.time()
    heard = deepgram(wav, meta["lang"])
    t_asr = time.time() - t1
    place, refine, attempted = kotlin_place(jar, d, gt, heard)
    res = line_result(place, gt)
    res.update({"audio_s": round(secs, 1), "prep_s": round(t_prep, 1), "asr_s": round(t_asr, 1),
                "heard_words": len(heard), "attempted": attempted, "refine": refine})
    return res


def separate(d, work_threads):
    t0 = time.time()
    out = os.path.join(d, "demucs")
    run([sys.executable, "-m", "demucs", "--two-stems=vocals", "-n", "htdemucs", "-d", "cpu", "-j", str(work_threads),
         "-o", out, os.path.join(d, "src.mp3")])
    vocals = os.path.join(out, "htdemucs", "src", "vocals.wav")
    os.replace(vocals, os.path.join(d, "vocals.wav"))
    return round(time.time() - t0, 1)


def ctc_candidate(wav_in, d, gt, meta, tag):
    import torch
    from ctc_forced_aligner import (load_audio, load_alignment_model, generate_emissions, preprocess_text,
                                    get_alignments, get_spans, postprocess_results)
    global _CTC
    if "_CTC" not in globals() or _CTC is None:
        _CTC = load_alignment_model("cpu", dtype=torch.float32)
    model, tok = _CTC
    t0 = time.time()
    wf = load_audio(wav_in, model.dtype, model.device)
    emissions, stride = generate_emissions(model, wf, batch_size=4)
    text = " ".join(gt["words"])
    tokens, text_starred = preprocess_text(text, romanize=True, language=ISO3[meta["lang"]])
    segments, scores, blank = get_alignments(emissions, tokens, tok)
    spans = get_spans(tokens, segments, blank)
    results = postprocess_results(text_starred, spans, stride, scores)
    ts = [r for r in results if str(r["text"]).strip() != "<star>"]
    wall = round(time.time() - t0, 1)
    n_gt = len(gt["word_start_ms"])
    res = {"audio_s": round(wav_seconds(wav_in), 1), "wall_s": wall, "words_out": len(ts), "words_gt": n_gt}
    if len(ts) != n_gt:
        res["error"] = "word count mismatch"
        return res
    wstart = [round(r["start"] * 1000) for r in ts]
    res["word_err_ms"] = [abs(a - b) for a, b in zip(wstart, gt["word_start_ms"])]
    first, line_pred = True, []
    for i, s in enumerate(wstart):
        if first:
            line_pred.append(s)
        first = gt["word_line_end"][i]
    if len(line_pred) == len(gt["line_start_ms"]):
        res.update(line_result(line_pred, gt))
    else:
        res["error"] = "line count mismatch %d/%d" % (len(line_pred), len(gt["line_start_ms"]))
    return res


_CTC = None


def korean_smoke():
    """Does the aligner's text path accept Hangul at all? (token count only, no audio, no lyrics)"""
    from ctc_forced_aligner import preprocess_text
    tokens, text_starred = preprocess_text("안녕하세요 반갑습니다 노래", romanize=True, language="kor")
    return {"tokens": len(tokens), "words": len([t for t in text_starred if t != "<star>"]),
            "sample_in": "annyeonghaseyo-style hangul, 3 words", "text_starred": [str(t) for t in text_starred][:8],
            "tokens_head": [str(t) for t in tokens][:8]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cands", default="A,B,C")
    ap.add_argument("--limit", type=int, default=10)
    ap.add_argument("--work", default="work")
    ap.add_argument("--jar", default="bench.jar")
    ap.add_argument("--out", default="results.json")
    a = ap.parse_args()
    cands = a.cands.split(",")
    os.makedirs(a.work, exist_ok=True)
    out = {"cands": cands, "songs": [], "errors": []}
    if "E" in cands:
        try:
            out["korean_smoke"] = korean_smoke()
        except Exception as e:  # noqa
            out["korean_smoke"] = {"error": type(e).__name__ + ": " + str(e)[:200]}
    for lang, row in pick_songs(a.limit):
        try:
            d, gt, meta = fetch_song(a.work, lang, row)
        except Exception as e:  # noqa
            out["errors"].append({"song": row["URL"].split("/track/")[1].split("/")[0], "stage": "fetch", "why": str(e)[:200]})
            continue
        s = {"meta": meta, "lines": len(gt["line_start_ms"]), "words": len(gt["words"]), "gt_line_start_ms": gt["line_start_ms"], "cand": {}}
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", os.path.join(d, "src.mp3")], stdout=subprocess.PIPE)
        s["duration_s"] = round(float(probe.stdout.decode().strip() or 0), 1)
        try:
            if "D" in cands or "F" in cands:
                s["demucs_s"] = separate(d, os.cpu_count() or 2)
            for c in cands:
                try:
                    if c in ("A", "B", "C", "D"):
                        s["cand"][c] = asr_candidate(c, d, gt, meta, a.jar)
                    elif c == "E":
                        wav = os.path.join(d, "mix16.wav"); ffwav(os.path.join(d, "src.mp3"), wav)
                        s["cand"][c] = ctc_candidate(wav, d, gt, meta, c)
                    elif c == "F":
                        s["cand"][c] = ctc_candidate(os.path.join(d, "vocals.wav"), d, gt, meta, c)
                except Exception as e:  # noqa
                    out["errors"].append({"song": meta["id"], "cand": c, "why": (type(e).__name__ + ": " + str(e))[:300]})
        except Exception as e:  # noqa
            out["errors"].append({"song": meta["id"], "stage": "separate", "why": (type(e).__name__ + ": " + str(e))[:300]})
        out["songs"].append(s)
        # per-song cleanup: audio and text never leave this dir
        for f in os.listdir(d):
            if f.endswith((".wav", ".mp3", ".tsv")):
                os.remove(os.path.join(d, f))
        json.dump(out, open(a.out, "w"))
        print("done song", meta["id"], meta["lang"], flush=True)
    json.dump(out, open(a.out, "w"))


if __name__ == "__main__":
    main()
