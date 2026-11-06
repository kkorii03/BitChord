#!/usr/bin/env python3
"""Sync research bench: three candidates other than ctc-forced-aligner. Numbers only leave the runner.

  W  stable-ts model.align (Whisper large-v3 + the known lines), on the mix and on Demucs vocals
  K  Apache-2.0 per-language wav2vec2 CTC + torchaudio forced_align, on the mix and on Demucs vocals
  R  no words at all: cross-correlate the sheet's line rhythm with vocal onsets/activity for one offset

Data: JamendoLyrics (public, has ground truth) and the encrypted Korean bundle (no ground truth).
stdout and the artifact never carry lyric text: exceptions are reduced to their type name.
Usage: research.py --part ko|jam --index i --of n --data kodata --out r.json
"""
import argparse, json, math, os, re, subprocess, sys, time, unicodedata

import numpy as np
import soundfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bench  # copied from task/sync-bench by the workflow: pick_songs, fetch_song, ffwav, separate

KO = "ko"
CTC_MODEL = {
    "ko": "kresnik/wav2vec2-large-xlsr-korean",
    "en": "jonatasgrosman/wav2vec2-large-xlsr-53-english",
    "de": "jonatasgrosman/wav2vec2-large-xlsr-53-german",
    "es": "jonatasgrosman/wav2vec2-large-xlsr-53-spanish",
    "fr": "jonatasgrosman/wav2vec2-large-xlsr-53-french",
}
SR = 16000
CHUNK_S, OVERLAP_S = 30, 2

# ---------------------------------------------------------------- W: stable-ts
_whisper = None


def squash(text):
    return re.sub(r"\s+", "", unicodedata.normalize("NFC", text))


def stable_align(wav, lines, lang):
    """(line start ms, per-line mean word probability, seconds, chars out / chars in); lines = [[word, ...], ...]"""
    global _whisper
    import stable_whisper
    if _whisper is None:
        _whisper = stable_whisper.load_model("large-v3", device="cpu")
    t0 = time.time()
    text = "\n".join(" ".join(w) for w in lines)
    res = _whisper.align(wav, text, language=lang, original_split=True, verbose=None)
    words = list(res.all_words())
    starts, probs, cum = [], [], 0
    spans = []  # (first char, last char) of each output word
    for w in words:
        n = len(squash(w.word))
        spans.append((cum, cum + n))
        cum += n
    pos, k = 0, 0
    for ln in lines:
        size = len(squash(" ".join(ln)))
        while k < len(spans) and spans[k][1] <= pos:
            k += 1
        j, ps = k, []
        while j < len(spans) and spans[j][0] < pos + size:
            ps.append(float(getattr(words[j], "probability", 0) or 0))
            j += 1
        starts.append(round(float(words[k].start) * 1000) if k < len(words) else None)
        probs.append(round(sum(ps) / len(ps), 3) if ps else None)
        pos += size
    total = sum(len(squash(" ".join(ln))) for ln in lines)
    return starts, probs, round(time.time() - t0, 1), round(cum / max(1, total), 3)


# ---------------------------------------------------------------- K: wav2vec2 + forced_align
_ctc = {}


def ctc_model(lang):
    from transformers import AutoModelForCTC, AutoProcessor
    if lang not in _ctc:
        _ctc.clear()  # one language at a time: each model is over 1 GB
        name = CTC_MODEL[lang]
        _ctc[lang] = (AutoProcessor.from_pretrained(name), AutoModelForCTC.from_pretrained(name).eval())
    return _ctc[lang]


def emissions(wav, lang):
    """log-probs [T, C] for a whole song in overlapping chunks, and seconds per frame."""
    import torch
    proc, model = ctc_model(lang)
    y, sr = soundfile.read(wav, dtype="float32")
    assert sr == SR
    hop, size, parts = (CHUNK_S - OVERLAP_S) * SR, CHUNK_S * SR, []
    per_s = None
    with torch.inference_mode():
        for a in range(0, max(1, len(y) - OVERLAP_S * SR), hop):
            chunk = y[a:a + size]
            if len(chunk) < SR // 2:
                break
            inp = proc(chunk, sampling_rate=SR, return_tensors="pt").input_values
            lp = torch.log_softmax(model(inp).logits[0], dim=-1)
            per_s = per_s or lp.size(0) / (len(chunk) / SR)
            keep = int(per_s * (CHUNK_S - OVERLAP_S)) if a + size < len(y) else lp.size(0)
            parts.append(lp[:keep])
    return torch.cat(parts), 1.0 / per_s


