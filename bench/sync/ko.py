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
needs one. The alignment and the fit/score metrics are ko_align.py's, so both
paths report the same numbers.
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
    import ko_align
    out = {"cands": cands, "songs": [], "errors": []}
    for song in payload["songs"]:
        vid = song["id"]
        lines = [(l["ms"], l["text"].split()) for l in song["lines"]
                 if any(ch.isalnum() for ch in l["text"])]
        words = [w for _, ws in lines for w in ws]
        sheet = [ms for ms, _ in lines]
        d = os.path.join(work, vid)
        os.makedirs(d, exist_ok=True)
        s = {"id": vid, "lines": len(lines), "gt_line_start_ms": sheet, "cand": {}}
        try:
            src = fetch_audio(vid, d, proxy)
            if "F" in cands and not os.path.exists(os.path.join(d, "vocals.wav")):
                s["demucs_s"] = bench.separate(d, os.cpu_count() or 2)
            for c in cands:
                try:
                    wav = os.path.join(d, "mix16.wav") if c == "E" else os.path.join(d, "vocals.wav")
                    if c == "E":
                        bench.ffwav(src, wav)
                    res, wall, tokens = ko_align.align(wav, words, lang)
                    r = {"wall_s": wall, "words_out": len(res), "words_in": len(words)}
                    pred = ko_align.line_starts(res, lines)
                    if pred is None:
                        r["error"] = "word count mismatch"
                    else:
                        errs = [abs(p - g) for p, g in zip(pred, sheet)]
                        r.update({"line_starts": pred, "line_err_ms": errs, "placed": len(pred),
                                  "median_ms": round(statistics.median(errs), 1),
                                  "pcs300": round(sum(e <= 300 for e in errs) / len(errs), 3)})
                        r.update(ko_align.sheet_fit(pred, sheet))
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
    import ko_align
    a, b = ko_align.theil_sen([1, 2, 3], [1000, 2000, 3000])
    assert abs(a - 1000) < 1e-6 and abs(b) < 1e-6
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
