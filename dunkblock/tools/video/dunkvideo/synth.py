"""Synthetic test video with KNOWN event times (frame-exact), to verify the pipeline mechanics.
It is NOT game footage: passing this test says nothing about accuracy on real Dunk City videos.

Scene: a textured court larger than the view, a slowly panning camera, a static HUD, six players (rectangles),
an orange ball. Each possession: dribble (ball bounces), gather (ball lifted), attacker jump, release,
a defender jump next to the attacker, and for some shots a block (ball deflected at contact)."""
import json
import math
import os

import cv2
import numpy as np

W, H, FPS = 960, 540, 60
WORLD_W = 2600
# HUD drawn by this generator, as analysis-mask rectangles (fractions): top bar + two button discs.
HUD = [[0.0, 0.0, 1.0, 0.095], [0.06, 0.71, 0.18, 0.93], [0.83, 0.71, 0.93, 0.89]]


def _court(rng):
    g = rng.integers(70, 110, (H, WORLD_W), dtype=np.uint8)
    g = cv2.GaussianBlur(g, (0, 0), 3)
    court = cv2.merge([g // 2 + 40, g // 2 + 60, g // 2 + 90])     # brownish wood (BGR)
    for x in range(0, WORLD_W, 160):
        cv2.line(court, (x, 0), (x, H), (200, 200, 200), 2)
    cv2.line(court, (0, 470), (WORLD_W, 470), (230, 230, 230), 3)
    return court


def make(out_dir, seconds=34, seed=7):
    os.makedirs(out_dir, exist_ok=True)
    rng = np.random.default_rng(seed)
    court = _court(rng)
    path = os.path.join(out_dir, "synthetic.mp4")
    vw = cv2.VideoWriter(path, cv2.VideoWriter_fourcc(*"mp4v"), FPS, (W, H))
    floor = 440
    shots = []
    starts = [3.2, 9.0, 14.6, 20.3, 26.0, 31.0]
    def_offsets = [0.06, 0.16, 0.10, 0.22, 0.12, 0.18]   # defender jump after gather start (s)
    blocked = [True, False, True, False, True, False]
    for k, t0 in enumerate(starts):
        ax = 500 + 330 * k
        # floor contacts every 0.4 s; the ball is caught at the TOP of the bounce, 0.2 s after the last contact
        shots.append({"id": k + 1, "attacker_x": ax, "dribble_bounces": [round(t0 - 0.2 - 0.4 * j, 4) for j in range(3, -1, -1)],
                      "gather": t0, "attacker_jump": t0 + 0.10, "release": t0 + 0.30,
                      "defender_jump": t0 + def_offsets[k], "blocked": blocked[k],
                      "contact": t0 + 0.30 + 0.08 if blocked[k] else None})
    n = int(seconds * FPS)
    cam_f = 0.0
    for f in range(n):
        t = f / FPS
        # broadcast-style camera: follows the next/active attacker, with a gentle sway
        nxt = next((s for s in shots if t <= s["gather"] + 1.4), shots[-1])
        target = nxt["attacker_x"] - W // 2 + int(60 * math.sin(t / 1.7))
        cam_f = target if f == 0 else cam_f + 0.06 * (target - cam_f)
        cam = max(0, min(WORLD_W - W, int(cam_f)))
        img = court[:, cam:cam + W].copy()
        # wandering players (noise)
        for p in range(4):
            px = int(400 + 520 * p + 120 * math.sin(t * (0.7 + 0.2 * p) + p)) - cam
            cv2.rectangle(img, (px, floor - 70), (px + 28, floor), (60 + 40 * p, 180, 90), -1)
        # active possession
        sh = next((s for s in shots if s["gather"] - 2.0 <= t <= s["gather"] + 1.4), None)
        if sh:
            ax = sh["attacker_x"] - cam
            lift = 0.0
            if t >= sh["attacker_jump"]:
                u = (t - sh["attacker_jump"]) / 0.55
                lift = max(0.0, 60 * math.sin(math.pi * min(u, 1.0)))
            cv2.rectangle(img, (ax, int(floor - 72 - lift)), (ax + 30, int(floor - lift)), (200, 60, 60), -1)
            dlift = 0.0
            if t >= sh["defender_jump"]:
                u = (t - sh["defender_jump"]) / 0.55
                dlift = max(0.0, 70 * math.sin(math.pi * min(u, 1.0)))
            dx = ax + 48
            cv2.rectangle(img, (dx, int(floor - 74 - dlift)), (dx + 30, int(floor - dlift)), (60, 60, 200), -1)
            # ball
            bx, by = ax + 15, floor - 40
            if t < sh["gather"]:
                ph = ((t - (sh["gather"] - 0.2)) % 0.4) / 0.4     # bounce cycle: floor at phase 0, hand at 0.5
                by = floor - 8 - 46 * abs(math.sin(math.pi * ph))
                bx = ax + 34
            elif t < sh["release"]:
                u = (t - sh["gather"]) / 0.30
                by = floor - 45 - 60 * u - lift
                bx = ax + 15
            else:
                tt = t - sh["release"]
                rx, ry = ax + 15, floor - 105 - 60 * math.sin(math.pi * min(1.0, (sh["release"] - sh["attacker_jump"]) / 0.55))
                if sh["blocked"] and t >= sh["contact"]:
                    tc = sh["contact"] - sh["release"]; ta = t - sh["contact"]
                    cx, cy = rx + 260 * tc, ry - 520 * tc + 600 * tc * tc
                    bx, by = cx - 180 * ta, cy + 200 * ta + 600 * ta * ta
                else:
                    bx, by = rx + 260 * tt, ry - 520 * tt + 600 * tt * tt
            if by < floor - 4:
                cv2.circle(img, (int(bx), int(by)), 8, (30, 120, 240), -1)
        # static HUD
        cv2.rectangle(img, (0, 0), (W, 50), (30, 30, 30), -1)
        cv2.putText(img, f"{int(t // 60):02d}:{int(t % 60):02d}", (W // 2 - 40, 35), cv2.FONT_HERSHEY_SIMPLEX, 1, (255, 255, 255), 2)
        cv2.circle(img, (int(0.12 * W), int(0.82 * H)), 55, (90, 90, 90), -1)
        cv2.circle(img, (int(0.88 * W), int(0.80 * H)), 45, (90, 90, 160), -1)
        vw.write(img)
    vw.release()
    truth = {"fps": FPS, "frame_ms": 1000 / FPS, "shots": shots,
             "note": "합성 영상. 파이프라인 동작 검증용이며 실제 게임 정확도와 무관"}
    with open(os.path.join(out_dir, "truth.json"), "w", encoding="utf-8") as f:
        json.dump(truth, f, ensure_ascii=False, indent=2)
    return path, truth


def k_progress(t, starts):
    return sum(1 for s in starts if t > s + 1.5)


def score(events_doc, truth):
    """Matches detected segments to truth shots by gather time and reports timing errors (ms)."""
    segs = {}
    for e in events_doc["events"]:
        segs.setdefault(e["segment_id"], {})[e["event"]] = e
    res = {"shots": len(truth["shots"]), "found": 0, "false_candidates": 0, "errors_ms": {}, "outcome_correct": 0, "outcome_known": 0}
    used = set()
    for sh in truth["shots"]:
        best = None
        for sid, ev in segs.items():
            if sid in used or "shot_motion_start" not in ev:
                continue
            d = float(ev["shot_motion_start"]["pts_s"]) - sh["gather"]
            if -0.15 <= d <= 0.25 and (best is None or abs(d) < abs(best[1])):
                best = (sid, d)
        if not best:
            continue
        used.add(best[0]); res["found"] += 1
        ev = segs[best[0]]
        pairs = {"shot_motion_start": sh["gather"], "attacker_jump_start": sh["attacker_jump"], "release": sh["release"],
                 "defender_jump_start": sh["defender_jump"], "ball_contact": sh["contact"],
                 "dribble_last_bounce": sh["dribble_bounces"][-1]}
        for name, tt in pairs.items():
            if tt is None or name not in ev:
                continue
            res["errors_ms"].setdefault(name, []).append(round((float(ev[name]["pts_s"]) - tt) * 1000, 1))
        oc = ev.get("block_outcome", {}).get("outcome")
        if oc in ("blocked", "not_blocked"):
            res["outcome_known"] += 1
            res["outcome_correct"] += int((oc == "blocked") == sh["blocked"])
    res["false_candidates"] = len(segs) - len(used)
    res["detection_rate"] = {k: f"{len(v)}/{len(truth['shots'])}" for k, v in res["errors_ms"].items()}
    return res
