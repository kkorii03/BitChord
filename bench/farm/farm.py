"""Alignment farm: a GitHub runner places a sheet's words on a song with wav2vec2 CTC.

Bench on Jamendo (task/sync-research): median line error 36 ms on the mix against 306 ms for
stable-ts. It needs a model over 1 GB, so it runs on a runner and the app reads the result.

  local : farm.py pack  <out.enc> <id> [<id> ...]   sheets from phone-out logs
  runner: farm.py run   <in.enc> --index I --of N --out <dir>
  local : farm.py score <dir>                       against the scorer's whisper-large words

Sheets and results hold lyric text, file names would show what the listener plays, and the
repository is public: everything travels enciphered under the Deepgram key, a secret on the
runner and baked into the signed APK, never in the repository. Scores print numbers and ids only.
"""
import argparse, hashlib, hmac, json, os, re, statistics, subprocess, sys, time, unicodedata
from pathlib import Path

SR = 16000
CHUNK_S, OVERLAP_S = 30, 2
CTC_MODEL = {
    # Apache-2.0
    "ko": "kresnik/wav2vec2-large-xlsr-korean",
    "en": "jonatasgrosman/wav2vec2-large-xlsr-53-english",
    "ja": "jonatasgrosman/wav2vec2-large-xlsr-53-japanese",
}


def data_name(vid, secret):
    """A file name that does not say which video it is."""
    return hmac.new(secret.encode(), vid.encode(), hashlib.sha256).hexdigest()[:32]


def stream(data, password):
    """SHA-256 counter keystream, both ways, under a key the repository does not hold."""
    key, out = password.encode(), bytearray()
    for i in range(0, len(data), 32):
        pad = hashlib.sha256(key + i.to_bytes(8, "big")).digest()
        out += bytes(a ^ b for a, b in zip(data[i:i + 32], pad))
    return bytes(out)


def split_words(text):
    return [w for w in text.split() if any(c.isalnum() for c in w)]


# ---------------------------------------------------------------- runner

def emissions(wav, proc, model):
    import soundfile, torch
    y, sr = soundfile.read(wav, dtype="float32")
    assert sr == SR
    hop, size, parts, per_s = (CHUNK_S - OVERLAP_S) * SR, CHUNK_S * SR, [], None
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
    return torch.cat(parts), 1.0 / per_s, len(y) / SR


def targets(lines, proc, wildcard):
    """Token ids, and (line index, word text, first token, past-last token) per word."""
    vocab = proc.tokenizer.get_vocab()
    upper = "a" not in vocab and "A" in vocab
    delim = vocab.get("|")
    ids, words = [], []
    for li, text in enumerate(lines):
        for word in split_words(text):
            first, run = len(ids), 0
            for ch in unicodedata.normalize("NFC", word):
                c = ch.upper() if upper else ch.lower()
                if c in vocab:
                    ids.append(vocab[c])
                elif ch.isalnum():
                    run += 1
                    if run % 2 == 1:  # two unknown letters are about one sung sound
                        ids.append(wildcard)
            if len(ids) > first:
                words.append((li, word, first, len(ids)))
            if delim is not None:
                ids.append(delim)
    return ids, words


def align(wav, lines, proc, model):
    import torch, torchaudio
    em, spf, audio_s = emissions(wav, proc, model)
    blank = proc.tokenizer.pad_token_id
    # A wildcard column as WhisperX does it: the best non-blank letter in each frame.
    nb = em.clone()
    nb[:, blank] = -1e9
    full = torch.cat([em, nb.max(dim=1, keepdim=True).values], dim=1)
    ids, words = targets(lines, proc, em.size(1))
    if not ids or full.size(0) < 2 * len(ids):
        raise RuntimeError("too few frames")
    path, scores = torchaudio.functional.forced_align(
        full.unsqueeze(0), torch.tensor([ids], dtype=torch.int32), blank=blank)
    spans = torchaudio.functional.merge_tokens(path[0], scores[0].exp(), blank=blank)
    if len(spans) != len(ids):
        raise RuntimeError("span count")
    out = []
    for li, word, a, b in words:
        conf = sum(float(s.score) for s in spans[a:b]) / (b - a)
        out.append([li, word, round(spans[a].start * spf * 1000), round(spans[b - 1].end * spf * 1000), round(conf, 3)])
    return out, audio_s


