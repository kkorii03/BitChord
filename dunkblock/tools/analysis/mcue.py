"""What the screen shows before the ball leaves, on match shots, in units of the ball disc (HANDOFF 0-4w).

mcue.py <mp4> <name.clips.csv> <name.rel.csv from mrel.py> <name.key.csv from mrel.py -key> <out.npz> [-pre 1.2] [-post 0.15]
mcue.py -sum a.cue.npz ... [-img out.jpg]
For every shot with a release in the key (t0; doubtful ones skipped) and every video frame in [t0 - pre, t0 + post]
where the disc is tracked: the red outline (poseclip.outline) in a box around the disc, 10 R wide and 20 R high
(2 R above the disc centre to 18 R below; R = median radius of the ringed disc in the 0.6 s before t0), reduced to
40 x 80 cells of R / 4 (cell = share of outline pixels, 0..255).
The disc gives the scale: no guess of the shooter's size on screen (the match test of 0-4v had to guess it).
  npz: shot (name+k per row), t (s relative to t0), m (N x 80 x 40 uint8), R, dx, dy (disc centre - its position at t0, in R)
-sum: per shot and per 50 ms bin before t0: top = highest outline row under/around the disc, feet = lowest row with
outline (the floor ring), both in R below the disc centre.
"""
import sys, os, csv
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mrel, poseclip

GW, GH = 40, 80


def extract(mp4, clips, rel, keyp, outp, pre, post):
    S = {}
    for r in csv.DictReader(open(rel, encoding="utf-8")):
        S.setdefault(int(r["k"]), []).append((float(r["t_rel"]), float(r["r"]), float(r["ring"]), float(r["x"]), float(r["y"])))
    KEY = {int(r["k"]): r for r in csv.DictReader(open(keyp, encoding="utf-8"))}
    name = os.path.basename(clips).split(".")[0]
    v = cv2.VideoCapture(mp4); shot = []; T = []; M = []; RR = []; DX = []; DY = []
    for row in csv.DictReader(open(clips, encoding="utf-8")):
        k = int(row["k"]); ls = float(row["label_s"]); ref = ls if ls > 0 else float(row["shot_s"])
        kr = KEY.get(k)
        if not kr or not kr["release_rel"] or kr["src"].endswith("?"): continue
        t0 = float(kr["release_rel"])
        F = [f for f in S.get(k, []) if f[1] > 0]
        G = [f for f in F if t0 - 0.6 <= f[0] <= t0 + 0.05 and f[2] >= 0.3]      # the ringed disc just before the release
        if len(G) < 8: print(name, k, "disc not tracked before the release"); continue
        R = float(np.median([f[1] for f in G])); pos = {"%.3f" % f[0]: f for f in F if 0.6 * R <= f[1] <= 1.4 * R}
        x00, y00 = G[-1][3], G[-1][4]
        v.set(cv2.CAP_PROP_POS_MSEC, max(0.0, ref + t0 - pre - 1.0) * 1000); n = 0
        while v.grab():
            t = v.get(cv2.CAP_PROP_POS_MSEC) / 1000.0 - ref
            if t < t0 - pre: continue
            if t > t0 + post: break
            f = pos.get("%.3f" % t)
            if f is None: continue
            ok, im = v.retrieve()
            if not ok: break
            cx, cy = f[3], f[4]; h, w = im.shape[:2]
            x0 = int(round(cx - 5 * R)); x1 = int(round(cx + 5 * R)); y0 = int(round(cy - 2 * R)); y1 = int(round(cy + 18 * R))
            box = np.zeros((y1 - y0, x1 - x0), np.uint8)
            sx0 = max(0, x0); sx1 = min(w, x1); sy0 = max(0, y0); sy1 = min(h, y1)
            if sx1 > sx0 and sy1 > sy0: box[sy0 - y0:sy1 - y0, sx0 - x0:sx1 - x0] = poseclip.outline(im[sy0:sy1, sx0:sx1]) * 255
            shot.append("%s%d" % (name, k)); T.append(t - t0); M.append(cv2.resize(box, (GW, GH), interpolation=cv2.INTER_AREA))
            RR.append(R); DX.append((cx - x00) / R); DY.append((cy - y00) / R); n += 1
        print(name, k, "t0 %.3f R %.1f frames %d" % (t0, R, n)); sys.stdout.flush()
    np.savez_compressed(outp, shot=np.array(shot), t=np.array(T, np.float32), m=np.array(M, np.uint8), R=np.array(RR, np.float32),
                        dx=np.array(DX, np.float32), dy=np.array(DY, np.float32))


