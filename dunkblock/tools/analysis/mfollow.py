"""Who is my matchup? The in-app version, replayed on a colour match recording (HANDOFF 0-4t).

mfollow.py <colour match mp4> -csv out.csv [-hz 10] [-t0 s] [-t1 s]

Everything here is meant to be ported 1:1 to Kotlin (ShotWatcher style: point-sampled grid, integral images, small
windows at capture resolution), so no connected components and no big filters:
  grid   = the 1170 x 540 capture, every 3rd pixel (390 x 180 cells)
  side   = ShotWatcher.readSide (block button look): DEF / OFF / ?
  tags   = position boxes under every player: per colour class, 8 x 4 cell windows with >= 16 cells of that colour
  body   = for each tag, the box above it: share of grey-dark cells (opponents are drawn grey with a red outline)
  floor  = for each tag, a window at capture resolution around the feet: red outline/rim pixels and pixels of the
           fan fill (court teal under translucent red-grey), with the centroid of the fill
  me     = cyan foot ring: sqrt(left box * right box) - centre box on the cyan integral image, 5 sizes
csv: t_s, side, me_x, me_y, me_conf, tags = "CLS:x:y:grey:red:fanx:fany:fanarea:fanrim;..." (fan_at: the windowed
     finder), fans = "x:y:area:rim;..." (mscan.find_fans over the whole frame, for comparison), btn_dark, btn_orange,
     btn_white (px at 1170 x 540)
(A plain count of fan-coloured pixels at the feet did not tell the matchup from the others: PF 44 %, C 42 % on 50 s.)
"""
import sys, os, csv
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))   # python -I leaves the script folder out
import mscan           # find_fans: fan-coloured areas with a red rim (the app would run it in windows around opponents' feet)

W, H, S = 1170, 540, 3
GW, GH = W // S, H // S
TAGS = {"SF": ((185, 235), (95, 145), (0, 50)), "C": ((172, 215), (0, 45), (0, 55)), "PF": ((10, 90), (198, 235), (85, 140)),
        "SG": ((105, 180), (0, 45), (150, 225)), "PG": ((0, 70), (110, 160), (160, 212))}
GY, GX = np.mgrid[0:GH, 0:GW]
TAGHUD = ((GX < 0.25 * GW) & (GY < 0.30 * GH)) | (GY < 0.20 * GH) | ((GX > 0.72 * GW) & (GY > 0.40 * GH))
MEHUD = TAGHUD | ((GX < 0.26 * GW) & (GY > 0.52 * GH) & (GY < 0.95 * GH)) | (GY < 0.30 * GH)
BLOCK_FX, BLOCK_FY = 0.889, 0.835


def side(im):
    cx = int(BLOCK_FX * W); cy = int(BLOCK_FY * H); r = int(0.045 * W); st = max(2, int(0.0028 * W))
    ys = np.arange(cy - r, cy + r + 1, st); xs = np.arange(cx - r, cx + r + 1, st)
    yy, xx = np.meshgrid(ys, xs, indexing="ij"); m = ((xx - cx) ** 2 + (yy - cy) ** 2 <= r * r) & (yy >= 0) & (yy < H) & (xx >= 0) & (xx < W)
    p = im[yy[m], xx[m]].astype(np.int16); b, g, rr = p[:, 0], p[:, 1], p[:, 2]
    df = float(((rr < 70) & (g < 70) & (b < 90)).mean()); of = float(((rr > 190) & (g >= 80) & (g <= 190) & (b < 90)).mean())
    wf = float(((rr > 200) & (g > 200) & (b > 200)).mean())      # the white figure drawn on every action button
    # The label under the button, opaque white even when the button is dimmed: "전면 블록" (long) on defence, "슛" on offence.
    lb = im[cy + 60:cy + 84, cx - 56:cx + 24].astype(np.int16)
    lf = float((lb.min(2) >= 215).mean()) if lb.size else 0.0
    return ("DEF" if df > 0.15 and of < 0.005 else "OFF" if of > 0.01 else "?"), df, of, wf, lf


def box(ii, x0, y0, x1, y1):
    """Sum over [x0, x1) x [y0, y1) for arrays of window corners on integral image ii ((GH+1) x (GW+1))."""
    return ii[y1, x1] - ii[y0, x1] - ii[y1, x0] + ii[y0, x0]


def integral(m):
    ii = np.zeros((GH + 1, GW + 1), np.int32); ii[1:, 1:] = m.astype(np.int32).cumsum(0).cumsum(1); return ii


