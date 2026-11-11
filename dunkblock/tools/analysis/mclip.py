"""Shooter silhouettes around the opponent shots of a colour match recording, in poseclip.py's format (HANDOFF 0-4v).

mclip.py <mp4> <mfollow.csv> <out folder> <name> [-pre 3.0] [-post 0.4]
Purpose: run the FROZEN practice-court shot scorer (learnapply.py's model) on real match shots without touching it.
  shots   = mshots.py's rule on the csv (label under the big button: block -> rebound within 1 s)
  anchor  = the ball handler's red floor ring (mscan.find_ball, conf >= 0.28, held <= 150 ms, past only); in the
            practice recordings the anchor was the marker x and the foot ring row
  size    = the practice box is 300 x 312 px at 720p for a ring of about 104 px (0.145 of the screen height). Here the
            box is scaled by the median ring width of the last 1.5 s before the shot: u = (H / 720) * (ring / H) / 0.145,
            and written three times (u * 0.85, u, u * 1.15) because the size match is a guess.
  outline = poseclip.outline (the red outline test of ShotWatcher.findTop), every video frame (72-87 fps, variable)
Writes <out>/<name>_<k>_s<085|100|115>.pose.npz (t, ok, m, off, gap) and <out>/<name>.clips.csv:
  k, shot_s (csv scan), label_s (first video frame with the rebound label), ring_x, ring_y (1170 wide, 0.3 s before),
  ring_w, frames, with_anchor
"""
import sys, os, csv
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mscan, poseclip

W, H = 1170, 540
CX = int(0.889 * W); CY = int(0.835 * H)
GEN_HALF, GEN_UP, GEN_DOWN = 340, 680, 30          # generous box at full resolution (covers u <= 2.2)
SCALES = (("085", 0.85), ("100", 1.0), ("115", 1.15))


def label(im):
    lb = im[CY + 60:CY + 84, CX - 56:CX + 24].astype(np.int16)
    return float((lb.min(2) >= 215).mean())


def shots(rows):
    lastBlock = -9.0; inReb = False; out = []
    for r in rows:
        t = float(r["t_s"]); l = float(r["btn_label"])
        if 0.20 <= l <= 0.27: lastBlock = t; inReb = False
        elif 0.16 <= l < 0.20:
            if not inReb and t - lastBlock <= 1.0: out.append(t)
            inReb = True
        else: inReb = inReb and t - lastBlock <= 1.5
    return out


def finish(out, name, k, shot, F, wr):
    """F: list of (t, label, anchor or None, packed generous mask or None)."""
    t = np.array([f[0] for f in F], np.float32)
    lab = [f[1] for f in F]
    ls = -1.0; seen = False
    for tt, l in zip(t, lab):                                   # first frame with the rebound label after a block label
        if 0.20 <= l <= 0.27: seen = True
        elif seen and 0.16 <= l < 0.20 and tt >= shot - 0.6: ls = float(tt); break
    ref = ls if ls > 0 else shot
    ws = [f[2][2] for f in F if f[2] and f[2][3] and ref - 1.5 <= f[0] <= ref]
    near = [f[2] for f in F if f[2] and f[2][3] and f[0] <= ref - 0.3]
    rx, ry = (near[-1][0], near[-1][1]) if near else (-1, -1)
    if len(ws) < 5:
        wr.writerow([k, shot, round(ls, 3), rx, ry, -1, len(F), 0]); return
    w = float(np.median(ws)); u0 = (2 * H / 720.0) * (w / H) / 0.145       # full-resolution px per practice-720p px
    na = 0
    for tag, s in SCALES:
        u = u0 * s; N = len(F)
        ok = np.zeros(N, bool); m = np.zeros((N, poseclip.CH, poseclip.CW), np.uint8); off = np.full(N, -999.0, np.float32); gap = np.full(N, -1.0, np.float32)
        hw = min(GEN_HALF, int(poseclip.HALF * u)); up = min(GEN_UP, int(poseclip.UP * u)); dn = min(GEN_DOWN, int(poseclip.DOWN * u))
        for i, f in enumerate(F):
            if f[3] is None: continue
            g = np.unpackbits(f[3])[:(GEN_UP + GEN_DOWN) * 2 * GEN_HALF].reshape(GEN_UP + GEN_DOWN, 2 * GEN_HALF)
            box = g[GEN_UP - up:GEN_UP + dn, GEN_HALF - hw:GEN_HALF + hw] * 255
            m[i] = cv2.resize(box, (poseclip.CW, poseclip.CH), interpolation=cv2.INTER_AREA)
            low = box[int(130 * u):int(300 * u)]; cols = low.sum(0).astype(np.float64)
            if cols.sum() >= 30 * 255 * u:
                off[i] = float((cols * np.arange(len(cols))).sum() / cols.sum() - hw) / u; ok[i] = True
        np.savez_compressed(os.path.join(out, "%s_%02d_s%s.pose.npz" % (name, k, tag)), t=t, ok=ok, m=m, off=off, gap=gap)
        if tag == "100": na = int(ok.sum())
    wr.writerow([k, shot, round(ls, 3), rx, ry, round(w, 1), len(F), na])


if __name__ == "__main__":
    a = sys.argv[1:]; mp4, src, out, name = a[0], a[1], a[2], a[3]
    PRE = float(a[a.index("-pre") + 1]) if "-pre" in a else 3.0; POST = float(a[a.index("-post") + 1]) if "-post" in a else 0.4
    S = shots(list(csv.DictReader(open(src)))); os.makedirs(out, exist_ok=True)
    v = cv2.VideoCapture(mp4); k = 0; F = []; last = None
    with open(os.path.join(out, name + ".clips.csv"), "w", newline="") as f:
        wr = csv.writer(f); wr.writerow(["k", "shot_s", "label_s", "ring_x", "ring_y", "ring_w", "frames", "with_anchor"])
        while k < len(S) and v.grab():
            t = v.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
            if t < S[k] - PRE: continue
            if t > S[k] + POST:
                finish(out, name, k, S[k], F, wr); f.flush(); print(".", end="", flush=True); k += 1; F = []; last = None; continue
            good, im = v.retrieve()
            if not good: break
            sm = cv2.resize(im, (W, H), interpolation=cv2.INTER_AREA)
            b = mscan.find_ball(sm); fresh = bool(b and b[0] >= 0.28)
            if fresh: last = (t, b[1], b[2], b[3])
            anc = None; pk = None
            if last and t - last[0] < 0.15:
                anc = (last[1], last[2], last[3], fresh)
                cx = 2 * last[1]; fy = 2 * last[2]; x0 = cx - GEN_HALF; y0 = fy - GEN_UP
                g = np.zeros((GEN_UP + GEN_DOWN, 2 * GEN_HALF), np.uint8)
                sx0 = max(0, x0); sx1 = min(im.shape[1], x0 + 2 * GEN_HALF); sy0 = max(0, y0); sy1 = min(im.shape[0], y0 + GEN_UP + GEN_DOWN)
                if sx1 > sx0 and sy1 > sy0: g[sy0 - y0:sy1 - y0, sx0 - x0:sx1 - x0] = poseclip.outline(im[sy0:sy1, sx0:sx1])
                pk = np.packbits(g)
            F.append((t, label(sm), anc, pk))
        if k < len(S) and F: finish(out, name, k, S[k], F, wr)
    print("\n%d shots -> %s" % (len(S), out))
