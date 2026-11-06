#!/usr/bin/env python3
"""Reads the sync scenario matrix: one row per play of a song in a set. Numbers and ids
only; sync-dump lines (lyrics) are never read here.

Usage:
  matrix_report.py fetch <root> [set-prefix]   download finished sets from emu-matrix-runs.tsv
  matrix_report.py score <root> [set ...]      independent scoring (synctest --dry-run) per set
  matrix_report.py table <root> [set ...]      the table, as markdown

Layout: <root>/<set>/smoke-logcat.txt + synctest/*.wav; <root>/<set>/score/report.csv.
<root>/words/ keeps what the whole-song hearing cost once, so a song is paid for once.
"""
import csv
import json
import os
import pathlib
import re
import shutil
import statistics
import subprocess
import sys
from datetime import datetime

BASE = pathlib.Path(r"C:\Users\jang\Desktop\BitChord")
GH = str(BASE / "tools" / "gh" / "bin" / "gh.exe")
PY = str(BASE / "tools" / "python" / "python.exe")
RUNS = BASE / "ship" / "lanes" / "emu-matrix-runs.tsv"
SYNCTEST = pathlib.Path(__file__).resolve().parents[1] / "synctest.py"
ID = r"([\w-]{11})"
TS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)")


def runs():
    rows = []
    if RUNS.exists():
        for line in RUNS.read_text(encoding="utf-8").splitlines():
            p = line.split("\t")
            if len(p) >= 6:
                rows.append(dict(set=p[0], account=p[1], run=p[2], commit=p[3], songs=p[4].split(","), env=json.loads(p[5])))
    return rows


def token(account):
    r = subprocess.run([GH, "auth", "token", "-h", "github.com", "-u", account], capture_output=True, text=True)
    return {**os.environ, "GH_TOKEN": r.stdout.strip()}


def fetch(root, prefix=""):
    for s in runs():
        if not s["set"].startswith(prefix):
            continue
        out = root / s["set"]
        if (out / "smoke-logcat.txt").exists():
            continue
        r = subprocess.run([GH, "api", f"repos/{s['account']}/BitChord/actions/runs/{s['run']}"], capture_output=True,
                           text=True, encoding="utf-8", env=token(s["account"]))
        try:
            state = json.loads(r.stdout)
        except ValueError:
            print(s["set"], "no answer")
            continue
        if state.get("status") != "completed":
            print(s["set"], state.get("status"))
            continue
        out.mkdir(parents=True, exist_ok=True)
        d = subprocess.run([GH, "run", "download", s["run"], "-R", f"{s['account']}/BitChord", "-n", "smoke-play", "-D", str(out)],
                           capture_output=True, text=True, encoding="utf-8", errors="replace", env=token(s["account"]))
        print(s["set"], state.get("conclusion"), "downloaded" if d.returncode == 0 else "download failed: " + d.stderr[-120:])


def layout(run, out):
    """<id>.log + <id>.wav as synctest --dry-run reads them, one per song in the set."""
    log = run / "smoke-logcat.txt"
    if not log.exists():
        return 0
    text = log.read_text(encoding="utf-8", errors="replace")
    out.mkdir(parents=True, exist_ok=True)
    n = 0
    for vid in set(re.findall(r"sync-dump: begin ([\w-]{11})", text)):
        wav = run / "synctest" / (vid + ".wav")
        if not wav.exists():
            continue
        keep = [l for l in text.splitlines() if vid in l]
        (out / (vid + ".log")).write_text("\n".join(keep) + "\n", encoding="utf-8")
        shutil.copy(wav, out / (vid + ".wav"))
        n += 1
    return n


