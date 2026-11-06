#!/usr/bin/env python3
"""Korean fixed-id sync bench: E (CTC forced align on the mix) and F (on Demucs vocals).

Lyrics come from the app's own `sync-dump:` blocks in a smoke logcat, so the sheet
is exactly what the app showed. The payload is AES-256-CBC/PBKDF2 encrypted with
the Deepgram key, the same way scripts/syncjudge/judge.py's payload is packed
(openssl enc -aes-256-cbc -pbkdf2 -pass env:DEEPGRAM_API_KEY), because this repo
is public. Results hold ids, counts and ms only; no lyric text is written.

  extract  --logs <glob> --ids <id,id> --out payload.enc
  run      --payload payload.enc --work work --out results-ko.json [--cands E,F]
  selftest

Audio is fetched with yt-dlp (the app plays YouTube); pass --proxy if the runner
needs one. E/F use the same ctc_forced_aligner calls as bench.py.
"""
import argparse, glob, json, os, re, statistics, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bench

BEGIN = re.compile(r"begin (\S+)")
LINE = re.compile(r"line (\S+) (\d+) (\d+) ?(.*)$")
META = re.compile(r"(?:rep|words)=\S+\s+")


def parse_blocks(text, vid):
    """Complete sync-dump blocks for vid: [(header, [(n, start_ms, text)])]."""
    blocks, cur = [], None
    for raw in text.splitlines():
        i = raw.find("sync-dump: ")
        if i < 0:
            continue
        s = raw[i + len("sync-dump: "):].rstrip("\r")
        m = BEGIN.match(s)
        if m:
            cur = (dict(p.split("=", 1) for p in s.split()[2:] if "=" in p), [])
            if m.group(1) != vid:
                cur = None
            continue
        m = LINE.match(s)
        if m and cur is not None and m.group(1) == vid:
            rest = m.group(4)
            while True:
                meta = META.match(rest)
                if not meta:
                    break
                rest = rest[meta.end():]
            cur[1].append((int(m.group(2)), int(m.group(3)), rest))
        elif s.startswith("end ") and cur is not None:
            blocks.append(cur)
            cur = None
    return blocks


def extract(logs, ids):
    songs = []
    for vid in ids:
        best = None
        for path in logs:
            with open(path, encoding="utf-8", errors="replace") as f:
                for head, lines in parse_blocks(f.read(), vid):
                    if head.get("lines") and len(lines) == int(head["lines"]):
                        if best is None or len(lines) > len(best):
                            best = lines
        if best:
            songs.append({"id": vid, "lines": [{"n": n, "ms": ms, "text": t} for n, ms, t in best]})
    return {"songs": songs}


def openssl():
    return os.environ.get("OPENSSL") or "openssl"


def pack(payload, out, passphrase):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    r = subprocess.run([openssl(), "enc", "-aes-256-cbc", "-pbkdf2", "-salt", "-pass", "env:KO_PASS"],
                       input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       env=dict(os.environ, KO_PASS=passphrase))
    if r.returncode != 0:
        raise RuntimeError("openssl enc failed: " + r.stderr.decode("utf-8", "replace")[-200:])
    with open(out, "wb") as f:
        f.write(r.stdout)


def unpack(path, passphrase):
    r = subprocess.run([openssl(), "enc", "-d", "-aes-256-cbc", "-pbkdf2", "-pass", "env:KO_PASS",
                        "-in", path], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       env=dict(os.environ, KO_PASS=passphrase))
    if r.returncode != 0:
        raise RuntimeError("openssl dec failed: " + r.stderr.decode("utf-8", "replace")[-200:])
    return json.loads(r.stdout.decode("utf-8"))


def linfit(xs, ys):
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx if sxx else 0.0
    return my - b * mx, b


