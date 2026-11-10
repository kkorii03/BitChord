"""Count game '블록' labels in DunkBlock clip folders (offline success check for auto-block).

Usage: python count_block_labels.py <session_dir_or_bundle_root> [--thr 0.7]
Looks at every clips/clip_NN_<tag>_<time>/ folder (640 px grey JPEGs named f###_+0000.0ms.jpg, time relative
to the event) and reports, per clip, whether the game's '블록' word appeared after the event and when.
The game draws '블록' only on a successful block (user-confirmed), so trig_* clips with a label = auto-block hits.
"""
import argparse
import os
import re
import sys

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
TPL = os.path.join(HERE, "video", "dunkvideo", "assets", "label_block_2340.png")
NAME = re.compile(r"f\d+_([+-]\d+\.\d)ms\.jpg$")


def scan_clip(folder, tpls, thr):
    best = (0.0, None)
    for f in sorted(os.listdir(folder)):
        m = NAME.match(f)
        if not m:
            continue
        rel = float(m.group(1))
        g = cv2.imread(os.path.join(folder, f), cv2.IMREAD_GRAYSCALE)
        if g is None:
            continue
        # Blank out text that always says 블록: our own overlay at the top ("자동 블록 N회") and the
        # game's "전면 블록" button at the bottom right. The game's success label appears over the players.
        H, W = g.shape
        g = g.copy()
        g[: int(0.13 * H), :] = 128
        g[int(0.55 * H):, int(0.72 * W):] = 128
        g[: int(0.5 * H), : int(0.36 * W)] = 128    # 스킬 연습장 explanation panel ("블록 버튼을 탭하여 블록 동작을")
        for t in tpls:
            if t.shape[0] >= g.shape[0] or t.shape[1] >= g.shape[1]:
                continue
            mx = cv2.minMaxLoc(cv2.matchTemplate(g, t, cv2.TM_CCOEFF_NORMED))[1]
            if mx >= thr and (best[1] is None or rel < best[1]):
                best = (max(best[0], mx), rel)
            elif mx > best[0] and best[1] is None:
                best = (mx, None)
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--thr", type=float, default=0.7)
    a = ap.parse_args()
    base = cv2.imread(TPL, cv2.IMREAD_GRAYSCALE)
    if base is None:
        sys.exit("template missing: " + TPL)
    tpls = []
    for k in (0.8, 0.9, 1.0, 1.12, 1.25):
        s = 640 / 2340.0 * k
        tpls.append(cv2.resize(base, None, fx=s, fy=s, interpolation=cv2.INTER_AREA))
    rows = []
    for dirpath, dirnames, _ in os.walk(a.root):
        for d in dirnames:
            if d.startswith("clip_"):
                score, rel = scan_clip(os.path.join(dirpath, d), tpls, a.thr)
                rows.append((os.path.relpath(os.path.join(dirpath, d), a.root), score, rel))
    by_kind = {}
    for path, score, rel in sorted(rows):
        tag = re.sub(r"^clip_\d+_", "", os.path.basename(path)).rsplit("_", 1)[0]
        hit = rel is not None
        k = by_kind.setdefault(tag, [0, 0])
        k[0] += 1
        k[1] += int(hit)
        print(f"{path}\tbest={score:.2f}\t" + (f"블록 글자 {rel:+.0f}ms" if hit else "없음"))
    print("== 종류별: 클립 수 / 블록 글자 있음 ==")
    for tag, (n, h) in sorted(by_kind.items()):
        print(f"{tag}\t{n}\t{h}")


if __name__ == "__main__":
    main()
