"""Game-drawn action labels as ground truth. Dunk City draws '블록' above a player ONLY when a block succeeds
(user-confirmed), and '무리한 블록' for a forced block. Finding that word is far more reliable than tracking the
ball: on the first 4 real replay clips every detected segment was a real block label (checked by eye) and
each clip gave exactly one segment.

Template: the word '블록' with its dark outline, cut from a 2340x1080 replay frame (assets/label_block_2340.png).
"""
import os

import cv2
import numpy as np

HERE = os.path.dirname(__file__)
TEMPLATE = os.path.join(HERE, "assets", "label_block_2340.png")
PREFIX = os.path.join(HERE, "assets", "label_forced_prefix_2340.png")   # '무리한' (forced block)


FORCED_THR = 0.6


def detect(path, pts, work, step=2, scale=0.75, sizes=(0.8, 0.9, 1.0, 1.12), thr=0.75, min_hits=3, gap_s=0.2):
    tpl_full = cv2.imread(TEMPLATE, cv2.IMREAD_GRAYSCALE)
    pre_full = cv2.imread(PREFIX, cv2.IMREAD_GRAYSCALE)
    cap = cv2.VideoCapture(path)
    i, hits = 0, []
    ref_w = None
    while True:
        ok, f = cap.read()
        if not ok:
            break
        if i % step == 0:
            if ref_w is None:
                ref_w = f.shape[1]
            s = scale * f.shape[1] / 2340.0                       # template was cut at 2340 px width
            g = cv2.resize(cv2.cvtColor(f, cv2.COLOR_BGR2GRAY), None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
            best = (0.0, None, 1.0)
            for k in sizes:
                t = cv2.resize(tpl_full, None, fx=s * k / scale * scale, fy=s * k / scale * scale, interpolation=cv2.INTER_AREA)
                if t.shape[0] >= g.shape[0] or t.shape[1] >= g.shape[1]:
                    continue
                r = cv2.matchTemplate(g, t, cv2.TM_CCOEFF_NORMED)
                _, mx, _, loc = cv2.minMaxLoc(r)
                if mx > best[0]:
                    best = (mx, loc, k)
            if best[0] >= thr:
                x, y = best[1]
                tw, th = tpl_full.shape[1] * s * best[2] / scale, tpl_full.shape[0] * s * best[2] / scale
                # '무리한' prefix: match its own template in the strip left of the word (the hand icon alone does not match)
                kk = s * best[2]
                pt = cv2.resize(pre_full, None, fx=kk, fy=kk, interpolation=cv2.INTER_AREA)
                gy0 = max(0, int(y - 0.3 * pt.shape[0])); gy1 = int(y + 1.3 * pt.shape[0])
                gx0 = max(0, int(x - 2.4 * pt.shape[1])); gx1 = int(x + 0.2 * pt.shape[1])
                strip = g[gy0:gy1, gx0:gx1]
                left_text = 0.0
                if strip.shape[0] > pt.shape[0] and strip.shape[1] > pt.shape[1]:
                    left_text = float(cv2.minMaxLoc(cv2.matchTemplate(strip, pt, cv2.TM_CCOEFF_NORMED))[1])
                hits.append({"frame": i, "pts_s": float(pts[i]) if i < len(pts) else None, "score": round(float(best[0]), 3),
                             "x": round(x / scale + tw / 2, 1), "y": round(y / scale + th / 2, 1), "left_text": round(left_text, 3)})
        i += 1
    cap.release()
    segs = []
    for h in hits:
        if segs and h["pts_s"] - segs[-1][-1]["pts_s"] <= gap_s:
            segs[-1].append(h)
        else:
            segs.append([h])
    out = []
    for g in segs:
        if len(g) < min_hits:
            continue
        lt = float(np.median([h["left_text"] for h in g]))
        first = g[0]
        out.append({"event": "game_label_block", "onset_frame": first["frame"], "onset_pts_s": round(first["pts_s"], 4),
                    "end_pts_s": round(g[-1]["pts_s"], 4), "peak_score": max(h["score"] for h in g), "hits": len(g),
                    "x": first["x"], "y": first["y"], "left_text_fraction": round(lt, 3),
                    "forced_prefix_score": round(lt, 3),
                    "label_kind": "forced(무리한 블록)" if lt > FORCED_THR else "clean(블록)",
                    "onset_resolution_ms": round(1000 * step / max(1.0, len(pts) / max(1e-6, pts[-1] - pts[0])), 1),
                    "note": "글자 등장 시각. 블록 순간은 이보다 앞(4번 영상: 손 아이콘 4.56s, 글자 4.86s)."})
    return out


def crop_sheet(path, pts, label, out_path, before_s=1.4, after_s=0.15, every=4, box=(-330, -60, 330, 420), cols=8):
    """Frames before the label, cropped around the label position (the blocker stands under it)."""
    t0 = label["onset_pts_s"]
    lo, hi = t0 - before_s, t0 + after_s
    cap = cv2.VideoCapture(path)
    i, tiles = 0, []
    while True:
        ok, f = cap.read()
        if not ok:
            break
        t = float(pts[i]) if i < len(pts) else None
        if t is not None and lo <= t <= hi and i % every == 0:
            x, y = int(label["x"]), int(label["y"])
            pad = cv2.copyMakeBorder(f, 500, 500, 500, 500, cv2.BORDER_CONSTANT)
            c = pad[y + 500 + box[1]:y + 500 + box[3], x + 500 + box[0]:x + 500 + box[2]]
            c = cv2.resize(c, None, fx=0.5, fy=0.5, interpolation=cv2.INTER_AREA)
            cv2.putText(c, f"#{i} {t:.3f} ({1000 * (t - t0):+.0f}ms)", (3, 14), cv2.FONT_HERSHEY_SIMPLEX, 0.42, (0, 255, 255), 1)
            tiles.append(c)
        if t is not None and t > hi:
            break
        i += 1
    cap.release()
    if not tiles:
        return None
    h, w = tiles[0].shape[:2]
    rn = (len(tiles) + cols - 1) // cols
    sheet = np.zeros((rn * h, cols * w, 3), np.uint8)
    for k, t in enumerate(tiles):
        sheet[(k // cols) * h:(k // cols + 1) * h, (k % cols) * w:(k % cols + 1) * w] = t
    cv2.imwrite(out_path, sheet, [cv2.IMWRITE_JPEG_QUALITY, 85])
    return out_path