def find_tags(g):
    r = g[..., 0].astype(np.int16); gg = g[..., 1].astype(np.int16); b = g[..., 2].astype(np.int16); out = []
    for name, (rr, gr, br) in TAGS.items():
        m = (r >= rr[0]) & (r <= rr[1]) & (gg >= gr[0]) & (gg <= gr[1]) & (b >= br[0]) & (b <= br[1]) & ~TAGHUD
        if m.sum() < 16: continue
        ii = integral(m)
        c = box(ii, GX[:GH - 3, :GW - 7], GY[:GH - 3, :GW - 7], GX[:GH - 3, :GW - 7] + 8, GY[:GH - 3, :GW - 7] + 4).copy()
        for _ in range(3):
            j = int(np.argmax(c)); y, x = divmod(j, c.shape[1])
            if c[y, x] < 16: break
            # centre of the colour cells inside the window
            sub = m[y:y + 4, x:x + 8]; yy, xx = np.nonzero(sub)
            out.append((name, (x + xx.mean() + 0.5) * S, (y + yy.mean() + 0.5) * S))
            c[max(0, y - 6):y + 7, max(0, x - 12):x + 13] = 0
    return out


def body_grey(g, tx, ty):
    """Share of grey-dark cells in the box above the tag (tx, ty in px)."""
    x0 = int(tx / S) - 4; x1 = x0 + 34; y1 = int(ty / S) - 20; y0 = y1 - 40
    x0 = max(0, x0); y0 = max(0, y0); x1 = min(GW, x1); y1 = min(GH, y1)
    if x1 - x0 < 8 or y1 - y0 < 8: return -1.0
    c = g[y0:y1, x0:x1].astype(np.int16); mx = c.max(2); mn = c.min(2)
    return float(((mx - mn <= 22) & (mx <= 135)).mean())


def floor(im, tx, ty):
    """Window at capture resolution around the feet of the player tagged at (tx, ty): red px, fan fill px, fill centroid."""
    x0 = int(tx) - 30; x1 = int(tx) + 150; y0 = int(ty) - 92; y1 = int(ty) - 28   # fan centre seen at tag + (10..115, -76..-45)
    x0 = max(0, x0); y0 = max(0, y0); x1 = min(W, x1); y1 = min(H, y1)
    if x1 - x0 < 20 or y1 - y0 < 20: return 0, 0, 0, -1, -1
    c = im[y0:y1, x0:x1].astype(np.int16); b, g, r = c[..., 0], c[..., 1], c[..., 2]
    red = int(((r >= 125) & (g <= 45) & (b <= 90)).sum())
    fa = (r >= 62) & (r <= 125) & (g >= 60) & (g <= 105) & (b >= 78) & (b <= 125) & (np.abs(r - g) <= 28) & (b - g >= 0) & (b - g <= 40)
    fb = fa & (b - g >= 6)
    nb = int(fb.sum())
    if nb == 0: return red, int(fa.sum()), 0, -1, -1
    yy, xx = np.nonzero(fb)
    return red, int(fa.sum()), nb, x0 + float(xx.mean()), y0 + float(yy.mean())


K3 = np.ones((3, 3), np.uint8)
HUDPX = cv2.resize(mscan.HUD.astype(np.uint8), (W, H), interpolation=cv2.INTER_NEAREST).astype(bool)


def fan_at(im, tx, ty):
    """The windowed fan finder the app would run: mscan.find_fans restricted to the feet of the player tagged at
    (tx, ty). Returns (area * rim, x, y, area, rim) of the best red-rimmed fan whose centre is at tag + (-30..150, -92..-28)."""
    x0 = max(0, int(tx) - 60); x1 = min(W, int(tx) + 180); y0 = max(0, int(ty) - 115); y1 = min(H, int(ty) - 10)
    if x1 - x0 < 30 or y1 - y0 < 20: return None
    c = im[y0:y1, x0:x1].astype(np.int16); b, g, r = c[..., 0], c[..., 1], c[..., 2]
    fill = ((r >= 62) & (r <= 125) & (g >= 60) & (g <= 105) & (b >= 78) & (b <= 125) & (np.abs(r - g) <= 28) & (b - g >= 0) & (b - g <= 40) & ~HUDPX[y0:y1, x0:x1]).astype(np.uint8)
    red = ((r >= 125) & (g <= 45) & (b <= 90)).astype(np.uint8)
    fill = cv2.morphologyEx(fill, cv2.MORPH_OPEN, K3)
    n, lab, st, cen = cv2.connectedComponentsWithStats(fill, 8); best = None
    for i in range(1, n):
        x, y, w, h, a = st[i]
        if a < 120 or a > 4500 or w < 1.2 * h or h < 6 or a < 0.225 * w * h: continue
        cx = x0 + cen[i][0]; cy = y0 + cen[i][1]
        if not (-30 <= cx - tx <= 150 and -92 <= cy - ty <= -28): continue
        xa = max(0, x - 3); ya = max(0, y - 3); xb = min(x1 - x0, x + w + 3); yb = min(y1 - y0, y + h + 3)
        m = (lab[ya:yb, xa:xb] == i).astype(np.uint8); ring = cv2.dilate(m, K3, iterations=3) - m; k = int(ring.sum())
        if k == 0: continue
        rim = float((ring & red[ya:yb, xa:xb]).sum()) / k
        if rim < 0.22: continue
        if best is None or a * rim > best[0]: best = (a * rim, cx, cy, int(a), rim)
    return best


