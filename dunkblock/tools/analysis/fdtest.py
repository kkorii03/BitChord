"""Does the app's findDisc keep the ball disc in the second before a match release? And a ring-checked variant.

fdtest.py <mp4> <name.clips.csv> <name.rel.csv> <name.key.csv> <out.csv> [-loose] [-pre 1.0] [-h 720]
Every video frame in [release - pre, release] of every shot with a firm release is reduced to the app's capture size
(1560 x 720 on the S24 Ultra, -h 540 = 1170 x 540) and run through
  A = a port of ShotWatcher.findDisc (390 x 180 grid, HUD mask, 4 x 4 cell window, fill >= 0.45,
      second <= 0.6 fill or the stacked foot-ring exception, the "ring alone" veto)
  B = a port of ShotWatcher.findDisc2 (shadow, vc1088): the 5 strongest windows with fill >= 0.45 (apart by > 2 k)
      are candidates; for each the centroid and pixel count of red in a box of +-16 px (at 540p) give centre and
      radius; ring = share of 24 directions where a grey pixel (all channels >= 150, spread <= 45) lies within 5 px
      outside the last red pixel of the ray; the disc is the candidate with the highest ring >= RING_MIN. No rule
      about other red on the screen.
truth = the full-resolution track of mrel.py (rel.csv) at the same frame; "right" = within 14 px (at 540p) of it.
-loose: disc red as mrel.py reads it from video (the codec shifts colours) instead of the app's thresholds.
out.csv: shot, t (s before the release), truth_x, truth_y, a_found, a_x, a_y, a_fill, a_second, b_found, b_x, b_y, b_ring, b_n, h
-sum a.csv ...: seen / right shares, whole second and the window -0.35..-0.10 s.
"""
import sys, os, csv, math
import numpy as np, cv2

FOUND_FILL = 0.45; RING_MIN = 0.4
ANG = [(math.cos(a), math.sin(a)) for a in np.linspace(0, 2 * math.pi, 24, endpoint=False)]


def setup(h):
    """Capture size and grid as ShotWatcher.ensureGrid makes them (width = h * 13 / 6, the S24 Ultra's 3120 x 1440)."""
    global W, H, S, K, GW, GH, U, HUD, ACTIVE
    H = h; W = h * 13 // 6; S = max(2, W // 390); GW, GH = W // S, H // S; K = max(3, int(round(0.0104 * W / S))); U = H / 540.0
    gx = np.arange(GW) * S; gy = np.arange(GH) * S
    HUD = ((gx[None, :] < 0.30 * W) & (gy[:, None] < 0.22 * H)) | ((gx[None, :] > 0.62 * W) & (gy[:, None] < 0.14 * H)) | \
          ((gx[None, :] > 0.74 * W) & (gy[:, None] > 0.40 * H)) | ((gx[None, :] < 0.32 * W) & (gy[:, None] > 0.50 * H))
    ACTIVE = np.zeros((GH, 1), bool); ACTIVE[int(0.06 * GH):int(0.88 * GH)] = True


setup(720)


def red(c, loose):
    b, g, r = [c[..., i].astype(np.int16) for i in range(3)]
    if loose: return (r >= 135) & (g <= 75) & (b >= 10) & (b <= 125) & (r - g >= 90)
    return (r > 160) & (g < 25) & (b >= 30) & (b <= 90) & (r - b > 95)


class A:
    def __init__(self): self.lastRing = -9.0; self.ringYLast = -1.0; self.ringX = -1.0

    def find(self, win, t):
        """win = window sums (GH-K+1, GW-K+1). Returns found, x, y (fractions), fill, second."""
        by, bx = np.unravel_index(int(np.argmax(win)), win.shape); best = int(win[by, bx])
        w2 = win.copy(); w2[max(0, by - 2 * K):by + 2 * K + 1, max(0, bx - 2 * K):bx + 2 * K + 1] = 0
        sy, sx = np.unravel_index(int(np.argmax(w2)), w2.shape); sec = int(w2[sy, sx])
        fill = best / float(K * K); second = sec / float(K * K)
        bX = (bx + K / 2.0) * S / W; bY = (by + K / 2.0) * S / H; sX = (sx + K / 2.0) * S / W; sY = (sy + K / 2.0) * S / H
        ringY = -1.0; ok = fill >= FOUND_FILL and second <= 0.6 * fill; dX, dY = bX, bY
        if not ok and fill >= FOUND_FILL and abs(bX - sX) <= 0.08 and 0.12 <= abs(bY - sY) <= 0.45:
            if bY < sY: ok = True; ringY = sY; self.ringX = sX
            elif second >= FOUND_FILL: ok = True; dX, dY = sX, sY; ringY = bY; self.ringX = bX
        if ringY >= 0: self.lastRing = t
        elif ok and t - self.lastRing < 1.5 and abs(dY - self.ringYLast) < 0.06 and abs(dX - self.ringX) < 0.08: ok = False
        if ringY >= 0: self.ringYLast = ringY
        return ok, dX, dY, fill, second


