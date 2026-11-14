"""Match recording scan (HANDOFF 0-4t): where am I, and which opponent does the game mark as my matchup?

mscan.py <colour match mp4> [-hz 10] [-sheet out.jpg t1,t2,...] [-csv out.csv]

Seen in the user's colour match recordings (11-11, 2340x1080): my player stands on a navy disc with a cyan ring;
every opponent is drawn dark with a bright red outline; on defence ONE opponent has a translucent grey-red fan on the
floor at his feet with a bright red rim = the game's "your matchup" marker (checked on two matches, HANDOFF 0-4t;
on offence the same wedge is drawn at MY feet).
  me   = best cyan ring over the whole court (metrack.py's arc score, global search)
  fan  = connected area of the fan fill colour (court teal blended with red: R, G, B all near 85-100) whose border
         is to a good part bright red, lying on the floor (wider than tall)
csv: t_s, me_x, me_y, me_conf, fan_x, fan_y, fan_area, fan_rim, n_fans      (px at 1170x540; -1 = not found)
"""
import sys, os, csv
import numpy as np, cv2

W, H = 1170, 540
SIZES = [22, 26, 30, 34, 39, 44, 50]         # ring outer width in px on the half-size (585 wide) mask
ASPECT = 0.39


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
YY, XX = np.mgrid[0:H, 0:W]
FX = XX / float(W); FY = YY / float(H)
HUD = ((FX < 0.25) & (FY < 0.30)) | ((FX > 0.60) & (FY < 0.14)) | ((FX > 0.70) & (FY > 0.36)) | ((FX < 0.26) & (FY > 0.52) & (FY < 0.95)) | (FY < 0.30)