def ctc_targets(lines, proc, wildcard):
    """Token ids for the lines and, per line, the index of its first token. Unknown letters use the wildcard."""
    vocab = proc.tokenizer.get_vocab()
    upper = "a" not in vocab and "A" in vocab
    delim = vocab.get("|")
    ids, firsts = [], []
    for ln in lines:
        firsts.append(len(ids))
        for wi, word in enumerate(ln):
            run = 0
            for ch in unicodedata.normalize("NFC", word):
                c = ch.upper() if upper else ch.lower()
                if c in vocab:
                    ids.append(vocab[c])
                elif ch.isalnum():
                    run += 1
                    if run % 2 == 1:  # two unknown letters are about one sung sound
                        ids.append(wildcard)
            if delim is not None:
                ids.append(delim)
    return ids, firsts


def ctc_align(wav, lines, lang, cached=None):
    """(line start ms, per-line mean token probability, seconds), reusing cached emissions for another sheet."""
    import torch, torchaudio
    t0 = time.time()
    proc, _ = ctc_model(lang)
    em, spf = cached if cached else emissions(wav, lang)
    blank = proc.tokenizer.pad_token_id
    # A wildcard column as WhisperX does it: the best non-blank letter in each frame.
    nb = em.clone()
    nb[:, blank] = -1e9
    full = torch.cat([em, nb.max(dim=1, keepdim=True).values], dim=1)
    ids, firsts = ctc_targets(lines, proc, em.size(1))
    if not ids or full.size(0) < 2 * len(ids):
        raise RuntimeError("too few frames")
    path, scores = torchaudio.functional.forced_align(
        full.unsqueeze(0), torch.tensor([ids], dtype=torch.int32), blank=blank)
    spans = torchaudio.functional.merge_tokens(path[0], scores[0].exp(), blank=blank)
    if len(spans) != len(ids):
        raise RuntimeError("span count")
    starts, probs = [], []
    for i, f in enumerate(firsts):
        end = firsts[i + 1] if i + 1 < len(firsts) else len(ids)
        starts.append(round(spans[f].start * spf * 1000) if f < end else None)
        sc = [float(s.score) for s in spans[f:end]]
        probs.append(round(sum(sc) / len(sc), 3) if sc else None)
    return starts, probs, round(time.time() - t0, 1), (em, spf)


# ---------------------------------------------------------------- R: rhythm only
HOP_S = 0.05
MAX_LAG_S = 40


def rhythm_features(wav):
    """Onset strength and activity of the 300-3400 Hz band, one value per 50 ms."""
    y, sr = soundfile.read(wav, dtype="float32")
    n, hop = 1024, int(sr * HOP_S)
    win = np.hanning(n)
    fr = np.fft.rfftfreq(n, 1 / sr)
    band = (fr >= 300) & (fr <= 3400)
    frames = (len(y) - n) // hop
    env = np.zeros(max(0, frames))
    for i in range(0, frames, 2000):
        idx = np.arange(i, min(frames, i + 2000))
        block = np.stack([y[j * hop:j * hop + n] * win for j in idx])
        env[idx] = (np.abs(np.fft.rfft(block, axis=1)) ** 2)[:, band].sum(axis=1)
    env = np.log1p(env / (np.percentile(env, 95) + 1e-9) * 100)
    onset = np.maximum(0, np.diff(env, prepend=env[:1]))
    onset = np.convolve(onset, np.ones(3) / 3, "same")
    act = np.convolve(env, np.ones(5) / 5, "same")
    return onset, act


def _z(c):
    med = np.median(c)
    return (c - med) / (1.4826 * np.median(np.abs(c - med)) + 1e-9)