def fetch(vid, tmp):
    """A 16 kHz mono wav of the video's own audio, or None: the same clock the app streams."""
    proxy = os.environ.get("FARM_PROXY")
    wav = tmp / f"{vid}.wav"
    cmd = ["yt-dlp", "-q", "--no-warnings", "--no-playlist", "-f", "bestaudio/best", "-o", str(tmp / f"{vid}.%(ext)s")]
    if proxy:
        cmd += ["--proxy", proxy]
    got = []
    # YouTube answers each client differently, and which one works moves from month to month.
    for client in ("default", "web_safari,tv", "android_vr", "mweb", "tv_simply"):
        r = subprocess.run(cmd + ["--extractor-args", f"youtube:player_client={client}", f"https://www.youtube.com/watch?v={vid}"],
                           capture_output=True, text=True, timeout=600)
        got = [p for p in tmp.glob(f"{vid}.*") if p.suffix != ".wav"]
        if not r.returncode and got:
            print(f"{vid}: fetched as {client}", flush=True)
            break
        print(f"{vid}: {client} failed: {r.stderr.strip()[-160:]}", flush=True)
    if not got:
        return None
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", str(got[0]), "-ac", "1", "-ar", str(SR), str(wav)], check=True)
    got[0].unlink()
    return wav


def hear_one(vid, tmp, key_text):
    """Whole-song whisper-large words of the video's own audio, as the app reads them; None if nothing usable."""
    import urllib.error, urllib.request
    proxy = os.environ.get("FARM_PROXY")
    cmd = ["yt-dlp", "-q", "--no-warnings", "--no-playlist", "-f", "bestaudio/best", "-o", str(tmp / f"{vid}.%(ext)s")]
    if proxy:
        cmd += ["--proxy", proxy]
    got = []
    for client in ("default", "mweb", "web_safari,tv", "android_vr", "tv_simply"):
        r = subprocess.run(cmd + ["--extractor-args", f"youtube:player_client={client}", f"https://www.youtube.com/watch?v={vid}"],
                           capture_output=True, text=True, timeout=600)
        got = list(tmp.glob(f"{vid}.*"))
        if not r.returncode and got:
            break
    if not got:
        print("no audio", flush=True)
        return None
    audio = got[0].read_bytes()
    got[0].unlink()
    url = "https://api.deepgram.com/v1/listen?model=whisper-large&detect_language=true&punctuate=false&smart_format=false&numerals=false"
    req = urllib.request.Request(url, data=audio, method="POST",
                                 headers={"Authorization": f"Token {key_text}", "Content-Type": "application/octet-stream"})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=300) as resp:
                ch = json.load(resp)["results"]["channels"][0]
            break
        except urllib.error.HTTPError as e:
            print(f"http {e.code}", flush=True)
            if e.code != 429:
                return None
        except Exception as e:
            print(type(e).__name__, flush=True)
        time.sleep(15 * (attempt + 1))
    else:
        return None
    words = ch["alternatives"][0].get("words", [])
    if len(words) < 30:
        print(f"only {len(words)} words", flush=True)
        return None
    print(f"{ch.get('detected_language')} words={len(words)}", flush=True)
    return {"lang": ch.get("detected_language") or "auto", "audio_s": round(float(words[-1]["end"]) + 1.0, 1),
            "words": [{"t": w["word"], "s": round(w["start"] * 1000), "e": round(w["end"] * 1000)} for w in words]}


