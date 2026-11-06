#!/usr/bin/env python3
"""Korean forced-alignment bench (E = CTC on the mix, F = CTC on Demucs vocals) with no hand-made ground truth.

Input dir: one folder per song with sheet.tsv (sheet start ms <TAB> line text) and audio.flac.
Judging (numbers only, no lyric text is printed or written):
  placed lines, straight-line fit agreement with the sheet, E-vs-F line difference,
  agreement with Deepgram word times, time per song, romanisation health counters.
"""
import argparse, json, os, re, statistics, subprocess, sys, time, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bench  # noqa: E402  (reuses ffwav, separate, wav_seconds, deepgram, run)

HANGUL = re.compile(r"[가-힣]")


def norm(w):
    return "".join(ch for ch in w.lower() if ch.isalnum())


def pairs(text):
    syl = [c for c in text if HANGUL.match(c)]
    return {a + b for a, b in zip(syl, syl[1:])}


def theil_sen(x, y):
    sl = []
    for i in range(len(x)):
        for j in range(i + 1, len(x)):
            if x[j] != x[i]:
                sl.append((y[j] - y[i]) / (x[j] - x[i]))
    a = statistics.median(sl) if sl else 1.0
    b = statistics.median([yi - a * xi for xi, yi in zip(x, y)])
    return a, b


def frac(vals, lim):
    return round(sum(1 for v in vals if v <= lim) / max(1, len(vals)), 3)


def pct(vals, p):
    s = sorted(vals)
    return s[min(len(s) - 1, int(len(s) * p))] if s else None


def load_sheet(d):
    lines = []
    for row in open(os.path.join(d, "sheet.tsv"), encoding="utf-8").read().splitlines():
        ms, _, txt = row.partition("\t")
        words = txt.split()
        if words and any(ch.isalnum() for ch in txt):
            lines.append((int(ms), words))
    return lines


_CTC = None


def align(wav, words, lang_iso):
    import torch
    from ctc_forced_aligner import (load_audio, load_alignment_model, generate_emissions, preprocess_text,
                                    get_alignments, get_spans, postprocess_results)
    global _CTC
    if _CTC is None:
        _CTC = load_alignment_model("cpu", dtype=torch.float32)
    model, tok = _CTC
    t0 = time.time()
    wf = load_audio(wav, model.dtype, model.device)
    emissions, stride = generate_emissions(model, wf, batch_size=4)
    tokens, text_starred = preprocess_text(" ".join(words), romanize=True, language=lang_iso)
    segments, scores, blank = get_alignments(emissions, tokens, tok)
    spans = get_spans(tokens, segments, blank)
    res = postprocess_results(text_starred, spans, stride, scores)
    res = [r for r in res if str(r["text"]).strip() != "<star>"]
    return res, round(time.time() - t0, 1), tokens


def line_starts(res, lines):
    if len(res) != sum(len(w) for _, w in lines):
        return None
    out, k = [], 0
    for _, w in lines:
        out.append(round(res[k]["start"] * 1000))
        k += len(w)
    return out


def line_scores(res, lines):
    out, k = [], 0
    for _, w in lines:
        sc = [float(r.get("score", 0)) for r in res[k:k + len(w)]]
        out.append(sum(sc) / max(1, len(sc)))
        k += len(w)
    return out


def sheet_fit(pred, sheet):
    a, b = theil_sen(sheet, pred)
    res_fit = [abs(p - (a * s + b)) for p, s in zip(pred, sheet)]
    off = statistics.median([p - s for p, s in zip(pred, sheet)])
    res_off = [abs(p - (s + off)) for p, s in zip(pred, sheet)]
    return {"slope": round(a, 4), "offset_ms": round(b), "fit_median_ms": round(statistics.median(res_fit)),
            "fit_le300": frac(res_fit, 300), "fit_le1000": frac(res_fit, 1000),
            "off_only_ms": round(off), "off_median_ms": round(statistics.median(res_off)),
            "off_le300": frac(res_off, 300), "off_le1000": frac(res_off, 1000)}


def vs_heard(res, words, heard):
    """Heard word start minus aligned word start, for heard words that equal a sheet word within 5 s."""
    sheet = [(norm(w), r["start"] * 1000) for w, r in zip(words, res)]
    diffs = []
    for hw, hs, _ in heard:
        n = norm(hw)
        if not n:
            continue
        near = [abs(hs - s) for t, s in sheet if t == n and abs(hs - s) <= 5000]
        if near:
            best = min((hs - s for t, s in sheet if t == n and abs(hs - s) <= 5000), key=abs)
            diffs.append(best)
    ab = [abs(v) for v in diffs]
    return {"matched": len(diffs), "heard": len(heard),
            "abs_median_ms": round(statistics.median(ab)) if ab else None,
            "signed_median_ms": round(statistics.median(diffs)) if diffs else None,
            "le300": frac(ab, 300) if ab else None}