def align(wav, lines, model, tok, lang):
    from ctc_forced_aligner import (load_audio, generate_emissions, preprocess_text,
                                    get_alignments, get_spans, postprocess_results)
    wf = load_audio(wav, model.dtype, model.device)
    t0 = time.time()
    emissions, stride = generate_emissions(model, wf, batch_size=4)
    t_emit = time.time() - t0
    tokens, texts, per_line = [], [], []
    for line in lines:
        tk, tx = preprocess_text(line["text"], romanize=True, language=lang)
        tokens.extend(tk)
        texts.extend(tx)
        per_line.append(sum(1 for t in tx if t != "<star>"))
    t1 = time.time()
    segments, scores, blank = get_alignments(emissions, tokens, tok)
    spans = get_spans(tokens, segments, blank)
    results = postprocess_results(texts, spans, stride, scores)
    t_align = time.time() - t1
    if len(results) != sum(per_line):
        return {"error": "word count mismatch", "words_out": len(results), "words_in": sum(per_line)}
    starts = [round(r["start"] * 1000) for r in results]
    line_starts, k = [], 0
    for n in per_line:
        line_starts.append(starts[k] if n else None)
        k += n
    return {"line_starts": line_starts, "word_starts": starts, "words": len(results),
            "audio_s": round(wf.size(0) / 16000, 1), "emit_s": round(t_emit, 1),
            "align_s": round(t_align, 1)}


def fetch_audio(vid, d, proxy=None):
    src = os.path.join(d, "src.mp3")
    if os.path.exists(src):
        return src
    cmd = ["yt-dlp", "--no-warnings", "-f", "bestaudio/best", "-x", "--audio-format", "mp3",
           "-o", src, "https://www.youtube.com/watch?v=" + vid]
    if proxy:
        cmd[1:1] = ["--proxy", proxy]
    bench.run(cmd)
    return src


def run(payload, work, out_path, cands, lang, proxy=None):
    import torch
    from ctc_forced_aligner import load_alignment_model
    try:
        model, tok = load_alignment_model("cpu", dtype=torch.float32)
    except TypeError:  # transformers 4.x spells it torch_dtype
        from transformers import AutoModelForCTC, AutoTokenizer
        path = "MahmoudAshraf/mms-300m-1130-forced-aligner"
        model = AutoModelForCTC.from_pretrained(path, torch_dtype=torch.float32).to("cpu").eval()
        tok = AutoTokenizer.from_pretrained(path, word_delimiter_token=None)
    out = {"cands": cands, "songs": [], "errors": []}
    for song in payload["songs"]:
        vid, lines = song["id"], song["lines"]
        d = os.path.join(work, vid)
        os.makedirs(d, exist_ok=True)
        gt = [l["ms"] for l in lines]
        s = {"id": vid, "lines": len(lines), "gt_line_start_ms": gt, "cand": {}}
        xs = [l["n"] for l in lines]
        a, slope = linfit(xs, gt)
        resid = [y - (a + slope * x) for x, y in zip(xs, gt)]
        s["sheet_fit"] = {"slope_ms_per_line": round(slope, 1),
                          "within_300ms": sum(abs(r) <= 300 for r in resid),
                          "within_1000ms": sum(abs(r) <= 1000 for r in resid)}
        try:
            src = fetch_audio(vid, d, proxy)
            if "F" in cands and not os.path.exists(os.path.join(d, "vocals.wav")):
                s["demucs_s"] = bench.separate(d, os.cpu_count() or 2)
            for c in cands:
                try:
                    wav = os.path.join(d, "mix16.wav") if c == "E" else os.path.join(d, "vocals.wav")
                    if c == "E":
                        bench.ffwav(src, wav)
                    r = align(wav, lines, model, tok, lang)
                    if "line_starts" in r:
                        errs = [None if p is None else abs(p - g) for p, g in zip(r["line_starts"], gt)]
                        placed = [e for e in errs if e is not None]
                        r.update({"line_err_ms": errs, "placed": len(placed),
                                  "median_ms": round(statistics.median(placed), 1) if placed else None,
                                  "pcs300": round(sum(e <= 300 for e in placed) / len(placed), 3) if placed else None})
                    s["cand"][c] = r
                except Exception as e:  # noqa
                    out["errors"].append({"song": vid, "cand": c, "why": (type(e).__name__ + ": " + str(e))[:300]})
        except Exception as e:  # noqa
            out["errors"].append({"song": vid, "stage": "audio", "why": (type(e).__name__ + ": " + str(e))[:300]})
        out["songs"].append(s)
        for f in os.listdir(d):
            if f.endswith((".wav", ".mp3", ".webm")):
                os.remove(os.path.join(d, f))
        json.dump(out, open(out_path, "w"), ensure_ascii=False, indent=1)
        print("done", vid, flush=True)