def find_b(win, m, im):
    """m = red mask at capture resolution, im = capture image. Returns found, x, y (capture px), ring, n."""
    w2 = win.copy(); best = (False, -1.0, -1.0, 0.0, 0); half = int(round(16 * U)); out = max(2, int(round(5 * U)))
    for _ in range(5):
        by, bx = np.unravel_index(int(np.argmax(w2)), w2.shape)
        if w2[by, bx] < FOUND_FILL * K * K: break
        w2[max(0, by - 2 * K):by + 2 * K + 1, max(0, bx - 2 * K):bx + 2 * K + 1] = 0
        cx = int((bx + K / 2.0) * S); cy = int((by + K / 2.0) * S)
        for _ in range(2):                                   # centroid of the red around the window, twice
            x0 = max(0, cx - half); y0 = max(0, cy - half); q = m[y0:cy + half + 1, x0:cx + half + 1]; n = int(q.sum())
            if n < 20 * U * U: break
            ys, xs = np.nonzero(q); cx = int(round(x0 + xs.mean())); cy = int(round(y0 + ys.mean()))
        if n < int(60 * U * U): continue
        R = min(16.0 * U, max(6.0 * U, math.sqrt(n / math.pi))); hit = 0; rmax = int(1.6 * R + 2 * U)
        for ca, sa in ANG:
            e = 0                                            # last red pixel on this ray = the rim of the disc
            for r in range(3, rmax + 1):
                x = int(round(cx + r * ca)); y = int(round(cy + r * sa))
                if not (0 <= x < W and 0 <= y < H): break
                if m[y, x]: e = r
            if e == 0: continue
            for r in range(e + 1, e + out + 1):              # the grey ring sits right outside it
                x = int(round(cx + r * ca)); y = int(round(cy + r * sa))
                if not (0 <= x < W and 0 <= y < H): break
                p = im[y, x]; lo = int(p.min())
                if lo >= 150 and int(p.max()) - lo <= 45: hit += 1; break
        ring = hit / 24.0
        if ring >= RING_MIN and ring > best[3]: best = (True, float(cx), float(cy), ring, n)
    return best


def run(mp4, clips, rel, keyp, outp, loose, pre):
    TR = {}
    for r in csv.DictReader(open(rel, encoding="utf-8")):
        if float(r["r"]) > 0 and float(r["ring"]) >= 0.3: TR[(int(r["k"]), r["t_rel"])] = (float(r["x"]) * H / 1080.0, float(r["y"]) * H / 1080.0)
    KEY = {int(r["k"]): r for r in csv.DictReader(open(keyp, encoding="utf-8"))}
    name = os.path.basename(clips).split(".")[0]; v = cv2.VideoCapture(mp4)
    out = csv.writer(open(outp, "w", newline="")); out.writerow("shot,t,truth_x,truth_y,a_found,a_x,a_y,a_fill,a_second,b_found,b_x,b_y,b_ring,b_n,h".split(","))
    for row in csv.DictReader(open(clips, encoding="utf-8")):
        k = int(row["k"]); ls = float(row["label_s"]); ref = ls if ls > 0 else float(row["shot_s"]); kr = KEY.get(k)
        if not kr or not kr["release_rel"] or kr["src"].endswith("?"): continue
        t0 = float(kr["release_rel"]); a = A(); n = 0
        v.set(cv2.CAP_PROP_POS_MSEC, max(0.0, ref + t0 - pre - 1.0) * 1000)
        while v.grab():
            t = v.get(cv2.CAP_PROP_POS_MSEC) / 1000.0 - ref
            if t < t0 - pre: continue
            if t > t0: break
            ok, im = v.retrieve()
            if not ok: break
            im = cv2.resize(im, (W, H), interpolation=cv2.INTER_AREA); m = red(im, loose)
            g = m[::S, ::S][:GH, :GW] & ~HUD & ACTIVE
            sat = np.zeros((GH + 1, GW + 1), np.int32); sat[1:, 1:] = g.cumsum(0).cumsum(1)
            win = sat[K:, K:] - sat[:-K, K:] - sat[K:, :-K] + sat[:-K, :-K]
            fa = a.find(win, t); fb = find_b(win, m, im); tr = TR.get((k, "%.3f" % t), (-1, -1))
            out.writerow(["%s%d" % (name, k), "%.3f" % (t - t0), "%.0f" % tr[0], "%.0f" % tr[1], int(fa[0]), "%.0f" % (fa[1] * W), "%.0f" % (fa[2] * H),
                          "%.2f" % fa[3], "%.2f" % fa[4], int(fb[0]), "%.0f" % fb[1], "%.0f" % fb[2], "%.2f" % fb[3], fb[4], H]); n += 1
        print(name, k, n, "frames"); sys.stdout.flush()


