import cv2, sys, numpy as np, glob, re, csv, bisect
# For each colour frame (frames\*.jpg): red-pixel mask with the app's rule, WITHOUT the HUD mask. Report the disc seen by the
# app at that time (shot.csv) and the best red window inside each HUD zone (buttons / joystick), to see whether foot rings
# under the HUD are visible and whether the HUD itself has red.
d = sys.argv[1]; tmin = float(sys.argv[2]); save = sys.argv[3] if len(sys.argv) > 3 else None
rows = [(float(r["t_s"]), r["side"], r["disc"] == "1", float(r["x"]), float(r["y"]), float(r["ring_y"])) for r in csv.DictReader(open(d + r"\shot.csv"))]
ts = [r[0] for r in rows]
def red(img):
    b, g, r = img[:, :, 0].astype(int), img[:, :, 1].astype(int), img[:, :, 2].astype(int)
    return ((r > 160) & (g < 25) & (b >= 30) & (b <= 90) & (r - b > 95)).astype(np.uint8)
def red_loose(img):  # JPEG-tolerant
    b, g, r = img[:, :, 0].astype(int), img[:, :, 1].astype(int), img[:, :, 2].astype(int)
    return ((r > 150) & (g < 60) & (b >= 20) & (b <= 110) & (r - b > 80) & (r - g > 100)).astype(np.uint8)
picked = []
for p in sorted(glob.glob(d + r"\frames\*.jpg")):
    t = float(re.search(r"_(\d+\.\d)s", p).group(1))
    if t < tmin: continue
    i = min(bisect.bisect_left(ts, t), len(rows) - 1); r = rows[i]
    img = cv2.imread(p); H, W = img.shape[:2]
    m = red_loose(img); k = max(3, round(0.0104 * W)); ker = np.ones((k, k), np.float32) / (k * k)
    f = cv2.filter2D(m.astype(np.float32), -1, ker)
    zones = {"btn": (int(.74 * W), W, int(.40 * H), int(.88 * H)), "joy": (0, int(.32 * W), int(.50 * H), int(.88 * H))}
    s = "%7.1f %s disc=%s x=%.2f y=%.2f ring=%.2f |" % (t, r[1], "D" if r[2] else "-", r[3], r[4], r[5])
    for name, (x0, x1, y0, y1) in zones.items():
        z = f[y0:y1, x0:x1]; yy, xx = np.unravel_index(np.argmax(z), z.shape)
        s += " %s best=%.2f at (%.2f,%.2f) n=%d |" % (name, z.max(), (x0 + xx) / W, (y0 + yy) / H, int(m[y0:y1, x0:x1].sum()))
    print(s)
    picked.append((t, p))
