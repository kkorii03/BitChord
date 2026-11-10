"""Step 3: one cheap pass over every frame (no AI). Per frame, at a small analysis width:
  - scene cut (histogram change)            -> replays / camera cuts are never mixed into timings
  - camera pan (phase correlation)          -> motion is measured relative to the court, not the screen
  - camera-compensated motion mask          -> moving regions (players) as bounding boxes
  - ball candidate (colour + shape)         -> ball track
Everything is written to features.csv with the frame's original PTS.
"""
import csv
import json
import math
import os

import cv2
import numpy as np

DEFAULTS = {
    "analysis_width": 320,
    # Ball colour in OpenCV HSV (H 0-180). Default = orange basketball. Calibrate per game from review sheets.
    "ball_h": [5, 22], "ball_s_min": 110, "ball_v_min": 110,
    "ball_r_frac": [0.004, 0.025],      # ball radius as a fraction of frame width
    # Static HUD areas ignored for motion/ball (fractions: x0, y0, x1, y1). Landscape Dunk City layout guess.
    # Kept tight on purpose: players and the ball also pass under the buttons. Calibrate per video if needed.
    "hud": [[0.0, 0.0, 1.0, 0.10], [0.12, 0.66, 0.30, 0.92], [0.80, 0.62, 0.98, 0.96]],
    "diff_threshold": 25,
    "cut_hist_corr": 0.55,
    "max_blobs": 8,
    # Only accept ball blobs that overlap camera-compensated motion (static yellow seats/banners are rejected).
    "ball_require_motion": False,
}


def load_config(path=None):
    cfg = dict(DEFAULTS)
    if path and os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            cfg.update(json.load(f))
    return cfg


def _hud_mask(w, h, hud):
    m = np.ones((h, w), np.uint8)
    for x0, y0, x1, y1 in hud:
        m[int(y0 * h):int(y1 * h), int(x0 * w):int(x1 * w)] = 0
    return m


def _find_ball(hsv, mask_ok, cfg, w, prev, motion=None):
    lo = np.array([cfg["ball_h"][0], cfg["ball_s_min"], cfg["ball_v_min"]], np.uint8)
    hi = np.array([cfg["ball_h"][1], 255, 255], np.uint8)
    m = cv2.inRange(hsv, lo, hi) & (mask_ok * 255)
    # No morphological opening: at analysis width the ball is only ~5 px across and an opening erases it
    # (selftest: 45% -> see scan_ball_rate in selftest.json).
    n, lab, stats, cent = cv2.connectedComponentsWithStats(m, 8)
    rmin, rmax = cfg["ball_r_frac"][0] * w, cfg["ball_r_frac"][1] * w
    best, best_score = None, 0.0
    for i in range(1, n):
        x, y, bw, bh, area = stats[i]
        if motion is not None and not motion[y:y + bh, x:x + bw][lab[y:y + bh, x:x + bw] == i].any():
            continue
        r = math.sqrt(area / math.pi)
        if r < rmin or r > rmax:
            continue
        aspect = min(bw, bh) / max(bw, bh)
        fill = area / float(bw * bh)            # a disc fills ~0.785 of its box
        shape = aspect * (1.0 - min(1.0, abs(fill - 0.785) * 2.5))
        cx, cy = cent[i]
        near = 1.0
        if prev is not None:
            d = math.hypot(cx - prev[0], cy - prev[1])
            near = 1.0 / (1.0 + d / (0.08 * w))
        score = shape * (0.4 + 0.6 * near)
        if score > best_score:
            best, best_score = (float(cx), float(cy), float(r)), score
    return best, best_score