def romanisation_probe():
    from ctc_forced_aligner import preprocess_text
    out = {}
    probe = ["안녕하세요", "감사합니다", "학교", "읽다", "같이", "값", "않아", "좋아", "꽃잎", "ㅋㅋㅋ", "and"]
    for w in probe:
        try:
            tokens, starred = preprocess_text(w, romanize=True, language="kor")
            out[w] = [" ".join(str(t) for t in tokens)]
        except Exception as e:  # noqa
            out[w] = ["error " + type(e).__name__]
    return out


def sheet_health(words, tokens):
    syl = sum(len(HANGUL.findall(w)) for w in words)
    empty = sum(1 for t in tokens if not str(t).strip())
    return {"words": len(words), "tokens": len(tokens), "empty_tokens": empty, "hangul_syllables": syl,
            "tokens_per_syllable": round(sum(len(str(t).replace(" ", "")) for t in tokens) / max(1, syl), 2)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="kodata")
    ap.add_argument("--work", default="kowork")
    ap.add_argument("--out", default="results-ko.json")
    ap.add_argument("--cands", default="E,F,H")
    a = ap.parse_args()
    cands = a.cands.split(",")
    os.makedirs(a.work, exist_ok=True)
    out = {"songs": [], "errors": []}
    if "E" in cands:
        try:
            out["romanisation_probe"] = romanisation_probe()
        except Exception as e:  # noqa
            out["errors"].append({"stage": "probe", "why": type(e).__name__})
    for sid in sorted(os.listdir(a.data)):
        d = os.path.join(a.data, sid)
        if not os.path.isfile(os.path.join(d, "sheet.tsv")):
            continue
        s = {"id": sid}
        try:
            lines = load_sheet(d)
            words = [w for _, ws in lines for w in ws]
            sheet = [ms for ms, _ in lines]
            s["lines"] = len(lines)
            s["sheet_words"] = len(words)
            wav = os.path.join(a.work, sid + ".wav")
            bench.ffwav(os.path.join(d, "audio.flac"), wav)
            s["audio_s"] = round(bench.wav_seconds(wav), 1)
            ps = set()
            for w in words:
                ps |= pairs(w)
            s["hangul_ratio"] = round(sum(len(HANGUL.findall(w)) for w in words) / max(1, sum(len(w) for w in words)), 2)
            heard = []
            if "H" in cands:
                t0 = time.time()
                try:
                    heard = bench.deepgram(wav, "ko")
                    s["deepgram_s"] = round(time.time() - t0, 1)
                    hp = set()
                    for w, _, _ in heard:
                        hp |= pairs(w)
                    s["heard_words"] = len(heard)
                    s["syllable_pair_overlap"] = round(len(ps & hp) / max(1, len(ps)), 3)
                except Exception as e:  # noqa
                    out["errors"].append({"song": sid, "cand": "H", "why": str(e)[:120]})
            preds = {}
            for c in ("E", "F"):
                if c not in cands:
                    continue
                try:
                    src = wav
                    if c == "F":
                        vs = os.path.join(a.work, sid)
                        os.makedirs(vs, exist_ok=True)
                        # separate() expects src.mp3 inside the song dir and writes vocals.wav next to it
                        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", os.path.join(d, "audio.flac"),
                                        "-ac", "2", "-ar", "44100", os.path.join(vs, "src.mp3")], check=True)
                        s["demucs_s"] = bench.separate(vs, os.cpu_count() or 2)
                        src = os.path.join(a.work, sid + "-vocals16.wav")
                        bench.ffwav(os.path.join(vs, "vocals.wav"), src)
                    res, wall, tokens = align(src, words, "kor")
                    c_out = {"wall_s": wall, "words_out": len(res), "words_in": len(words)}
                    if c == "E":
                        c_out["health"] = sheet_health(words, tokens)
                    pred = line_starts(res, lines)
                    if pred is None:
                        c_out["error"] = "word count mismatch"
                    else:
                        preds[c] = pred
                        c_out["placed"] = len(pred)
                        c_out["monotone"] = sum(1 for x, y in zip(pred, pred[1:]) if y >= x)
                        c_out.update(sheet_fit(pred, sheet))
                        sc = line_scores(res, lines)
                        c_out["score_median"] = round(statistics.median(sc), 3)
                        if heard:
                            c_out["vs_heard"] = vs_heard(res, words, heard)
                    s[c] = c_out
                except Exception as e:  # noqa
                    out["errors"].append({"song": sid, "cand": c, "why": (type(e).__name__ + ": " + str(e))[:200]})
            if "E" in preds and "F" in preds:
                df = [abs(x - y) for x, y in zip(preds["E"], preds["F"])]
                s["E_vs_F"] = {"median_ms": round(statistics.median(df)), "p90_ms": pct(df, 0.9),
                               "le300": frac(df, 300), "le1000": frac(df, 1000)}
        except Exception as e:  # noqa
            out["errors"].append({"song": sid, "stage": "song", "why": (type(e).__name__ + ": " + str(e))[:200]})
        out["songs"].append(s)
        json.dump(out, open(a.out, "w"), ensure_ascii=False)
        print("done song", sid, flush=True)
    json.dump(out, open(a.out, "w"), ensure_ascii=False)


if __name__ == "__main__":
    main()
