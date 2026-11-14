import cv2, sys, numpy as np, glob, os, csv, re, bisect
GW, GH = 390, 180
NW, NH = 16, 6
def hud(x, y):
    return (x < 0.30 and y < 0.22) or (x > 0.62 and y < 0.14) or (x > 0.74 and y > 0.40) or (x < 0.32 and y > 0.50)
HUD = np.array([[hud(x / GW, y / GH) for x in range(GW)] for y in range(GH)])
def detect(img, sx, sy, dy):
    g = cv2.resize(img, (GW, GH), interpolation=cv2.INTER_NEAREST).astype(int)
    b, gg, r = g[:, :, 0], g[:, :, 1], g[:, :, 2]
    navy = (r < 75) & (gg < 70) & (b >= 25) & (b <= 120) & (b - r >= 8) & ~HUD
    blue = (r < 120) & (gg >= 90) & (gg <= 200) & (b > 165) & (b - r > 80) & ~HUD
    sn = cv2.integral(navy.astype(np.uint8)); sb = cv2.integral(blue.astype(np.uint8))
    def box(s, x0, y0, x1, y1):
        x0 = min(GW, max(0, x0)); y0 = min(GH, max(0, y0)); x1 = min(GW, max(0, x1)); y1 = min(GH, max(0, y1))
        return int(s[y1, x1] - s[y0, x1] - s[y1, x0] + s[y0, x0])
    best = None
    xa = max(0, int((sx - 0.3) * GW)); xb = min(GW - NW, int((sx + 0.3) * GW))
    ya = max(0, int((dy + 0.05) * GH)); yb = min(GH - NH, int(min(0.95, sy + 0.3) * GH))
    for y in range(ya, yb):
        for x in range(xa, xb):
            n = box(sn, x, y, x + NW, y + NH)
            if n < 0.5 * NW * NH: continue
            bl = box(sb, x - 5, y - 2, x + 3, y + NH + 2)
            br = box(sb, x + NW - 3, y - 2, x + NW + 5, y + NH + 2)
            big = box(sn, x - NW, y - NH, x + 2 * NW, y + 2 * NH)
            if bl < 3 or br < 3 or big > 2.2 * NW * NH: continue
            sc = n + min(bl, 12) + min(br, 12)
            if best is None or sc > best[0]: best = (sc, (x + NW / 2) / GW, (y + NH / 2) / GH, n, bl, br, big)
    return best
out = sys.argv[1]; tiles = []
for d in sys.argv[2:]:
    rows = list(csv.DictReader(open(d + r"\shot.csv"))); T = [float(r["t_s"]) for r in rows]
    for p in sorted(glob.glob(d + r"\frames\*.jpg")):
        t = float(re.search(r"_(\d+\.\d)s", p).group(1))
        i = bisect.bisect_left(T, t); cand = [rows[k] for k in range(max(0, i - 6), min(len(rows), i + 6)) if rows[k]["disc"] == "1"]
        if not cand: continue
        r0 = min(cand, key=lambda r: abs(float(r["t_s"]) - t))
        dx, dy, ry = float(r0["x"]), float(r0["y"]), float(r0["ring_y"])
        sx, sy = dx, (ry if ry >= 0 else dy + 0.25)
        img = cv2.imread(p); H, W = img.shape[:2]
        det = detect(img, sx, sy, dy)
        x0 = int(max(0, min(W - 0.5 * W, (sx - 0.25) * W))); y0 = int(max(0, min(H - 0.7 * H, (dy - 0.1) * H)))
        v = img[y0:y0 + int(0.7 * H), x0:x0 + int(0.5 * W)].copy()
        cv2.circle(v, (int(sx * W - x0), int(sy * H - y0)), 6, (0, 0, 255), 2)
        txt = "none"
        if det:
            px, py = det[1], det[2]
            cv2.rectangle(v, (int(px * W - x0) - 20, int(py * H - y0) - 9), (int(px * W - x0) + 20, int(py * H - y0) + 9), (0, 255, 0), 2)
            dist = (((px - sx) ** 2 + ((py - sy) * H / W) ** 2) ** 0.5)
            txt = f"d={dist:.3f} n{det[3]} l{det[4]} r{det[5]} g{det[6]}"
        cv2.putText(v, f"{t:.1f} {txt}", (4, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1)
        tiles.append(cv2.resize(v, (384, 248)))
print(len(tiles))
while len(tiles) % 5: tiles.append(np.zeros_like(tiles[0]))
cv2.imwrite(out, np.vstack([np.hstack(tiles[i:i + 5]) for i in range(0, len(tiles), 5)]), [cv2.IMWRITE_JPEG_QUALITY, 80])