def _blobs(motion, max_blobs, w):
    # small closing kernel: neighbouring players should stay separate regions when possible
    m = cv2.morphologyEx(motion, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    n, lab, stats, _ = cv2.connectedComponentsWithStats(m, 8)
    min_area = (0.012 * w) ** 2
    out = [tuple(int(v) for v in stats[i][:4]) for i in range(1, n) if stats[i][4] >= min_area]
    out.sort(key=lambda b: -b[2] * b[3])
    return out[:max_blobs]


def scan(path, pts, work, cfg, max_seconds=None, progress=True):
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        raise RuntimeError(f"OpenCV가 영상을 열지 못함: {path}")
    aw = int(cfg["analysis_width"])
    rows, max_ts_dev = [], 0.0
    prev_gray = prev_hist = None
    prev_ball = None
    cum_dx = cum_dy = 0.0
    i = 0
    mask_ok = None
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        t = float(pts[i]) if i < len(pts) else cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
        if i < len(pts):
            max_ts_dev = max(max_ts_dev, abs(cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0 - (pts[i] - pts[0])))
        if max_seconds is not None and t - float(pts[0]) > max_seconds:
            break
        h0, w0 = frame.shape[:2]
        ah = int(round(aw * h0 / w0))
        small = cv2.resize(frame, (aw, ah), interpolation=cv2.INTER_AREA)
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
        if mask_ok is None or mask_ok.shape != gray.shape:
            mask_ok = _hud_mask(aw, ah, cfg["hud"])
        hist = cv2.calcHist([gray], [0], None, [32], [0, 256])
        cv2.normalize(hist, hist)
        cut = False
        dx = dy = 0.0
        resp = 0.0
        motion_frac = 0.0
        blobs = []
        motion_for_ball = None
        if prev_gray is not None:
            corr = cv2.compareHist(prev_hist, hist, cv2.HISTCMP_CORREL)
            cut = corr < cfg["cut_hist_corr"]
            if not cut:
                (dx, dy), resp = cv2.phaseCorrelate(np.float32(prev_gray), np.float32(gray))
                M = np.float32([[1, 0, dx], [0, 1, dy]])
                warped = cv2.warpAffine(prev_gray, M, (aw, ah), borderMode=cv2.BORDER_REPLICATE)
                diff = cv2.absdiff(gray, warped)
                motion = ((diff > cfg["diff_threshold"]).astype(np.uint8) * 255) & (mask_ok * 255)
                motion_frac = float(np.count_nonzero(motion)) / motion.size
                blobs = _blobs(motion, cfg["max_blobs"], aw)
                cum_dx += dx; cum_dy += dy
                if cfg.get("ball_require_motion"):
                    motion_for_ball = cv2.dilate(motion, np.ones((3, 3), np.uint8)) > 0
        if cut:
            prev_ball = None
        ball, bscore = _find_ball(hsv, mask_ok, cfg, aw, prev_ball, motion_for_ball)
        prev_ball = ball if ball else None
        rows.append({
            "frame": i, "pts_s": round(t, 6), "cut": int(cut), "pan_dx": round(dx, 3), "pan_dy": round(dy, 3),
            "pan_resp": round(float(resp), 3), "cum_dx": round(cum_dx, 2), "cum_dy": round(cum_dy, 2),
            "motion_frac": round(motion_frac, 5),
            "ball_x": round(ball[0], 2) if ball else "", "ball_y": round(ball[1], 2) if ball else "",
            "ball_r": round(ball[2], 2) if ball else "", "ball_score": round(bscore, 3) if ball else "",
            "blobs": ";".join(f"{x},{y},{bw},{bh}" for x, y, bw, bh in blobs),
        })
        prev_gray, prev_hist = gray, hist
        i += 1
        if progress and i % 600 == 0:
            print(f"  scan: {i} frames ({t - float(pts[0]):.1f}s)", flush=True)
    cap.release()
    meta = {"analysis_width": aw, "analysis_height": int(round(aw * h0 / w0)) if rows else 0, "decoded_frames": i,
            "pts_frames": int(len(pts)), "frame_count_match": i == len(pts) or max_seconds is not None,
            "max_opencv_vs_pts_dev_s": round(max_ts_dev, 6), "config": cfg}
    with open(os.path.join(work, "features.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()) if rows else ["frame"])
        w.writeheader(); w.writerows(rows)
    with open(os.path.join(work, "scan.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    return rows, meta
