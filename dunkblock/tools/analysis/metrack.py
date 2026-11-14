"""My-player tracker prototype (HANDOFF 0-4l): where is my defender relative to the shooter, also when they overlap?

metrack.py [-sheet out.jpg] <folder | mp4> ...     needs <name>.sim.csv (shotsim.py), <name>.pose.npz (poseclip.py)
                                                    writes <name>.me.csv next to the video

My player stands on a navy disc with a bright cyan ring. The defender is normally BEHIND the shooter (camera looks at
the basket), so the shooter's legs cover the middle of the disc and the app's `findMe` (a 16x6-cell window at least
half navy) fails exactly when the two are close. The left and right arcs of the cyan ring stay visible, so:
  new  = for 6 ring sizes, share of the left arc band and of the right arc band that is cyan (two box-filter passes
         per size on a half-resolution mask); score = sqrt(L * R) - share of cyan in the middle (rejects filled blobs
         such as the blue position tag). Best score >= MINC = "seen"; the ring size is the local scale (px per disc).
  track= causal: a detection further than GATE from the constant-velocity prediction with a score < 0.5 is not
         believed; without a detection the position is predicted for HOLD s ("pred", never "seen"), then "lost".
  old  = port of ShotWatcher.findMe (same as myring2.py) on the same scans, for comparison.
me.csv: t_s, state (seen|pred|lost|-), mx, my (px at 720p), a (ring half-width px), conf, sx, sy (shooter's feet px),
        old_x, old_y (-1 = not found).   Relative position in disc units: dx = (mx - sx) / a, dy = (my - sy) / (0.39 a).
"""
import sys, os, csv, glob
import numpy as np, cv2

SIZES = [34, 40, 46, 52, 60, 68]          # ring outer half-width in px at 720p
ASPECT = 0.39; MINC = 0.28; GATE = 70.0; HOLD = 0.30
GW, GH, NW, NH = 390, 180, 16, 6


def kernels():
    out = []
    for a in SIZES:
        ah = a / 2.0; bh = ah * ASPECT; h = int(bh + 2); w = int(ah + 2)
        yy, xx = np.mgrid[-h:h + 1, -w:w + 1]
        r = np.sqrt((xx / ah) ** 2 + (yy / bh) ** 2)
        band = (r <= 1.03) & (r >= 0.76)
        L = (band & (xx <= -0.45 * ah)).astype(np.float32); R = (band & (xx >= 0.45 * ah)).astype(np.float32)
        C = (r <= 0.5).astype(np.float32)
        out.append((a, L / L.sum(), R / R.sum(), C / C.sum()))
    return out


KER = kernels()


def hud(x, y):
    return (x < 0.36 and y < 0.50) or (x > 0.62 and y < 0.14) or (x > 0.74 and y > 0.40) or (x < 0.32 and y > 0.50) or y < 0.25


HUDH = None