def hear(a):
    """Runner: hear each id not yet in the data folder; each result is committed at once, so a dead job loses nothing."""
    key_text = os.environ["FARM_DEEPGRAM"].strip()
    data = Path(a.data)
    tmp = Path(a.tmp)
    tmp.mkdir(parents=True, exist_ok=True)
    ids = stream(Path(a.ids).read_bytes(), key_text).decode().split()
    for i, vid in enumerate(ids):
        name = f"{data_name(vid, key_text)}.enc"
        if i % a.of != a.index or (data / name).exists():
            continue
        try:
            body = hear_one(vid, tmp, key_text)
        except Exception as e:  # one odd song must not end the job
            print(type(e).__name__, flush=True)
            continue
        finally:
            for p in tmp.glob(f"{vid}.*"):
                p.unlink(missing_ok=True)
        if body is None:
            continue
        (data / name).write_bytes(stream(json.dumps(body, ensure_ascii=False).encode("utf-8"), key_text + vid))
        git = ["git", "-C", str(data)]
        subprocess.run(git + ["add", name], check=True)
        subprocess.run(git + ["commit", "-q", "-m", "data"], check=True)
        for _ in range(8):
            subprocess.run(git + ["pull", "-q", "--rebase", "origin", "align-data"], check=False)
            if subprocess.run(git + ["push", "-q", "origin", "HEAD:align-data"]).returncode == 0:
                break
            time.sleep(5)


def run(a):
    from transformers import AutoModelForCTC, Wav2Vec2Processor
    password = os.environ["FARM_PASS"].strip()
    jobs = json.loads(stream(Path(a.input).read_bytes(), password).decode("utf-8"))
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    tmp = out / "_tmp"
    tmp.mkdir(exist_ok=True)
    mine = [j for i, j in enumerate(sorted(jobs, key=lambda j: (j["lang"], j["id"]))) if i % a.of == a.index]
    loaded = {}
    for job in mine:
        vid, lang = job["id"], job["lang"]
        if lang not in CTC_MODEL:
            print(f"{vid}: no model for {lang}", flush=True)
            continue
        if lang not in loaded:
            loaded.clear()  # one language at a time: each model is over 1 GB
            name = CTC_MODEL[lang]
            loaded[lang] = (Wav2Vec2Processor.from_pretrained(name), AutoModelForCTC.from_pretrained(name).eval())
        t0 = time.time()
        wav = fetch(vid, tmp)
        if wav is None:
            continue
        t1 = time.time()
        try:
            words, audio_s = align(str(wav), job["lines"], *loaded[lang])
        except Exception as e:
            print(f"{vid}: align failed: {type(e).__name__}", flush=True)
            continue
        finally:
            wav.unlink(missing_ok=True)
        print(f"{vid}: {lang} words={len(words)} audio={audio_s:.0f}s fetch={t1 - t0:.0f}s align={time.time() - t1:.0f}s", flush=True)
        body = {"id": vid, "lang": lang, "audio_s": round(audio_s, 1), "sheet_hash": job["sheet_hash"], "words": words}
        (out / f"{vid}.enc").write_bytes(stream(json.dumps(body, ensure_ascii=False).encode("utf-8"), password))


# ---------------------------------------------------------------- local

def root():
    return Path(__file__).resolve().parents[3]  # Desktop\BitChord (the farm folder sits inside it)


def st():
    sys.path.insert(0, str(root() / "BitChord-night" / "scripts"))
    import synctest
    return synctest


def key():
    return (root() / "keys" / "deepgram_key.txt").read_text(encoding="utf-8").strip()