def rhythm_offset(onset, act, sheet_ms):
    """(offset ms to add to the sheet, peak z, peak / best other peak beyond 1 s). Positive: sung later than the sheet."""
    n = len(onset)
    t = np.arange(n)
    starts = np.array(sheet_ms, dtype=float) / 1000 / HOP_S
    impulse = np.exp(-0.5 * ((t[:, None] - starts[None, :]) / 3.0) ** 2).sum(axis=1)
    mask = np.zeros(n)
    for i, s in enumerate(starts):
        end = starts[i + 1] if i + 1 < len(starts) else s + 60
        end = min(end, s + 160)
        mask[max(0, int(s)):max(0, int(end))] = 1
    lags = np.arange(-int(MAX_LAG_S / HOP_S), int(MAX_LAG_S / HOP_S) + 1)

    def corr(a, b):
        a, b = a - a.mean(), b - b.mean()
        m = 2 * n
        full = np.fft.irfft(np.fft.rfft(a, m) * np.conj(np.fft.rfft(b, m)), m)
        return full[lags % m]  # a(t + lag) against b(t)

    both = _z(corr(onset, impulse)) + _z(corr(act, mask))
    k = int(np.argmax(both))
    far = np.abs(lags - lags[k]) > int(1.0 / HOP_S)
    other = float(both[far].max()) if far.any() else 0.0
    return round(float(lags[k]) * HOP_S * 1000), round(float(both[k]), 2), round(float(both[k]) / max(other, 1e-6), 3)


# ---------------------------------------------------------------- songs
def load_ko(data, sid):
    lines = []
    for row in open(os.path.join(data, sid, "sheet.tsv"), encoding="utf-8").read().splitlines():
        ms, _, txt = row.partition("\t")
        if txt.split() and any(ch.isalnum() for ch in txt):
            lines.append((int(ms), txt.split()))
    return [m for m, _ in lines], [w for _, w in lines]


def safe(errors, tag, fn):
    try:
        return fn()
    except Exception as e:  # noqa: only the type name: messages can quote lyrics
        errors.append({"where": tag, "why": type(e).__name__})
        print("failed", tag, type(e).__name__, flush=True)
        return None


def vocals(d, src, tag):
    """16 kHz mono mix and vocals beside src; returns (mix, vocals or None, demucs seconds)."""
    if os.path.abspath(src) != os.path.abspath(os.path.join(d, "src.mp3")):
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-ac", "2", "-ar", "44100",
                        os.path.join(d, "src.mp3")], check=True)
    mix = os.path.join(d, "mix16.wav")
    bench.ffwav(src, mix)
    took = bench.separate(d, os.cpu_count() or 2)
    voc = os.path.join(d, "voc16.wav")
    bench.ffwav(os.path.join(d, "vocals.wav"), voc)
    return mix, voc, took


