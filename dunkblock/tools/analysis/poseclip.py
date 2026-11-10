"""Shooter silhouette per scan, for the image-based shot recogniser test (HANDOFF 0-6 "0-아침").

poseclip.py <folder | mp4> ...      needs <name>.sim.csv (shotsim.py); writes <name>.pose.npz next to the video

For every scan of shotsim (every 2nd video frame) where the red marker and the foot ring are known, the red outline of
the opponent (the same colour test as ShotWatcher.findTop) is cut out of a box anchored at the FEET: 300 px wide around
the marker x, from 300 px above the foot ring to 12 px below it (at 720p), and reduced to 75 x 78 cells of 4 px
(cell value = share of outline pixels, 0..255). The marker sits on the ball and bounces with the dribble, the foot ring
does not, so the feet are the anchor. `off` = x of the outline's lower half relative to the marker (px), for centring.
  npz: t (s, every scan), ok (bool), m (N x 78 x 75 uint8), off (px, -999 = unknown), gap (ring_y - marker y, or -1)
"""
import sys, os, csv, glob
import numpy as np, cv2

CW, CH, CELL = 75, 78, 4           # cells; box = 300 x 312 px at 720p
UP, DOWN, HALF = 300, 12, 150


def outline(px):
    b = px[..., 0].astype(np.int32); g = px[..., 1].astype(np.int32); r = px[..., 2].astype(np.int32)
    return (r >= 140) & (g * 100 <= r * 28) & (b * 100 <= r * 55) & (r - g >= 90)


def extract(mp4):
    rows = list(csv.DictReader(open(mp4[:-4] + ".sim.csv")))
    v = cv2.VideoCapture(mp4); n = 0; i = 0
    N = len(rows)
    t = np.zeros(N, np.float32); ok = np.zeros(N, bool); m = np.zeros((N, CH, CW), np.uint8)
    off = np.full(N, -999.0, np.float32); gap = np.full(N, -1.0, np.float32)
    last = (-9.0, -1.0); bad = 0
    while i < N:
        good, im = v.read()
        if not good: break
        ts = v.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
        n += 1
        if n % 2 != 0: continue
        r = rows[i]; t[i] = float(r["t_s"])
        if abs(t[i] - ts) > 0.002: bad += 1
        h, w = im.shape[:2]; u = h / 720.0
        ry = float(r["ring_y"])
        if r["disc"] == "1":
            if ry >= 0: last = (ts, ry)
            if ts - last[0] < 0.15:
                cx = int(float(r["x"]) * w); fy = int(last[1] * h)
                gap[i] = last[1] - float(r["y"])
                x0 = cx - int(HALF * u); x1 = cx + int(HALF * u); y0 = fy - int(UP * u); y1 = fy + int(DOWN * u)
                box = np.zeros((y1 - y0, x1 - x0), np.uint8)
                sx0 = max(0, x0); sx1 = min(w, x1); sy0 = max(0, y0); sy1 = min(h, y1)
                if sx1 > sx0 and sy1 > sy0:
                    box[sy0 - y0:sy1 - y0, sx0 - x0:sx1 - x0] = outline(im[sy0:sy1, sx0:sx1]) * 255
                    m[i] = cv2.resize(box, (CW, CH), interpolation=cv2.INTER_AREA)
                    low = box[int(130 * u):int(300 * u)]           # hips and legs: 170 px above the ring to the ring
                    cols = low.sum(0).astype(np.float64)
                    if cols.sum() >= 30 * 255:
                        off[i] = float((cols * np.arange(len(cols))).sum() / cols.sum() - HALF * u) / u
                        ok[i] = True
        i += 1
    np.savez_compressed(mp4[:-4] + ".pose.npz", t=t[:i], ok=ok[:i], m=m[:i], off=off[:i], gap=gap[:i])
    return i, int(ok[:i].sum()), bad


if __name__ == "__main__":
    for a in sys.argv[1:]:
        for p in (sorted(glob.glob(os.path.join(a, "*.mp4"))) if os.path.isdir(a) else [a]):
            if not os.path.exists(p[:-4] + ".sim.csv"): continue
            out = p[:-4] + ".pose.npz"
            if os.path.exists(out) and os.path.getmtime(out) >= os.path.getmtime(p): continue
            n, k, bad = extract(p)
            print("%-22s scans %5d, with silhouette %5d, time mismatches %d" % (os.path.basename(p), n, k, bad), flush=True)
