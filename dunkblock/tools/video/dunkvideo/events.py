"""Step 4: attack-scene candidates and timing events, from features.csv only (no re-decoding).

Candidates: the ball rises steadily (court coordinates, camera pan removed) for >= MIN_RISE_S without a scene cut.
Only those windows are analysed further. Events per candidate (all are ESTIMATES from video frames):

  dribble_last_bounce   last floor bounce of the ball before the shot motion (earliest warning for pull-ups)
  shot_motion_start     ball begins its sustained rise (gather / lift)
  attacker_jump_start   attacker's moving region starts rising (low confidence: motion-mask based)
  release               ball clears the top of the attacker's region
  defender_jump_start   another region next to the attacker starts rising (block animation start)
  ball_contact          ball path breaks away from its free-flight parabola right after release
  block_outcome         blocked / not_blocked / unknown

The user's actual button press time is NOT observable in video. defender_jump_start is when the block
animation becomes visible, i.e. press time + the game's input/animation delay (unknown), quantised to the
video frame interval.
"""
import math

import numpy as np

MIN_RISE_S = 0.10
MIN_RISE_FRAC = 0.10      # of frame height
RISE_SPEED = 0.35         # frame heights per second (upwards)
MERGE_GAP_S = 1.0
START_SPEED = 0.08        # walking back from the fast rise: the shot motion starts where the ball begins to rise at all


def _f(v):
    return float(v) if v not in ("", None) else float("nan")


def _parse_blobs(s):
    if not s:
        return []
    return [tuple(int(x) for x in b.split(",")) for b in s.split(";") if b]


class Track:
    def __init__(self, rows, ah):
        self.n = len(rows)
        self.ah = ah
        self.t = np.array([_f(r["pts_s"]) for r in rows])
        self.cut = np.array([int(r["cut"]) for r in rows], bool)
        cdx = np.array([_f(r["cum_dx"]) for r in rows]); cdy = np.array([_f(r["cum_dy"]) for r in rows])
        self.cdx, self.cdy = cdx, cdy
        bx = np.array([_f(r["ball_x"]) for r in rows]); by = np.array([_f(r["ball_y"]) for r in rows])
        self.br = np.array([_f(r["ball_r"]) for r in rows])
        self.bscore = np.array([_f(r["ball_score"]) for r in rows])
        self.bx, self.by = bx, by                       # screen (analysis px)
        self.wx, self.wy = bx - cdx, by - cdy           # court-ish (pan removed)
        self.blobs = [_parse_blobs(r["blobs"]) for r in rows]
        # fill single-frame ball gaps
        self.wy_f = self._fill(self.wy); self.wx_f = self._fill(self.wx)

    @staticmethod
    def _fill(a, max_gap=2):
        a = a.copy()
        n = len(a); i = 0
        while i < n:
            if math.isnan(a[i]):
                j = i
                while j < n and math.isnan(a[j]):
                    j += 1
                if 0 < i and j < n and j - i <= max_gap:
                    a[i:j] = np.linspace(a[i - 1], a[j], j - i + 2)[1:-1]
                i = j
            else:
                i += 1
        return a

    def idx_at(self, t):
        return int(np.clip(np.searchsorted(self.t, t), 0, self.n - 1))

    def blob_world(self, i, b):
        x, y, w, h = b
        return (x + w / 2 - self.cdx[i], y - self.cdy[i], y + h - self.cdy[i], w, h)  # cx, top, bottom (world), w, h

    def blob_with_ball(self, i):
        if math.isnan(self.bx[i]):
            return None
        best, bd = None, 1e9
        for b in self.blobs[i]:
            x, y, w, h = b
            cx, cy = x + w / 2, y + h / 2
            inside = x - 3 <= self.bx[i] <= x + w + 3 and y - 3 <= self.by[i] <= y + h + 3
            d = 0 if inside else math.hypot(self.bx[i] - cx, self.by[i] - cy)
            if d < bd:
                best, bd = b, d
        return best if bd < 0.25 * self.ah else None