def selftest():
    log = "\n".join([
        "I LyricsTiming: sync-dump: begin abc playing=abc source=X base=none shift=0 match=0 lines=3",
        "I LyricsTiming: sync-dump: line abc 1 1000 rep=1/1 words=2 a b",
        "I LyricsTiming: sync-dump: line abc 2 2000 rep=1/2 words=1 c",
        "I LyricsTiming: sync-dump: line abc 3 3000 rep=1/1 words=0 ",
        "I LyricsTiming: sync-dump: end abc",
        "I LyricsTiming: sync-dump: begin old playing=old source=Y base=none shift=0 match=0 lines=2",
        "I LyricsTiming: sync-dump: line old 1 500 d e",
        "I LyricsTiming: sync-dump: line old 2 900 f",
        "I LyricsTiming: sync-dump: end old",
    ])
    b = parse_blocks(log, "abc")
    assert len(b) == 1 and len(b[0][1]) == 3, b
    assert b[0][1][0] == (1, 1000, "a b"), b[0][1][0]
    o = parse_blocks(log, "old")
    assert len(o) == 1 and o[0][1][1] == (2, 900, "f"), o
    payload = {"songs": [{"id": "abc", "lines": [{"n": 1, "ms": 1000, "text": "a b"}]}]}
    tmp = os.path.join(os.environ.get("TEMP", "."), "ko-selftest.enc")
    pack(payload, tmp, "test-pass")
    assert unpack(tmp, "test-pass") == payload
    os.remove(tmp)
    a, slope = linfit([1, 2, 3], [1000, 2000, 3000])
    assert abs(slope - 1000) < 1e-6 and abs(a) < 1e-6
    print("selftest ok")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("extract")
    e.add_argument("--logs", required=True, help="glob of smoke-logcat.txt files")
    e.add_argument("--ids", required=True)
    e.add_argument("--out", required=True)
    r = sub.add_parser("run")
    r.add_argument("--payload", required=True)
    r.add_argument("--work", default="work-ko")
    r.add_argument("--out", default="results-ko.json")
    r.add_argument("--cands", default="E,F")
    r.add_argument("--lang", default="kor")
    r.add_argument("--proxy")
    sub.add_parser("selftest")
    a = ap.parse_args()
    if a.cmd == "selftest":
        return selftest()
    if a.cmd == "extract":
        passphrase = os.environ.get("DEEPGRAM_API_KEY", "").strip()
        if not passphrase:
            raise SystemExit("DEEPGRAM_API_KEY is the payload passphrase (same as sync-judge)")
        payload = extract(sorted(glob.glob(a.logs, recursive=True)), a.ids.split(","))
        pack(payload, a.out, passphrase)
        print("packed", len(payload["songs"]), "songs ->", a.out)
        return
    passphrase = os.environ.get("DEEPGRAM_API_KEY", "").strip()
    if not passphrase:
        raise SystemExit("DEEPGRAM_API_KEY is the payload passphrase (same as sync-judge)")
    run(unpack(a.payload, passphrase), a.work, a.out, a.cands.split(","), a.lang, a.proxy)


if __name__ == "__main__":
    main()