def rows_of(m):
    """top, feet (cells of R/4 below the box top; box top = disc centre - 2 R) of one silhouette. The disc itself
    (within 1.6 R of its centre) is blanked. top = first row with >= 2 cells of outline >= 60 within 2.5 R of the disc
    column; feet = last row with >= 4 such cells anywhere."""
    z = m.copy(); yy, xx = np.mgrid[0:GH, 0:GW]
    z[np.hypot(xx - GW / 2.0 + 0.5, yy - 8 + 0.5) <= 6.4] = 0
    on = z >= 60
    mid = on[:, GW // 2 - 10:GW // 2 + 10].sum(1); allc = on.sum(1)
    a = np.nonzero(mid >= 2)[0]; b = np.nonzero(allc >= 4)[0]
    return (a[0] if len(a) else -1), (b[-1] if len(b) else -1)


def summary(paths, img):
    BINS = np.arange(-0.9, 0.151, 0.05); tiles = []
    print("shot      R  " + " ".join("%5.2f" % b for b in BINS[:-1]) + "   (top of the outline, R below the disc centre; then disc rise in R)")
    ALL = []; ALLD = []
    for p in paths:
        d = np.load(p)
        for s in sorted(set(d["shot"].tolist()), key=lambda q: (q[0], int(q[1:]))):
            I = np.nonzero(d["shot"] == s)[0]; t = d["t"][I]
            top = np.array([rows_of(d["m"][i])[0] for i in I], np.float32); top = np.where(top >= 0, (top - 8) / 4.0, np.nan)
            dy = d["dy"][I]
            a = []; b = []; row = []
            for j in range(len(BINS) - 1):
                q = (t >= BINS[j]) & (t < BINS[j + 1])
                a.append(np.nanmedian(top[q]) if q.any() and np.isfinite(top[q]).any() else np.nan)
                b.append(-np.median(dy[q]) if q.any() else np.nan)
                if img is not None:
                    c = np.nonzero(q)[0]
                    row.append(cv2.resize(d["m"][I[c[len(c) // 2]]], (GW * 3, GH * 3), interpolation=cv2.INTER_NEAREST) if len(c) else np.zeros((GH * 3, GW * 3), np.uint8))
            ALL.append(a); ALLD.append(b)
            print("%-5s %5.1f  " % (s, d["R"][I[0]]) + " ".join("%5.1f" % x if np.isfinite(x) else "    ." for x in a))
            print("        rise " + " ".join("%5.1f" % x if np.isfinite(x) else "    ." for x in b))
            if img is not None:
                z = np.hstack(row); cv2.putText(z, s, (2, 14), cv2.FONT_HERSHEY_SIMPLEX, 0.5, 255, 1); tiles.append(z)
    A = np.array(ALL); D = np.array(ALLD)
    print("median top  " + " ".join("%5.1f" % x for x in np.nanmedian(A, 0)))
    print("median rise " + " ".join("%5.1f" % x for x in np.nanmedian(D, 0)))
    if img is not None and tiles:
        for i in range(0, len(tiles), 12): cv2.imwrite(img.replace(".jpg", "_%d.jpg" % (i // 12)), np.vstack(tiles[i:i + 12]))


def main():
    a = sys.argv[1:]
    if a[0] == "-sum":
        img = None; ps = []
        a = a[1:]
        while a:
            if a[0] == "-img": img = a[1]; a = a[2:]
            else: ps.append(a[0]); a = a[1:]
        summary(ps, img); return
    mp4, clips, rel, keyp, outp = a[:5]; pre = 1.2; post = 0.15; a = a[5:]
    while a:
        if a[0] == "-pre": pre = float(a[1]); a = a[2:]
        elif a[0] == "-post": post = float(a[1]); a = a[2:]
        else: raise SystemExit("unknown " + a[0])
    extract(mp4, clips, rel, keyp, outp, pre, post)


if __name__ == "__main__":
    main()