def summary(paths):
    tot = {}
    for p in paths:
        for r in csv.DictReader(open(p)):
            t = float(r["t"]); has = float(r["truth_x"]) >= 0
            for z in ("all", "win") if -0.35 <= t <= -0.10 else ("all",):
                c = tot.setdefault((r["shot"], z), [0, 0, 0, 0, 0, 0, 0])      # frames, truth, a seen, a right, b seen, b right, a or b
                c[0] += 1; c[1] += has
                for j, d in ((2, "a"), (4, "b")):
                    if r[d + "_found"] == "1":
                        c[j] += 1
                        if has and math.hypot(float(r[d + "_x"]) - float(r["truth_x"]), float(r[d + "_y"]) - float(r["truth_y"])) <= 14 * float(r["h"]) / 540: c[j + 1] += 1
    shots = sorted({s for s, _ in tot}, key=lambda q: (q[0], int(q[1:])))
    print("shot  | last second: truth  A seen/right  B seen/right | -0.35..-0.10: truth  A seen/right  B seen/right   (%)")
    good = {"a": 0, "b": 0}
    for s in shots:
        c = tot[(s, "all")]; w = tot.get((s, "win"), [1, 0, 0, 0, 0, 0, 0]); f = lambda x, n: 100 * x // max(1, n)
        print("%-5s | %3d  %3d/%3d  %3d/%3d | %3d  %3d/%3d  %3d/%3d" % (s, f(c[1], c[0]), f(c[2], c[0]), f(c[3], c[0]), f(c[4], c[0]), f(c[5], c[0]),
                                                                    f(w[1], w[0]), f(w[2], w[0]), f(w[3], w[0]), f(w[4], w[0]), f(w[5], w[0])))
        good["a"] += w[3] >= 0.73 * w[0]; good["b"] += w[5] >= 0.73 * w[0]
    A_ = np.array([tot[(s, "all")] for s in shots]).sum(0); B_ = np.array([tot.get((s, "win"), [0] * 7) for s in shots]).sum(0)
    for nm, c in (("last second", A_), ("-0.35..-0.10", B_)):
        print("%s: frames %d, truth %.0f%% | A seen %.0f%% right %.0f%% | B seen %.0f%% right %.0f%%" % (nm, c[0], 100.0 * c[1] / c[0], 100.0 * c[2] / c[0], 100.0 * c[3] / c[0], 100.0 * c[4] / c[0], 100.0 * c[5] / c[0]))
    print("shots with the disc right in >= 73%% of -0.35..-0.10: A %d, B %d of %d" % (good["a"], good["b"], len(shots)))


def main():
    a = sys.argv[1:]
    if a[0] == "-sum": summary(a[1:]); return
    loose = "-loose" in a; pre = float(a[a.index("-pre") + 1]) if "-pre" in a else 1.0
    if "-h" in a: setup(int(a[a.index("-h") + 1]))
    run(a[0], a[1], a[2], a[3], a[4], loose, pre)


if __name__ == "__main__":
    main()
