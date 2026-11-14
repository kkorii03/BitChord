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
    """Earliest label time, or None. On 640 px grey frames any background scores ~0.60 at a wandering spot; the real
    label (clip_34 vc1031) scores 0.66-0.78 and stays put (within a few px) for 1 s+. So a hit = 4 frames >= thr
    within 300 ms at the same place (8 px)."""
    pts = []
    peak = 0.0
    bestv = (0.0, None, None, None)   # score, rel, gray frame, (x, y, w, h)
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
        mx, loc = 0.0, None
        for t in tpls:
            if t.shape[0] >= g.shape[0] or t.shape[1] >= g.shape[1]:
                continue
            _, m, _, l = cv2.minMaxLoc(cv2.matchTemplate(g, t, cv2.TM_CCOEFF_NORMED))
            if m > mx:
                mx, loc = m, (l[0] + t.shape[1] // 2, l[1] + t.shape[0] // 2)
                if rel >= 0 and m > bestv[0]:
                    bestv = (m, rel, g, (l[0], l[1], t.shape[1], t.shape[0]))
        peak = max(peak, mx)
        if mx >= thr:
            pts.append((rel, loc))
    pts.sort()
    starts = []
    for i, (rel, loc) in enumerate(pts):
        near = [p for p in pts[i:] if p[0] - rel <= 300 and abs(p[1][0] - loc[0]) <= 8 and abs(p[1][1] - loc[1]) <= 8]
        if len(near) >= 4:
            starts.append(rel)
    # A label already on screen before the event belongs to an earlier play (it stays ~1.5 s): count only new ones.
    if any(r < 0 for r in starts):
        return peak, None, ""
    new = [r for r in starts if r <= 1500]
    return peak, (new[0] if new else None), (verdict(bestv) if new else "")


def verdict(b):
    """'블록' alone = success. '블록 위치 부족' / '블록 타이밍 늦음' carry 2-3 more words of the same white text right
    of '블록'. Compare bright-pixel density right after the word with the word itself (vc1035: 17 clips read by eye)."""
    _, _, g, (x, y, w, h) = b
    if g is None:
        return ""
    word = g[y:y + h, x:x + w]
    right = g[y:y + h, x + w + 2:x + 3 * w + 2]
    if right.size == 0 or word.size == 0:
        return "?"
    thr = max(170, int(np.percentile(word, 85)) - 25)
    ratio = (right >= thr).mean() / max(1e-3, (word >= thr).mean())
    return f"실패({ratio:.2f})" if ratio > 0.45 else f"성공({ratio:.2f})"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--thr", type=float, default=0.66)
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
                score, rel, v = scan_clip(os.path.join(dirpath, d), tpls, a.thr)
                rows.append((os.path.relpath(os.path.join(dirpath, d), a.root), score, rel, v))
    by_kind = {}
    for path, score, rel, v in sorted(rows):
        tag = re.sub(r"^clip_\d+_", "", os.path.basename(path)).rsplit("_", 1)[0]
        hit = rel is not None
        k = by_kind.setdefault(tag, [0, 0, 0])
        k[0] += 1
        k[1] += int(hit)
        k[2] += int(v.startswith("성공"))
        print(f"{path}\tbest={score:.2f}\t" + (f"블록 글자 {rel:+.0f}ms {v}" if hit else "없음"))
    print("== 종류별: 클립 수 / 판정 글자 있음 / 그중 성공('블록'만) ==")
    for tag, (n, h, s) in sorted(by_kind.items()):
        print(f"{tag}\t{n}\t{h}\t{s}")


if __name__ == "__main__":
    main()