def score(root, names):
    words = root / "words"
    words.mkdir(exist_ok=True)
    for name in names:
        run, out = root / name, root / name / "score"
        if (out / "report.csv").exists() or not layout(run, out):
            continue
        # Only the YouTube copy's hearing and file carry over: the app's own head differs per set.
        for f in words.iterdir():
            if not (out / f.name).exists():
                shutil.copy(f, out / f.name)
        subprocess.run([PY, "-I", str(SYNCTEST), "--dry-run", "--out", str(out)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, encoding="utf-8")
        for f in out.iterdir():
            if (".opus." in f.name or f.suffix == ".webm") and not (words / f.name).exists():
                shutil.copy(f, words / f.name)
        print(name, "scored", (out / "report.csv").exists(), flush=True)


def stamp(line):
    m = TS.match(line)
    return datetime.strptime("2026-" + m.group(1), "%Y-%m-%d %H:%M:%S.%f") if m else None


def plays(path):
    """Every play in a log, in order, with what happened to it until the next one and after."""
    out, latest, cur, events = [], {}, None, []
    for line in open(path, encoding="utf-8", errors="replace"):
        if "sync-dump" in line:
            continue
        t = stamp(line)
        if not t:
            continue
        m = re.search(r"play: -> .*\(" + ID + r"\) idx=(\S+) reason=(\S+)", line)
        if m:
            if cur:
                cur["left"] = t
            cur = dict(id=m.group(1), start=t, reason=m.group(3), base=[], sync=[], hear=[], gaps=[], marks=[], swaps=0, hq=[], src=None)
            latest[cur["id"]] = cur
            out.append(cur)
            continue
        m = re.search(r"matrix: set=\S+ (.*)", line)
        if m:
            if cur:
                cur["marks"].append((t, m.group(1).strip()))
            events.append(m.group(1))
            continue
        m = re.search(r"lyric-clock: drawn=(-?\d+) reading=(-?\d+) age=(-?\d+)ms gap=(-?\d+)ms", line)
        if m and cur:
            cur["gaps"].append((t, int(m.group(4)), int(m.group(3))))
            continue
        m = re.search(r"\[" + ID + r"\] TIMING first audio", line)
        if m and m.group(1) in latest:
            latest[m.group(1)].setdefault("audio", t)
            continue
        m = re.search(r"base-apply: " + ID + r" base=(\S+) shift=(-?\d+)ms(.*?)src=(\S+)", line)
        if m and m.group(1) in latest:
            p = latest[m.group(1)]
            p["base"].append((t, m.group(2), int(m.group(3)), "other-lyrics" in m.group(4)))
            p["src"] = m.group(5)
            continue
        m = re.search(r"song-sync: " + ID + r" result=(\S+)(?: match=(\d+)%)?(?: shift=(-?\d+))?", line)
        if m and m.group(1) in latest:
            latest[m.group(1)]["sync"].append((t, m.group(2), m.group(3), m.group(4)))
            continue
        m = re.search(r"song-hear: sent .*?wait=(\d+)ms send=(\d+)ms requests=(\d+) http=(\S+) words=(\S+) audio=\S+ copy=([\w-]{11})(\S*)", line)
        if m and m.group(6) in latest:
            latest[m.group(6)]["hear"].append((t, int(m.group(3)), m.group(4), m.group(7)))
            continue
        m = re.search(r"track-summary: " + ID + r" .*?firstAudio=(\S+) played=(\d+) lyrics=(\S+) sync=(\S+) shift=(\S+) match=(\S+) .*?ended=(\S+)", line)
        if m and m.group(1) in latest:
            latest[m.group(1)].update(played=int(m.group(3)), lyrics=m.group(4), summary_sync=m.group(5), ended=m.group(8))
            continue
        if "hq-swap:" in line and " start " in line and cur:
            cur["swaps"] += 1
            cur["hq"].append(t)
    return out, events


def recovery(events, gaps):
    """Seconds from each event to the next lyric-clock line, as label:seconds.

    The lyric clock only logs while the lyrics draw, so the first line after an
    event is when they came back. Timestamps and gap numbers only, never text.
    """
    out = []
    for label, t in events:
        nxt = [g[0] for g in gaps if g[0] >= t]
        if nxt:
            out.append(f"{label}:{(min(nxt) - t).total_seconds():.1f}")
    return " ".join(out)


def table(root, names):
    meta = {s["set"]: s for s in runs()}
    print("| 세트 | 다른 점 | 곡 | 재생(초) | 첫 소리→요청답(초) | 첫 소리→화면 보정(초) | 곡 안에 도착 | 최종 base/shift | base 변화 | 답 | 요청 | 독립 판정 | 중앙값(초) | 0.3초 이내 | 최대(초) | lyric-clock gap 최대(ms) | 복귀 뒤 gap(ms) | 이벤트→다음 lyric-clock(초) |")
    print("|" + "---|" * 18)
    crashes = {}
    for name in names:
        log = root / name / "smoke-logcat.txt"
        if not log.exists():
            print(f"| {name} | 결과 없음 |" + " |" * 16)
            continue
        text = log.read_text(encoding="utf-8", errors="replace")
        crashes[name] = len(re.findall(r"FATAL EXCEPTION|ANR in com", text))
        scored = {}
        report = root / name / "score" / "report.csv"
        if report.exists():
            with open(report, encoding="utf-8-sig") as f:
                scored = {r["video"]: r for r in csv.DictReader(f)}
        env = meta.get(name, {}).get("env", {})
        what = " ".join(f"{k.replace('MATRIX_', '').lower()}={v}" for k, v in env.items() if k not in ("MATRIX_WAIT",)) or "기준"
        all_plays, _ = plays(log)
        wanted = set(meta.get(name, {}).get("songs", []))
        for p in all_plays:
            if p["id"] not in wanted and p.get("played", 0) < 30:
                continue
            a = p.get("audio")
            ok = [b for b in p["base"] if b[1] != "none"]
            shown = (ok[0][0] - a).total_seconds() if ok and a else None
            answer = (p["sync"][0][0] - a).total_seconds() if p["sync"] and a else None
            inside = "" if shown is None else ("예" if "left" not in p or ok[0][0] <= p["left"] else "늦음")
            last = p["base"][-1] if p["base"] else (None, "-", 0, False)
            flips = "→".join(dict.fromkeys(f"{b[1]}{b[2]}" for b in p["base"])) if len(p["base"]) > 1 else ""
            results = ",".join(f"{r[1]}{'' if not r[2] else ':' + r[2]}" for r in p["sync"][:4])
            # Whisper allows three at once on the key: latency here is not comparable between sets.
            requests = f"{sum(h[1] for h in p['hear'])}/429x{sum(h[2] == '429' for h in p['hear'])}/{p.get('lyrics', '')}"
            r = scored.get(p["id"], {})
            verdict = r.get("verdict") or (r.get("error") or "")[:24]
            gaps = [abs(g[1]) for g in p["gaps"]]
            back = []
            for t, mark in p["marks"]:
                if mark.startswith(("home back", "resume", "seek")) or mark.startswith("lyrics open"):
                    near = [g[1] for g in p["gaps"] if 0 <= (g[0] - t).total_seconds() < 25]
                    if near:
                        back.append(f"{mark.split()[0]}:{max(near, key=abs)}")
            fmt = lambda v: "" if v is None else f"{v:.1f}"
            events = [("lyrics", t) for t, mark in p["marks"] if mark.startswith("lyrics open")]
            events += [("hq", t) for t in p["hq"]]
            print(f"| {name.replace('matrix-', '')} | {what} | {p['id']} | {p.get('played', '')} | {fmt(answer)} | {fmt(shown)} | {inside} | "
                  f"{last[1]}/{last[2]} | {flips[:40]} | {results[:40]} | {requests} | {verdict} | {r.get('median', '')} | {r.get('in03', '')} | "
                  f"{r.get('max_abs', '')} | {max(gaps) if gaps else ''} | {' '.join(back)[:50]} | {recovery(events, p['gaps'])[:50]} |")
    print()
    print("크래시·ANR:", {k.replace("matrix-", ""): v for k, v in crashes.items() if v} or "0")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    cmd, root = sys.argv[1], pathlib.Path(sys.argv[2])
    rest = sys.argv[3:]
    if cmd == "fetch":
        fetch(root, rest[0] if rest else "")
        return
    names = rest or sorted(p.name for p in root.iterdir() if (p / "smoke-logcat.txt").exists())
    (score if cmd == "score" else table)(root, names)


if __name__ == "__main__":
    main()