def find_candidates(tr: Track):
    """Runs of sustained upward ball motion (court coordinates)."""
    dt = np.diff(tr.t, prepend=tr.t[0])
    dt[dt <= 0] = np.nan
    vy = np.diff(tr.wy_f, prepend=np.nan) / dt / tr.ah        # frame heights per second, + = down
    rising = (vy < -RISE_SPEED) & ~tr.cut
    runs, i = [], 0
    while i < tr.n:
        if rising[i]:
            j = i
            while j + 1 < tr.n and (rising[j + 1] or (j + 2 < tr.n and rising[j + 2] and not tr.cut[j + 1])):
                j += 1
            s, e = i - 1 if i > 0 else i, j
            # walk back to where the (slower) lift began: still rising faster than START_SPEED, no cut
            while s > 0 and not tr.cut[s] and not math.isnan(vy[s]) and vy[s] < -START_SPEED:
                s -= 1
            dur = tr.t[e] - tr.t[s]
            rise = (tr.wy_f[s] - np.nanmin(tr.wy_f[s:e + 1])) / tr.ah
            if dur >= MIN_RISE_S and rise >= MIN_RISE_FRAC:
                runs.append({"s": s, "e": e, "dur": dur, "rise": float(rise)})
            i = j + 1
        else:
            i += 1
    merged = []
    for r in runs:
        if merged and tr.t[r["s"]] - tr.t[merged[-1]["e"]] < MERGE_GAP_S:
            continue  # keep the first rise of a sequence (gather), ignore e.g. the rebound
        merged.append(r)
    return merged


def _ev(name, tr, i, t0, conf, method, **extra):
    d = {"event": name, "frame": int(i), "pts_s": round(float(tr.t[i]), 6),
         "t_rel_ms": round((tr.t[i] - t0) * 1000, 1), "confidence": round(float(conf), 2),
         "method": method, "status": "auto_estimate", "evidence_frame": int(i)}
    d.update(extra)
    return d