def find_new(im, sx, sy):
    """im BGR 720p-scaled; sx, sy shooter feet px. -> (score, x, y, a) of the best ring in the search window or None."""
    global HUDH
    h, w = im.shape[:2]
    x0 = max(0, int(sx - 0.32 * w)); x1 = min(w, int(sx + 0.32 * w)); y0 = max(0, int(sy - 0.33 * h)); y1 = min(h, int(sy + 0.25 * h))
    if x1 - x0 < 160 or y1 - y0 < 80: return None
    c = im[y0:y1:2, x0:x1:2].astype(np.int16); b, g, r = c[..., 0], c[..., 1], c[..., 2]
    m = ((g >= 90) & (g <= 200) & (b > 165) & (b - r > 80)).astype(np.float32)
    if HUDH is None or HUDH.shape != (h // 2 + 1, w // 2 + 1):
        HUDH = np.array([[hud(x * 2.0 / w, y * 2.0 / h) for x in range(w // 2 + 1)] for y in range(h // 2 + 1)])
    m[HUDH[y0 // 2:y0 // 2 + m.shape[0], x0 // 2:x0 // 2 + m.shape[1]]] = 0
    if m.sum() < 25: return None
    best = None
    for a, L, R, C in KER:
        l = cv2.filter2D(m, -1, L, borderType=cv2.BORDER_CONSTANT); rr = cv2.filter2D(m, -1, R, borderType=cv2.BORDER_CONSTANT)
        s = np.sqrt(np.maximum(l * rr, 0)) - cv2.filter2D(m, -1, C, borderType=cv2.BORDER_CONSTANT)
        j = int(np.argmax(s)); v = float(s.flat[j])
        if best is None or v > best[0]: best = (v, x0 + 2 * (j % m.shape[1]), y0 + 2 * (j // m.shape[1]), a)
    return best


def find_old(im, sx, sy, dy):
    """ShotWatcher.findMe on the app's coarse grid; sx, sy, dy as screen fractions. -> (x, y) fractions or None."""
    g = cv2.resize(im, (GW, GH), interpolation=cv2.INTER_NEAREST).astype(np.int16)
    b, gg, r = g[..., 0], g[..., 1], g[..., 2]
    hm = np.array([[(x / GW < 0.30 and y / GH < 0.22) or (x / GW > 0.62 and y / GH < 0.14) or (x / GW > 0.74 and y / GH > 0.40) or (x / GW < 0.32 and y / GH > 0.50)
                    for x in range(GW)] for y in range(GH)]) if not hasattr(find_old, "hm") else find_old.hm
    find_old.hm = hm
    navy = (r < 75) & (gg < 70) & (b >= 25) & (b <= 120) & (b - r >= 8) & ~hm
    blue = (r < 120) & (gg >= 90) & (gg <= 200) & (b > 165) & (b - r > 80) & ~hm
    P = 2 * NW
    sn = cv2.integral(np.pad(navy.astype(np.uint8), P)); sb = cv2.integral(np.pad(blue.astype(np.uint8), P))
    xa = max(0, int((sx - 0.3) * GW)); xb = min(GW - NW, int((sx + 0.3) * GW)); ya = max(0, int((dy + 0.05) * GH)); yb = min(GH - NH, int(min(0.95, sy + 0.3) * GH))
    if xb <= xa or yb <= ya: return None
    Y, X = np.mgrid[ya:yb, xa:xb]

    def box(s, dx0, dy0, dx1, dy1):
        return (s[Y + P + dy1, X + P + dx1] - s[Y + P + dy0, X + P + dx1] - s[Y + P + dy1, X + P + dx0] + s[Y + P + dy0, X + P + dx0]).astype(np.int32)
    n = box(sn, 0, 0, NW, NH); l = box(sb, -5, -2, 3, NH + 2); rr = box(sb, NW - 3, -2, NW + 5, NH + 2); big = box(sn, -NW, -NH, 2 * NW, 2 * NH)
    ok = (n >= NW * NH / 2) & (l >= 3) & (rr >= 3) & (big <= 2.2 * NW * NH)
    if not ok.any(): return None
    sc = np.where(ok, n + np.minimum(l, 12) + np.minimum(rr, 12), -1); j = int(np.argmax(sc))
    return ((X.flat[j] + NW / 2) / GW, (Y.flat[j] + NH / 2) / GH)


def track(mp4, tiles=None):
    rows = list(csv.DictReader(open(mp4[:-4] + ".sim.csv")))
    z = np.load(mp4[:-4] + ".pose.npz"); off = z["off"]
    v = cv2.VideoCapture(mp4); n = 0; i = 0; N = min(len(rows), len(off))
    out = []; last_ring = (-9.0, -1.0); pos = None; vel = (0.0, 0.0); seen_t = -9.0; prev_t = 0.0
    want = {N // 3, 2 * N // 3} if tiles is not None else set()
    while i < N:
        good, im = v.read()
        if not good: break
        n += 1
        if n % 2 != 0: continue
        r = rows[i]; t = float(r["t_s"]); h, w = im.shape[:2]
        if h != 720: im = cv2.resize(im, (int(w * 720 / h), 720)); h, w = im.shape[:2]
        ry = float(r["ring_y"])
        if r["disc"] == "1" and ry >= 0: last_ring = (t, ry)
        rec = [t, "-", -1, -1, -1, 0.0, -1, -1, -1, -1]
        if r["disc"] == "1" and t - last_ring[0] < 0.15:
            sx = float(r["x"]) * w + (off[i] if off[i] > -900 else 0.0); sy = last_ring[1] * h
            rec[6] = round(sx, 1); rec[7] = round(sy, 1)
            d = find_new(im, sx, sy)
            o = find_old(im, sx / w, sy / h, float(r["y"]))
            if o: rec[8] = round(o[0] * w, 1); rec[9] = round(o[1] * h, 1)
            dt = t - prev_t
            pred = (pos[0] + vel[0] * dt, pos[1] + vel[1] * dt) if pos is not None and t - seen_t <= HOLD else None
            ok = d is not None and d[0] >= MINC
            if ok and pred is not None and d[0] < 0.5 and np.hypot(d[1] - pred[0], d[2] - pred[1]) > GATE: ok = False
            if ok:
                if pos is not None and t - seen_t <= HOLD and dt > 0:
                    vel = (0.7 * vel[0] + 0.3 * (d[1] - pos[0]) / dt, 0.7 * vel[1] + 0.3 * (d[2] - pos[1]) / dt)
                else: vel = (0.0, 0.0)
                pos = (float(d[1]), float(d[2])); seen_t = t
                rec[1:6] = ["seen", d[1], d[2], d[3], round(d[0], 3)]
            elif pred is not None:
                pos = pred; rec[1:6] = ["pred", round(pred[0], 1), round(pred[1], 1), -1, round(d[0], 3) if d else 0.0]
            else:
                pos = None; rec[1] = "lost"; rec[5] = round(d[0], 3) if d else 0.0
            if i in want:
                x0 = int(min(max(0, sx - 190), w - 380)); y0 = int(min(max(0, sy - 170), h - 250))
                c = im[y0:y0 + 250, x0:x0 + 380].copy()
                cv2.circle(c, (int(sx - x0), int(sy - y0)), 5, (0, 0, 255), 2)
                if rec[1] in ("seen", "pred"):
                    a = d[3] if rec[1] == "seen" else 46
                    cv2.ellipse(c, (int(rec[2] - x0), int(rec[3] - y0)), (a, int(a * ASPECT)), 0, 0, 360, (0, 255, 0) if rec[1] == "seen" else (0, 255, 255), 1)
                if o: cv2.rectangle(c, (int(o[0] * w - x0) - 32, int(o[1] * h - y0) - 12), (int(o[0] * w - x0) + 32, int(o[1] * h - y0) + 12), (255, 0, 255), 1)
                cv2.putText(c, "%s %.1f %s %.2f" % (os.path.basename(mp4)[:-4], t, rec[1], rec[5]), (4, 14), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1)
                tiles.append(c)
        prev_t = t; out.append(rec); i += 1
    with open(mp4[:-4] + ".me.csv", "w", newline="") as f:
        wr = csv.writer(f); wr.writerow(["t_s", "state", "mx", "my", "a", "conf", "sx", "sy", "old_x", "old_y"]); wr.writerows(out)
    return out


if __name__ == "__main__":
    a = sys.argv[1:]; sheet = None
    if a and a[0] == "-sheet": sheet = a[1]; a = a[2:]
    tiles = [] if sheet else None; tot = {}
    for x in a:
        for p in (sorted(glob.glob(os.path.join(x, "*.mp4"))) if os.path.isdir(x) else [x]):
            if not (os.path.exists(p[:-4] + ".sim.csv") and os.path.exists(p[:-4] + ".pose.npz")): continue
            o = [r for r in track(p, tiles) if r[1] != "-"]
            c = {s: sum(1 for r in o if r[1] == s) for s in ("seen", "pred", "lost")}; old = sum(1 for r in o if r[8] >= 0)
            both = [np.hypot(r[2] - r[8], r[3] - r[9]) for r in o if r[1] == "seen" and r[8] >= 0]
            jump = sum(1 for q, r in zip(o, o[1:]) if q[1] == "seen" and r[1] == "seen" and r[0] - q[0] < 0.03 and np.hypot(r[2] - q[2], r[3] - q[3]) > 30)
            for k, val in list(c.items()) + [("old", old), ("n", len(o)), ("jump", jump), ("agree", sum(1 for d in both if d <= 25)), ("both", len(both))]: tot[k] = tot.get(k, 0) + val
            print("%-22s scans with shooter %5d | new: seen %4.0f%% pred %3.0f%% lost %3.0f%% jumps>30px %2d | old findMe %4.0f%% | both found %4d, within 25 px %4d" % (
                os.path.basename(p)[:-4], len(o), 100.0 * c["seen"] / max(1, len(o)), 100.0 * c["pred"] / max(1, len(o)), 100.0 * c["lost"] / max(1, len(o)), jump,
                100.0 * old / max(1, len(o)), len(both), sum(1 for d in both if d <= 25)), flush=True)
    if tot:
        print("TOTAL scans %d | new seen %.1f%% pred %.1f%% lost %.1f%% jumps %d | old %.1f%% | both %d agree %d" % (
            tot["n"], 100.0 * tot["seen"] / tot["n"], 100.0 * tot["pred"] / tot["n"], 100.0 * tot["lost"] / tot["n"], tot["jump"], 100.0 * tot["old"] / tot["n"], tot["both"], tot["agree"]))
    if sheet and tiles:
        tiles = tiles[::max(1, len(tiles) // 30)][:30]
        while len(tiles) % 6: tiles.append(np.zeros_like(tiles[0]))
        cv2.imwrite(sheet, np.vstack([np.hstack(tiles[k:k + 6]) for k in range(0, len(tiles), 6)]), [cv2.IMWRITE_JPEG_QUALITY, 85])