def run_song(s, mix, voc, lines, sheet, lang, errors, other_sheet=None, other_lines=None):
    sid = s["id"]
    feats = {}
    for name, wav in (("mix", mix), ("voc", voc)):
        feats[name] = safe(errors, sid + "/rfeat/" + name, lambda: rhythm_features(wav))
        if feats[name] is not None:
            onset, act = feats[name]
            s["R_" + name] = {"inj": {}}
            for inj in (0, 800, -2500, 6000, 15000):
                got = safe(errors, sid + "/R", lambda: rhythm_offset(onset, act, [m + inj for m in sheet]))
                if got:
                    s["R_" + name]["inj"][str(inj)] = got
            if other_sheet:
                got = safe(errors, sid + "/Rw", lambda: rhythm_offset(onset, act, other_sheet))
                s["R_" + name]["wrong"] = got
    cached = {}
    for name, wav in (("voc", voc), ("mix", mix)):
        def k():
            st, pr, wall, cache = ctc_align(wav, lines, lang)
            cached[name] = cache
            return {"pred": st, "conf": pr, "wall_s": wall}
        s["K_" + name] = safe(errors, sid + "/K_" + name, k)
    if other_lines and "voc" in cached:
        def kw():
            st, pr, wall, _ = ctc_align(voc, other_lines, lang, cached["voc"])
            return {"conf": pr, "wall_s": wall}
        s["K_voc_wrong"] = safe(errors, sid + "/Kw", kw)
    cached.clear()
    for name, wav in (("voc", voc), ("mix", mix)):
        def w():
            st, pr, wall, ratio = stable_align(wav, lines, lang)
            return {"pred": st, "conf": pr, "wall_s": wall, "chars_ratio": ratio}
        s["W_" + name] = safe(errors, sid + "/W_" + name, w)
    if other_lines:
        def ww():
            _, pr, wall, _ = stable_align(voc, other_lines, lang)
            return {"conf": pr, "wall_s": wall}
        s["W_voc_wrong"] = safe(errors, sid + "/Ww", ww)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--part", required=True)
    ap.add_argument("--index", type=int, default=0)
    ap.add_argument("--of", type=int, default=1)
    ap.add_argument("--data", default="kodata")
    ap.add_argument("--limit", type=int, default=10)
    ap.add_argument("--out", default="r.json")
    a = ap.parse_args()
    out = {"part": a.part, "index": a.index, "songs": [], "errors": []}
    work = "work"
    os.makedirs(work, exist_ok=True)
    if a.part == "ko":
        ids = sorted(x for x in os.listdir(a.data) if os.path.isfile(os.path.join(a.data, x, "sheet.tsv")))
        for i, sid in enumerate(ids):
            if i % a.of != a.index:
                continue
            sheet, lines = load_ko(a.data, sid)
            other_sheet, other_lines = load_ko(a.data, ids[(i + 1) % len(ids)])
            s = {"id": sid, "lang": KO, "lines": len(lines), "sheet_ms": sheet, "wrong_id": ids[(i + 1) % len(ids)]}
            d = os.path.join(work, sid)
            os.makedirs(d, exist_ok=True)
            got = safe(out["errors"], sid + "/prep", lambda: vocals(d, os.path.join(a.data, sid, "audio.flac"), sid))
            if got:
                mix, voc, s["demucs_s"] = got
                s["audio_s"] = round(bench.wav_seconds(mix), 1)
                run_song(s, mix, voc, lines, sheet, KO, out["errors"], other_sheet, other_lines)
            out["songs"].append(s)
            json.dump(out, open(a.out, "w"))
            print("done", sid, flush=True)
    else:
        for i, (lang, row) in enumerate(bench.pick_songs(a.limit)):
            if i % a.of != a.index:
                continue
            got = safe(out["errors"], "fetch%d" % i, lambda: bench.fetch_song(work, lang, row))
            if not got:
                continue
            d, gt, meta = got
            lines, cur = [], []
            line_rate, first = [], []
            for j, (w, ms, end) in enumerate(zip(gt["words"], gt["word_start_ms"], gt["word_line_end"])):
                if not cur:
                    first.append(j)
                cur.append(w)
                if end:
                    lines.append(cur)
                    cur = []
            if cur:
                lines.append(cur)
            for q, f in enumerate(first):
                n = len(lines[q])
                span = (gt["word_start_ms"][f + n - 1] - gt["word_start_ms"][f]) / 1000 if n > 1 else 0
                line_rate.append(round((n - 1) / span, 2) if span > 0 else None)
            sheet = [gt["word_start_ms"][f] for f in first]
            s = {"id": meta["id"], "lang": lang, "lines": len(lines), "sheet_ms": sheet, "line_start_gt": gt["line_start_ms"],
                 "line_rate": line_rate, "poly": meta.get("polyphonic"), "overlap": meta.get("overlap")}
            prep = safe(out["errors"], meta["id"] + "/prep", lambda: vocals(d, os.path.join(d, "src.mp3"), meta["id"]))
            if prep:
                mix, voc, s["demucs_s"] = prep
                s["audio_s"] = round(bench.wav_seconds(mix), 1)
                run_song(s, mix, voc, lines, sheet, lang, out["errors"])
            out["songs"].append(s)
            json.dump(out, open(a.out, "w"))
            for f in os.listdir(d):
                if f.endswith((".wav", ".mp3", ".tsv")):
                    os.remove(os.path.join(d, f))
            print("done", meta["id"], lang, flush=True)
    json.dump(out, open(a.out, "w"))


if __name__ == "__main__":
    main()