def analyse_candidate(tr: Track, c, seg_id):
    s, e = c["s"], c["e"]
    t0 = tr.t[s]
    events = []
    ball_cov = float(np.mean(~np.isnan(tr.by[max(0, tr.idx_at(t0 - 1.0)):tr.idx_at(t0 + 1.0) + 1])))
    events.append(_ev("shot_motion_start", tr, s, t0, min(1.0, 0.4 + c["rise"] + 0.3 * ball_cov), "ball_rise_court_coords",
                      rise_frac=round(c["rise"], 3)))

    # --- dribble: floor bounces = local maxima of ball y (image y grows downwards) before the rise
    a = tr.idx_at(t0 - 2.0)
    seg = tr.wy_f[a:s + 1]
    bounces = []
    for k in range(1, len(seg) - 1):
        if not math.isnan(seg[k]) and seg[k] >= np.nanmax(seg[max(0, k - 3):k + 4]):
            lo = np.nanmin(seg[max(0, k - 12):k + 13])
            if (seg[k] - lo) / tr.ah > 0.04:
                bounces.append(a + k)
    if bounces:
        lb = bounces[-1]
        periods = np.diff(tr.t[bounces]) if len(bounces) > 1 else np.array([])
        dribble = bool(len(periods) and np.all((periods > 0.2) & (periods < 0.9)))
        events.append(_ev("dribble_last_bounce", tr, lb, t0, 0.6 if dribble else 0.35, "ball_y_local_max",
                          bounces=len(bounces), dribble_rhythm=dribble))

    # --- attacker region and release
    att = tr.blob_with_ball(s) or tr.blob_with_ball(min(s + 1, tr.n - 1))
    rel = None
    if att is not None:
        acx, atop, abot, aw, ah = tr.blob_world(s, att)
        for i in range(s, min(e + 1, tr.n)):
            if math.isnan(tr.by[i]):
                continue
            # nearest region to the attacker centre in this frame
            cand = [tr.blob_world(i, b) for b in tr.blobs[i]]
            if not cand:
                continue
            cur = min(cand, key=lambda b: abs(b[0] - acx))
            if abs(cur[0] - acx) > 1.5 * aw:
                continue
            acx = cur[0]
            ball_bottom = tr.wy[i] + (tr.br[i] if not math.isnan(tr.br[i]) else 0)
            if ball_bottom < cur[1]:
                rel = i
                break
        if rel is not None:
            events.append(_ev("release", tr, rel, t0, 0.45, "ball_clears_attacker_region"))
        # attacker jump: bottom of the attacker region rising before release
        end = rel if rel is not None else e
        st = tr.idx_at(t0 - 0.8)
        bots = []
        acx2 = tr.blob_world(s, att)[0]
        for i in range(st, end + 1):
            cand = [tr.blob_world(i, b) for b in tr.blobs[i]]
            if cand:
                cur = min(cand, key=lambda b: abs(b[0] - acx2))
                if abs(cur[0] - acx2) < 1.5 * aw:
                    bots.append((i, cur[2])); acx2 = cur[0]
        if len(bots) >= 4:
            ys = np.array([b[1] for b in bots])
            k_end = len(ys) - 1
            k = k_end
            while k > 0 and ys[k - 1] - ys[k] > -0.5:      # walking back while the bottom keeps rising (y falling)
                k -= 1
            if (ys[k] - ys[k_end]) / tr.ah > 0.03:
                events.append(_ev("attacker_jump_start", tr, bots[k][0], t0, 0.3, "attacker_region_bottom_rising",
                                  rise_frac=round(float((ys[k] - ys[k_end]) / tr.ah), 3)))

    # --- defender: another region next to the attacker whose top rises sharply
    dfd = None
    if att is not None:
        acx, atop, abot, aw, ah = tr.blob_world(s, att)
        w0, w1 = tr.idx_at(t0 - 0.4), tr.idx_at(t0 + 0.8)
        series = []
        for i in range(w0, w1 + 1):
            cand = [tr.blob_world(i, b) for b in tr.blobs[i]]
            # a separate region beside the attacker (not the attacker's own region, not far-away players)
            others = [b for b in cand if 0.5 * aw < abs(b[0] - acx) < 2.2 * aw and b[2] > atop]
            if others:
                o = min(others, key=lambda b: abs(b[0] - acx))
                series.append((i, o[1]))
        for k in range(len(series)):
            i0, top0 = series[k]
            for k2 in range(k + 1, len(series)):
                i1, top1 = series[k2]
                if tr.t[i1] - tr.t[i0] > 0.25:
                    break
                if (top0 - top1) / tr.ah > 0.05:
                    dfd = i0
                    break
            if dfd is not None:
                break
        if dfd is not None:
            events.append(_ev("defender_jump_start", tr, dfd, t0, 0.35, "neighbour_region_top_rising",
                              note="블록 애니메이션 시작(영상). 실제 버튼 입력 시각 아님"))

    # --- contact / outcome: break from a free-flight parabola after release
    outcome, oc_conf, contact = "unknown", 0.2, None
    if rel is not None:
        idx = [i for i in range(rel, min(tr.n, tr.idx_at(tr.t[rel] + 0.6))) if not math.isnan(tr.wy[i]) and not tr.cut[i]]
        if len(idx) >= 8:
            tt = tr.t[idx] - tr.t[rel]
            yy = tr.wy[idx]; xx = tr.wx[idx]
            k = min(6, len(idx) - 2)
            py = np.polyfit(tt[:k], yy[:k], 2); px = np.polyfit(tt[:k], xx[:k], 1)
            tol = max(3.0, 0.03 * tr.ah)
            for j in range(k, len(idx)):
                ry = yy[j] - np.polyval(py, tt[j]); rx = xx[j] - np.polyval(px, tt[j])
                if math.hypot(rx, ry) > tol:
                    contact = idx[j - 1]
                    break
            if contact is not None:
                near_def = dfd is not None and abs(tr.t[contact] - tr.t[dfd]) < 0.6
                outcome, oc_conf = "blocked", (0.55 if near_def else 0.35)
                events.append(_ev("ball_contact", tr, contact, t0, oc_conf, "parabola_break_after_release",
                                  defender_nearby=near_def))
            else:
                outcome, oc_conf = "not_blocked", 0.4
    events.append(_ev("block_outcome", tr, contact if contact is not None else (rel if rel is not None else s), t0, oc_conf,
                      "derived", outcome=outcome))
    seg = {"segment_id": seg_id, "start_frame": int(tr.idx_at(t0 - 1.0)), "end_frame": int(tr.idx_at(t0 + 1.2)),
           "shot_motion_start_pts_s": round(float(t0), 6), "ball_tracked_fraction": round(ball_cov, 2),
           "outcome": outcome, "n_events": len(events)}
    for ev in events:
        ev["segment_id"] = seg_id
    return seg, events


def extract(rows, ah):
    tr = Track(rows, ah)
    cands = find_candidates(tr)
    segs, events = [], []
    for k, c in enumerate(cands):
        s, ev = analyse_candidate(tr, c, k + 1)
        segs.append(s); events.extend(ev)
    stats = {"frames": tr.n, "ball_tracked_fraction": round(float(np.mean(~np.isnan(tr.by))), 3) if tr.n else 0,
             "scene_cuts": int(tr.cut.sum()), "candidates": len(cands)}
    return segs, events, stats