def pack(a):
    """The provider's sheet per id, from the newest scorer folder or log that has one."""
    s = st()
    logs = sorted((root() / "phone-out").glob("score-*/*.log"), key=lambda p: p.stat().st_mtime, reverse=True)
    jobs = []
    for vid in a.ids:
        for log in (p for p in logs if p.stem == vid):
            blocks = s.parse_blocks(log.read_text(encoding="utf-8", errors="replace"), vid)
            if blocks and blocks[0][1]:
                lines = [t for _, _, t in blocks[0][1] if t.strip()]
                jobs.append(dict(id=vid, lang=s.language_for(blocks[0][1]), lines=lines,
                                 sheet_hash=hashlib.sha1("\n".join(lines).encode("utf-8")).hexdigest()[:12]))
                break
        else:
            print(f"{vid}: no sheet in the logs")
    Path(a.out).write_bytes(stream(json.dumps(jobs, ensure_ascii=False).encode("utf-8"), key()))
    print(f"packed {len(jobs)}: " + " ".join(f"{j['id']}:{j['lang']}:{len(j['lines'])}" for j in jobs))


def pack_ids(a):
    """The ids to hear, enciphered: they are the listener's plays."""
    Path(a.out).write_bytes(stream(Path(a.src).read_bytes(), key()))


def score(a):
    """Per song: CTC line starts against whisper-large's on the lines whisper heard, and how many lines only CTC has."""
    s = st()
    truth_files = sorted((root() / "phone-out").glob("score-*/*.opus.whisper-large.words.json"),
                         key=lambda p: p.stat().st_mtime, reverse=True)
    for enc in sorted(Path(a.dir).rglob("*.enc")):
        r = json.loads(stream(enc.read_bytes(), key()).decode("utf-8"))
        vid = enc.stem
        tf = next((p for p in truth_files if p.name.startswith(vid + ".")), None)
        log = tf.parent / f"{vid}.log" if tf else None
        if not tf or not log.exists():
            print(f"{vid}: no whisper words to compare")
            continue
        blocks = s.parse_blocks(log.read_text(encoding="utf-8", errors="replace"), vid)
        lines = blocks[0][1]
        kept = [row for row in lines if row[2].strip()]
        ctc = {}
        for li, _, start, _, conf in r["words"]:
            ctc.setdefault(li, (start / 1000.0, []))[1].append(conf)
        whisper = {n: sung for n, _, sung, _ in s.align(lines, [tuple(w) for w in json.loads(tf.read_text(encoding="utf-8"))])}
        diffs = [ctc[i][0] - whisper[row[0]] for i, row in enumerate(kept) if i in ctc and row[0] in whisper]
        if not diffs:
            print(f"{vid}: {r['lang']} nothing in common, ctc lines {len(ctc)}")
            continue
        med = statistics.median(diffs)
        tight = sum(abs(d - med) <= 0.3 for d in diffs)
        print(f"{vid} {r['lang']} sheet={len(kept)} whisper={len(whisper)} ctc={len(ctc)} both={len(diffs)} "
              f"ctc-whisper med={med:+.3f}s within0.3={tight / len(diffs):.0%} within1.0={sum(abs(d - med) <= 1 for d in diffs) / len(diffs):.0%} "
              f"max={max(abs(d - med) for d in diffs):.1f}s conf={statistics.mean(c for _, cs in ctc.values() for c in cs):.2f}")


def agreement(vid, r):
    """(lines both placed, offset, share within 1 s) of CTC against the scorer's whisper-large; None if no whisper."""
    s = st()
    files = sorted((root() / "phone-out").glob(f"score-*/{vid}.opus.whisper-large.words.json"),
                   key=lambda p: p.stat().st_mtime, reverse=True)
    if not files or not (files[0].parent / f"{vid}.log").exists():
        return None
    blocks = s.parse_blocks((files[0].parent / f"{vid}.log").read_text(encoding="utf-8", errors="replace"), vid)
    lines = blocks[0][1]
    kept = [row for row in lines if row[2].strip()]
    first = {}
    for li, _, start, _, _ in r["words"]:
        first.setdefault(li, start / 1000.0)
    whisper = {n: sung for n, _, sung, _ in s.align(lines, [tuple(w) for w in json.loads(files[0].read_text(encoding="utf-8"))])}
    diffs = [first[i] - whisper[row[0]] for i, row in enumerate(kept) if i in first and row[0] in whisper]
    if not diffs:
        return 0, 0.0, 0.0
    return len(diffs), statistics.median(diffs), sum(abs(d) <= 1 for d in diffs) / len(diffs)