def find_ring(m):
    """m: float mask at 1170x540. Best floor ring (left and right arcs, empty middle) on the half-size mask."""
    if m.sum() < 25: return None
    m = cv2.resize(m, (W // 2, H // 2), interpolation=cv2.INTER_AREA); best = None
    for a, L, R, C in KER:
        l = cv2.filter2D(m, -1, L, borderType=cv2.BORDER_CONSTANT); rr = cv2.filter2D(m, -1, R, borderType=cv2.BORDER_CONSTANT)
        s = np.sqrt(np.maximum(l * rr, 0)) - cv2.filter2D(m, -1, C, borderType=cv2.BORDER_CONSTANT)
        j = int(np.argmax(s)); v = float(s.flat[j])
        if best is None or v > best[0]: best = (v, 2 * (j % (W // 2)), 2 * (j // (W // 2)), 2 * a)
    return best


def find_me(im):
    b, g, r = [im[..., k].astype(np.int16) for k in range(3)]
    return find_ring(((g >= 90) & (g <= 215) & (b > 165) & (b - r > 80) & ~HUD).astype(np.float32))


def find_ball(im):
    """The ball handler's red floor ring."""
    b, g, r = [im[..., k].astype(np.int16) for k in range(3)]
    return find_ring(((r >= 150) & (g <= 60) & (b <= 100) & ~HUD).astype(np.float32))


K3 = np.ones((3, 3), np.uint8)


def find_fans(im):
    b, g, r = [im[..., k].astype(np.int16) for k in range(3)]
    fill = ((r >= 62) & (r <= 125) & (g >= 60) & (g <= 105) & (b >= 78) & (b <= 125) & (np.abs(r - g) <= 28) & (b - g >= 0) & (b - g <= 40) & ~HUD).astype(np.uint8)
    red = ((r >= 125) & (g <= 45) & (b <= 90)).astype(np.uint8)
    fill = cv2.morphologyEx(fill, cv2.MORPH_OPEN, K3)
    n, lab, st, cen = cv2.connectedComponentsWithStats(fill, 8)
    out = []
    for i in range(1, n):
        x, y, w, h, a = st[i]
        if a < 120 or a > 4500 or w < 1.2 * h or h < 6: continue
        x0 = max(0, x - 3); y0 = max(0, y - 3); x1 = min(W, x + w + 3); y1 = min(H, y + h + 3)
        c = (lab[y0:y1, x0:x1] == i).astype(np.uint8)
        ring = cv2.dilate(c, K3, iterations=3) - c
        k = int(ring.sum())
        if k == 0: continue
        rim = float((ring & red[y0:y1, x0:x1]).sum()) / k
        if rim < 0.22 or a < 0.45 * w * h * 0.5: continue
        out.append((a * rim, float(cen[i][0]), float(cen[i][1]), int(a), round(rim, 2), (x, y, w, h)))
    out.sort(reverse=True)
    return out


TAGHUD = ((FX < 0.25) & (FY < 0.30)) | (FY < 0.20) | ((FX > 0.72) & (FY > 0.40))
TAGS = {        # position tag under every player (both teams): colour box, measured 11-11 (R, G, B ranges)
    "SF": ((185, 235), (95, 145), (0, 50)), "C": ((172, 215), (0, 45), (0, 55)), "PF": ((10, 90), (198, 235), (85, 140)),
    "SG": ((105, 180), (0, 45), (150, 225)), "PG": ((0, 70), (110, 160), (160, 212))}


def find_tags(im):
    b, g, r = [im[..., k].astype(np.int16) for k in range(3)]; out = []
    for name, (rr, gg, bb) in TAGS.items():
        m = ((r >= rr[0]) & (r <= rr[1]) & (g >= gg[0]) & (g <= gg[1]) & (b >= bb[0]) & (b <= bb[1]) & ~TAGHUD).astype(np.uint8)
        if m.sum() < 80: continue
        n, lab, st, cen = cv2.connectedComponentsWithStats(m, 8)
        for i in range(1, n):
            x, y, w, h, a = st[i]
            if 90 <= a <= 700 and 1.2 * h <= w <= 3.6 * h and 7 <= h <= 24 and a >= 0.55 * w * h: out.append((name, round(float(cen[i][0]), 1), round(float(cen[i][1]), 1)))
    return out


def frames(mp4, hz):
    v = cv2.VideoCapture(mp4); nxt = 0.0
    while True:
        if not v.grab(): break
        t = v.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
        if t + 1e-6 < nxt: continue
        nxt = t + 1.0 / hz
        ok, im = v.retrieve()
        if not ok: break
        yield t, cv2.resize(im, (W, H), interpolation=cv2.INTER_AREA)


def at(mp4, t):
    v = cv2.VideoCapture(mp4); v.set(cv2.CAP_PROP_POS_MSEC, t * 1000); ok, im = v.read()
    return cv2.resize(im, (W, H), interpolation=cv2.INTER_AREA) if ok else None


def draw(im, me, fans, t):
    c = im.copy()
    if me and me[0] >= 0.28: cv2.ellipse(c, (me[1], me[2]), (me[3] // 2, int(me[3] * ASPECT / 2)), 0, 0, 360, (0, 255, 0), 2)
    bl = find_ball(im)
    if bl and bl[0] >= 0.28: cv2.ellipse(c, (bl[1], bl[2]), (bl[3] // 2, int(bl[3] * ASPECT / 2)), 0, 0, 360, (255, 0, 255), 2); cv2.putText(c, "ball %.2f" % bl[0], (bl[1] - 30, bl[2] + 30), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 0, 255), 1)
    for k, f in enumerate(fans):
        x, y, w, h = f[5]; cv2.rectangle(c, (x - 2, y - 2), (x + w + 2, y + h + 2), (0, 255, 255) if k == 0 else (255, 255, 255), 2)
        cv2.putText(c, "%d %.2f" % (f[3], f[4]), (x, y - 5), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1)
    for name, x, y in find_tags(im):
        cv2.rectangle(c, (int(x) - 16, int(y) - 9), (int(x) + 16, int(y) + 9), (255, 255, 255), 1); cv2.putText(c, name, (int(x) - 14, int(y) - 12), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 2)
    cv2.putText(c, "%.1f me %.2f fans %d" % (t, me[0] if me else 0, len(fans)), (W // 2 - 90, 20), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 255), 2)
    return c


if __name__ == "__main__":
    a = sys.argv[1:]; mp4 = a[0]
    hz = float(a[a.index("-hz") + 1]) if "-hz" in a else 10.0
    if "-sheet" in a:
        out = a[a.index("-sheet") + 1]; ts = [float(x) for x in a[a.index("-sheet") + 2].split(",")]; tiles = []
        for t in ts:
            im = at(mp4, t)
            if im is not None: tiles.append(draw(im, find_me(im), find_fans(im), t))
        while len(tiles) % 2: tiles.append(np.zeros_like(tiles[0]))
        cv2.imwrite(out, np.vstack([np.hstack(tiles[k:k + 2]) for k in range(0, len(tiles), 2)]), [cv2.IMWRITE_JPEG_QUALITY, 88])
    else:
        out = a[a.index("-csv") + 1] if "-csv" in a else mp4[:-4] + ".mscan.csv"
        with open(out, "w", newline="") as f:
            wr = csv.writer(f); wr.writerow(["t_s", "me_x", "me_y", "me_conf", "fan_x", "fan_y", "fan_area", "fan_rim", "n_fans", "tags", "ball_x", "ball_y", "ball_conf"]); n = 0
            for t, im in frames(mp4, hz):
                me = find_me(im); fans = find_fans(im); bl = find_ball(im); n += 1
                wr.writerow([round(t, 3)] + ([me[1], me[2], round(me[0], 3)] if me else [-1, -1, 0]) + ([round(fans[0][1], 1), round(fans[0][2], 1), fans[0][3], fans[0][4]] if fans else [-1, -1, 0, 0]) + [len(fans), ";".join("%s:%.0f:%.0f" % q for q in find_tags(im))] + ([bl[1], bl[2], round(bl[0], 3)] if bl else [-1, -1, 0]))
                if n % 200 == 0: print(".", end="", flush=True)
        print("\n%d scans -> %s" % (n, out))
