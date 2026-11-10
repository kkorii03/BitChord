import sys, glob, os, csv
# crouch.py <game folder> [S px] [WIN ms] [X px]
# Replay of the crouch cue (ShotWatcher.crouch, vc1060): the ball handler stands still, then his marker SINKS (knees
# bend) before the lift. Quick-release shooters (LaMelo Ball, vc1056 practice 144-208 s) let the ball go ~30 ms after
# the gap trigger, so the only usable moment is this sink, 0.45-0.22 s before that trigger.
# Marker y = centroid of the marker's disc-red pixels (ShotWatcher.findCentre, shot.csv columns yc / yc_n from vc1060),
# quiet to ~0.2 px, where the grid y (column y) moves in 4 px steps and cannot show a 13-18 px sink.
#   bundle from vc1060 on: reads yc from shot.csv (60 Hz) and also lists the app's own "crouch" events
#   older bundle:          computes the same centre on the colour clips (30 Hz, only around marks / triggers)
# Rule at scan t (px at 720p):
#   track unbroken for 0.7 s (no pause > 0.1 s, no step > 43 px down/up or 125 px sideways)
#   calm:  y range <= 9 px over [t-700, t-200] ms
#   sink:  y(t) - min(y over the last WIN ms) >= S, y(t) >= y(previous), y(t) >= calm median + X
#   marker whole: red pixel count within 0.8..1.25 of the calm mean; outline top standing (top_dy > 22 px)
#   1.5 s between fires
# Output: one line per fire with the nearest shot_trigger (ms, - = fire first), sink px, ms since the window minimum,
# calm range, top_dy. "none" lines = triggers with no fire in the 0.7 s before them.
g = sys.argv[1]
S = float(sys.argv[2]) if len(sys.argv) > 2 else 7.0
WIN = float(sys.argv[3]) / 1000 if len(sys.argv) > 3 else 0.17
X = float(sys.argv[4]) if len(sys.argv) > 4 else 0.0

def fires(P):
    """P = [(t_s, y_px, x_px, n or None, top_px or None)], time-sorted, valid centres only."""
    out = []; last = -9.0; r0 = 0
    for i, p in enumerate(P):
        if i and (p[0] - P[i - 1][0] > 0.1 or abs(p[1] - P[i - 1][1]) > 43 or abs(p[2] - P[i - 1][2]) > 125): r0 = i
        t = p[0]
        if i == r0 or t - P[r0][0] < 0.7: continue
        w = [q for q in P[r0:i + 1] if t - q[0] <= 0.7]
        calm = [q for q in w if t - q[0] >= 0.2]; rec = [q for q in w if t - q[0] <= WIN]
        if len(calm) < MINC or calm[-1][0] - calm[0][0] < 0.4: continue
        cy = sorted(q[1] for q in calm); med = cy[len(cy) // 2]
        if cy[-1] - cy[0] > 9: continue
        lo = min(rec, key=lambda q: q[1])
        if p[1] - lo[1] < S or p[1] < P[i - 1][1] or p[1] < med + X: continue
        if p[3] is not None:
            nm = sum(q[3] for q in calm) / len(calm)
            if not 0.8 * nm <= p[3] <= 1.25 * nm: continue
        if p[4] is not None and p[4] <= 22: continue
        if t - last <= 1.5: continue
        last = t
        out.append((t, p[1] - lo[1], (t - lo[0]) * 1000, cy[-1] - cy[0], p[1] - med, p[4]))
    return out

rows = list(csv.DictReader(open(os.path.join(g, "shot.csv"))))
ev = list(csv.DictReader(open(os.path.join(g, "shot_events.csv"))))
trig = [(float(e["t_s"]), e["detail"].split(" ")[0]) for e in ev if e["event"] == "shot_trigger"]
tracks = []
MINC = 10 if "yc" in rows[0] else 8      # calm samples needed: the app's 10 of ~30 at 60 Hz; 8 of ~15 on 30 Hz clips
if "yc" in rows[0]:
    P = [(float(r["t_s"]), float(r["yc"]) * 720, float(r["x"]) * 1560, float(r["yc_n"]), float(r["top_dy"]) * 720)
         for r in rows if r["disc"] == "1" and r["side"] != "off" and float(r["yc"]) >= 0]
    tracks.append(("shot.csv", P)); src = "shot.csv yc (60 Hz)"
else:
    import cv2, numpy as np
    src = "colour clips (30 Hz, around marks/triggers only)"
    for d in sorted(glob.glob(os.path.join(g, "colour", "colour_*"))):
        t0 = float(os.path.basename(d).rsplit("_", 1)[1][:-1]); P = []
        for r in csv.DictReader(open(os.path.join(d, "crops.csv"))):
            fp = os.path.join(d, r["file"])
            im = cv2.imdecode(np.fromfile(fp, dtype=np.uint8), cv2.IMREAD_COLOR) if os.path.exists(fp) else None
            if im is None: continue
            B, G, R = [im[:, :, i].astype(np.int32) for i in range(3)]
            red = (R > 160) & (G < 25) & (B >= 30) & (B <= 90) & (R - B > 95)
            _, mv, _, ml = cv2.minMaxLoc(cv2.boxFilter(red.astype(np.float32), -1, (16, 16), normalize=False, anchor=(0, 0)))
            if mv < 0.45 * 256: continue
            cx = (ml[0] // 4) * 4 + 8; cy = (ml[1] // 4) * 4 + 8; H, W = red.shape     # the app's 4 px grid centre
            bot = cy; y = cy
            while y <= min(H - 1, cy + 30):
                if red[y, max(0, cx - 10):min(W, cx + 11)].sum() >= 3: bot = y
                elif y > bot + 2: break
                y += 1
            ya = max(0, bot - 44); xa = max(0, cx - 40)
            ys, xs = np.nonzero(red[ya:bot + 1, xa:min(W, cx + 41)])
            if len(ys) < 150: continue
            out = (R >= 140) & (G * 100 <= R * 28) & (B * 100 <= R * 55) & (R - G >= 90)
            top = -1
            for y in range(bot + 4, min(H, bot + 91)):
                if out[y, max(0, cx - 26):min(W, cx + 27)].sum() >= 3: top = y - bot; break
            P.append((t0 + float(r["t_rel_ms"]) / 1000, ya + ys.mean() + int(r["y0"]), xa + xs.mean() + int(r["x0"]), len(ys), top))
        if P: tracks.append((os.path.basename(d)[7:], P))
print("== %s  source: %s  S=%.1f px WIN=%.0f ms X=%.1f px  triggers=%d" % (g[-22:], src, S, WIN * 1000, X, len(trig)))
allf = []
for name, P in tracks:
    for f in fires(P):
        allf.append(f[0])
        nt = min(trig, key=lambda x: abs(x[0] - f[0])) if trig else None
        near = "%+5.0f ms to %s trigger %.2f" % ((f[0] - nt[0]) * 1000, nt[1], nt[0]) if nt and abs(nt[0] - f[0]) < 1.5 else "no trigger within 1.5 s"
        print("  fire %7.2f  %-36s sink=%.1f in %3.0f ms calm=%.1f above_med=%+.1f top=%s  [%s]" % (
            f[0], near, f[1], f[2], f[3], f[4], "%.0f" % f[5] if f[5] is not None else "?", name))
for t, k in trig:
    if not any(0 <= t - f <= 0.7 for f in allf): print("  none before %s trigger %.2f" % (k, t))
for e in ev:
    if e["event"] == "crouch": print("  app crouch %7.2f %s" % (float(e["t_s"]), e["detail"]))