def publish(a):
    """Results that agree with whisper, as the app reads them. A wrong sheet still aligns, so whisper is the guard."""
    out = Path(a.to)
    out.mkdir(parents=True, exist_ok=True)
    for enc in sorted(Path(a.dir).rglob("*.enc")):
        r = json.loads(stream(enc.read_bytes(), key()).decode("utf-8"))
        vid = enc.stem
        agreed = agreement(vid, r)
        ok = agreed is not None and agreed[0] >= 5 and abs(agreed[1]) <= 0.5 and agreed[2] >= a.min_agree
        print(f"{vid} {r['lang']} agreed={agreed and tuple(round(x, 2) for x in agreed)} -> {'publish' if ok else 'hold'}")
        if ok:
            body = {"lang": r["lang"], "audio_s": r["audio_s"], "words": [{"t": w[1], "s": w[2], "e": w[3]} for w in r["words"]]}
            (out / f"{data_name(vid, key())}.enc").write_bytes(stream(json.dumps(body, ensure_ascii=False).encode("utf-8"), key() + vid))


def publish_heard(a):
    """Whole-song whisper-large words the scorer already paid for, one file per video: the app needs no request."""
    s = st()
    out = Path(a.to)
    out.mkdir(parents=True, exist_ok=True)
    newest = {}
    for tf in sorted((root() / "phone-out").glob("score-*/*.opus.whisper-large.words.json"), key=lambda p: p.stat().st_mtime):
        newest[tf.name.split(".")[0]] = tf
    done = skipped = 0
    for vid, tf in sorted(newest.items()):
        log = tf.parent / f"{vid}.log"
        blocks = s.parse_blocks(log.read_text(encoding="utf-8", errors="replace"), vid) if log.exists() else []
        words = json.loads(tf.read_text(encoding="utf-8"))
        if not blocks or len(words) < 30:
            skipped += 1
            continue
        body = {"lang": s.language_for(blocks[0][1]), "audio_s": round(words[-1][2] + 1.0, 1),
                "words": [{"t": w, "s": round(a0 * 1000), "e": round(b0 * 1000)} for w, a0, b0 in words]}
        (out / f"{data_name(vid, key())}.enc").write_bytes(stream(json.dumps(body, ensure_ascii=False).encode("utf-8"), key() + vid))
        done += 1
    print(f"published {done}, skipped {skipped}")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("pack"); p.add_argument("out"); p.add_argument("ids", nargs="+"); p.set_defaults(fn=pack)
    p = sub.add_parser("run"); p.add_argument("input"); p.add_argument("--index", type=int, default=0)
    p.add_argument("--of", type=int, default=1); p.add_argument("--out", default="out"); p.set_defaults(fn=run)
    p = sub.add_parser("score"); p.add_argument("dir"); p.set_defaults(fn=score)
    p = sub.add_parser("publish"); p.add_argument("dir"); p.add_argument("to")
    p.add_argument("--min-agree", type=float, default=0.8); p.set_defaults(fn=publish)
    p = sub.add_parser("publish-heard"); p.add_argument("to"); p.set_defaults(fn=publish_heard)
    p = sub.add_parser("pack-ids"); p.add_argument("src"); p.add_argument("out"); p.set_defaults(fn=pack_ids)
    p = sub.add_parser("hear"); p.add_argument("ids"); p.add_argument("--data", default="data"); p.add_argument("--tmp", default="tmp")
    p.add_argument("--index", type=int, default=0); p.add_argument("--of", type=int, default=1); p.set_defaults(fn=hear)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
