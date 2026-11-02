#!/usr/bin/env python3
"""Precise line onsets for the sync scorer (11-02), run on a GitHub runner.

synctest.py finds where each lyric line was sung from Deepgram's whisper words.
That finds the right place, but whisper's word times carry their own error:
hand-timed AMLL sheets on their own recordings passed only 5 of 14 under the
strict rules, every miss from a few lines heard 0.3-1.3 s off. Here each line
is timed again inside a short window around whisper's answer: the vocals are
separated (Demucs htdemucs) and the line's text, romanized (uroman), is
force-aligned to them with torchaudio's MMS_FA model; the line starts where its
first letter does. Star tokens on both ends take whatever else is in the window.

Input (decrypted payload): {"songs": [{"vid", "audio", "offset", "lines":
[{"n", "sung", "span", "text"}]}]} where sung/span are on the app's clock and
YouTube time = app time + offset. Output out/<vid>.fa-out.json: {"lines":
[{"n", "fa", "score"}]}, times on the app's clock, no lyrics text.
Usage: judge.py payload.json outdir [--proxy http://127.0.0.1:3128]
"""
import json
import re
import subprocess
import sys
from pathlib import Path

import torch
import torchaudio
import uroman

PAD_BEFORE_S = 1.5
PAD_AFTER_S = 0.6
MAX_SPAN_S = 8.0

bundle = torchaudio.pipelines.MMS_FA
model = bundle.get_model(with_star=True)
model.eval()
LABELS = bundle.get_dict(star="*")
STAR = LABELS["*"]
romanizer = uroman.Uroman()


def letters(text):
    """The line as MMS_FA letters: romanized, lower case, a-z and apostrophe only."""
    roman = romanizer.romanize_string(text).lower()
    return [LABELS[c] for c in re.sub(r"[^a-z']", "", roman) if c in LABELS]


def vocals_of(audio, work):
    wav = work / "song.wav"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(audio), "-ac", "2", "-ar", "44100", str(wav)],
                   check=True)
    subprocess.run([sys.executable, "-m", "demucs", "--two-stems=vocals", "-n", "htdemucs", "-o", str(work / "sep"),
                    str(wav)], check=True, capture_output=True)
    voc, rate = torchaudio.load(str(work / "sep" / "htdemucs" / "song" / "vocals.wav"))
    return torchaudio.functional.resample(voc.mean(0, keepdim=True), rate, bundle.sample_rate)


def onset(vocals, start_s, end_s, targets):
    """(seconds from start_s where the first letter begins, mean letter probability)."""
    sr = bundle.sample_rate
    a, b = max(0, int(start_s * sr)), min(vocals.size(1), int(end_s * sr))
    if b - a < sr // 2:
        return None
    with torch.inference_mode():
        emission, _ = model(vocals[:, a:b])
    emission = torch.log_softmax(emission, dim=-1)  # idempotent if already log-probs
    seq = [STAR] + targets + [STAR]
    frames = emission.size(1)
    repeats = sum(1 for x, y in zip(seq, seq[1:]) if x == y)
    if frames < len(seq) + repeats:
        return None
    path, scores = torchaudio.functional.forced_align(emission, torch.tensor([seq], dtype=torch.int32), blank=0)
    spans = torchaudio.functional.merge_tokens(path[0], scores[0].exp())
    real = [s for s in spans if s.token != STAR]
    if not real:
        return None
    sec_per_frame = (b - a) / sr / frames
    first = real[0]
    return a / sr - start_s + first.start * sec_per_frame, sum(s.score for s in real) / len(real)


def main():
    payload, out = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")), Path(sys.argv[2])
    proxy = sys.argv[sys.argv.index("--proxy") + 1] if "--proxy" in sys.argv else None
    out.mkdir(parents=True, exist_ok=True)
    timed = 0
    for song in payload["songs"]:
        vid, offset = song["vid"], float(song.get("offset", 0.0))
        work = Path("work") / vid
        work.mkdir(parents=True, exist_ok=True)
        audio = work / "audio.webm"
        cmd = [sys.executable, "-m", "yt_dlp", "-q", "-f", "251/bestaudio[ext=webm]/bestaudio", "-o", str(audio),
               f"https://www.youtube.com/watch?v={song['audio']}"]
        if proxy:
            cmd[3:3] = ["--proxy", proxy]
        if subprocess.run(cmd).returncode != 0 or not audio.exists():
            print(f"{vid}: download failed", flush=True)
            continue
        try:
            vocals = vocals_of(audio, work)
        except Exception as e:  # one song's failure must not end the run
            print(f"{vid}: separation failed: {e}", flush=True)
            continue
        rows = []
        for line in song["lines"]:
            targets = letters(line["text"])
            if len(targets) < 2:
                continue
            yt = float(line["sung"]) + offset
            span = min(max(float(line.get("span", 3.0)), 1.5), MAX_SPAN_S)
            start = yt - PAD_BEFORE_S
            got = onset(vocals, start, yt + span + PAD_AFTER_S, targets)
            if got is None:
                continue
            at, score = got
            rows.append(dict(n=line["n"], fa=round(start + at - offset, 3), score=round(float(score), 3)))
        (out / f"{vid}.fa-out.json").write_text(json.dumps(dict(vid=vid, lines=rows)), encoding="utf-8")
        print(f"{vid}: {len(rows)}/{len(song['lines'])} lines timed", flush=True)
        timed += bool(rows)
    # A run that timed nothing must not read as a success (11-02: no ffmpeg on
    # the runner failed every song and the job was still green).
    print(f"songs timed: {timed}/{len(payload['songs'])}", flush=True)
    return 0 if timed else 1


if __name__ == "__main__":
    sys.exit(main())