SIZES = [16, 20, 24, 28, 32]


def find_me(g):
    r = g[..., 0].astype(np.int16); gg = g[..., 1].astype(np.int16); b = g[..., 2].astype(np.int16)
    m = (gg >= 90) & (gg <= 215) & (b > 165) & (b - r > 80) & ~MEHUD
    if m.sum() < 8: return None
    ii = integral(m); best = None
    for a in SIZES:
        hw = a // 2; q = a // 4; hh = max(2, int(0.39 * a * 0.35)); cw = max(1, a // 6); ch = max(1, hh // 2)
        ys = GY[hh:GH - hh, hw:GW - hw]; xs = GX[hh:GH - hh, hw:GW - hw]
        L = box(ii, xs - hw, ys - hh, xs - q, ys + hh + 1) / float((hw - q) * (2 * hh + 1))
        R = box(ii, xs + q, ys - hh, xs + hw, ys + hh + 1) / float((hw - q) * (2 * hh + 1))
        C = box(ii, xs - cw, ys - ch, xs + cw + 1, ys + ch + 1) / float((2 * cw + 1) * (2 * ch + 1))
        s = np.sqrt(L * R) - C
        j = int(np.argmax(s)); y, x = divmod(j, s.shape[1]); v = float(s[y, x])
        if best is None or v > best[0]: best = (v, (x + hw + 0.5) * S, (y + hh + 0.5) * S, a * S)
    return best


def frames(mp4, hz, t0, t1):
    v = cv2.VideoCapture(mp4); nxt = t0
    if t0 > 1: v.set(cv2.CAP_PROP_POS_MSEC, (t0 - 1) * 1000)
    while v.grab():
        t = v.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
        if t > t1: break
        if t + 1e-6 < nxt: continue
        nxt = t + 1.0 / hz
        ok, im = v.retrieve()
        if not ok: break
        yield t, cv2.resize(im, (W, H), interpolation=cv2.INTER_AREA)


if __name__ == "__main__":
    a = sys.argv[1:]; mp4 = a[0]; out = a[a.index("-csv") + 1]
    hz = float(a[a.index("-hz") + 1]) if "-hz" in a else 10.0
    t0 = float(a[a.index("-t0") + 1]) if "-t0" in a else 0.0; t1 = float(a[a.index("-t1") + 1]) if "-t1" in a else 1e9
    with open(out, "w", newline="") as f:
        wr = csv.writer(f); wr.writerow(["t_s", "side", "me_x", "me_y", "me_conf", "tags", "fans", "btn_dark", "btn_orange", "btn_white", "btn_label"]); n = 0
        for t, im in frames(mp4, hz, t0, t1):
            g = im[::S, ::S, ::-1]; me = find_me(g); tg = []
            for name, x, y in find_tags(g):
                red, fa, fb, fx, fy = floor(im, x, y)
                fw = fan_at(im, x, y)
                tg.append("%s:%.0f:%.0f:%.2f:%d:%.0f:%.0f:%d:%.2f" % ((name, x, y, body_grey(g, x, y), red) + (fw[1:] if fw else (-1, -1, 0, 0))))
            fans = ";".join("%.0f:%.0f:%d:%.2f" % (q[1], q[2], q[3], q[4]) for q in mscan.find_fans(im)[:6])
            sd, df, of, wf, lf = side(im)
            wr.writerow([round(t, 3), sd] + ([round(me[1]), round(me[2]), round(me[0], 3)] if me else [-1, -1, 0]) + [";".join(tg), fans, round(df, 3), round(of, 4), round(wf, 3), round(lf, 3)]); n += 1
            if n % 200 == 0: print(".", end="", flush=True)
    print("\n%d scans -> %s" % (n, out))
